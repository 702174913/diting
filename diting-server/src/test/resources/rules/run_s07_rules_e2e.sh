#!/usr/bin/env bash
# =====================================================================
# S07 规则库端到端验证（CRUD、版本推进、启停、集合快照、权限）
#
# 需要：已按 V1+V2+V3 迁移的独立测试库 + 一个实例（规则库不依赖云凭据）：
#   DITING_DB_PASSWORD='<库密码>' DITING_JWT_SECRET='<测试密钥>' \
#   DITING_ADMIN_INIT_USERNAME=ops_admin DITING_ADMIN_INIT_PASSWORD='<临时强密码>' \
#   java -jar target/diting-server-0.0.1-SNAPSHOT.jar --server.port=8086 \
#     --spring.datasource.url='jdbc:mysql://127.0.0.1:3306/diting_s07_test?...' \
#     --diting.jwt.secret='<测试专用强密钥>' \
#     --diting.aliyun.access-key-id=请替换 --diting.aliyun.access-key-secret=请替换 \
#     --diting.aliyun.oss.bucket=请替换
#
# 运行：MYSQL_PWD='<库密码>' S07_ADMIN_PASSWORD='<密码>' bash run_s07_rules_e2e.sh
# =====================================================================
set -uo pipefail
BASE=${S07_BASE_URL:-http://127.0.0.1:8086}
DB=${S07_TEST_DB:-diting_s07_test}
ADMIN_USER=${S07_ADMIN_USER:-ops_admin}
MYSQL_HOST=${MYSQL_HOST:-127.0.0.1}
MYSQL_USER=${MYSQL_USER:-root}
if [ -z "${S07_ADMIN_PASSWORD:-}" ]; then echo "缺少 S07_ADMIN_PASSWORD" >&2; exit 2; fi
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

echo "== S07 规则库端到端验证 @ $(date '+%F %T')（测试库 ${DB}，后缀 ${SFX}）=="

# ---------- 准备：后台账号与权限 ----------
RESP=$(call POST /api/admin/login "{\"username\":\"${ADMIN_USER}\",\"password\":\"${S07_ADMIN_PASSWORD}\"}")
expect "后台登录 200" 200 "$(s)"
ADMIN_TOKEN=$(p | jq -r '.data.token')
RESP=$(call POST /api/admin/accounts "{\"username\":\"op_${SFX}\",\"password\":\"Operator#2026x\",\"nickname\":\"规则只读\",\"role\":\"OPERATOR\"}" "$ADMIN_TOKEN")
expect "建 OPERATOR 账号 200" 200 "$(s)"
RESP=$(call POST /api/admin/login "{\"username\":\"op_${SFX}\",\"password\":\"Operator#2026x\"}")
OPERATOR_TOKEN=$(p | jq -r '.data.token')
RESP=$(call POST /api/auth/register "{\"username\":\"u_${SFX}\",\"password\":\"Passw0rd2026\"}")
USER_TOKEN=$(p | jq -r '.data.token')

# ---------- 新增规则 ----------
RULE_BODY_A="{\"code\":\"R-HIT-${SFX}\",\"groupName\":\"话术\",\"name\":\"禁用承诺\",\"description\":\"依据平台规则第 3 条：不得承诺减免\",\"matchType\":\"hit\",\"roleScope\":\"AGENT\",\"keywords\":[\"保证免息\",\"ＷＯＲＬＤ\"],\"deduct\":30}"
RESP=$(call POST /api/admin/rules "$RULE_BODY_A" "$OPERATOR_TOKEN")
expect "OPERATOR 新增规则 403" 403 "$(s)"
RESP=$(call POST /api/admin/rules "$RULE_BODY_A" "$USER_TOKEN")
expect "用户 token 新增规则 403" 403 "$(s)"
RESP=$(call POST /api/admin/rules "$RULE_BODY_A" "$ADMIN_TOKEN")
expect "ADMIN 新增规则 200" 200 "$(s)"
RULE_ID=$(p | jq -r '.data.ruleId')
expect_jq "返回首个版本号 1" '.data.version == 1'
expect_eq "规则落库" "1" "$(sqlv "SELECT COUNT(*) FROM audit_rules WHERE code='R-HIT-${SFX}' AND current_version=1 AND enabled=1")"
expect_eq "规则版本落库" "1" "$(sqlv "SELECT COUNT(*) FROM audit_rule_versions WHERE rule_id=${RULE_ID} AND version=1 AND deduct=30")"

# ---------- 校验与冲突 ----------
RESP=$(call POST /api/admin/rules "{\"code\":\"R-BAD-${SFX}\",\"name\":\"n\",\"description\":\"d\",\"matchType\":\"hit\",\"roleScope\":\"BOSS\",\"keywords\":[\"x\"],\"deduct\":10}" "$ADMIN_TOKEN")
expect "角色范围非法 422" 422 "$(s)"
RESP=$(call POST /api/admin/rules "{\"code\":\"R-WEAK-${SFX}\",\"name\":\"n\",\"description\":\"d\",\"matchType\":\"hit\",\"roleScope\":\"AGENT\",\"keywords\":[\"x\"],\"deduct\":101}" "$ADMIN_TOKEN")
expect "扣分越界 422" 422 "$(s)"
RESP=$(call POST /api/admin/rules "{\"code\":\"R-REQ-${SFX}\",\"name\":\"n\",\"description\":\"d\",\"matchType\":\"require\",\"roleScope\":\"CUSTOMER\",\"keywords\":[\"x\"],\"expectedKeywords\":[\"y\"],\"deduct\":20}" "$ADMIN_TOKEN")
expect "require 缺触发角色 422" 422 "$(s)"
RESP=$(call POST /api/admin/rules "$RULE_BODY_A" "$ADMIN_TOKEN")
expect "编码重复 409" 409 "$(s)"

# ---------- 版本推进（带乐观锁） ----------
RULE_BODY_B="{\"expectedVersion\":99,\"rule\":{\"code\":\"R-HIT-${SFX}\",\"groupName\":\"话术\",\"name\":\"禁用承诺\",\"description\":\"依据平台规则第 3 条\",\"matchType\":\"hit\",\"roleScope\":\"AGENT\",\"keywords\":[\"保证免息\"],\"deduct\":50}}"
RESP=$(call PUT "/api/admin/rules/${RULE_ID}" "$RULE_BODY_B" "$ADMIN_TOKEN")
expect "过期版本修改 409" 409 "$(s)"
RULE_BODY_C="{\"expectedVersion\":1,\"rule\":{\"code\":\"R-HIT-${SFX}\",\"groupName\":\"话术\",\"name\":\"禁用承诺\",\"description\":\"依据平台规则第 3 条\",\"matchType\":\"hit\",\"roleScope\":\"AGENT\",\"keywords\":[\"保证免息\"],\"deduct\":50}}"
RESP=$(call PUT "/api/admin/rules/${RULE_ID}" "$RULE_BODY_C" "$ADMIN_TOKEN")
expect "正确版本修改 200" 200 "$(s)"
expect_jq "版本推进到 2" '.data.version == 2'
expect_eq "旧版本仍保留（历史可复算）" "2" "$(sqlv "SELECT COUNT(*) FROM audit_rule_versions WHERE rule_id=${RULE_ID}")"
expect_eq "当前版本指向 2" "2" "$(sqlv "SELECT current_version FROM audit_rules WHERE id=${RULE_ID}")"

# ---------- 查询与分页 ----------
RESP=$(call GET "/api/admin/rules?page=1&pageSize=20" '' "$OPERATOR_TOKEN")
expect "OPERATOR 只读列表 200" 200 "$(s)"
expect_jq "分页信封含 items/total/page/pageSize" '.data | has("items") and has("total") and has("page") and has("pageSize")'
RESP=$(call GET "/api/admin/rules?page=0" '' "$ADMIN_TOKEN")
expect "page=0 → 422" 422 "$(s)"
RESP=$(call GET "/api/admin/rules?keyword=R-HIT-${SFX}" '' "$ADMIN_TOKEN")
expect_jq "按编码筛选命中" '.data.items | length >= 1'
RESP=$(call GET "/api/admin/rules/${RULE_ID}" '' "$ADMIN_TOKEN")
expect_jq "详情返回当前版本与关键词" '.data.version == 2 and (.data.keywords | length == 1)'

# ---------- 集合快照 ----------
RESP=$(call POST /api/admin/rules/snapshot '' "$ADMIN_TOKEN")
expect "冻结规则集合 200" 200 "$(s)"
SNAP_VERSION=$(p | jq -r '.data.ruleSetVersion')
expect_eq "快照落库（rule_sets）" "1" "$(sqlv "SELECT COUNT(*) FROM rule_sets WHERE version=${SNAP_VERSION}")"
expect_eq "快照只含启用规则的当前版本" "1" "$(sqlv "SELECT JSON_LENGTH(rule_version_ids) FROM rule_sets WHERE version=${SNAP_VERSION}")"

# ---------- 停用：只影响未来集合 ----------
RESP=$(call DELETE "/api/admin/rules/${RULE_ID}" '' "$ADMIN_TOKEN")
expect "停用规则 200" 200 "$(s)"
expect_jq "返回已停用" '.data.enabled == false'
expect_eq "历史版本仍保留" "2" "$(sqlv "SELECT COUNT(*) FROM audit_rule_versions WHERE rule_id=${RULE_ID}")"
RESP=$(call POST /api/admin/rules/snapshot '' "$ADMIN_TOKEN")
expect_eq "停用后新快照不含该规则" "0" "$(sqlv "SELECT JSON_LENGTH(rule_version_ids) FROM rule_sets WHERE version=$(p | jq -r '.data.ruleSetVersion')")"
RESP=$(call DELETE "/api/admin/rules/${RULE_ID}" '' "$USER_TOKEN")
expect "用户 token 停用 403" 403 "$(s)"

echo "== 结果：PASS=${PASS} FAIL=${FAIL} =="
[ "$FAIL" -eq 0 ]
