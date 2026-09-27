# 后端审核 · diting-server（只读审查）

> 证据范围：`diting-server/src/main/**` 与 `src/test/**` 源码、`src/main/resources/sql/migration/**`、`docs/server-review*.md`、`docs/server-fix-batch1.md`（S15 只做了检索，未通读）。本工作区根目录与 `diting-server/` 都不是 Git 仓库（仅 `diting-admin/.git`），**本轮没有任何 diff 证据**，所有"是否已修复"的判断均来自当前源码阅读，不代表已运行验证。

---

F1 · P0 · OPERATOR 仍可通过资产接口拿到原始录音地址（历史 R01 未修复）
位置：`diting-server/src/main/java/com/diting/service/TaskAssetService.java:49-70`（第 63 行签发）、`controller/AssetController.java:44-49`，对照 `controller/AdminRecordingController.java:142-143`
证据：
```java
// TaskAssetService.list：只收 actorDomain，不收角色，也不看 asset.kind
56  for (TaskAsset asset : taskAssetMapper.selectByTask(taskId)) {
63      String url = objectStorage.presignGet(asset.getObjectKey(),
// AssetController
44  @GetMapping("/api/admin/recordings/{id}/assets")
45  @RequireRole({AdminRole.ADMIN, AdminRole.OPERATOR})
// AdminRecordingController：同一条录音的原件另一条路径却限 ADMIN
142 @GetMapping("/{id}/audio")
143 @RequireRole(AdminRole.ADMIN)
```
影响：运营角色按设计只能看脱敏文本（`AdminRecordingController.detail` 走 `redact()`），但可改用 `/assets` 拿到 15 分钟有效的原始录音 GET 链接；同一份数据的第二条路径权限更宽，这是明确的越权读原件，且审计里只留下一条看起来合规的 `ASSET_PRESIGN`。
最小修法：`list()` 增加 `actorRole` 参数，`kind=AUDIO` 时只有 ADMIN 才签发（否则返回 `withoutUrl`），并在 `AdminEndpointSecurityTest` 里补 `/audio` 与 `/assets` 的 OPERATOR 403/200 矩阵。

F2 · P0 · 列表 `flaggedCount` 恒为 0：首页/历史把命中违规的记录渲染成"没有发现要留意的话"（历史 R13 未修复）
位置：`service/RecognitionTaskService.java:288-305`（295 行 `SUM(flagged)`）、`controller/TaskController.java:86-93`、`service/TaskPipelineService.java:298,429`
证据：
```java
// 全生产源码只有两处写 flagged，且都是 false（grep setFlagged 仅 298/429 命中）
298  row.setFlagged(false);     429  row.setFlagged(false);
// 列表数值来源
295  wrapper.select("task_id", "COUNT(*) AS total", "SUM(flagged) AS flagged")
```
前端 `diting-app/src/components/RecordCard.vue:25,51` 用该字段判定 `pass/warn` 与文案（`flaggedCount ? '找到 N 处要留意的话' : '没有发现要留意的话'`），数据源正是 `GET /api/task/list`。
影响：任何已完成、报告命中违规的记录，列表都给出绿色"通过"与"没有发现要留意的话"——这是与详情页相反的错误结论，而不是少的数字；合规质检产品里这等于把"有问题"判成"没问题"。
最小修法：`countSentences` 改为按任务最新 PUBLISHED 版本的 `violations` 去重句数（JOIN `report_versions`/`violations`），或在发布事务里回填 `sentences.flagged`。

F3 · P1 · 图片资产卡在 RUNNING 后任务永久停在 TRANSCRIBING，重启也救不回（历史 R02 未修复）
位置：`service/TaskPipelineService.java:196-236`（207 行 `read` 在 try 之外）、`mapper/TaskAssetMapper.java:17-19,23-26`、`resources/sql/migration/V4__S16_media_assets.sql:82-104`
证据：
```java
202  if (taskAssetMapper.markOcrRunning(asset.getId()) == 0) { allDone = false; continue; }  // 只认 PENDING/FAILED
207  byte[] bytes = objectStorage.read(asset.getObjectKey());   // 抛错 → advanceOne catch(Exception) → backoff(TRANSCRIBING)
// selectImagesToOcr 反过来包含 RUNNING：RUNNING 资产每轮都会被取回，但领取永远 0 行 → 永远 RETRY
```
`task_assets` 没有租约列也没有尝试次数字段（V4 建表语句已核对）。
影响：一次 OSS 读取异常（或进程在 `markOcrRunning` 之后被杀）就让该任务在 `TRANSCRIBING` 永久空转，永不进入终态、也没有失败提示；重启无效。
最小修法：为 `task_assets` 增加租约/尝试列，`read` 异常也走 `markOcrFailed`，并让 `selectImagesToOcr` 只取"RUNNING 且租约过期"的行。

