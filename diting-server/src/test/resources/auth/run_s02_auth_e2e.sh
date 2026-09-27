#!/usr/bin/env bash
# =====================================================================
# S02 鉴权端到端验证（AUTH-01..AUTH-09 + 权限矩阵 + 登录失败保护）
# 需要一个已按 S01 迁移过的**独立测试库**与一个用测试配置启动的服务端实例：
#
#   DITING_DB_PASSWORD='<库密码>' DITING_JWT_SECRET='<测试密钥>' \
#   DITING_ADMIN_INIT_USERNAME=ops_admin DITING_ADMIN_INIT_PASSWORD='<临时强密码>' \
#   java -jar target/diting-server-0.0.1-SNAPSHOT.jar --server.port=8081 \
#     --spring.datasource.url='jdbc:mysql://127.0.0.1:3306/diting_s02_test?...' \
#     --diting.jwt.secret='<测试用密钥>' --diting.aliyun.access-key-id=test-key ...
#
# 运行：
#   S02_ADMIN_PASSWORD='<与初始化一致的密码>' bash run_s02_auth_e2e.sh
#   可选：S02_BASE_URL / S02_ADMIN_USER
# 说明：脚本只读/写这个测试库，不要指向真实库；密码只从环境变量取，不落盘。
# =====================================================================
set -uo pipefail
BASE=${S02_BASE_URL:-http://127.0.0.1:8081}
ADMIN_USER=${S02_ADMIN_USER:-ops_admin}
if [ -z "${S02_ADMIN_PASSWORD:-}" ]; then
    echo "缺少 S02_ADMIN_PASSWORD：请用与被测实例初始化后台账号时相同的密码" >&2
    exit 2
fi
ADMIN_PASSWORD=$S02_ADMIN_PASSWORD
OP_PASSWORD=${S02_OPERATOR_PASSWORD:-'E2eOperator#2026x'}  # 脚本自建的测试账号密码，可覆盖
SFX=$RANDOM
PASS=0; FAIL=0
ok(){ printf 'PASS  %s\n' "$1"; PASS=$((PASS+1)); }
bad(){ printf 'FAIL  %s | %s\n' "$1" "${2:-}"; FAIL=$((FAIL+1)); }
call(){ local m="$1" p="$2" b="${3:-}" t="${4:-}"
  local args=(-s -m 10 -X "$m" -H 'Content-Type: application/json' -w '\n%{http_code}')
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
  if jq -e "$expr" <<<"$(p)" >/dev/null; then ok "$name"; else bad "$name" "断言失败：$expr"; fi; }

echo "== S02 AUTH 端到端验证 @ $(date '+%F %T')（用户名后缀 ${SFX}）=="

# ---------- C 端注册/登录（白名单） ----------
RESP=$(call POST /api/auth/register "{\"username\":\"e2e_a_${SFX}\",\"password\":\"Passw0rd2026\"}")
expect "AUTH-01-0 注册不需要 token" 200 "$(s)"
USER_A_TOKEN=$(p | jq -r '.data.token')
expect_jq "AUTH-09-1 登录结果是 {token,userInfo,isNewUser}" '.data | has("token") and has("userInfo") and has("isNewUser")'
RESP=$(call POST /api/auth/register "{\"username\":\"e2e_b_${SFX}\",\"password\":\"Passw0rd2026\"}")
USER_B_TOKEN=$(p | jq -r '.data.token')

# ---------- 白名单收紧（AUTH-07） ----------
RESP=$(call GET /api/auth/userinfo); expect "AUTH-07-1 userinfo 未登录 401" 401 "$(s)"
expect_jq "AUTH-07-1b symbol=UNAUTHORIZED" '.symbol == "UNAUTHORIZED"'
RESP=$(call POST /api/auth/bind-wechat '{"code":"fake"}'); expect "AUTH-07-2 bind-wechat 未登录 401" 401 "$(s)"

# ---------- userinfo 字段边界（AUTH-08） ----------
RESP=$(call GET /api/auth/userinfo '' "$USER_A_TOKEN")
expect "AUTH-08-1 userinfo 带 token 200" 200 "$(s)"
if grep -qE 'passwordHash|password_hash|\$2a\$' <<<"$(p)"; then bad "AUTH-08-2 不含密码哈希" "$(p)"; else ok "AUTH-08-2 不含密码哈希"; fi
expect_jq "AUTH-08-3 hasPassword/hasWechat 口径正确" '.data.hasPassword == true and .data.hasWechat == false'

# ---------- 后台登录（AUTH-01/03） ----------
RESP=$(call POST /api/admin/login "{\"username\":\"${ADMIN_USER}\",\"password\":\"${ADMIN_PASSWORD}\"}")
expect "AUTH-01-1 后台密码正确 200" 200 "$(s)"
ADMIN_TOKEN=$(p | jq -r '.data.token')
expect_jq "AUTH-09-2 后台登录是 {token,role,nickname}" '.data | has("token") and has("role") and has("nickname")'
RESP=$(call POST /api/admin/login "{\"username\":\"${ADMIN_USER}\",\"password\":\"wrong-password\"}")
expect "AUTH-01-2 后台密码错误 401" 401 "$(s)"
expect_jq "AUTH-01-2b symbol=LOGIN_FAILED" '.symbol == "LOGIN_FAILED"'

# ---------- 身份域隔离（AUTH-04/05） ----------
RESP=$(call GET '/api/admin/dashboard?period=7d' '' "$USER_A_TOKEN"); expect "AUTH-04 用户 token 访问后台 403" 403 "$(s)"
RESP=$(call GET /api/auth/userinfo '' "$ADMIN_TOKEN"); expect "AUTH-05 后台 token 冒充同数字用户 403" 403 "$(s)"
RESP=$(call GET '/api/admin/dashboard?period=7d'); expect "后台未登录 401" 401 "$(s)"
RESP=$(call GET '/api/admin/dashboard?period=7d' '' 'forged.token.value'); expect "伪造 token 401" 401 "$(s)"

# ---------- 账号管理 / 密码策略 / 分页 ----------
RESP=$(call POST /api/admin/accounts "{\"username\":\"e2e_op_${SFX}\",\"password\":\"${OP_PASSWORD}\",\"nickname\":\"测试运营\",\"role\":\"OPERATOR\"}" "$ADMIN_TOKEN")
expect "账号管理：ADMIN 建 OPERATOR 200" 200 "$(s)"
OP_ID=$(p | jq -r '.data.id')
RESP=$(call POST /api/admin/accounts "{\"username\":\"weak_${SFX}\",\"password\":\"12345678\",\"role\":\"OPERATOR\"}" "$ADMIN_TOKEN")
expect "密码策略：弱密码 422" 422 "$(s)"
expect_jq "密码策略 symbol=UNPROCESSABLE" '.symbol == "UNPROCESSABLE"'
# ---------- C 端密码策略（P1-3：与后台共用弱密码表） ----------
RESP=$(call POST /api/auth/register "{\"username\":\"w_${SFX}\",\"password\":\"12345678\"}")
expect "C 端弱密码 422" 422 "$(s)"
RESP=$(call POST /api/auth/register "{\"username\":\"u_${SFX}\",\"password\":\"u_${SFX}12345\"}")
expect "C 端密码含用户名 422" 422 "$(s)"
RESP=$(call GET '/api/admin/accounts?page=0' '' "$ADMIN_TOKEN"); expect "分页：page=0 → 422" 422 "$(s)"
RESP=$(call GET '/api/admin/accounts?page=1&pageSize=20' '' "$ADMIN_TOKEN")
expect "分页：账号列表 200" 200 "$(s)"
expect_jq "AUTH-09-3 分页信封 {items,total,page,pageSize}" '.data.page == 1 and .data.pageSize == 20 and (.data.items | type) == "array"'
RESP=$(call PUT "/api/admin/accounts/$OP_ID" '{}' "$ADMIN_TOKEN"); expect "账号管理：空更新体 422" 422 "$(s)"
RESP=$(call PUT "/api/admin/accounts/$OP_ID" '{"nickname":"x"}' "$USER_A_TOKEN"); expect "账号管理：用户 token 403" 403 "$(s)"

# ---------- 最后一个 ADMIN 保护 ----------
RESP=$(call GET '/api/admin/accounts?page=1&pageSize=100' '' "$ADMIN_TOKEN")
LAST_ADMIN_ID=$(p | jq -r '[.data.items[] | select(.role=="ADMIN")][0].id')
RESP=$(call PUT "/api/admin/accounts/$LAST_ADMIN_ID" '{"enabled":false}' "$ADMIN_TOKEN")
expect "最后一个 ADMIN 不可禁用 409" 409 "$(s)"
expect_jq "最后 ADMIN 保护 symbol=CONFLICT" '.symbol == "CONFLICT"'

# ---------- 禁用账号立即失效（AUTH-03） ----------
RESP=$(call POST /api/admin/login "{\"username\":\"e2e_op_${SFX}\",\"password\":\"${OP_PASSWORD}\"}")
expect "OPERATOR 可正常登录 200" 200 "$(s)"
OP_TOKEN=$(p | jq -r '.data.token')
RESP=$(call GET '/api/admin/dashboard?period=7d' '' "$OP_TOKEN"); expect "OPERATOR 可读聚合看板 200" 200 "$(s)"
RESP=$(call GET '/api/admin/dashboard' '' "$OP_TOKEN")
expect "缺必填参数：400 而不是 500" 400 "$(s)"
expect_jq "缺参数 symbol=BAD_REQUEST" '.symbol == "BAD_REQUEST"' 
RESP=$(call GET /api/admin/accounts '' "$OP_TOKEN"); expect "OPERATOR 访问账号管理 403" 403 "$(s)"
RESP=$(call PUT "/api/admin/accounts/$OP_ID" '{"enabled":false}' "$ADMIN_TOKEN"); expect "禁用 OPERATOR 200" 200 "$(s)"
RESP=$(call GET '/api/admin/dashboard?period=7d' '' "$OP_TOKEN")
expect "AUTH-03-1 禁用后旧 token 立即失效 403" 403 "$(s)"
expect_jq "AUTH-03-1b symbol=ACCOUNT_DISABLED" '.symbol == "ACCOUNT_DISABLED"'
RESP=$(call POST /api/admin/login "{\"username\":\"e2e_op_${SFX}\",\"password\":\"${OP_PASSWORD}\"}")
expect "AUTH-03-2 禁用账号登录被拒 403" 403 "$(s)"
expect_jq "AUTH-03-2b symbol=ACCOUNT_DISABLED" '.symbol == "ACCOUNT_DISABLED"'

# ---------- 他人资源 404（AUTH-06） ----------
RESP=$(call GET /api/report/999999 '' "$USER_B_TOKEN"); expect "AUTH-06-1 不存在/他人的记录 404" 404 "$(s)"
expect_jq "AUTH-06-2 symbol=NOT_FOUND" '.symbol == "NOT_FOUND"'

# ---------- 登录失败保护 ----------
for i in 1 2 3 4 5; do call POST /api/admin/login "{\"username\":\"probe_${SFX}\",\"password\":\"bad\"}" >/dev/null; done
RESP=$(call POST /api/admin/login "{\"username\":\"probe_${SFX}\",\"password\":\"bad\"}")
expect "登录失败保护：超限后 429" 429 "$(s)"
expect_jq "登录失败保护 symbol=TOO_MANY_REQUESTS" '.symbol == "TOO_MANY_REQUESTS"'

echo "== 结果：PASS=${PASS} FAIL=${FAIL} =="
[ "$FAIL" -eq 0 ]
