#!/usr/bin/env bash
# =====================================================================
# S11 导出端到端验证：作业生命周期、复用、权限、原件过期、导出中删除、
#      产物过期、ZIP 内容与音频一致性（EXPORT-04/05/07/08/09/10）
#
# 需要：已按 V1+V2+V3 迁移的独立测试库 + test profile 实例（内存存储 + 桩听悟 + 关掉导出定时器）：
#   DITING_DB_PASSWORD='<库密码>' DITING_JWT_SECRET='<测试密钥>' \
#   DITING_ADMIN_INIT_USERNAME=ops_admin DITING_ADMIN_INIT_PASSWORD='<临时强密码>' \
#   java -jar target/diting-server-0.0.1-SNAPSHOT.jar --server.port=8090 \
#     --spring.profiles.active=test --diting.storage.type=memory \
#     --diting.aliyun.tingwu.type=stub --diting.pipeline.enabled=false \
#     --diting.export.enabled=false \
#     --spring.datasource.url='jdbc:mysql://127.0.0.1:3306/diting_s11_test?...' \
#     --diting.jwt.secret='<测试专用强密钥>'
#
# 运行：MYSQL_PWD='<库密码>' S11_ADMIN_PASSWORD='<密码>' bash run_s11_export_e2e.sh
# 说明：PDF 中文/分页/注入与脱敏渲染由 PdfBoxReportRendererTest 直接对 PDF 做文本抽取验证。
# =====================================================================
set -uo pipefail
BASE=${S11_BASE_URL:-http://127.0.0.1:8090}
DB=${S11_TEST_DB:-diting_s11_test}
ADMIN_USER=${S11_ADMIN_USER:-ops_admin}
MYSQL_HOST=${MYSQL_HOST:-127.0.0.1}
MYSQL_USER=${MYSQL_USER:-root}
if [ -z "${S11_ADMIN_PASSWORD:-}" ]; then echo "缺少 S11_ADMIN_PASSWORD" >&2; exit 2; fi
if [ -z "${MYSQL_PWD:-}" ]; then echo "缺少 MYSQL_PWD" >&2; exit 2; fi
case "$DB" in *_test) ;; *) echo "拒绝执行：测试库名必须以 _test 结尾" >&2; exit 2 ;; esac