F4 · P1 · 导出作业在"上传失败/进程退出"后永久停在 RUNNING，不再重试（历史 R05 未修复）
位置：`service/export/ExportService.java:101-107,157,163-179`
证据：
```java
101  List<ExportJob> queued = exportJobMapper.selectList(... .eq(ExportJob::getStatus, STATUS_QUEUED) ...)  // 只扫 QUEUED
157  objectStorage.put(key, artifact, contentType);   // try/catch 之外，抛错直接冒泡出 tick()
165  if (job == null || !STATUS_QUEUED.equals(job.getStatus())) { return null; }   // RUNNING 永不重领
178  job.setStatus(STATUS_RUNNING); exportJobMapper.updateById(job);              // 先读后写，非 CAS
```
影响：对象存储一次抖动即可让用户的导出永久卡在"生成中"；该作业还会被产物过期清理跳过（`selectExpiredArtifacts` 要求 `artifact_key` 非空）。多实例下两个进程可同时领取同一作业并把同名 key 重复上传。
最小修法：领取改条件更新（`QUEUED→RUNNING` 并校验影响行数），`put` 失败写 `RETRY_WAIT`，并让 `tick` 同时回收超期 RUNNING。

F5 · P1 · 历史版本报告的角色用"最新映射"渲染，导出路径可复现（历史 R07 未修复）
位置：`service/report/ReportService.java:254-259`、`service/export/ExportService.java:283-290`
证据：
```java
258  Map<Integer, String> roles = currentRoles(task.getId());   // 最新 revision，不是 version.getMappingRevision()
290  var detail = reportService.detailOf(task, version);        // 导出按 reportVersionId 指定的版本行
```
影响：对调角色再读/导出旧版，逐句角色来自新 revision，而证据与分数来自旧 revision；导出作业若在角色重算之后才被领取，PDF 的"角色-证据"对应关系不可复算——合规证据链失效。
最小修法：`detailOf` 改用 `rolesAt(task.getId(), version.getMappingRevision())`，另在响应里增加 `currentMappingRevision` 专供前端 CAS 使用。

F6 · P1 · 额度账务不闭环：纯图片任务永不结算，`release()` 无任何调用方，两条更新不在同一事务（历史 R09 未修复）
位置：`service/TaskPipelineService.java:328`、`service/QuotaService.java:85-117`、`service/RecognitionTaskService.java:159`
证据：
```java
// 生产源码仅有这三处额度调用（grep：quotaService. 只有下面三处；release( 零调用）
TaskPipelineService:328  quotaService.consume(task.getId());   // 只在音频提交成功路径
RecognitionTaskService:159 quotaService.reserve(userId, task.getId(), config.dailyFreeQuota());
// consume 内部是两条独立语句，方法上没有 @Transactional
90   int cas = quotaLedgerMapper.casState(...);
95   dailyQuotaMapper.consumeReserved(ledger.getUserId(), ledger.getBusinessDate());
```
影响：图片/混合任务的流水永远停在 RESERVED，当日额度被永久占用（用户看到"已用完"却没有消费记录），后台"额度预占（未结算）"异常增长；`casState` 成功后进程中断则流水与日计数永久不一致且无法重试修复。测试覆盖了 `release` 的幂等语义，但生产没有任何调用者。
最小修法：`consume/release` 加 `@Transactional`；图片任务成功发布时也调用 `consume`，并为确定未受理的失败补一条 `release`（或人工核销入口）。

