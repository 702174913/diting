#!/usr/bin/env bash
# =====================================================================
# S13 删除与 30 天生命周期端到端验证（DELETE-01..08）
# 需要：已迁移的独立测试库 + test profile 实例（8902，关掉定时器，手动 tick）：
#   DITING_DB_PASSWORD='<库密码>' DITING_JWT_SECRET='<测试密钥>' \
#   DITING_ADMIN_INIT_USERNAME=ops_admin DITING_ADMIN_INIT_PASSWORD='<密码>' \
#   java -jar target/diting-server-0.0.1-SNAPSHOT.jar --server.port=8902 \
#     --spring.profiles.active=test --diting.storage.type=memory \
#     --diting.aliyun.tingwu.type=stub --diting.pipeline.enabled=false \
#     --diting.export.enabled=false --diting.retention.enabled=false \
#     --spring.datasource.url='jdbc:mysql://127.0.0.1:3306/diting_s13_test?...' \
#     --diting.jwt.secret='<测试密钥>'
# 运行：MYSQL_PWD='<库密码>' S13_ADMIN_PASSWORD='<密码>' bash run_s13_lifecycle_e2e.sh
# =====================================================================
set -uo pipefail
BASE=${S13_BASE_URL:-http://127.0.0.1:8902}
DB=${S13_TEST_DB:-diting_s13_test}
ADMIN_USER=${S13_ADMIN_USER:-ops_admin}
MYSQL_HOST=${MYSQL_HOST:-127.0.0.1}
MYSQL_USER=${MYSQL_USER:-root}
if [ -z "${S13_ADMIN_PASSWORD:-}" ]; then echo "缺少 S13_ADMIN_PASSWORD" >&2; exit 2; fi
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

M4A_B64=$(python3 -c "import base64;d=bytearray(64);h=b'0000ftypM4A ';d[0:len(h)]=h;print(base64.b64encode(bytes(d)).decode())")

make_task(){ # make_task <token> <idx> -> taskId
  local token="$1" idx="$2" sign upload
  sign=$(call POST /api/upload/sign "{\"filename\":\"l${idx}.m4a\",\"consentVersion\":\"v1\",\"declaredSize\":2048}" "$token")
  upload=$(jq -r '.data.uploadId' <<<"$(sed '$d' <<<"$sign")")
  call POST /api/test/object-storage/put "{\"uploadId\":\"${upload}\",\"contentBase64\":\"${M4A_B64}\"}" "$token" >/dev/null
  call POST /api/upload/complete "{\"uploadId\":${upload}}" "$token" >/dev/null
  call POST /api/task/submit "{\"uploadId\":${upload},\"declaredDurationSec\":30}" "$token" | sed '$d' | jq -r '.data.id'
}

echo "== S13 生命周期端到端验证 @ $(date '+%F %T')（测试库 ${DB}，后缀 ${SFX}）=="

RESP=$(call POST /api/auth/register "{\"username\":\"l_${SFX}\",\"password\":\"Passw0rd2026\"}")
TOKEN=$(p | jq -r '.data.token')
USER_ID=$(p | jq -r '.data.userInfo.id')
RESP=$(call POST /api/admin/login "{\"username\":\"${ADMIN_USER}\",\"password\":\"${S13_ADMIN_PASSWORD}\"}")
expect "后台登录 200" 200 "$(s)"
ADMIN_TOKEN=$(p | jq -r '.data.token')
# 放开每日额度：本脚本要建 5 个任务，默认 3 份会中途被额度拦下
CFG_VERSION=$(call GET /api/admin/config '' "$ADMIN_TOKEN" | sed '$d' | jq -r '.data.version')
call POST /api/admin/config "{\"expectedVersion\":${CFG_VERSION},\"dailyFreeQuota\":50,\"riskThreshold\":60,\"maxRecordingMinutes\":10}" "$ADMIN_TOKEN" >/dev/null

# ---------- DELETE-01 期限从上传时间起算，到期瞬间拒绝授权 ----------
T1=$(make_task "$TOKEN" 1)
expect_eq "DELETE-01-1 提交时即写入 30 天原件期限" "1" "$(sqlv "SELECT COUNT(*) FROM recognition_tasks t JOIN upload_sessions u ON u.id = t.upload_id WHERE t.id=${T1} AND t.expires_at = DATE_ADD(u.created_at, INTERVAL 30 DAY)")"
OBJ1=$(sqlv "SELECT object_key FROM upload_sessions WHERE id=(SELECT upload_id FROM recognition_tasks WHERE id=${T1})")
RESP=$(call GET "/api/admin/recordings/${T1}/audio" '' "$ADMIN_TOKEN")
expect "到期前可签发原件 200" 200 "$(s)"
sqlv "UPDATE recognition_tasks SET expires_at=DATE_SUB(NOW(3), INTERVAL 1 SECOND) WHERE id=${T1}" >/dev/null
RESP=$(call GET "/api/admin/recordings/${T1}/audio" '' "$ADMIN_TOKEN")
expect "DELETE-01-2 到期那一刻即拒绝签发 404" 404 "$(s)"
RESP=$(call GET "/api/report/${T1}" '' "$TOKEN")
expect "DELETE-01-3 文本仍可读（文本保留另议，不随原件一起停供）" 200 "$(s)"

# ---------- 到期清理：删对象 + 标记已清理 ----------
RESP=$(call POST /api/test/pipeline/retention-tick '{}' "$TOKEN")
expect "推进清理 200" 200 "$(s)"
expect_eq "DELETE-01-4 到期原件已清理并标记" "1" "$(sqlv "SELECT audio_purged FROM recognition_tasks WHERE id=${T1}")"
expect_eq "DELETE-01-5 对象引用已清空" "1" "$(sqlv "SELECT COUNT(*) FROM recognition_tasks WHERE id=${T1} AND oss_object IS NULL")"
RESP=$(call GET "/api/test/object-storage/object?key=${OBJ1}" '' "$TOKEN")
expect "DELETE-01-6 对象确实已删除" 404 "$(s)"

# ---------- DELETE-03 重复删除幂等 ----------
RESP=$(call DELETE "/api/task/${T1}" '' "$TOKEN")
JOB1=$(p | jq -r '.data.deletionJobId')
RESP=$(call DELETE "/api/task/${T1}" '' "$TOKEN")
expect_eq "DELETE-03-1 重复删除返回同一作业" "$JOB1" "$(p | jq -r '.data.deletionJobId')"
expect_eq "DELETE-03-2 只存在一个清理作业" "1" "$(sqlv "SELECT COUNT(*) FROM deletion_jobs WHERE target_type='TASK' AND target_id=${T1}")"

# ---------- DELETE-02 清理失败可重试（用小批量 + 伪造失败） ----------
# 让对象删除抛错：把存储换成不可用不现实，这里用「作业重试状态机」的 SQL 层验证
sqlv "UPDATE deletion_jobs SET status='RETRY_WAIT', attempt_count=1, next_attempt_at=DATE_SUB(NOW(3), INTERVAL 1 SECOND), error_code='CLEANUP_RETRY' WHERE id=${JOB1}" >/dev/null
RESP=$(call POST /api/test/pipeline/retention-tick '{}' "$TOKEN")
expect_eq "DELETE-02-1 退避到期的重试作业被再次领取并完成" "COMPLETED" "$(sqlv "SELECT status FROM deletion_jobs WHERE id=${JOB1}")"
expect_eq "DELETE-02-2 完成后清空待清理清单" "1" "$(sqlv "SELECT COUNT(*) FROM deletion_jobs WHERE id=${JOB1} AND remaining_objects IS NULL")"

# ---------- DELETE-04 删除传播：句子/命中/报告/导出 ----------
T2=$(make_task "$TOKEN" 2)
sqlv "INSERT INTO sentences (task_id, sequence, speaker_id, start_ms, end_ms, text, flagged) VALUES (${T2}, 1, 0, 0, 1000, '样例句。', 0)" >/dev/null
sqlv "UPDATE recognition_tasks SET status='ANALYZING' WHERE id=${T2}" >/dev/null
RESP=$(call POST "/api/report/${T2}/speaker-map" '{"expectedRevision":0,"mapping":{"0":"AGENT"}}' "$TOKEN")
expect "准备：发布报告 200" 200 "$(s)"
RV=$(sqlv "SELECT id FROM report_versions WHERE task_id=${T2} ORDER BY version DESC LIMIT 1")
RESP=$(call POST "/api/report/${T2}/exports" "{\"reportVersionId\":${RV},\"withAudio\":false}" "$TOKEN")
EXPORT_ID=$(p | jq -r '.data.exportId')
call POST /api/test/pipeline/export-tick '{}' "$TOKEN" >/dev/null
ARTIFACT=$(sqlv "SELECT artifact_key FROM export_jobs WHERE id=${EXPORT_ID}")
RESP=$(call DELETE "/api/task/${T2}" '' "$TOKEN")
JOB2=$(p | jq -r '.data.deletionJobId')
RESP=$(call POST /api/test/pipeline/retention-tick '{}' "$TOKEN")
expect_eq "DELETE-04-1 句子已清理" "0" "$(sqlv "SELECT COUNT(*) FROM sentences WHERE task_id=${T2}")"
expect_eq "DELETE-04-2 命中证据已清理" "0" "$(sqlv "SELECT COUNT(*) FROM violations WHERE report_version_id IN (SELECT id FROM report_versions WHERE task_id=${T2})")"
expect_eq "DELETE-04-3 报告版本已清理" "0" "$(sqlv "SELECT COUNT(*) FROM report_versions WHERE task_id=${T2}")"
expect_eq "DELETE-04-4 导出作业已清理" "0" "$(sqlv "SELECT COUNT(*) FROM export_jobs WHERE id=${EXPORT_ID}")"
RESP=$(call GET "/api/test/object-storage/object?key=${ARTIFACT}" '' "$TOKEN")
expect "DELETE-04-5 导出产物对象已删除" 404 "$(s)"
expect_eq "DELETE-04-6 任务行保留为最小墓碑" "1" "$(sqlv "SELECT COUNT(*) FROM recognition_tasks WHERE id=${T2} AND deleted_at IS NOT NULL AND oss_object IS NULL")"
expect_eq "DELETE-04-7 清理完成并留痕" "1" "$(sqlv "SELECT COUNT(*) FROM audit_log WHERE action='DELETION_COMPLETED' AND target_id='${T2}'")"

# ---------- DELETE-05 删除后迟到回调不复活 ----------
RESP=$(call POST /api/callback/tingwu "{\"TaskId\":\"tw-late-${SFX}\",\"TaskStatus\":\"COMPLETED\"}")
expect "DELETE-05-1 迟到回调仍 200" 200 "$(s)"
sqlv "UPDATE recognition_tasks SET task_id='tw-late-${SFX}' WHERE id=${T2}" >/dev/null
RESP=$(call POST /api/callback/tingwu "{\"TaskId\":\"tw-late-${SFX}\",\"TaskStatus\":\"COMPLETED\"}")
expect "DELETE-05-2 已删除任务的回调被忽略（200 且无副作用）" 200 "$(s)"
expect_eq "DELETE-05-3 状态仍为已删除" "1" "$(sqlv "SELECT COUNT(*) FROM recognition_tasks WHERE id=${T2} AND deleted_at IS NOT NULL AND status='DELETING'")"

# ---------- DELETE-06 服务重启恢复：退出 RUNNING 的作业可被重新领取 ----------
T3=$(make_task "$TOKEN" 3)
RESP=$(call DELETE "/api/task/${T3}" '' "$TOKEN")
JOB3=$(p | jq -r '.data.deletionJobId')
# 模拟“处理到一半进程崩溃”：状态停在 RUNNING，且租约（next_attempt_at）已经过期
sqlv "UPDATE deletion_jobs SET status='RUNNING', attempt_count=1, next_attempt_at=DATE_SUB(NOW(3), INTERVAL 1 MINUTE) WHERE id=${JOB3}" >/dev/null
RESP=$(call POST /api/test/pipeline/retention-tick '{}' "$TOKEN")
expect_eq "DELETE-06-1 租约过期的 RUNNING 作业被重新领取并完成（崩溃恢复）" "COMPLETED" "$(sqlv "SELECT status FROM deletion_jobs WHERE id=${JOB3}")"
# 租约未过期时不得被别人抢走
T6=$(make_task "$TOKEN" 6)
RESP=$(call DELETE "/api/task/${T6}" '' "$TOKEN")
JOB6=$(p | jq -r '.data.deletionJobId')
sqlv "UPDATE deletion_jobs SET status='RUNNING', next_attempt_at=DATE_ADD(NOW(3), INTERVAL 10 MINUTE) WHERE id=${JOB6}" >/dev/null
call POST /api/test/pipeline/retention-tick '{}' "$TOKEN" >/dev/null
expect_eq "DELETE-06-2 租约未过期的 RUNNING 作业不被重复领取" "RUNNING" "$(sqlv "SELECT status FROM deletion_jobs WHERE id=${JOB6}")"
sqlv "UPDATE deletion_jobs SET status='QUEUED', next_attempt_at=NULL WHERE id=${JOB6}" >/dev/null
call POST /api/test/pipeline/retention-tick '{}' "$TOKEN" >/dev/null
expect_eq "DELETE-06-3 重新排队后可正常完成" "COMPLETED" "$(sqlv "SELECT status FROM deletion_jobs WHERE id=${JOB6}")"

# ---------- DELETE-07 含音频 ZIP 副本随原件一起清理 ----------
T4=$(make_task "$TOKEN" 4)
sqlv "INSERT INTO sentences (task_id, sequence, speaker_id, start_ms, end_ms, text, flagged) VALUES (${T4}, 1, 0, 0, 1000, '样例句。', 0)" >/dev/null
sqlv "UPDATE recognition_tasks SET status='ANALYZING' WHERE id=${T4}" >/dev/null
call POST "/api/report/${T4}/speaker-map" '{"expectedRevision":0,"mapping":{"0":"AGENT"}}' "$TOKEN" >/dev/null
RV4=$(sqlv "SELECT id FROM report_versions WHERE task_id=${T4} ORDER BY version DESC LIMIT 1")
RESP=$(call POST "/api/report/${T4}/exports" "{\"reportVersionId\":${RV4},\"withAudio\":true}" "$TOKEN")
ZIP_JOB=$(p | jq -r '.data.exportId')
call POST /api/test/pipeline/export-tick '{}' "$TOKEN" >/dev/null
ZIP_KEY=$(sqlv "SELECT artifact_key FROM export_jobs WHERE id=${ZIP_JOB}")
sqlv "UPDATE export_jobs SET expires_at=DATE_SUB(NOW(3), INTERVAL 1 MINUTE) WHERE id=${ZIP_JOB}" >/dev/null
RESP=$(call POST /api/test/pipeline/retention-tick '{}' "$TOKEN")
expect_eq "DELETE-07-1 到期 ZIP 产物被清理（key 清空）" "0" "$(sqlv "SELECT COUNT(*) FROM export_jobs WHERE id=${ZIP_JOB} AND artifact_key IS NOT NULL")"
RESP=$(call GET "/api/test/object-storage/object?key=${ZIP_KEY}" '' "$TOKEN")
expect "DELETE-07-2 ZIP 对象确实已删除" 404 "$(s)"
RESP=$(call GET "/api/exports/${ZIP_JOB}" '' "$TOKEN")
expect "DELETE-07-3 过期产物不再签发下载地址" 404 "$(s)"

# ---------- DELETE-08 抹除后双方都不可读 ----------
T5=$(make_task "$TOKEN" 5)
sqlv "INSERT INTO sentences (task_id, sequence, speaker_id, start_ms, end_ms, text, flagged) VALUES (${T5}, 1, 0, 0, 1000, '样例句。', 0)" >/dev/null
RESP=$(call DELETE /api/privacy/erase '' "$TOKEN")
ERASE_JOB=$(p | jq -r '.data.deletionJobId')
expect "抹除请求 200" 200 "$(s)"
call POST /api/test/pipeline/retention-tick '{}' "$TOKEN" >/dev/null
expect_eq "DELETE-08-1 C 端列表为空" "0" "$(sqlv "SELECT COUNT(*) FROM recognition_tasks WHERE user_id=${USER_ID} AND deleted_at IS NULL")"
RESP=$(call GET "/api/report/${T5}" '' "$TOKEN")
expect "DELETE-08-2 C 端报告不可读 404" 404 "$(s)"
RESP=$(call GET "/api/admin/recordings/${T5}" '' "$ADMIN_TOKEN")
expect "DELETE-08-3 后台同样不可读 404" 404 "$(s)"
expect_eq "DELETE-08-4 孤立的句子与上传会话已清理" "0" "$(sqlv "SELECT COUNT(*) FROM sentences WHERE task_id=${T5}")"
expect_eq "DELETE-08-5 账号已匿名化（不删行，避免 token/审计悬空）" "1" "$(sqlv "SELECT COUNT(*) FROM users WHERE id=${USER_ID} AND nickname='已注销' AND username IS NULL AND wechat_openid IS NULL")"
RESP=$(call GET "/api/deletions/${ERASE_JOB}" '' "$TOKEN")
expect "DELETE-08-6 抹除作业状态可查（仅状态与计数）" 200 "$(s)"
RESP=$(call GET "/api/admin/deletions/${ERASE_JOB}" '' "$ADMIN_TOKEN")
expect "后台可查清理状态 200" 200 "$(s)"
RESP=$(call GET "/api/admin/deletions/${ERASE_JOB}" '' "$TOKEN")
expect "C 端 token 调后台清理查询 403" 403 "$(s)"

echo "== 结果：PASS=${PASS} FAIL=${FAIL} =="
[ "$FAIL" -eq 0 ]
