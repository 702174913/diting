# 谛听 · 三端代码质量审核（2026-09-26）

版本：v1.0 · 日期：2026-09-26 · 方式：3 个**只读** subagent 并行独立复核 + 主 agent 交叉复核
范围：`diting-server`（后端）、`diting-admin`（管理后台，含全部未提交改动）、`diting-app`（小程序/H5）

---

## 0. 审核方式与证据边界

| Lane | Agent | 范围 | 结论 |
|---|---|---|---|
| A | `.pi/agents/reviewer.md`（只读：read/grep/find/ls，thinking=high） | diting-server 173 个 java + 37 个测试文件 + 迁移 SQL + 历史审核文档 | **BLOCK** |
| B | 同上 | diting-admin 全部源码 + 3005 行未提交 diff + 前后端契约逐条对照 | **OK with notes** |
| C | 同上 | diting-app src/** + tests/** + 后端契约对照 | **BLOCK** |

**证据边界（必须与结论一起读）**

1. **没有 diff 证据**：工作区根目录不是 Git 仓库，`diting-server` / `diting-app` 也不是。只有 `diting-admin` 有 `.git`。因此「问题是不是本轮新引入」无法判定；A/C 两 lane 读的是当前源码快照。
   为补足这一点，主 agent 预先导出了 `.pi/review/admin-uncommitted.diff` 与 `.pi/review/recent-files.txt` 供 B lane 使用。
2. **没有任何运行时证据**：3 个审核 agent 都不能跑 shell / 测试 / 构建 / 连库。所有并发类、渲染类、云行为类结论都是**源码与 SQL 条件推断**，报告中已逐条标「未验证」。现有测试**是否仍然全绿，本轮未复现**。
3. 主 agent 对其中 12 条高价值结论做了**源码级交叉复核**，下文中带 `✔已复核` 的条目为主 agent 亲自读过源码确认，不是仅凭 agent 转述。

**与历史报告的关系**：本轮首要目标是核实 `docs/server-review.md`、`server-review-followup.md`、`server-fix-batch1.md`、`S15-多agent审核与阻断修复记录.md` 里的问题**是否真的修好了**。结论是：**R01/R02/R04/R05/R07/R09/R11/R12/R13/R17/R18/R19/R20/R23/R24 共 15 项在当前源码里均未关闭**；同时 S15 的 H1/H2(报告页)/M1/M3/M5/M6(停)/M7 已核实确实修复。

**统计**：P0 阻断 2 条 · P1 发布前必修 20 条 · P2 仅报告 23 条，共 45 条。

---

## 1. P0 · 阻断发布（2 条）

### P0-1 · OPERATOR 可绕过「仅 ADMIN 可看原件」拿到原始录音地址 ✔已复核

- **位置**：`diting-server/.../controller/AssetController.java:44-49`、`service/TaskAssetService.java:49-70`（第 63 行签发）；对照 `controller/AdminRecordingController.java:142-143`
- **证据**：
  ```java
  // AssetController：同一条录音的两条路径，权限不一致
  44  @GetMapping("/api/admin/recordings/{id}/assets")
  45  @RequireRole({AdminRole.ADMIN, AdminRole.OPERATOR})      // ← OPERATOR 可以
  // AdminRecordingController
  142 @GetMapping("/{id}/audio")
  143 @RequireRole(AdminRole.ADMIN)                            // ← 原件只给 ADMIN

  // TaskAssetService.list 只收身份域，不收角色，也不看 asset.getKind()
  49  public List<AssetItemResponse> list(Long taskId, String actorDomain, Long actorId)
  63      String url = objectStorage.presignGet(asset.getObjectKey(), ...)
  ```
- **影响**：设计上 OPERATOR 只能看脱敏文本（`AdminRecordingController.detail` 走 `redact()`），但改用 `/assets` 即可拿到 15 分钟有效的**原始录音** GET 链接。这是明确的越权读原件，且审计里只留一条看起来合规的 `ASSET_PRESIGN`（**历史 R01，未修复**）。
- **最小修法**：`list()` 增加 `actorRole` 形参，`kind=AUDIO` 时非 ADMIN 返回 `withoutUrl`；在 `AdminEndpointSecurityTest` 补 `/audio` 与 `/assets` 的 OPERATOR 矩阵。

### P0-2 · 列表 `flaggedCount` 恒为 0：所有已完成记录在列表里显示「没有发现要留意的话」，与报告页结论相反 ✔已复核

- **位置**：`diting-server/.../service/RecognitionTaskService.java:288-305`（295 行 `SUM(flagged)`）、`controller/TaskController.java:86-93`；消费方 `diting-app/src/components/RecordCard.vue:25,51`
- **证据**（**两个 lane 独立得出同一结论**，主 agent 用 grep 复核过全仓写入点）：
  ```java
  // 全生产源码只有两处写 flagged，且都是 false
  TaskPipelineService.java:298  row.setFlagged(false);
  TaskPipelineService.java:429  row.setFlagged(false);
  // 而列表数值恰恰来自它
  RecognitionTaskService.java:295  wrapper.select("task_id", "COUNT(*) AS total", "SUM(flagged) AS flagged")
  ```
  ```ts
  // RecordCard.vue:51
  return flaggedCount ? `找到 ${flaggedCount} 处要留意的话` : '没有发现要留意的话'
  ```
  对照：报告详情页的 `flaggedCount` 走的是 `ReportService.java:233-241`（按 `violations` 句去重），口径正确。
- **影响**：`SUM(flagged)` 恒为 0 ⇒ 「找到 N 处」分支**代码上不可达**。一条命中 3 项违规的已完成记录，「今天」「记录」两张列表都显示绿色「没有发现要留意的话」，点进去报告页却写「发现 3 项」。**合规质检产品里这是把「有问题」判成「没问题」，不是少一个数字**（历史 R13；与 S15 的 H2 同类，但影响面扩大到全部已完成记录）。
- **最小修法**：`countSentences` 改为按任务最新 PUBLISHED 版本的 `violations` 按句去重（复用 `ReportService` 的口径），或在发布事务里回填 `sentences.flagged`。**不要再依赖 `sentences.flagged`**。

---

## 2. P1 · 发布前必须修（20 条）

### 后端（12 条）

#### P1-1 · 图片资产卡在 RUNNING 后任务永久停在 TRANSCRIBING，重启无效
- 位置：`service/TaskPipelineService.java:196-236`（207 行 `read` 在 try 之外）、`mapper/TaskAssetMapper.java:17-19,23-26`、`V4__S16_media_assets.sql:82-104`
- 证据：`markOcrRunning` 只认 PENDING/FAILED；`selectImagesToOcr` 反而**包含 RUNNING** ⇒ RUNNING 资产每轮都被取回但永远领不到；`task_assets` 无租约列、无尝试次数字段。
- 影响：一次 OSS 读取异常或进程在 `markOcrRunning` 之后被杀，任务就在 `TRANSCRIBING` 永久空转，永不进终态、无失败提示（**历史 R02**）。
- 修法：加租约/尝试列，`read` 异常也走 `markOcrFailed`，`selectImagesToOcr` 只取「RUNNING 且租约过期」。

#### P1-2 · 导出作业上传失败后永久停在 RUNNING，且领取非 CAS
- 位置：`service/export/ExportService.java:101-107,157,163-179`
- 证据：`101` 只扫 `QUEUED`；`157 objectStorage.put(...)` 在 try/catch 之外；`178 job.setStatus(RUNNING); updateById` 是先读后写。
- 影响：对象存储一次抖动 ⇒ 用户导出永久「生成中」；该作业还会被产物过期清理跳过（要求 `artifact_key` 非空）。多实例可同时领取同一作业并重复上传同名 key（**历史 R05**）。
- 修法：领取改条件更新并校验影响行数；`put` 失败写 `RETRY_WAIT`；`tick` 回收超期 RUNNING。

#### P1-3 · 历史版本报告用「最新角色映射」渲染，导出证据链不可复算
- 位置：`service/report/ReportService.java:254-259`、`service/export/ExportService.java:283-290`
- 证据：`258 Map<Integer,String> roles = currentRoles(task.getId())`（最新 revision），而 `290` 按 `reportVersionId` 取的是**指定版本行**，行上有 `mappingRevision` 却没用。
- 影响：对调角色后再读/导出旧版，逐句角色来自新 revision、证据与分数来自旧 revision。合规证据链失效（**历史 R07**）。
- 修法：`detailOf` 改用 `rolesAt(task.getId(), version.getMappingRevision())`。

#### P1-4 · 额度账务不闭环：图片任务永不结算，`release()` 零调用方，两条更新不同事务 ✔已复核
- 位置：`service/TaskPipelineService.java:328`、`service/QuotaService.java:85-117`、`service/RecognitionTaskService.java:159`
- 证据（主 agent grep 复核：生产代码仅 3 处 `quotaService.` 调用）：
  ```java
  TaskController.java:74          quotaService.snapshot(...)
  TaskPipelineService.java:328    quotaService.consume(task.getId());   // 只在音频提交成功路径
  RecognitionTaskService.java:159 quotaService.reserve(userId, task.getId(), ...)
  // release( 零调用；consume 内部 casState + consumeReserved 是两条独立语句，方法上无 @Transactional
  ```
- 影响：图片/混合任务的流水永远停在 `RESERVED`，当日额度被永久占用（用户看到「已用完」却无消费记录）；`casState` 成功后进程中断则流水与日计数永久不一致且无法重试修复。单测覆盖了 `release` 的幂等语义，但**生产没有任何调用者**（**历史 R09**）。
- 修法：`consume/release` 加 `@Transactional`；图片任务发布成功也 `consume`；确定未受理的失败补 `release` 或人工核销入口。

#### P1-5 · 未配置凭据也照常登记并扣额度；任何云端失败都归为「不确定」且从不真正对账
- 位置：`service/TaskPipelineService.java:107-118,305-345`、`service/tingwu/AliyunTingwuApi.java:41-45`
- 证据：`107/110` 未配置时 `return false`——不动状态、不判失败；`45` 把一切异常（含 4xx 明确拒绝）包成 `IllegalStateException`；`339` 达上限后写 `ERR_SUBMIT_UNRECONCILED`，而 `reconcileSubmit` 全程不查远端。
- 影响：缺凭据的部署上用户提交返回 200 并被预占额度，任务永远停在「排队中」；明确被云端拒绝也要空转 5 轮，额度永久 RESERVED，且没有退款入口（**历史 R04**）。
- 修法：按 mediaKind 校验已配置（未配置直接 503，不登记不预占）；按 code/HTTP 状态区分「确定未受理」（退额度）与不确定；`reconcileSubmit` 真正查一次远端状态。

#### P1-6 · 「抹除」后旧 token 仍能写入并重新绑定微信
- 位置：`config/UserAuthInterceptor.java:23-28`、`service/TokenService.java:38-52`、`mapper/UserMapper.java:15-17`、`service/AuthService.java:97-112`
- 证据：拦截器只验 JWT，不回库查账号是否已注销/已抹除；`anonymize` 只是把列置 NULL，无状态位。
- 影响：请求「清除我的数据」后，72 小时内仍可用旧 token 上传、继续生成新记录，还能 `POST /api/auth/bind-wechat` 把新微信绑回已匿名化账号。隐私页承诺不成立（**历史 R18**）。
- 修法：`users` 增加注销/撤销字段或 token 版本，拦截器每次请求回库复核。

#### P1-7 · 删除完成后在途工作者仍可把整份转写原文插回
- 位置：`service/TaskPipelineService.java:268-303,415-436`、`service/lifecycle/RetentionService.java:151-184`
- 证据：`persistSentences`（415）与 `refreshAssetSentences`（268）都是无校验 `sentenceMapper.insert(row)`；`cleanupTask` 标记 COMPLETED 后不会再清第二轮。
- 影响：清理作业报「完成」之后，快照在删除前取得的工作者仍能把整份通话原文重新落库，且这批新行永远不会被再次清理（**历史 R17**）。
- 修法：写入入口用 `lockById` 或在 SQL 上带 `deleted_at IS NULL` 校验，删除后直接放弃。

#### P1-8 · 清理作业无所有权/代际栅栏，旧工作者可把新一轮作业标成已完成
- 位置：`mapper/DeletionJobMapper.java:14-30,44-55`、`service/lifecycle/RetentionService.java:107-142`
- 证据：完成条件只有 `WHERE id=? AND status='RUNNING'`，不校验「是谁领的」；租约超时后同一条 SQL 又能被重新领取。
- 影响：用户 rearm 后新工作者领成 RUNNING，旧工作者随后的 `markCompletedRunning` 也会命中 ⇒ 把尚未清理的新对象清单清空并报 COMPLETED，原件留在 OSS 且再无作业清理（**历史 R24**）。
- 修法：`deletion_jobs` 增加 generation/lease_token，领取与完成都按 token + 租约 CAS。

#### P1-9 · 同一图片上传会话可被并发登记到两个任务
- 位置：`service/UploadSessionService.java:192-205`、`V4__S16_media_assets.sql:99`
- 证据：`203-204` 先读 VERIFIED 再无条件下写 CONSUMED（非 CAS）；`task_assets` 只有 `uk_task_sequence`，`upload_id` 无唯一约束。
- 影响：两个并发请求各自登记任务并共享同一 objectKey ⇒ 重复 OCR 计费、重复预占额度；删除其中一个任务的清理会**删掉另一个仍在引用的图片原件**（**历史 R20**）。
- 修法：改条件更新 `status='VERIFIED' → 'CONSUMED'` 并校验影响行数；`upload_id` 加唯一约束。

#### P1-10 · 「至少保留一个启用 ADMIN」是可并发的读后写
- 位置：`service/AdminUserService.java:133,150-155,172`
- 证据：`countEnabledAdmins()` 是普通 `selectCount`，无锁；判断与写入分离。
- 影响：两个启用的 ADMIN 被同时降级/注销时都读到 2 并都成功，后台从此没有任何启用 ADMIN，无自助恢复入口（**历史 R23**）。
- 修法：成员变更用同一把锁（管理行 `FOR UPDATE`），或「启用 ADMIN 数」做成条件更新。

#### P1-11 · 策略配置的 `expectedVersion` 不是原子乐观锁
- 位置：`service/ConfigService.java:94-108,145-157`
- 证据：`98` 读版本、`99` 比对、`105` 无条件 `upsert`；`155` 无条件 `updateById`；version 列无 `@Version`。
- 影响：两个 ADMIN 同时保存都能通过校验，后提交者静默覆盖前者的三项策略（每日配额/风险阈值/最长时长），版本都只 +1，冲突不可见——直接影响判读与计费的参数被无痕覆盖（**历史 R19**）。
- 修法：三项写入改 `UPDATE ... WHERE config_key=? AND version=?`，任一 0 行即回滚 409。

#### P1-12 · OCR 失败没有次数上限，无限重试并持续计费
- 位置：`service/TaskPipelineService.java:196-236`、`mapper/TaskAssetMapper.java:17-19,23-26`
- 证据：注释声称「失败按任务级次数上限重试，避免重复付费」，但 `task_assets` 无 `ocr_attempts` 字段，代码里没有任何计数；FAILED 资产下一轮会被再次 `markOcrRunning` 并再次调用计费接口。
- 影响：被云端永久拒绝的图片每轮重新付费识别，直到人工发现；任务也不会失败告警（**历史 R04 第二条**）。
- 修法：增加 `ocr_attempts`，超限置终态并排除出 `selectImagesToOcr`。

### 管理后台（3 条，全部落在本轮新增/未跟踪文件上）

#### P1-13 · 顶部「时间范围」控件没有消费方，7 个看板页硬编码 `period="30d"` ✔已复核
- 位置：`diting-admin/src/layouts/AdminLayout.vue:119-124`、`views/{overview,growth,finance,ops,map,quality,compliance}/index.vue`
- 证据：主 agent grep 复核——全库读 `view.range` 的只有 `AdminLayout.vue:120`；7 个页面一律 `<MetricsPanel section="..." period="30d" />`。
- 影响：操作者点「今日 / 本季度」后按钮高亮并写入 localStorage，但取数始终是 `period=30d`（服务端 `StatsService.java:695-700` 明确支持 today/30d/quarter）。**界面把一个无效控件当成时间窗声明**，合规/尽调语境下等于把 30 天数据标示成「今日」（S15 待办第 9 条，未闭环）。
- 修法：把 `view.range` 接到 `MetricsPanel`（`TODAY→'today'` 等）并在变化时 `load()`；若不接线就删掉该 ToggleGroup 与 `Range` 类型。

#### P1-14 · 协议富文本：客户端零清洗直接 `innerHTML`，服务端清洗是可绕过的正则黑名单 ✔已复核
- 位置：`diting-admin/src/components/RichTextEditor.vue:27`（注释主张见 `:5`）、`diting-server/.../service/AgreementService.java:119-123`
- 证据：`RichTextEditor.vue:27 editor.value.innerHTML = props.modelValue || ''`（组件注释写「输出 HTML 由服务端二次清洗（去 script/事件属性）」，客户端不做任何渲染期清洗）；服务端 `sanitize` 的正则 `\s+on\w+\s*=` 要求 `on*` 前有空白，故 `<svg/onload=...>` 不匹配；`(href|src)\s*=\s*"` 要求引号，故 `href=javascript:...` 不匹配。落库字段 `content_html` 是 MEDIUMTEXT。
- 影响：写路径仅 ADMIN，外部不可直接写入，所以**可达性未验证**（经编辑器粘贴时浏览器会把属性序列化成 ` name="value"`，上述两种写法反而被拦）。但一旦经 API 写入，后台就在 `innerHTML` 处执行脚本，小程序端 `pages/agreement/index.vue:38` 也渲染同一批 HTML。**注释宣称的安全边界并不存在**。
- 修法：服务端改用白名单清洗（jsoup `Safelist`）再落库；客户端在 `syncFromModel` 前加一层 DOMPurify。

#### P1-15 · 协议保存成功后 `active.contentHtml` 不回填，`dirty` 永远为真 ✔已复核
- 位置：`diting-admin/src/views/agreements/index.vue:42-47`（dirty）、`:84-90`（save）
- 证据（主 agent 读源码确认）：save 只回写 `active.value.title` / `active.value.version`；`drafts.value[key]` 回填的是**客户端自己的 HTML**，`active.value.contentHtml` 从未更新，而 `dirty` 正是拿它与 `draft.contentHtml` 比。
- 影响：每次保存后 `dirty` 仍为 true，保存按钮一直可点，操作者无法判断是否已保存；若服务端清洗改动过正文，编辑器长期显示的是**与服务端/用户实际签署版本不同的文本**，而版本号已 +1。
- 修法：`saveAgreement` 返回 `contentHtml`，成功后同时写回 `active` 与 `drafts`。

### 小程序（5 条）

#### P1-16 · 幂等键每次「提交动作」重新生成，弱网重试重复登记任务、重复扣额度 ✔已复核
- 位置：`diting-app/src/pages/upload/index.vue:270`（`const attemptKey = newIdempotencyKey('upload')`，使用点 `:297`）
- 证据：`attemptKey` 在 `confirmUpload` 内部生成；失败分支只重置 `started/uploadStage` 并 toast（`:305-310`），**不清空 `pendingFiles`**（只在成功时清）。用户再点一次会重新 `signUpload`（新 objectKey）→ PUT → complete → 用**新的** attemptKey 提交。服务端幂等是 `(user_id, idempotency_key)` 唯一 + 按 `uploadIds` 派生。
- 影响：`submitTask` 已在服务端成功但响应在弱网丢失时，用户按提示重试 ⇒ 同一段录音登记**两个任务、扣两份额度**。M3 的 `busy` 连点守卫对「响应丢失后重试」完全无效。
- 修法：幂等键与文件集合绑定——选文件时生成并存入组件状态，重试复用同一个 key，仅在拿到 `task.id` 后重置。

#### P1-17 · 上传页在 `WAITING_ROLE` 上不停轮询，且不给角色确认入口 ✔已复核
- 位置：`diting-app/src/pages/upload/index.vue:86-88`（模板入口 `:397`）、`utils/taskStatus.ts:34-38`
- 证据（主 agent 确认）：上传页判停用的是 `isRunning(res.status)`，而 `isRunning` **把 `WAITING_ROLE` 视为运行中**；同文件里已经写好了正确的 `needsPolling()`，注释明确「WAITING_ROLE 是在等用户确认角色，一直轮询既不会推进也不会结束」。模板里进报告入口只有 `v-if="status==='COMPLETED'"`。
- 影响：音频/混合任务上传后**必然**经过 `WAITING_ROLE`，用户停在「正在一句一句听」每 3 秒无限刷新，页面上没有「去确认谁是谁」入口、也没有「看完整的记录」入口，必须自己想到切到「记录」Tab。**主转化路径断在这里**。
- 修法：改用 `needsPolling(res.status)` 判停，并在 `needsRoleConfirm(status)` 时渲染跳 `pages/report/detail` 的按钮。

#### P1-18 · 上传页 Tab 切回不恢复轮询，进度永久停在切走时的状态（M6 只修了「停」）
- 位置：`diting-app/src/pages/upload/index.vue:320-323`
- 证据：`onShow(() => { requireH5Login(userStore.token, 'upload') })` 只做登录校验；`onHide(stopPolling)` 之后没有任何重启逻辑。
- 影响：上传后切到「记录」再切回「上传」，卡片永远停在旧状态，完成/失败都不会更新 ⇒ `status==='COMPLETED'` 永不成立，「看完整的记录」按钮永不出现。
- 修法：`onShow` 里加 `if (started.value && isRunning(status.value)) startPolling()`。

#### P1-19 · `bind-wechat` 全仓无调用方，H5 注册的用户在小程序里被当成新账号 ✔已复核
- 位置：`diting-app/src/api/auth.ts:30`（主 agent grep 确认无调用点；`stores/user.ts:32` 的 `loadUserInfo` 同样无人调用）
- 证据：服务端 `AuthService.loginByWechat` 在 openid 未命中时**新建账号**；`bindWechat`（`:97-110`）正是为此存在的绑定通道，但前端从未调用。
- 影响：H5 用账号密码注册的用户在小程序里登录会得到另一个空账号，记录/额度/协议同意记录**全部「看起来消失」**；反之亦然。`UserInfo.hasPassword/hasWechat` 两个未使用字段表明这是未完成的实现。
- 修法：小程序 `ensureLogin` 成功后若 `hasWechat===false` 引导 `bindWechat`，或在「安心」页加绑定入口。

#### P1-20 · 报告页把任何加载失败都渲染成「记录不存在或已删除」，且没有重试入口 ✔已复核
- 位置：`diting-app/src/pages/report/detail.vue:152-153`，空态 `:427`
- 证据：`try { report.value = await fetchReport(id, token) } catch { report.value = null }`——网络抖动、超时、401 全部落到同一个空态。
- 影响：在「一键彻底删除」是核心承诺的产品里，把一次性网络失败呈现为「记录不存在或已删除」会让用户**以为数据已被删**；认识到是网络问题后也没有重试入口，只能退出重进。
- 修法：仅 `ApiError.code === 404` 走空态，其它错误显示「加载失败，请重试」+ 重试按钮。

---

## 3. P2 · 仅报告（23 条，摘要）

### 后端
| # | 问题 | 位置 | 修法 |
|---|---|---|---|
| P2-1 ✔已复核 | 「权限拦截次数」恒为 0：`RESULT_DENIED` 仅定义、生产 0 引用，而该指标被标成 `Source.REAL`（AI_CHANGELOG 待决线索核实） | `AuditLogService.java:40`、`AdminAuthInterceptor.java:58-74`、`StatsService.java:285` | 拦截器的拒绝分支写一条 DENIED 审计；未接入前把 note 改成「恒为 0」 |
| P2-2 | 有效报告量重复计入重算；风险占比分子（版本）与分母（任务）粒度不一致，可算出 >100% | `StatsMapper.java:24-28,71-75`、`StatsService.java:172-173,205-210` | 先按 task 取全量首版再筛窗口；分子分母统一粒度并写进口径说明 |
| P2-3 | 微信首登不校验也不记录协议同意；注册的「建号」与「写同意快照」不同事务 | `AuthController.java:37-39`、`AuthService.java:41-53,74-80` | 微信首登同样要求 agreed+seenVersions 并在同一事务写 consent |
| P2-4 | 彻底抹除不覆盖 `speaker_mappings`，任务墓碑永远停在 `DELETING` | `RetentionService.java:151-184,189-212` | `cleanupTask` 末尾删除该任务映射并推进到 `DELETED` |
| P2-5 | 「重算失败记 FAILED 版本」是死代码（`recordFailure` 0 调用），前端 `latestStatus==='FAILED'` 分支不可达 | `ReportAnalysisService.java:26-32,129-131`、`ReportService.java:219-227` | 删掉机制与文案，或在 `saveMapping` **外层**独立事务记录（注意 `lockById` 自锁） |
| P2-6 | 高风险路径无任何测试：无 `RetentionServiceTest`/`DeletionServiceTest`/`ExportServiceTest`/`TaskAssetServiceTest`；`AdminEndpointSecurityTest` 没有 assets/audio 的角色矩阵 | `src/test/java/com/diting/**` | 优先补 4 个用例（见 P0-1 / P1-1 / P1-2 / P1-3） |

### 管理后台
| # | 问题 | 位置 | 修法 |
|---|---|---|---|
| P2-7 | 403（账号被禁用/降级）不清理登录态也不刷新角色，菜单与管理员按钮照常渲染 | `src/api/request.ts:50-53`、`AdminAuthInterceptor.java:41-44` | 对 `ACCOUNT_DISABLED`/受保护路由的 `FORBIDDEN` 走 `handleUnauthorized()` |
| P2-8 | 短时签名下载地址被写进 localStorage，且该字段已无消费方（与自身注释矛盾） | `src/stores/exportTask.ts:27-28,150-154` | 删掉 `downloadUrl` 字段与写入，或改为仅内存态 |
| P2-9 | 3 个 `@deprecated` 看板函数用 GET 携带 `data`（启用即 400），且返回类型是假的 | `src/api/stats.ts:13-25`、`src/types/api.ts:50-67` | 删除（全库无引用），或改 `params` 并修正类型 |
| P2-10 | 审计页权限矩阵声称「与服务端 @RequireRole 一一对应」，却列了 3 个服务端不存在的角色；授权时长写 30 分钟（后端是 15） | `src/views/audit/index.vue:85-89,155,192` | 收敛为 ADMIN/OPERATOR/INVESTOR 三行；时长改为引用常量 |
| P2-11 | MetricsPanel 删除错误展示后，加载失败时面板全空白且无重试入口 | `src/components/MetricsPanel.vue:253-260,431` | 失败且无数据时渲染错误提示 + 「重新加载」按钮 |
| P2-12 | 图表换库后丢掉「全 0 序列仍有可见高度」的处理，与「图表渲染失败」不可区分（**未验证**） | `src/components/MetricsPanel.vue:170-174` | 为 0 值保留最小可见高度，或在 note 里写明「区间内全为 0」 |
| P2-13 | 查询串拼接 5 份逐字相同、分页文案 4 份重复实现 | `src/api/admin.ts:117-121,180-184,278-282,301-305`、`api/stats.ts:97-101`、`views/*/index.vue` 4 处 | 抽 `buildQuery()` 到 `api/request.ts`；`DataTablePagination` 自己渲染「第 X / Y 页」 |
| P2-14 | 无 404 兜底路由，过期 URL 进入空 router-view | `src/router/index.ts:8-92` | 加 `{ path: '/:pathMatch(.*)*', redirect: '/overview' }` |

### 小程序
| # | 问题 | 位置 | 修法 |
|---|---|---|---|
| P2-15 | 首页把「未登录」和「加载失败」都渲染成「没有记录」，且「N 段」用的是当页 20 条长度而非 `total` | `src/pages/home/index.vue:27,34,87,98` | `onShow` 补 `ensureLogin`（`#ifndef H5`），空态按 token 分流，段数用 `total` |
| P2-16 | 报告页直出后端内部英文状态（与 M7 口径相反） | `src/pages/report/detail.vue:322`、`ReportService.java:315` | 前端不复用 `notes`，或服务端把枚举映射成中文 |
| P2-17 | 图片 OCR 句子被渲染成「00:00 · 说话人未知」 | `src/components/SentenceItem.vue:23,56`、`TaskPipelineService.java:293-296` | `SentenceResponse` 加 `sourceAssetId`，渲染为「图片 N」 |
| P2-18 | 导出超时后作业凭证被丢弃，再点必然新建作业（重复审计记录） | `src/pages/report/detail.vue:177-183`、`api/record.ts:123-133` | `exportId` 存组件状态，下次点击先 `fetchExport` 复用 |
| P2-19 | 唯一的前端测试面很窄且没有 npm 入口；7 个纯函数模块零覆盖 | `tests/reportPresentation.test.cjs`、`package.json:4` | 加 `"test": "node --test tests/"`，补 polling/paging/taskStatus 单测 |
| P2-20 | `TaskItem.progress` 是服务端不存在的字段，进度分支为死代码 | `src/types/api.ts:30`、`RecordCard.vue:42-44` | 删字段与分支，或服务端补明确口径 |
| P2-21 | 进度页 `onLoad` 异步续体缺少「页面已隐藏」守卫，隐藏后仍可能启动轮询 | `src/pages/progress/index.vue:88-92,101` | 加 `visible` 标志，`onHide` 置 false，续体启动前判断 |
| P2-22 | 生产配置指向占位域名 `api.example.com`，`manifest.json` appid 为「请替换」 | `.env.production:1`、`src/manifest.json` | 构建时注入真实值，并对占位值直接报错退出 |
| P2-23 | 两个 500 行级单文件组件，且恰是历史缺陷最集中的两个页面 | `src/pages/report/detail.vue`（536 行）、`src/pages/upload/index.vue`（504 行） | 抽「上传选择+同意弹层」「报告概要+导出区」为子组件 |

