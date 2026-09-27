#!/usr/bin/env bash
# =====================================================================
# S04 上传协议与归属端到端验证（UPLOAD-01..UPLOAD-08 的离线可验部分）
#
# 需要：已按 S01 迁移的独立测试库 + 以 **test profile + 内存对象存储** 启动的实例：
#
#   DITING_DB_PASSWORD='<库密码>' DITING_JWT_SECRET='<测试密钥>' \
#   DITING_ADMIN_INIT_USERNAME=ops_admin DITING_ADMIN_INIT_PASSWORD='<临时强密码>' \
#   java -jar target/diting-server-0.0.1-SNAPSHOT.jar --server.port=8083 \
#     --spring.profiles.active=test --diting.storage.type=memory \
#     --spring.datasource.url='jdbc:mysql://127.0.0.1:3306/diting_s04_test?...' \
#     --diting.jwt.secret='<测试专用强密钥>' \
#     --diting.aliyun.access-key-id=test-key --diting.aliyun.access-key-secret=test-secret \
#     --diting.aliyun.oss.bucket=test-bucket --diting.wechat.appid=test-appid --diting.wechat.secret=test-secret
#
# 运行：MYSQL_PWD='<库密码>' S04_ADMIN_PASSWORD='<密码>' bash run_s04_upload_e2e.sh
# 说明：内存对象存储与 /api/test/object-storage 只在 test profile 存在，生产不会加载。
#       真实云上传（微信真机 + OSS）仍属 S14，未验证就不宣称端到端可用。
# =====================================================================
set -uo pipefail
BASE=${S04_BASE_URL:-http://127.0.0.1:8083}
DB=${S04_TEST_DB:-diting_s04_test}
MYSQL_HOST=${MYSQL_HOST:-127.0.0.1}
MYSQL_USER=${MYSQL_USER:-root}
if [ -z "${S04_ADMIN_PASSWORD:-}" ]; then echo "缺少 S04_ADMIN_PASSWORD" >&2; exit 2; fi
if [ -z "${MYSQL_PWD:-}" ]; then echo "缺少 MYSQL_PWD" >&2; exit 2; fi
case "$DB" in *_test) ;; *) echo "拒绝执行：测试库名必须以 _test 结尾（当前 ${DB}）" >&2; exit 2 ;; esac

PASS=0; FAIL=0
ok(){ printf 'PASS  %s\n' "$1"; PASS=$((PASS+1)); }
bad(){ printf 'FAIL  %s | %s\n' "$1" "${2:-}"; FAIL=$((FAIL+1)); }
SFX=$RANDOM

