#!/usr/bin/env bash
# =====================================================================
# S09 小程序闭环所需的服务端契约验证（分页、删除墓碑、抹除、清理状态、额度）
#
# 需要：已按 V1+V2+V3 迁移的独立测试库 + test profile 实例（内存存储 + 桩听悟）：
#   DITING_DB_PASSWORD='<库密码>' DITING_JWT_SECRET='<测试密钥>' \
#   DITING_ADMIN_INIT_USERNAME=ops_admin DITING_ADMIN_INIT_PASSWORD='<临时强密码>' \
#   java -jar target/diting-server-0.0.1-SNAPSHOT.jar --server.port=8088 \
#     --spring.profiles.active=test --diting.storage.type=memory \
#     --diting.aliyun.tingwu.type=stub --diting.pipeline.enabled=false \
#     --spring.datasource.url='jdbc:mysql://127.0.0.1:3306/diting_s09_test?...' \
#     --diting.jwt.secret='<测试专用强密钥>'
#
# 运行：MYSQL_PWD='<库密码>' bash run_s09_closure_e2e.sh
# =====================================================================
set -uo pipefail
BASE=${S09_BASE_URL:-http://127.0.0.1:8088}
DB=${S09_TEST_DB:-diting_s09_test}
MYSQL_HOST=${MYSQL_HOST:-127.0.0.1}
MYSQL_USER=${MYSQL_USER:-root}
if [ -z "${MYSQL_PWD:-}" ]; then echo "缺少 MYSQL_PWD" >&2; exit 2; fi
case "$DB" in *_test) ;; *) echo "拒绝执行：测试库名必须以 _test 结尾" >&2; exit 2 ;; esac

PASS=0; FAIL=0
ok(){ printf 'PASS  %s\n' "$1"; PASS=$((PASS+1)); }
bad(){ printf 'FAIL  %s | %s\n' "$1" "${2:-}"; FAIL=$((FAIL+1)); }
SFX=$RANDOM
call(){ local m="$1" p="$2" b="${3:-}" t="${4:-}"
  local args=(-s -m 20 -X "$m" -H 'Content-Type: application/json' -w '\n%{http_code}')
  [ -n "$b" ] && args+=(-d "$b")
  [ -n "$t" ] && args+=(-H "Authorization: Bearer $t")
  curl "${args[@]}" "$BASE$p"
}
sqlv(){ mysql -h"$MYSQL_HOST" -u"$MYSQL_USER" -D "$DB" --batch --skip-column-names -e "$1" 2>&1; }
RESP=""
s(){ tail -1 <<<"$RESP"; }
p(){ sed '$d' <<<"$RESP"; }
expect(){ local name="$1" want="$2" got="$3"
  if [ "$want" = "$got" ]; then ok "$name"; else bad "$name" "期望 HTTP ${want}；实际 HTTP ${got}"; fi; }
expect_eq(){ local name="$1" want="$2" got="$3"
  if [ "$want" = "$got" ]; then ok "$name"; else bad "$name" "期望 ${want}；实际 ${got}"; fi; }
expect_jq(){ local name="$1" expr="$2"
  if jq -e "$expr" <<<"$(p)" >/dev/null 2>&1; then ok "$name"; else bad "$name" "断言失败：${expr}（$(p)）"; fi; }

M4A_B64=$(python3 -c "import base64;d=bytearray(64);h=b'0000ftypM4A ';d[0:len(h)]=h;print(base64.b64encode(bytes(d)).decode())")

# 建一个任务（走完整上传链路）
submit_one(){ # submit_one <token> <idx>
  local token="$1" idx="$2" sign upload
  sign=$(call POST /api/upload/sign "{\"filename\":\"c${idx}.m4a\",\"consentVersion\":\"v1\",\"declaredSize\":2048}" "$token")
  upload=$(jq -r '.data.uploadId' <<<"$(sed '$d' <<<"$sign")")
  call POST /api/test/object-storage/put "{\"uploadId\":\"${upload}\",\"contentBase64\":\"${M4A_B64}\"}" "$token" >/dev/null
  call POST /api/upload/complete "{\"uploadId\":${upload}}" "$token" >/dev/null
  call POST /api/task/submit "{\"uploadId\":${upload},\"declaredDurationSec\":30}" "$token" | sed '$d' | jq -r '.data.id'
}

echo "== S09 闭环契约端到端验证 @ $(date '+%F %T')（测试库 ${DB}，后缀 ${SFX}）=="

# ---------- 准备：一个用户 + 3 个任务 ----------
RESP=$(call POST /api/auth/register "{\"username\":\"c_${SFX}\",\"password\":\"Passw0rd2026\"}")
expect "注册 200" 200 "$(s)"
TOKEN=$(p | jq -r '.data.token')
T1=$(submit_one "$TOKEN" 1)
T2=$(submit_one "$TOKEN" 2)
T3=$(submit_one "$TOKEN" 3)
expect_eq "登记 3 个任务" "3" "$(sqlv "SELECT COUNT(*) FROM recognition_tasks WHERE user_id=(SELECT id FROM users WHERE username='c_${SFX}')")"