PASS=0; FAIL=0
ok(){ printf 'PASS  %s\n' "$1"; PASS=$((PASS+1)); }
bad(){ printf 'FAIL  %s | %s\n' "$1" "${2:-}"; FAIL=$((FAIL+1)); }
SFX=$RANDOM
WORK=$(mktemp -d)
call(){ local m="$1" p="$2" b="${3:-}" t="${4:-}"
  local args=(-s -m 25 -X "$m" -H 'Content-Type: application/json' -w '\n%{http_code}')
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

echo "== S11 导出端到端验证 @ $(date '+%F %T')（测试库 ${DB}，后缀 ${SFX}）=="

# ---------- 准备：一个有已发布报告的记录 ----------
RESP=$(call POST /api/auth/register "{\"username\":\"e_${SFX}\",\"password\":\"Passw0rd2026\"}")
TOKEN=$(p | jq -r '.data.token')
RESP=$(call POST /api/auth/register "{\"username\":\"e2_${SFX}\",\"password\":\"Passw0rd2026\"}")
OTHER_TOKEN=$(p | jq -r '.data.token')
RESP=$(call POST /api/upload/sign "{\"filename\":\"a.m4a\",\"consentVersion\":\"v1\",\"declaredSize\":2048}" "$TOKEN")
UPLOAD_ID=$(p | jq -r '.data.uploadId')
call POST /api/test/object-storage/put "{\"uploadId\":\"${UPLOAD_ID}\",\"contentBase64\":\"${M4A_B64}\"}" "$TOKEN" >/dev/null
call POST /api/upload/complete "{\"uploadId\":${UPLOAD_ID}}" "$TOKEN" >/dev/null
RESP=$(call POST /api/task/submit "{\"uploadId\":${UPLOAD_ID},\"declaredDurationSec\":60}" "$TOKEN")
TASK_ID=$(p | jq -r '.data.id')
sqlv "INSERT INTO sentences (task_id, sequence, speaker_id, start_ms, end_ms, text, flagged) VALUES
  (${TASK_ID}, 1, 0, 0, 3000, '你好，我是客服，手机号 13812345678。', 0),
  (${TASK_ID}, 2, 1, 3000, 6000, '知道了。', 0)" >/dev/null
sqlv "UPDATE recognition_tasks SET status='ANALYZING', expires_at=DATE_ADD(NOW(3), INTERVAL 30 DAY) WHERE id=${TASK_ID}" >/dev/null
RESP=$(call POST /api/admin/login "{\"username\":\"${ADMIN_USER}\",\"password\":\"${S11_ADMIN_PASSWORD}\"}")
ADMIN_TOKEN=$(p | jq -r '.data.token')
call POST /api/admin/rules "{\"code\":\"R-HIT-${SFX}\",\"groupName\":\"话术\",\"name\":\"索取隐私\",\"description\":\"平台规则\",\"matchType\":\"hit\",\"roleScope\":\"AGENT\",\"keywords\":[\"手机号\"],\"deduct\":30}" "$ADMIN_TOKEN" >/dev/null
RESP=$(call POST "/api/report/${TASK_ID}/speaker-map" '{"expectedRevision":0,"mapping":{"0":"AGENT","1":"CUSTOMER"}}' "$TOKEN")
expect "准备：角色确认并发布报告 200" 200 "$(s)"
REPORT_VERSION_ID=$(sqlv "SELECT id FROM report_versions WHERE task_id=${TASK_ID} AND status='PUBLISHED' ORDER BY version DESC LIMIT 1")
expect_eq "报告版本已就绪" "1" "$(sqlv "SELECT COUNT(*) FROM report_versions WHERE id=${REPORT_VERSION_ID} AND status='PUBLISHED'")"

# ---------- EXPORT-07 跨用户拒绝 ----------
RESP=$(call POST "/api/report/${TASK_ID}/exports" "{\"reportVersionId\":${REPORT_VERSION_ID}}" "$OTHER_TOKEN")
expect "EXPORT-07-1 他人导出该记录 404" 404 "$(s)"
RESP=$(call POST "/api/admin/recordings/${TASK_ID}/exports" "{\"reportVersionId\":${REPORT_VERSION_ID}}" "$TOKEN")
expect "EXPORT-07-2 用户 token 调后台导出路由 403" 403 "$(s)"

# ---------- 创建 PDF 导出并推进 ----------
RESP=$(call POST "/api/report/${TASK_ID}/exports" "{\"reportVersionId\":${REPORT_VERSION_ID},\"withAudio\":false}" "$TOKEN")
expect "创建 PDF 导出 200" 200 "$(s)"
EXPORT_PDF=$(p | jq -r '.data.exportId')
expect_jq "初始状态为排队中且类型为 pdf" '.data.status == "QUEUED" and .data.fileType == "pdf"'
RESP=$(call POST /api/test/pipeline/export-tick '{}' "$TOKEN")
expect "推进导出作业 200" 200 "$(s)"
RESP=$(call GET "/api/exports/${EXPORT_PDF}" '' "$TOKEN")
expect "查询导出状态 200" 200 "$(s)"
expect_jq "状态查询只回状态、不再下发下载地址" '.data.status == "COMPLETED" and .data.downloadUrl == null and .data.expiresAt != null'
AUDIT_BEFORE_DL=$(sqlv "SELECT COUNT(*) FROM audit_log WHERE action='EXPORT_DOWNLOAD' AND target_id='${EXPORT_PDF}'")
RESP=$(call GET "/api/exports/${EXPORT_PDF}" '' "$TOKEN")
AUDIT_AFTER_POLL=$(sqlv "SELECT COUNT(*) FROM audit_log WHERE action='EXPORT_DOWNLOAD' AND target_id='${EXPORT_PDF}'")
expect_eq "轮询状态不产生签发审计" "${AUDIT_BEFORE_DL}" "${AUDIT_AFTER_POLL}"
RESP=$(call GET "/api/exports/${EXPORT_PDF}/download" '' "$TOKEN")
expect "显式下载签发 200" 200 "$(s)"
expect_jq "下载接口给出短时下载地址" '.data.downloadUrl != null and .data.expiresAt != null'
expect_eq "下载记一条签发审计" "1" "$(sqlv "SELECT COUNT(*) FROM audit_log WHERE action='EXPORT_DOWNLOAD' AND target_id='${EXPORT_PDF}'")"
PDF_KEY=$(sqlv "SELECT artifact_key FROM export_jobs WHERE id=${EXPORT_PDF}")
expect_eq "产物已落对象存储" "1" "$(sqlv "SELECT COUNT(*) FROM export_jobs WHERE id=${EXPORT_PDF} AND artifact_key IS NOT NULL")"

# ---------- EXPORT-04 同版本同选项复用 ----------
RESP=$(call POST "/api/report/${TASK_ID}/exports" "{\"reportVersionId\":${REPORT_VERSION_ID},\"withAudio\":false}" "$TOKEN")
expect_eq "EXPORT-04-1 复用有效产物（同一作业）" "$EXPORT_PDF" "$(p | jq -r '.data.exportId')"
expect_eq "EXPORT-04-2 未重复生成" "1" "$(sqlv "SELECT COUNT(*) FROM export_jobs WHERE report_version_id=${REPORT_VERSION_ID} AND with_audio=0")"

# ---------- EXPORT-10 含音频 ZIP：内容与音频一致 ----------
RESP=$(call POST "/api/report/${TASK_ID}/exports" "{\"reportVersionId\":${REPORT_VERSION_ID},\"withAudio\":true}" "$TOKEN")
EXPORT_ZIP=$(p | jq -r '.data.exportId')
call POST /api/test/pipeline/export-tick '{}' "$TOKEN" >/dev/null
RESP=$(call GET "/api/exports/${EXPORT_ZIP}" '' "$TOKEN")
expect_jq "ZIP 导出完成" '.data.status == "COMPLETED" and .data.fileType == "zip"'
ZIP_KEY=$(sqlv "SELECT artifact_key FROM export_jobs WHERE id=${EXPORT_ZIP}")
RESP=$(call GET "/api/test/object-storage/object?key=${ZIP_KEY}" '' "$TOKEN")
expect "读取产物对象 200" 200 "$(s)"
p | jq -r '.data.contentBase64' | base64 -d > "$WORK/artifact.zip"
python3 - "$WORK/artifact.zip" "$M4A_B64" <<'PY' > "$WORK/zip-check.txt"
import base64, hashlib, sys, zipfile
path, m4a_b64 = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(path) as z:
    names = sorted(z.namelist())
    pdf = z.read('report.pdf')
    audio = z.read([n for n in names if n.startswith('audio')][0])
print('names=' + ','.join(names))
print('pdf_magic=' + pdf[:4].decode('latin-1'))
print('pdf_pages_hint=' + str(b'/Type /Page' in pdf or b'/Type/Page' in pdf))
print('audio_hash_match=' + str(hashlib.md5(audio).hexdigest() == hashlib.md5(base64.b64decode(m4a_b64)).hexdigest()))
PY
grep -q "names=audio.m4a,report.pdf" "$WORK/zip-check.txt" && ok "EXPORT-10-1 ZIP 内含 report.pdf 与 audio.m4a" || bad "EXPORT-10-1 ZIP 内容" "$(cat "$WORK/zip-check.txt")"
grep -q "pdf_magic=%PDF" "$WORK/zip-check.txt" && ok "EXPORT-10-2 ZIP 内 PDF 头正确" || bad "EXPORT-10-2 PDF 头" "$(cat "$WORK/zip-check.txt")"
grep -q "audio_hash_match=True" "$WORK/zip-check.txt" && ok "EXPORT-10-3 音频与原上传一致（md5 相同）" || bad "EXPORT-10-3 音频一致性" "$(cat "$WORK/zip-check.txt")"
expect_eq "含音频 ZIP 有效期不晚于原件保留截止" "1" "$(sqlv "SELECT COUNT(*) FROM export_jobs j JOIN recognition_tasks t ON t.id=${TASK_ID} WHERE j.id=${EXPORT_ZIP} AND j.expires_at <= t.expires_at")"

# ---------- EXPORT-05 原件过期拒绝打包 ----------
# 先让此前生成的 ZIP 产物过期：否则服务会复用有效产物（这是正确行为，但会绕过本用例）
sqlv "UPDATE export_jobs SET expires_at=DATE_SUB(NOW(3), INTERVAL 1 HOUR) WHERE report_version_id=${REPORT_VERSION_ID} AND with_audio=1 AND status='COMPLETED'" >/dev/null
sqlv "UPDATE recognition_tasks SET expires_at=DATE_SUB(NOW(3), INTERVAL 1 DAY) WHERE id=${TASK_ID}" >/dev/null
RESP=$(call POST "/api/report/${TASK_ID}/exports" "{\"reportVersionId\":${REPORT_VERSION_ID},\"withAudio\":true}" "$TOKEN")
EXPIRED_CODE=$(s)
if [ "$EXPIRED_CODE" = "404" ] || [ "$EXPIRED_CODE" = "422" ]; then
  ok "EXPORT-05-1 原件过期后拒绝打包（HTTP ${EXPIRED_CODE}）"
else
  bad "EXPORT-05-1 原件过期后拒绝打包" "实际 HTTP ${EXPIRED_CODE}"
fi
RESP=$(call POST "/api/report/${TASK_ID}/exports" "{\"reportVersionId\":${REPORT_VERSION_ID},\"withAudio\":false}" "$TOKEN")
expect "EXPORT-05-3 纯 PDF 不受原件过期影响（复用或新建）" 200 "$(s)"

# ---------- EXPORT-08 导出中删除：复查墓碑 ----------
sqlv "UPDATE recognition_tasks SET expires_at=DATE_ADD(NOW(3), INTERVAL 30 DAY) WHERE id=${TASK_ID}" >/dev/null
sqlv "UPDATE export_jobs SET expires_at=DATE_SUB(NOW(3), INTERVAL 1 HOUR) WHERE report_version_id=${REPORT_VERSION_ID} AND with_audio=1 AND status='COMPLETED'" >/dev/null
RESP=$(call POST "/api/report/${TASK_ID}/exports" "{\"reportVersionId\":${REPORT_VERSION_ID},\"withAudio\":true}" "$TOKEN")
ZIP2=$(p | jq -r '.data.exportId')
sqlv "UPDATE recognition_tasks SET deleted_at=NOW(3), status='DELETED' WHERE id=${TASK_ID}" >/dev/null
call POST /api/test/pipeline/export-tick '{}' "$TOKEN" >/dev/null
expect_eq "EXPORT-08-1 删除后作业失败且不发布产物" "TASK_DELETED" "$(sqlv "SELECT error_code FROM export_jobs WHERE id=${ZIP2}")"
expect_eq "EXPORT-08-2 失败作业没有产物 key" "0" "$(sqlv "SELECT COUNT(*) FROM export_jobs WHERE id=${ZIP2} AND artifact_key IS NOT NULL")"
sqlv "UPDATE recognition_tasks SET deleted_at=NULL, status='ANALYZING', audio_purged=0 WHERE id=${TASK_ID}" >/dev/null

# ---------- EXPORT-09 产物过期 ----------
sqlv "UPDATE export_jobs SET expires_at=DATE_SUB(NOW(3), INTERVAL 1 HOUR) WHERE id=${EXPORT_PDF}" >/dev/null
RESP=$(call GET "/api/exports/${EXPORT_PDF}" '' "$TOKEN")
expect "EXPORT-09 产物过期后明确报错 404" 404 "$(s)"

# ---------- 跨身份域：后台作业与 C 端作业互不串 ----------
RESP=$(call POST "/api/admin/recordings/${TASK_ID}/exports" "{\"reportVersionId\":${REPORT_VERSION_ID},\"withAudio\":false}" "$ADMIN_TOKEN")
expect "后台创建导出 200" 200 "$(s)"
ADMIN_EXPORT=$(p | jq -r '.data.exportId')
RESP=$(call GET "/api/exports/${ADMIN_EXPORT}" '' "$TOKEN")
expect "C 端读后台作业 404" 404 "$(s)"
RESP=$(call GET "/api/admin/exports/${EXPORT_PDF}" '' "$ADMIN_TOKEN")
expect "后台读 C 端作业 404" 404 "$(s)"

# ---------- 审计：导出创建与签发都留痕，且不含链接 ----------
AUDIT_CREATES=$(sqlv "SELECT COUNT(*) FROM audit_log WHERE action='EXPORT_CREATE' AND target_id='${TASK_ID}'")
if [ "${AUDIT_CREATES:-0}" -ge 1 ]; then
  ok "导出动作已留痕（${AUDIT_CREATES} 条）"
else
  bad "导出动作已留痕" "没有 EXPORT_CREATE 记录"
fi
expect_eq "审计不含链接" "0" "$(sqlv "SELECT COUNT(*) FROM audit_log WHERE action LIKE 'EXPORT%' AND detail LIKE '%http%'")"

rm -rf "$WORK"
echo "== 结果：PASS=${PASS} FAIL=${FAIL} =="
[ "$FAIL" -eq 0 ]