---

## 4. 已核实「确实已修好」的部分（避免重复修）

- **后端**：`application.yml` 已无真实机密（库密码/JWT secret/初始密码/回调密钥全走环境变量，云 AK/SK 是「请替换」占位，`WechatAuthService.isReal()` 会把占位串当未配置）；`AdminAuthInterceptor` 每请求回库复核启用状态与角色；`/api/test/**` 都是 `@Profile("test")`，生产不注册；导出/下载路径会复查墓碑，报告发布加了 `lockById` + 状态校验；`ReportController`/`ExportController` 的归属校验正确。
- **管理后台**：新协议接口契约与 `AdminAgreementController` + `V6` 迁移逐字段对齐；导出下载改造两端一致（状态接口确实不再下发 `downloadUrl`，历史「轮询既签发又写审计」的噪声从两端关掉）；S15 的 V1/V2（假指标、假刷新承诺）确已修实——`DemoNotice` 已不存在，8 个看板页统一走 `/api/admin/stats/*`，无数据源的 KPI/漏斗/MRR 表已删除并写明「没有数据源，不编」；定时器/监听器无泄漏（唯一 `setInterval` 在 `exportTask.ts:116`，登出链路会 `stopPolling()`）；全库**无** `as any` / `@ts-ignore` / `@ts-expect-error` / `eslint-disable`。
- **小程序**：24 个请求点逐条对照后端 controller **全部命中真实路由与字段**，未发现 B1 类「调用不存在的接口」；导出已改为 `createExport → waitExport → /download` 三段且 fileType 分流与服务端一致；M1（技术文案转译）、M5（无百分比则不显示进度条）、H1（longpress emit）、H2（**报告页**未发布分流）、B2/B3（进度页死页）已核实修复；高亮区间有 `start/end` 越界校验；图片 6 张 / 10MB、音频 50MB 上限与后端一致。