F7 · P1 · 提交/对账语义未闭环：未配置也照常登记扣额度；任何云端失败都被归为"不确定"且从不真正对账（历史 R04 未修复）
位置：`service/TaskPipelineService.java:107-118,305-345`、`service/tingwu/AliyunTingwuApi.java:41-45`
证据：
```java
107  if (hasAudio && !tingwuService.isConfigured()) { return false; }   // 不动状态、不判失败 → 任务永久排队
110  if (!hasAudio && !ocrService.isConfigured()) { return false; }
// AliyunTingwuApi.createTask：一切异常都包成 IllegalStateException（含 4xx 明确拒绝）
45   throw new IllegalStateException("听悟 CreateTask 调用失败：" + e.getMessage(), e);
339  if (attempts >= props.getPipeline().getMaxAttempts()) { ... ERR_SUBMIT_UNRECONCILED ... }  // reconcile 全程不查远端
```
影响：缺凭据的部署上用户能提交成功（200）并被预占额度，任务永远停在"排队中，马上开始"；明确的云端拒绝（403/400）被当成"结果不确定"，空转 5 轮后才 FAILED，额度永久 RESERVED，而代码里没有任何远端对账或人工核销/退还入口。
最小修法：`submit` 阶段按 mediaKind 校验客户端已配置（未配置直接 503，不登记不预占）；`createTask` 按 code/HTTP 状态区分"确定未受理"（BizException→退额度）与不确定，并让 `reconcileSubmit` 真正按 TaskKey/远端状态查询一次。

F8 · P1 · 抹除后旧 token 仍能写入并重新绑定微信（历史 R18 未修复）
位置：`config/UserAuthInterceptor.java:23-28`、`service/TokenService.java:38-52`、`mapper/UserMapper.java:15-17`、`service/AuthService.java:97-112`
证据：
```java
27  LoginUserHolder.set(LoginUser.user(requireSubject(request)));   // 只验 JWT，不查账号是否已注销/已抹除
UserMapper.anonymize：UPDATE users SET username=NULL, password_hash=NULL, wechat_openid=NULL, ... WHERE id=#{id}   // 行仍在，无状态位
```
影响：请求"清除我的数据"后，72 小时内保留 token 的调用方仍可上传、继续生成新记录（与清理作业叠加），并能 `POST /api/auth/bind-wechat` 把新微信重新绑回已匿名化账号；隐私页承诺的"清除后即不可用"不成立。
最小修法：`users` 增加注销/撤销字段（或 token 版本），用户拦截器每次请求回库复核，注销后拒绝写入与绑定。

F9 · P1 · 删除完成后在途工作者仍可把整份转写原文插回（历史 R17 未修复）
位置：`service/TaskPipelineService.java:268-303,415-436`、`service/lifecycle/RetentionService.java:151-184`
证据：
```java
415  public void persistSentences(Long taskId, TranscriptionParser.ParsedTranscription parsed) {   // 无 deleted_at/版本校验
428      sentenceMapper.insert(row);
268  public void refreshAssetSentences(Long taskId) { ... sentenceMapper.insert(row); }           // 同样无校验
```
`cleanupTask` 已删除 sentences、清空对象并 `markAudioPurged`，作业标记 COMPLETED 后不会再清第二轮。
影响：清理作业报"完成"之后，旧工作者（快照在删除前取得）仍能把整份通话原文重新落库，且这批新行永远不会被再次清理——"删除即不留正文"的承诺在竞态下不成立。
最小修法：`persistSentences/refreshAssetSentences` 入口用 `lockById` 或在 SQL 上带 `deleted_at IS NULL` 条件校验，删除后直接放弃写入。

F10 · P1 · 清理作业没有所有权/代际栅栏，旧工作者可把新一轮作业标成已完成（历史 R24 未修复）
位置：`mapper/DeletionJobMapper.java:14-30,44-55`、`service/lifecycle/RetentionService.java:107-142`
证据：
```java
14  @Update("UPDATE deletion_jobs SET status = #{next}, attempt_count = attempt_count + 1, ... WHERE id = #{id} AND status = #{expected}")  // 只比 status
54  ... "WHERE id = #{id} AND status = 'RUNNING'"     // markCompletedRunning：不校验是谁领的
18  OR (status = 'RUNNING' AND next_attempt_at <= NOW(3))   // 租约超时后重新领取
```
影响：用户 rearm（`rearm` 把作业改回 QUEUED 并并入新对象）后，新工作者领成 RUNNING；旧工作者随后的 `markCompletedRunning` 也会命中（状态又变回 RUNNING），于是把尚未清理的新对象清单清空并报 COMPLETED——原件留在 OSS 且不再有作业清理它；两实例同时领取还会重复清理。
最小修法：为 `deletion_jobs` 增加 generation/lease_token，领取与完成都按 token + 租约条件 CAS。