call(){ local m="$1" p="$2" b="${3:-}" t="${4:-}"
  local args=(-s -m 15 -X "$m" -H 'Content-Type: application/json' -w '\n%{http_code}')
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
expect_jq(){ local name="$1" expr="$2"
  if jq -e "$expr" <<<"$(p)" >/dev/null 2>&1; then ok "$name"; else bad "$name" "断言失败：${expr}（$(p)）"; fi; }
expect_eq(){ local name="$1" want="$2" got="$3"
  if [ "$want" = "$got" ]; then ok "$name"; else bad "$name" "期望 ${want}；实际 ${got}"; fi; }

# 构造最小“真实文件头”载荷：m4a(ftyp) 与伪装的 mp3(ID3)
M4A_B64=$(python3 -c "import base64;d=bytearray(64);h=b'0000ftypM4A ';d[0:len(h)]=h;print(base64.b64encode(bytes(d)).decode())")
MP3_B64=$(python3 -c "import base64;d=bytearray(64);h=b'ID3';d[0:3]=h;print(base64.b64encode(bytes(d)).decode())")

sign(){ # sign <token> <filename> [consentVersion] [declaredSize] -> RESP
  local token="$1" filename="$2" consent="${3:-v1}" size="${4:-2048}"
  RESP=$(call POST /api/upload/sign "{\"filename\":\"${filename}\",\"consentVersion\":\"${consent}\",\"declaredSize\":${size}}" "$token")
}
put_object(){ # put_object <uploadId> <base64> <token>：仅 test profile 的写入口，同样需要登录态
  local resp code
  resp=$(call POST /api/test/object-storage/put "{\"uploadId\":\"$1\",\"contentBase64\":\"$2\"}" "$3")
  code=$(tail -1 <<<"$resp")
  if [ "$code" != "200" ]; then
    bad "测试对象写入（uploadId=$1）" "HTTP ${code} $(sed '$d' <<<"$resp")"
  fi
}

echo "== S04 上传协议端到端验证 @ $(date '+%F %T')（测试库 ${DB}，后缀 ${SFX}）=="

# ---------- 准备 ----------
RESP=$(call POST /api/auth/register "{\"username\":\"u_a_${SFX}\",\"password\":\"Passw0rd2026\"}")
USER_A=$(p | jq -r '.data.token')
USER_A_ID=$(p | jq -r '.data.userInfo.id')
RESP=$(call POST /api/auth/register "{\"username\":\"u_b_${SFX}\",\"password\":\"Passw0rd2026\"}")
USER_B=$(p | jq -r '.data.token')
expect_eq "准备：两个用户已注册" "2" "$(sqlv "SELECT COUNT(*) FROM users WHERE username IN ('u_a_${SFX}','u_b_${SFX}')")"

# ---------- 签发前的拒绝 ----------
RESP=$(call POST /api/upload/sign "{\"filename\":\"a.m4a\",\"declaredSize\":100}" "$USER_A")
expect "UPLOAD-08-1 未带同意条款 400" 400 "$(s)"
RESP=$(call POST /api/upload/sign "{\"filename\":\"a.m4a\",\"consentVersion\":\"  \",\"declaredSize\":100}" "$USER_A")
expect "UPLOAD-08-2 同意条款为空 400" 400 "$(s)"
sign "$USER_A" "a.exe"
expect "UPLOAD-02-1 扩展名不在允许列表 422" 422 "$(s)"
sign "$USER_A" "a.m4a" v1 52428801
expect "UPLOAD-03-1 声明大小超上限 422" 422 "$(s)"

# ---------- 签发结果形态 ----------
sign "$USER_A" "通话录音.m4a"
expect "签发成功 200" 200 "$(s)"
expect_jq "UPLOAD-01-1 method=PUT" '.data.method == "PUT"'
expect_jq "UPLOAD-01-2 objectKey 由服务端生成且带 owner 前缀" ".data.objectKey | startswith(\"recordings/${USER_A_ID}/\")"
expect_jq "UPLOAD-01-3 返回 uploadId 与有效期" '.data.uploadId > 0 and .data.expiresAt != null and .data.cleanupAfter != null'
UP1=$(p | jq -r '.data.uploadId')
KEY1=$(p | jq -r '.data.objectKey')
expect_eq "同意记录落库（版本 + 时间）" "1" "$(sqlv "SELECT COUNT(*) FROM upload_sessions WHERE id=${UP1} AND consent_version='v1' AND consented_at IS NOT NULL")"

# ---------- complete 的校验 ----------
RESP=$(call POST /api/upload/complete "{\"uploadId\":${UP1}}" "$USER_A")
expect "UPLOAD-03-2 对象不存在 422" 422 "$(s)"
expect_jq "UPLOAD-03-2b 文案提示重新上传" '.message | test("对象不存在")'
RESP=$(call POST /api/task/submit "{\"uploadId\":${UP1},\"declaredDurationSec\":30}" "$USER_A")
expect "未完成校验就登记 409" 409 "$(s)"

# ---------- 归属（UPLOAD-06） ----------
RESP=$(call POST /api/upload/complete "{\"uploadId\":${UP1}}" "$USER_B")
expect "UPLOAD-06-1 他人 complete 404" 404 "$(s)"
RESP=$(call POST /api/task/submit "{\"uploadId\":${UP1},\"declaredDurationSec\":30}" "$USER_B")
expect "UPLOAD-06-2 他人 uploadId 登记 404" 404 "$(s)"
RESP=$(call POST /api/upload/refresh "{\"uploadId\":${UP1}}" "$USER_B")
expect "UPLOAD-06-3 他人 refresh 404" 404 "$(s)"
RESP=$(call POST /api/task/submit "{\"uploadId\":99999999,\"declaredDurationSec\":30}" "$USER_A")
expect "UPLOAD-06-4 不存在的 uploadId 登记 404" 404 "$(s)"
RESP=$(call POST /api/task/submit "{\"objectKey\":\"audio/any.m4a\",\"declaredDurationSec\":30}" "$USER_A")
expect "不接受直接传 objectKey（400）" 400 "$(s)"

# ---------- 正常上传 → 校验 → 登记 ----------
put_object "$UP1" "$M4A_B64" "$USER_A"
RESP=$(call POST /api/upload/complete "{\"uploadId\":${UP1}}" "$USER_A")
expect "UPLOAD-01-4 校验通过 200" 200 "$(s)"
expect_jq "UPLOAD-03-3 返回服务端测得的大小" '.data.sizeBytes == 64'
expect_jq "UPLOAD-01-5 记录真实格式" '.data.mediaFormat == "m4a"'
expect_jq "UPLOAD-01-6 状态为 VERIFIED" '.data.status == "VERIFIED"'
expect_jq "上传阶段不伪造时长" '.data.durationMs == null'

RESP=$(call POST /api/task/submit "{\"uploadId\":${UP1},\"declaredDurationSec\":120}" "$USER_A")
expect "登记任务 200" 200 "$(s)"
TASK1=$(p | jq -r '.data.id')
expect_eq "任务使用会话里的可信 objectKey" "$KEY1" "$(sqlv "SELECT oss_object FROM recognition_tasks WHERE id=${TASK1}")"
expect_eq "任务关联 uploadId" "$UP1" "$(sqlv "SELECT upload_id FROM recognition_tasks WHERE id=${TASK1}")"
expect_eq "会话状态转为 CONSUMED" "CONSUMED" "$(sqlv "SELECT status FROM upload_sessions WHERE id=${UP1}")"
expect_eq "只预占 1 份额度（流水一行）" "1" "$(sqlv "SELECT COUNT(*) FROM quota_ledger WHERE task_id=${TASK1}")"

RESP=$(call POST /api/task/submit "{\"uploadId\":${UP1},\"declaredDurationSec\":120}" "$USER_A")
expect "重复提交同一 uploadId 200（幂等）" 200 "$(s)"
expect_eq "重复提交返回同一任务" "$TASK1" "$(p | jq -r '.data.id')"
expect_eq "额度没有被重复扣" "1" "$(sqlv "SELECT COUNT(*) FROM quota_ledger WHERE task_id=${TASK1}")"

# ---------- 已消费的会话不能再刷签名 ----------
RESP=$(call POST /api/upload/refresh "{\"uploadId\":${UP1}}" "$USER_A")
expect "UPLOAD-04-1 已消费会话不可刷新 409" 409 "$(s)"

# ---------- P1-2 TOCTOU：已校验的会话同样冻结 refresh ----------
# complete 之后同 key 覆盖会替换校验过的内容，因此 VERIFIED 会话刷新必须 409；
# 需要重传时重新 sign 一个新会话（新 objectKey）。
sign "$USER_A" "frozen.m4a"
UPF=$(p | jq -r '.data.uploadId')
put_object "$UPF" "$M4A_B64" "$USER_A"
call POST /api/upload/complete "{\"uploadId\":${UPF}}" "$USER_A" >/dev/null
RESP=$(call POST /api/upload/refresh "{\"uploadId\":${UPF}}" "$USER_A")
expect "TOCTOU-1 已校验会话不可刷新 409" 409 "$(s)"
# submit 时的 etag 纵深校验：校验后内容被同 key 覆盖则拒绝登记
UPFH=$(sqlv "SELECT content_hash FROM upload_sessions WHERE id=${UPF}")
if [ -n "${UPFH}" ] && [ "${UPFH}" != "null" ]; then
  # 用不同内容覆盖同一对象（模拟校验后替换），etag 必然变化
  put_object "$UPF" "$MP3_B64" "$USER_A"
  RESP=$(call POST /api/task/submit "{\"uploadId\":${UPF},\"declaredDurationSec\":60}" "$USER_A")
  expect "TOCTOU-2 校验后内容被替换则拒绝登记 409" 409 "$(s)"
fi

# ---------- 刷新签名：objectKey 不变 ----------
sign "$USER_A" "refresh.m4a"
UP2=$(p | jq -r '.data.uploadId')
KEY2=$(p | jq -r '.data.objectKey')
RESP=$(call POST /api/upload/refresh "{\"uploadId\":${UP2}}" "$USER_A")
expect "UPLOAD-04-2 未消费会话可刷新 200" 200 "$(s)"
expect_eq "UPLOAD-04-3 刷新后 objectKey 不变" "$KEY2" "$(p | jq -r '.data.objectKey')"
expect_eq "UPLOAD-04-4 刷新后仍是同一 uploadId" "$UP2" "$(p | jq -r '.data.uploadId')"

# ---------- 伪扩展名 ----------
sign "$USER_A" "伪装.m4a"
UP3=$(p | jq -r '.data.uploadId')
put_object "$UP3" "$MP3_B64" "$USER_A"
RESP=$(call POST /api/upload/complete "{\"uploadId\":${UP3}}" "$USER_A")
expect "UPLOAD-02-2 内容与后缀不一致 422" 422 "$(s)"
expect_jq "UPLOAD-02-2b 报出真实格式" '.message | test("实际格式为 mp3")'
expect_eq "UPLOAD-02-3 未通过校验的会话不能登记" "SIGNED" "$(sqlv "SELECT status FROM upload_sessions WHERE id=${UP3}")"

# ---------- UPLOAD-07 上传成功但登记失败：额度与任务一起回滚 ----------
sign "$USER_A" "rollback.m4a"
UP4=$(p | jq -r '.data.uploadId')
put_object "$UP4" "$M4A_B64" "$USER_A"
call POST /api/upload/complete "{\"uploadId\":${UP4}}" "$USER_A" >/dev/null
BEFORE_LEDGER=$(sqlv "SELECT COUNT(*) FROM quota_ledger")
RESP=$(call POST /api/task/submit "{\"uploadId\":${UP4},\"declaredDurationSec\":601}" "$USER_A")
expect "UPLOAD-07-1 超时长被拒 422" 422 "$(s)"
expect_eq "UPLOAD-07-2 失败后不产生额度流水" "$BEFORE_LEDGER" "$(sqlv "SELECT COUNT(*) FROM quota_ledger")"
expect_eq "UPLOAD-07-3 会话仍可用于重试（保持 VERIFIED）" "VERIFIED" "$(sqlv "SELECT status FROM upload_sessions WHERE id=${UP4}")"
RESP=$(call POST /api/task/submit "{\"uploadId\":${UP4},\"declaredDurationSec\":600}" "$USER_A")
expect "UPLOAD-07-4 修正后可正常登记 200" 200 "$(s)"

# ---------- 孤立会话的清理时间已登记（清理作业属 S13） ----------
expect_eq "孤立会话都带 cleanup_after（供 S13 清理）" \
  "$(sqlv "SELECT COUNT(*) FROM upload_sessions WHERE status IN ('SIGNED','VERIFIED')")" \
  "$(sqlv "SELECT COUNT(*) FROM upload_sessions WHERE status IN ('SIGNED','VERIFIED') AND cleanup_after IS NOT NULL")"

echo "== 结果：PASS=${PASS} FAIL=${FAIL} =="
[ "$FAIL" -eq 0 ]
