#!/usr/bin/env bash
# =====================================================================
# S05 守护验证：云凭据未配置时，服务必须**显式失败**（503 NOT_CONFIGURED），
# 不能返回看起来成功的假地址、假任务号或桩结果。
#
# 需要：一个**不带 test profile、不带云凭据**的实例（application.yml 里是「请替换」占位值）：
#   DITING_DB_PASSWORD='<库密码>' DITING_JWT_SECRET='<测试密钥>' \
#   java -jar target/diting-server-0.0.1-SNAPSHOT.jar --server.port=8084 \
#     --spring.datasource.url='jdbc:mysql://127.0.0.1:3306/diting_s05_test?...' \
#     --diting.jwt.secret='<测试专用强密钥>' \
#     --diting.aliyun.access-key-id=请替换 --diting.aliyun.access-key-secret=请替换 \
#     --diting.aliyun.oss.bucket=请替换
#
# 运行：MYSQL_PWD='<库密码>' bash run_s05_guard_e2e.sh
# =====================================================================
set -uo pipefail
BASE=${S05_BASE_URL:-http://127.0.0.1:8084}
DB=${S05_TEST_DB:-diting_s05_test}
MYSQL_HOST=${MYSQL_HOST:-127.0.0.1}
MYSQL_USER=${MYSQL_USER:-root}
if [ -z "${MYSQL_PWD:-}" ]; then echo "缺少 MYSQL_PWD" >&2; exit 2; fi
case "$DB" in *_test) ;; *) echo "拒绝执行：测试库名必须以 _test 结尾" >&2; exit 2 ;; esac

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
RESP=""
s(){ tail -1 <<<"$RESP"; }
p(){ sed '$d' <<<"$RESP"; }
expect(){ local name="$1" want="$2" got="$3"
  if [ "$want" = "$got" ]; then ok "$name"; else bad "$name" "期望 HTTP ${want}；实际 HTTP ${got}"; fi; }
expect_jq(){ local name="$1" expr="$2"
  if jq -e "$expr" <<<"$(p)" >/dev/null 2>&1; then ok "$name"; else bad "$name" "断言失败：${expr}（$(p)）"; fi; }

echo "== S05 未配置凭据守护验证 @ $(date '+%F %T') =="

RESP=$(call POST /api/auth/register "{\"username\":\"g_${SFX}\",\"password\":\"Passw0rd2026\"}")
expect "注册可用（不依赖云）" 200 "$(s)"
TOKEN=$(p | jq -r '.data.token')

# 1) 上传签名：必须 503，不能给出假 URL
RESP=$(call POST /api/upload/sign "{\"filename\":\"a.m4a\",\"consentVersion\":\"v1\",\"declaredSize\":1024}" "$TOKEN")
expect "未配置对象存储：签名 503" 503 "$(s)"
expect_jq "错误码为 NOT_CONFIGURED" '.symbol == "NOT_CONFIGURED"'
if grep -q "memory-storage\|http://" <<<"$(p)"; then
  bad "响应不应包含任何假的上传地址" "$(p)"
else
  ok "响应不含假上传地址"
fi

# 2) 与云无关的接口仍正常：额度概览可用
RESP=$(call GET /api/task/quota '' "$TOKEN")
expect "额度概览不受云配置影响 200" 200 "$(s)"
expect_jq "额度返回业务日与剩余" '.data.businessDate != null and .data.remaining != null'

# 3) 提交任务：上传会话不可用，因此登记被拒（409/404），不会悄悄扣额度
RESP=$(call POST /api/task/submit "{\"uploadId\":1,\"declaredDurationSec\":30}" "$TOKEN")
CODE=$(s)
if [ "$CODE" = "404" ] || [ "$CODE" = "409" ]; then
  ok "未校验的上传不能登记（HTTP ${CODE}）"
else
  bad "未校验的上传不能登记" "实际 HTTP ${CODE}"
fi

echo "== 结果：PASS=${PASS} FAIL=${FAIL} =="
[ "$FAIL" -eq 0 ]