F11 · P1 · 同一图片上传会话可被并发登记到两个任务（历史 R20 未修复）
位置：`service/UploadSessionService.java:192-205`、`resources/sql/migration/V4__S16_media_assets.sql:99`
证据：
```java
203  session.setStatus(STATUS_CONSUMED);
204  uploadSessionMapper.updateById(session);      // 先读 VERIFIED 再无条件下写，非 CAS
-- task_assets 只有 UNIQUE KEY uk_task_sequence (task_id, sequence)，upload_id 无唯一约束
```
影响：用不同幂等键的两个并发请求都能读到 VERIFIED 并各自登记任务，两个任务共享同一 objectKey/uploadId：重复 OCR 计费、重复预占额度；删除其中一个任务的清理会删掉另一个仍在引用的图片原件（数据丢失）。
最小修法：`consumeForSubmit` 改为条件更新 `status='VERIFIED'` → `'CONSUMED'` 并校验影响行数；`task_assets.upload_id` 加唯一约束。

F12 · P1 · "至少保留一个启用 ADMIN"仍是可并发的读后写（历史 R23 未修复）
位置：`service/AdminUserService.java:133,150-155,172`
证据：
```java
133  if (losesAdmin && countEnabledAdmins() <= 1) { throw ... }   // update
172      && countEnabledAdmins() <= 1) { throw ... }              // softDelete
151  return adminUserMapper.selectCount(...)                       // 普通 COUNT，无锁、无条件更新
```
影响：两个启用的 ADMIN 被同时降级/注销时，两个请求都可能读到 count=2 而都成功，后台从此没有任何启用 ADMIN，没有任何自助恢复入口（只能改库）。
最小修法：对成员变更使用同一把数据库锁（如锁定管理行 `FOR UPDATE`），或把"启用的 ADMIN 数"做成条件更新后再写入。

F13 · P1 · 策略配置的 expectedVersion 不是原子乐观锁（历史 R19 未修复）
位置：`service/ConfigService.java:94-108,145-157`
证据：
```java
98   long currentVersion = maxVersion(rows);
99   if (!rows.isEmpty() && expectedVersion != currentVersion) { throw ...409... }
105  upsert(KEY_DAILY_FREE_QUOTA, ... nextVersion, now);   // 无条件 updateById，version 列无 @Version
155      appConfigMapper.updateById(config);
```
影响：两个 ADMIN 同时保存，都能通过版本校验，后提交者静默覆盖前者的三项策略（三次配额、风险阈值、最长时长），版本号都只是 +1，冲突不可见——阈值/配额这种直接影响判读与计费的参数被无痕覆盖。
最小修法：把三项写入改为按 `expectedVersion` 的条件更新（`UPDATE ... WHERE config_key=? AND version=?`，任一 0 行即回滚 409）。

F14 · P1 · OCR 失败没有次数上限，会无限重试并持续计费（历史 R04 第二条未修复）
位置：`service/TaskPipelineService.java:196-236`、`mapper/TaskAssetMapper.java:17-19,23-26`
证据：
```java
// 注释声称"失败按任务级次数上限重试，避免重复付费"，但代码中没有任何计数：
196  List<TaskAsset> pending = taskAssetMapper.selectImagesToOcr(task.getId());  // FAILED 会被再次取回
202      if (taskAssetMapper.markOcrRunning(asset.getId()) == 0) { ... }              // FAILED → RUNNING 可再次领取
224      taskAssetMapper.markOcrFailed(asset.getId(), ...);                            // 下一轮再来一次 recognize(计费)
```
`task_assets` 无尝试次数字段（V4 建表已核对），`attempt_count` 只在 `reconcileSubmit` 一处被检查。
影响：内容被云端永久拒绝或 OCR 侧持续 5xx 的图片会被每轮重新付费识别，直到人工发现；任务本身也不会失败告警。
最小修法：增加 `ocr_attempts`，超过上限置 FAILED 终态并排除出 `selectImagesToOcr`，按任务级上限结束任务。

F15 · P2 · "权限拦截次数"恒为 0：没有任何代码写 DENIED 审计行（AI_CHANGELOG 待决线索核实）
位置：`service/admin/AuditLogService.java:40`（仅定义）、`config/AdminAuthInterceptor.java:58-74` 与 `config/BaseAuthInterceptor.java:39-52`（只抛异常不写审计）、`service/stats/StatsService.java:285,305`、`mapper/StatsMapper.java:179-181`
证据：
```java
AuditLogService:40  public static final String RESULT_DENIED = "DENIED";   // 生产代码 0 引用
AdminAuthInterceptor:74  throw new BizException(ResultCode.FORBIDDEN);      // 拦截即返回，不落审计
StatsService:285  long denied = results.getOrDefault("DENIED", 0L);         // 该指标被标成 Source.REAL
```
全仓 grep 的其它 DENIED 命中只有前端映射、Admin 端文案与演示数据 `sql/seed/S18_demo_seed.sql:782-787`（造了 6 条 DENIED 行）。
影响：结论 = 生产环境该指标结构性恒为 0，而演示库里有非 0 值——用演示数据会得出"越权拦截工作正常"的错误结论；同时每次真实越权尝试在审计里根本不存在（审计缺口本身）。
最小修法：在拦截器的身份域/角色拒绝分支写一条 `RESULT_DENIED` 审计（只记 actor id、action、target id），并把该指标的 note 改成"未接入前恒为 0"。