---

## 5. 建议修复顺序

1. **第一批（阻断，建议立即）**：P0-1 越权读原件、P0-2 列表假结论 —— 这两条共同特征是「**用户看到的结论与服务端事实相反**」，属合规产品不可接受项。P0-2 的两端（列表口径 + `sentences.flagged` 死字段）应一次性清理。
2. **第二批（状态机与账务，P1-1/2/4/5/12）**：都是「永久卡在非终态」或「额度不闭环」，共用「租约 + 次数上限 + 条件更新」这一套修法，一次做完最省。
3. **第三批（并发与越权，P1-6/7/8/9/10/11）**：统一把「读后写」改成条件更新/CAS，并补齐对应回归测试（P2-6）。
4. **第四批（本轮新增文件，P1-13/14/15）**：范围小、都在未提交代码里，顺手修完再过一轮 diff 审核。
5. **第五批（小程序转化路径，P1-16~20）**：P1-17/18 是「页面点了没反应」类的体验阻断，改动量最小、收益最直接。
6. **P2 按上表分批**，其中 P2-1、P2-2、P2-4 涉及对外指标与隐私承诺，建议不要排在最后。

---

## 6. 本轮审核的局限（请与本报告结论一起引用）

1. **无任何运行时证据**：未跑构建、未跑测试、未启服务、未连库、未真机、未调云 API。**现有测试是否仍全绿本轮未复现**，因此不能把本报告当作「CI 绿灯但仍有缺陷」之外的任何结论。
2. **无 diff 证据**：三个子项目里只有 `diting-admin` 是 Git 仓库。A/C 两 lane 的「是否新引入」判断不成立，报告中一律按「当前源码存在」陈述。
3. **推断未验证的高风险项**：OSS 预签名是否允许覆盖写（历史 R10 从源码看仍未关闭）、听悟回调与官方契约是否一致、OCR/Tingwu 真实计费与失败模式、Unovis 全 0 序列的实际渲染、富文本 payload 在真实浏览器中的执行/被拦行为、`getCurrentPages().at(-1).options.id` 在 H5 的可用性、小程序端 50MB `ArrayBuffer` 的真机限制。
4. **未覆盖**：`diting-admin/src/components/ui/**`（shadcn-vue 生成代码，仅在有真实安全影响时核查）；未做视觉/交互验收；仓库内没有 CI 配置可查，无法确认上述检查是否被任何流水线执行。
5. 五条 P2 级观察因无具体后果或证据不足**未**列为 Finding：`views/ops/index.vue:128` 面板名「实时队列」实为一次性快照、`lib/enums.ts` 多个无引用导出、`MetricsPanel.vue:265 defineExpose({ load })` 无调用方、`history` 的 `onShow` 与 `loadMore` 竞态窗口、`h5Auth` 未给协议页生成 resume。