# ---------- APP-06 分页 ----------
RESP=$(call GET '/api/task/list?page=1&pageSize=2' '' "$TOKEN")
expect "分页第 1 页 200" 200 "$(s)"
expect_jq "分页信封：total=3、items=2" '.data.total == 3 and (.data.items | length) == 2 and .data.page == 1 and .data.pageSize == 2'
RESP=$(call GET '/api/task/list?page=2&pageSize=2' '' "$TOKEN")
expect_jq "第 2 页只剩 1 条" '(.data.items | length) == 1'
RESP=$(call GET '/api/task/list?page=0' '' "$TOKEN")
expect "page=0 → 422" 422 "$(s)"
sqlv "UPDATE recognition_tasks SET status='COMPLETED' WHERE id=${T1}" >/dev/null
RESP=$(call GET '/api/task/list?status=COMPLETED' '' "$TOKEN")
expect_jq "按状态筛选：只返回已完成" '.data.total == 1 and .data.items[0].status == "COMPLETED"'

# ---------- APP-08 额度 ----------
RESP=$(call GET /api/task/quota '' "$TOKEN")
expect "额度概览 200" 200 "$(s)"
expect_jq "3 份额度已被占满" '.data.remaining == 0 and .data.reserved == 3'

# ---------- APP-07 单条删除：先墓碑再清理 ----------
RESP=$(call DELETE "/api/task/${T3}" '' "$TOKEN")
expect "单条删除 200" 200 "$(s)"
JOB1=$(p | jq -r '.data.deletionJobId')
expect_jq "返回清理作业与排队状态" '.data.status == "QUEUED" and .data.deletionJobId > 0'
expect_eq "墓碑已写入" "1" "$(sqlv "SELECT COUNT(*) FROM recognition_tasks WHERE id=${T3} AND deleted_at IS NOT NULL")"
expect_eq "状态转为删除中" "DELETING" "$(sqlv "SELECT status FROM recognition_tasks WHERE id=${T3}")"
RESP=$(call GET "/api/report/${T3}" '' "$TOKEN")
expect "删除后立即不可读（404）" 404 "$(s)"
RESP=$(call GET '/api/task/list?page=1&pageSize=20' '' "$TOKEN")
expect_jq "列表不再包含已删除记录" '.data.total == 2'
RESP=$(call DELETE "/api/task/${T3}" '' "$TOKEN")
expect "重复删除幂等 200" 200 "$(s)"
expect_eq "重复删除返回同一清理作业" "$JOB1" "$(p | jq -r '.data.deletionJobId')"
RESP=$(call GET "/api/deletions/${JOB1}" '' "$TOKEN")
expect "查询清理状态 200" 200 "$(s)"
expect_jq "状态查询只含最小元数据" '(.data.status == "QUEUED") and (.data.pendingObjects >= 1) and (.data | has("deletionJobId"))'

# ---------- 他人清理作业不可读 ----------
RESP=$(call POST /api/auth/register "{\"username\":\"c2_${SFX}\",\"password\":\"Passw0rd2026\"}")
OTHER=$(p | jq -r '.data.token')
RESP=$(call GET "/api/deletions/${JOB1}" '' "$OTHER")
expect "他人清理作业 404" 404 "$(s)"
RESP=$(call GET "/api/task/list?page=1&pageSize=20" '' "$OTHER")
expect_jq "新用户空列表（APP-05）" '.data.total == 0 and (.data.items | length) == 0'

# ---------- 全部抹除 ----------
RESP=$(call DELETE /api/privacy/erase '' "$TOKEN")
expect "全部抹除 200" "$((200))" "$(s)"
JOB2=$(p | jq -r '.data.deletionJobId')
expect_jq "抹除返回 ERASE 作业" '.data.targetType == "ERASE"'
expect_eq "本人全部记录已写墓碑" "0" "$(sqlv "SELECT COUNT(*) FROM recognition_tasks WHERE user_id=(SELECT id FROM users WHERE username='c_${SFX}') AND deleted_at IS NULL")"
RESP=$(call GET '/api/task/list?page=1&pageSize=20' '' "$TOKEN")
expect_jq "抹除后列表为空" '.data.total == 0'
RESP=$(call GET "/api/deletions/${JOB2}" '' "$TOKEN")
expect_jq "抹除作业状态可查" '.data.status != null and .data.targetType == "ERASE"'

echo "== 结果：PASS=${PASS} FAIL=${FAIL} =="
[ "$FAIL" -eq 0 ]