F16 · P2 · 统计口径：有效报告量会重复计入重算，"风险占比"分子分母不一致（历史 R11 未修复）
位置：`mapper/StatsMapper.java:24-28,71-75`、`service/stats/StatsService.java:172-173,205-210`
证据：
```java
25  "SELECT COUNT(*) FROM (SELECT task_id, MIN(published_at) AS first_published FROM report_versions
      WHERE status='PUBLISHED' AND published_at >= #{from} AND published_at < #{to} GROUP BY task_id)"
    // 先按窗口过滤再 MIN → 首次发布在窗口外、窗口内做了一次重算的任务会被再计一次
74      AND risk_score IS NOT NULL AND risk_score >= threshold_snapshot        // 分子按"报告版本"计数
205     ratio(risky, reports)  // 分母 reports = publishedReports（按 task 去重）
```
影响：同一任务重算一次即被算作"新增有效报告"；一条任务若产生 3 个风险版本，风险占比可算出 300%。北极星指标与风险占比都被高估，后台对外的质量数字不可采信。
最小修法：两者都先按 task 取全量首版再筛窗口，并让风险占比的分子分母使用同一分组粒度（版本或任务，选一并写进口径说明）。

F17 · P2 · 微信首登不校验、不记录协议同意；注册同意快照与建号不同事务（历史 R12 未修复）
位置：`controller/AuthController.java:37-39`、`service/AuthService.java:41-53,74-80`、`service/AgreementService.java:100-112`
证据：
```java
41  public LoginOutcome loginByWechat(String code) { ... 未命中创建占位账号 }   // 无 agreed/seenVersions 校验、无 consent 写入
74  public LoginOutcome registerWithConsent(...) { agreementService.assertConsented(...); LoginOutcome o = register(...); agreementService.recordConsents(...); }
    // register() 与 recordConsents() 都没有事务注解，中间失败会留下"已建号但无同意快照"
```
影响：微信通道新账号在"已阅读并同意四份协议"的合规举证上没有任何记录（这也是 `agreement_consents` 表的唯一来源之一）；密码注册路径存在"有账号、无同意记录"的中间态。
最小修法：微信首登同样要求 agreed+seenVersions 并在同一事务内写 consent。

F18 · P2 · 彻底抹除不覆盖说话人角色映射，任务墓碑停在 DELETING（历史遗留未修复）
位置：`service/lifecycle/RetentionService.java:151-184,189-212`
证据：
```java
151  public void cleanupTask(Long taskId) { ... 删 task_assets / violations / report_versions / sentences，
     clearObjectKey / markAudioPurged ... }      // 全文没有 speaker_mappings 的删除，也没有推进 DELETED
```
影响：抹除完成后仍保留"说话人 N = 催收员/客户 + 确认操作者 id"的角色映射行（`cleanupUser` 同样不删），与"全部抹除"的界面承诺不符；任务状态永远停在 DELETING，无法用状态证明抹除已终结。
最小修法：`cleanupTask` 末尾删除该任务的 `speaker_mappings`，并把 `DELETING` 推进为 `DELETED`。

F19 · P2 · "重算失败记 FAILED 版本"的机制是死代码，前端失败提示分支不可达（新发现）
位置：`service/report/ReportAnalysisService.java:26-32,129-131`、`service/report/ReportService.java:219-227`；对照 `diting-app/src/utils/reportPresentation.ts:6`
证据：
```java
129  @Transactional(propagation = Propagation.REQUIRES_NEW)
130  public ReportVersion recordFailure(...)     // 生产源码 0 处调用（grep recordFailure 仅定义处）
```
`saveMapping` 是整体事务，分析失败会连同映射一起回滚，因此 `latestStatus` 只会是 PUBLISHED 或 null。
影响：类注释承诺"失败另起独立事务记录 FAILED 版本，界面据此提示最新一次重算失败"，实际永远不会发生；前端 `latestStatus === 'FAILED'` 的提示分支是死逻辑，维护者会误以为失败有痕迹可查。
最小修法：要么删除 `recordFailure` 与相关文案，要么在 `saveMapping` 外层捕获分析异常后用独立事务记录——注意不能在持有 `lockById` 的外层事务内调用（`REQUIRES_NEW` 会等同一行的锁而自锁）。

F20 · P2 · 若干高风险路径没有任何测试，正是上面缺陷长期存在的原因
位置：`src/test/java/com/diting/**`（37 个文件已逐个核对命名与用例）
证据：`find src/test` 无 `RetentionServiceTest`/`DeletionServiceTest`/`ExportServiceTest`/`TaskAssetServiceTest`；`TaskPipelineImageTest` 只有 `ocrFailureRetries`（覆盖 FAILED→重试），无 `markOcrRunning` 返回 0 / `objectStorage.read` 抛错 / 资产保持 RUNNING 的用例；`ExportPublicationGuardTest` 只覆盖墓碑与未发布；`AdminEndpointSecurityTest` 只断言 `/api/admin/accounts`、`/dashboard` 的角色判权，**没有** `/api/admin/recordings/{id}/assets` 与 `/{id}/audio` 的 OPERATOR 矩阵；`ReportServiceTest` 无"对调角色后读旧版"用例。
影响：F1、F3、F4、F5 这类"权限不一致 / 状态永久卡死 / 版本角色漂移"缺陷在现有回归里结构上不可见，CI 全绿与这些缺陷并存。
最小修法：优先补四个用例——`assets` vs `audio` 的角色矩阵、OCR 资产 RUNNING 崩溃恢复、导出上传失败后的重试、按旧版本读取角色。

---

## Review

- Correct：`application.yml` 未发现真实机密——库密码、JWT secret、后台初始密码、听悟回调密钥全部走环境变量，云 AK/SK/AppKey 是"请替换"占位；`WechatAuthService.isReal()` 会把占位值当未配置，不会带占位串打真实接口。`AdminAuthInterceptor` 每请求回库复核启用状态/角色，`BaseAuthInterceptor` 区分身份域的 401/403 是正确实现。测试控制器（`/api/test/**`）都是 `@Profile("test")`，生产不会注册。删除/保留期的导出与下载路径现在会复查墓碑（`ExportService.presignArtifact` → `requireTask`），报告发布加了 `lockById` + 状态/文本校验，`ReportController`/`ExportController` 的归属校验也正确。
- Limitations：
  1. **没有 diff 证据**：工作区根目录与 `diting-server/` 都不是 Git 仓库，无法区分"本轮新引入"与"历史遗留"，也无法确认工作树是否与任何报告描述一致。
  2. **未运行任何东西**：不跑构建/测试/接口/数据库；所有并发类结论（F6、F10、F11、F12、F13）都是源码 + SQL 条件推断，未在真实 MySQL 多事务环境复现。
  3. 未核实真实云行为：OSS 预签名是否允许覆盖写（`AliyunOssObjectStorage.presignPut` 未加 forbid-overwrite 类约束，历史 R10 的"验证后仍可被原 PUT 签名覆盖"从源码看仍未关闭，但**是否真实可覆盖未验证**）、听悟回调协议与官方契约是否一致、OCR/Tingwu 的真实计费与失败模式。
  4. 未查真实库状态：历史遗留数据（卡死的 ANALYZING、旧 FAILED 版本、不一致映射、RESERVED 僵尸流水）是否存在、是否需要迁移，未验证。
  5. 前端只做了两处定向核对（`RecordCard.vue` 的 flaggedCount 消费、`mappingRevision` 作为 expectedRevision 的发送方），未做三端全量验收；`docs/S15-多agent审核与阻断修复记录.md` 只在 grep 层面检索过，未通读。
  6. 测试文件只检查了"是否存在覆盖"，没有执行，因此也无法声称现有回归是绿的。
- 结论：历史 R01/R02/R04/R05/R07/R09/R11/R12/R13/R17/R18/R19/R20/R23/R24 在本轮源码里**均未关闭**；另有 2 条新发现（F15 DENIED 审计缺口已核实、F19 失败版本机制为死代码）与 1 条测试缺口（F20）。其中 F1（越权读原件）与 F2（首页假结论）属于发布阻断。

Merge verdict: BLOCK