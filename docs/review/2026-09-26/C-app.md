# Lane C · diting-app 审核（原始报告，未经整理）

## Review

- **Correct（已核实的修复与契约结论）**
  - 契约核对：`src/api/` 下 5 个文件共 24 个请求点逐条对照 `diting-server/.../controller/`，全部命中真实路由、方法与请求体字段（`/api/upload/{sign,refresh,complete}`、`/api/task/{submit,list,quota,{id},{id}/status,{id}/assets}`、`/api/report/{id}`、`/api/report/{id}/{speaker-map,exports}`、`/api/exports/{id}[/download]`、`/api/privacy/erase`、`/api/deletions/{id}`、`/api/agreements[/versions|/{key}]`、`/api/auth/*`）。**未发现 B1 类「调用不存在的接口」**；导出确已改为 `createExport → waitExport → /download` 三段（`detail.vue:177-199`），文件类型分流 `'zip'`/`'pdf'` 与服务端 `ExportService.fileType`（`ExportService.java:227-229`）一致。
  - M1 文字转译已生效：`errors.ts:11-13` 把 `code===0` 的网络错误统一转成「网络不稳定」；`putWithRefresh` 抛出的 `new Error('上传失败（HTTP 403）')` 不带 `errMsg`，落到 `errors.ts:33` 的兜底文案，技术串不会直出。
  - M5 已生效：`TaskItemResponse` 无 `progress` 字段，`ProcessingCard.vue` 在 `progress===null` 时不渲染进度条与百分比，`RecordCard.vue:42-44` 只显示「正在听」。
  - H2 在**报告页**已修：`reportPresentation.ts:5-8` 以 `status==='PUBLISHED' && reportVersionId!==null` 作为唯一成功凭证，未发布/失败分流到「还在准备记录 / 记录生成失败」；`detail.vue:346` 未发布时只给转写与角色确认。B2/B3 已修（`progress/index.vue` 有 `polling.start/leaveNow/retryUpload`）。
  - M2 现状与服务端语义自洽：`UploadSessionService.refresh` 对 `VERIFIED` 会话必 409（`UploadSessionService.java:130-135`），客户端只在 PUT 401/403 后刷新且重试一次（`upload/index.vue:104-118`），未发现矛盾。
  - `SentenceItem.vue:29-34` 对高亮区间做了 `start>=0 / end<=text.length / start<end` 校验，且服务端按 `range.sentenceId()` 分桶（`ReportService.java:278-284`），未发现越界或错位。
  - 图片张数与大小上限与后端一致：`MAX_IMAGES=6`、`MAX_IMAGE_BYTES=10MB`、50MB 音频（`application.yml:36-40`）。

- **F1 · P1 · 记录列表卡永远显示「没有发现要留意的话」，与报告页结论直接矛盾**
  - 位置：`diting-app/src/components/RecordCard.vue:51`（证据链延伸至 `diting-server/.../controller/TaskController.java:88-92`、`service/RecognitionTaskService.java:295`）
  - 证据：前端 `return flaggedCount ? \`找到 ${flaggedCount} 处要留意的话\` : '没有发现要留意的话'`；`flaggedCount` 来自 `toResponse → SentenceCounts.flagged`，而该值由 `wrapper.select("task_id","COUNT(*) AS total","SUM(flagged) AS flagged")` 得到。全仓（`grep -i flagged src/main`）**只有两处写入 `setFlagged(false)`**（`TaskPipelineService.java:298,429`），schema 默认 0（`schema.sql:40`），无任何地方写 `true`。
  - 影响：`SUM(flagged)` 恒为 0 ⇒ 「找到 N 处要留意的话」分支**不可达**，每一段已完成记录（含命中 3 项的记录）在「今天」「记录」两张列表里都显示「没有发现要留意的话」，而点进去的报告页写着「发现 3 项需要留意的情况」。这正是 S15 H2（进行中任务被渲染成没有问题）的同类缺陷，只是此刻影响面扩大到**全部已完成记录**。
  - 最小修法：把列表计数改为按**已发布版本**的 `violations` 按句去重（复用 `ReportService.counts`），或让 `countSentences` 改为该口径；不要用 `sentences.flagged`。

- **F2 · P1 · 幂等键每次「提交动作」重新生成，弱网重试会重复登记任务、重复扣额度**
  - 位置：`diting-app/src/pages/upload/index.vue:270`（`const attemptKey = newIdempotencyKey('upload')`，使用点 `:297`）
  - 证据：`attemptKey` 在 `confirmUpload` 内生成；失败分支只重置 `started/uploadStage` 并 toast（`index.vue:305-310`），**不清空 `pendingFiles`**（只在成功时清，`:301-302`）。用户再点一次「上传并交给我」会重新 `signUpload`（新 `objectKey`）→ PUT → complete → 用**新的** `attemptKey` 提交。服务端幂等是 `(user_id, idempotency_key)` 唯一（`RecognitionTaskService.java:87-88,149-155`）+ 按 `uploadIds` 派生，新会话必然生成新任务并再次 `quotaService.reserve`（`:159`）。
  - 影响：`submitTask` 已在服务端成功、但响应在弱网丢失时，用户按提示重试 → 同一段录音登记**两个任务、扣两份额度**，额度是产品核心计量；M3 的 `busy` 连点守卫对「响应丢失后重试」完全无效。
  - 最小修法：把幂等键与文件集合绑定——选文件时生成并存入组件状态，重试复用同一个 key，仅在提交成功（拿到 `task.id`）后重置。

- **F3 · P1 · 上传页在 WAITING_ROLE 上不停轮询，且不给角色确认入口，用户卡死在主转化页**
  - 位置：`diting-app/src/pages/upload/index.vue:86-88`（模板入口 `:397`）
  - 证据：`if (!isRunning(res.status)) stopPolling()`，而 `isRunning` 把 `WAITING_ROLE` 视为运行中；`taskStatus.ts:34-38` 的 `needsPolling` 明确写「WAITING_ROLE 是在等用户确认角色…一直轮询既不会推进也不会结束」。管线确实停在 `WAITING_ROLE`（`TaskPipelineService.java:387-388,173`）。模板里进报告入口只有 `v-if="status === 'COMPLETED'"`。
  - 影响：音频/混合任务上传后必然经过 `WAITING_ROLE`，用户停在「正在一句一句听」（`ProcessingCard` 默认文案）无限刷新每 3 秒，且页面上没有「去确认谁是谁」入口、没有「看完整的记录」入口——必须自己想到切到「记录」Tab 才能继续，主转化路径断在这里。
  - 最小修法：改用 `needsPolling(res.status)` 判停，并在 `needsRoleConfirm(status)` 时渲染一个跳 `pages/report/detail` 的按钮。

- **F4 · P1 · 上传页 Tab 切回不恢复轮询，进度永久停在上一次状态（M6 只修了一半）**
  - 位置：`diting-app/src/pages/upload/index.vue:320-323`
  - 证据：`onShow(() => { requireH5Login(userStore.token, 'upload') })` 只做登录校验，`onHide(stopPolling)` 之后没有任何重启逻辑；进度卡与按钮全部依赖 `status`。
  - 影响：上传后切到「记录」再切回「上传」，卡片永远停在切走时的状态，任务完成/失败都不会更新，`status==='COMPLETED'` 永不成立 ⇒ 「看完整的记录」按钮永不出现；用户只能杀进程或去别的 Tab 找记录。
  - 最小修法：`onShow` 里加 `if (started.value && isRunning(status.value)) startPolling()`。

- **F5 · P1 · `bind-wechat` 全仓无调用方，H5 注册的用户在小程序里被当成新账号（看不到已有记录）**
  - 位置：`diting-app/src/api/auth.ts:30`（无调用点；`stores/user.ts:32` 的 `loadUserInfo` 同样无人调用）
  - 证据：`grep -rn "bindWechat|bind-wechat" diting-app/src diting-admin/src` 无命中，仅 `api/auth.ts:30` 有封装；服务端 `AuthService.loginByWechat` 在 openid 未命中时**新建账号**（`AuthService.java:41-52`），`bindWechat`（`:97-110`）正是为此存在的绑定通道。
  - 影响：H5 用账号密码注册的用户在小程序里 `ensureLogin → loginSilently → /api/auth/wechat` 会得到另一个空账号：记录、额度、协议同意记录全部「看起来消失」；反之小程序用户也无法用账号密码在 H5 登录同一身份。`UserInfo.hasPassword/hasWechat` 两个字段（`types/api.ts:5-8`）表明这是未完成的实现。
  - 最小修法：小程序端 `ensureLogin` 成功后若 `userInfo.hasWechat===false` 引导调用 `bindWechat`（或在「安心」页加绑定入口）。

- **F6 · P1 · 报告页把任何加载失败都渲染成「记录不存在或已删除」，且没有重试入口**
  - 位置：`diting-app/src/pages/report/detail.vue:152-153`，空态 `:427`
  - 证据：`try { report.value = await fetchReport(id, token) } catch { report.value = null }` —— 网络抖动、超时、401 全部落到同一个空态；模板 `v-if="!loading"` 显示「记录不存在或已删除」，页面没有任何重试按钮（也无 `onShow`/下拉刷新）。
  - 影响：在「一键彻底删除」是核心承诺的产品里，把一次性网络失败呈现为「记录不存在或已删除」会让用户以为数据已被删；且认识到是网络问题后也没有可操作的重试入口（只能退出重进）。
  - 最小修法：`catch` 里区分 `error instanceof ApiError && error.code === 404` 才走空态，其它错误显示「加载失败，请重试」并给一个重新 `loadReport` 的按钮。

- **F7 · P2 · 首页把「未登录」和「加载失败」都渲染成「没有记录」，段数还是被截断的 20**
  - 位置：`diting-app/src/pages/home/index.vue:27,34,87,98`
  - 证据：`load()` 首行 `if (!userStore.token) { return }`（小程序端 `requireH5Login` 恒真、且首页**不做** `ensureLogin`，与 `history/index.vue:32-35` 的 `#ifndef H5` 补登录不一致）；`catch { }` 静默；空态文案恒为「还没有听过的话，去上传一段录音吧」（不像 `history` 那样区分未登录）；`{{ records.length }} 段` 用的是第一页 20 条的长度，忽略了 `result.total`。
  - 影响：token 过期（`jwt.expire-hours: 72`）被 401 清掉后，小程序首页会一直显示「你没有记录」，而已有记录的用户会以为数据丢了；有 100 段记录时也只显示「20 段」。切换 Tab 才自愈。
  - 最小修法：`onShow` 里补一次 `ensureLogin`（`#ifndef H5`），空态按 `userStore.token` 分流，并在 `catch` 里保留错误提示。

- **F8 · P2 · 报告页直出后端内部英文状态（与 M7「不直出英文状态」的结论相反）**
  - 位置：`diting-app/src/pages/report/detail.vue:322`（渲染 `report.notes`）
  - 证据：`<text v-for="(note, index) in report.notes" class="hint">{{ note }}</text>`；服务端该数组会写入 `notes.add("最新一次重算未完成（" + latest + "），当前展示第 " + … + " 版")`（`ReportService.java:315`），`latest` 是 `ANALYZING/FAILED` 之类原始枚举值。
  - 影响：用户会同时看到友好的 `versionLabel`（「最新一次判读失败，当前展示之前成功发布的版本」）和一条「最新一次重算未完成（ANALYZING），当前展示第 2 版」，暴露内部术语且与 M7 的修复口径不一致。
  - 最小修法：前端不复用 `notes` 渲染状态说明（只渲染业务说明），或服务端在该条文案里把 `latest` 映射成中文。

- **F9 · P2 · 图片 OCR 句子被渲染成「00:00 · 说话人未知」**
  - 位置：`diting-app/src/components/SentenceItem.vue:56`（与 `:23`）
  - 证据：`{{ formatClock(sentence.startMs) }} · {{ speakerLabel }}`；服务端对 OCR 行写 `speakerId=null, startMs=0`，注释明确「界面按『图片 N』展示」（`TaskPipelineService.java:293-296`）。`SentenceResponse` 不含 `sourceAssetId`，前端无从区分图片行与音频行。
  - 影响：图片里的文字被标成某场通话 00:00 的发言，用户会把它当作对话内容核对；报告里「其余原文」区尤其容易误读。
  - 最小修法：`SentenceResponse` 增加 `sourceAssetId`（或前端以 `speakerId===null && startMs===0` 兜底），渲染为「图片 N」。

- **F10 · P2 · 导出超时后作业凭证被丢弃，用户再点会重复创建导出作业**
  - 位置：`diting-app/src/pages/report/detail.vue:177-183`（`waitExport` 见 `api/record.ts:123-133`）
  - 证据：`const job = await createExport(...)` 只存在于该次调用的局部变量；`waitExport` 默认 60s 后返回最后一次状态（可能是 `RUNNING`），页面提示「导出还在准备，请稍后再来」，`job.exportId` 随即丢失；再次点击必然走到 `:177` 新建作业（服务端每次创建都写一条 `EXPORT_CREATE` 审计）。
  - 影响：长录音 ZIP（含原件）超过 60s 时用户无法取回已完成的结果，只能反复点击产生多个导出作业与审计记录，也无法直接下载。
  - 最小修法：把 `exportId` 存到组件状态（或本地缓存），下次点击先 `fetchExport` 复用已有作业。

- **F11 · P2 · 唯一的前端测试面很窄，且没有 npm 入口**
  - 位置：`diting-app/tests/reportPresentation.test.cjs`（8 个用例）、`diting-app/package.json:4`（scripts 无 `test`）
  - 证据：测试只覆盖 `reportPresentation` 的分支与 `detail.vue` 的编译/绑定断言；`utils/polling.ts`、`paging.ts`、`taskStatus.ts`、`idempotency.ts`、`format.ts`、`errors.ts`、`choose.ts` 这些**无平台依赖、纯函数可测**的模块零覆盖；`package.json` 的 `scripts` 只有 `dev*/build*/type-check/lint`，只能手敲 `node --test tests/reportPresentation.test.cjs`（依赖 `@vue/compiler-sfc` 也只是 `vue` 的传递依赖，未显式声明）。
  - 影响：B2/B3（死页）、M5（假进度）、F1（列表假结论）这一类缺陷都不会被现有测试发现；后续迭代没有任何可自动触发的回归门禁。
  - 最小修法：加 `"test": "node --test tests/"` 并补 `polling`（重复 start 不叠加、终态停表）、`paging`（去重/翻页）、`taskStatus`（needsPolling 排除 WAITING_ROLE）的单测。

- **F12 · P2 · `TaskItem.progress` 是服务端不存在的字段，进度分支为死代码**
  - 位置：`diting-app/src/types/api.ts:30`（消费点 `RecordCard.vue:42-44`、`upload/index.vue:84`、`progress/index.vue:41`）
  - 证据：`TaskItemResponse` 只有 `id, taskId, status, durationSec, createdAt, flaggedCount, sentenceCount`（`dto/TaskItemResponse.java:5-14`），无 `progress`；类型注释还写着已被迁移掉的 `ONGOING`。
  - 影响：`res.progress ?? progress.value` 恒不生效，`正在听，已经 N%` 与进度条永远不可达；类型与真实响应不一致，后续若有人依赖它做判断会静默失效。
  - 最小修法：要么删掉该字段与相关分支，要么服务端补一个口径明确的 `progress`。

- **F13 · P2 · 进度页 `onLoad` 的异步续体缺少「页面已隐藏」守卫，隐藏后仍可能启动轮询**
  - 位置：`diting-app/src/pages/progress/index.vue:88-92`（对照 `:101` 的 `onHide`）
  - 证据：`onLoad` 内先 `await userStore.ensureLogin()`，之后**无条件** `ready.value = true; polling.start()`；`onHide` 只调用 `polling.stop()`，没有任何位置标记可以让这段续体感知页面已隐藏。
  - 影响：登录/网络较慢时用户先返回或切后台，续体随后启动的定时器不会再次被 stop（除非页面被 unload），回到「离开后仍每 3 秒请求」的 M6 场景（耗电 + 请求噪声）。
  - 最小修法：加 `let visible = true`，在 `onHide` 置 false，续体在 `polling.start()` 前判断。

- **F14 · P2 · 生产环境配置指向占位域名/未替换 appid，生产构建即失效**
  - 位置：`diting-app/.env.production:1`、`diting-app/src/manifest.json`（顶层 `appid` 为「请替换为微信小程序appid」，`mp-weixin.appid` 为空串）
  - 证据：`.env.production` 内容为 `VITE_BASE_URL = 'https://api.example.com'`，`api/request.ts:38` 直接用 `import.meta.env.VITE_BASE_URL` 拼 URL。
  - 影响：`npm run build:h5` / `build:mp-weixin` 的产物所有请求都打到不存在的域名；小程序因 appid 为空无法上传/发布。属发布前必须由部署方替换的占位值，但仓库里没有任何校验或失败提示。
  - 最小修法：构建时注入真实域名/appid，并在 `request.ts` 或构建脚本里对 `example.com`/空 appid 直接报错退出。

- **F15 · P2 · 两个 500 行级单文件组件（报告型）**
  - 位置：`diting-app/src/pages/report/detail.vue`（约 536 行）、`diting-app/src/pages/upload/index.vue`（约 504 行）
  - 证据：两文件均为 script + template + `<style scoped>` 混排，且恰好是历史缺陷最集中（B1/H2/H3/M3/M5）与本次 F1–F4 所在的两个页面。
  - 影响：状态分支（`waitingReport / canConfirmRoles / exporting / uploading`）与模板耦合在一处，任何状态改动都要在这两个文件里做全局核对，回归风险集中且无测试兜底。
  - 最小修法：把「上传选择+同意弹层」与「报告概要+导出区」抽成子组件，页面只保留编排逻辑。

## Review（汇总）

- Limitations:
  - **没有 diff**：本工作区不是 Git 仓库，任务也未提供 changed-file 列表或补丁，因此我无法判断上述问题是「本轮新引入」还是长期存在，也不能声称审阅了任何 diff；结论全部来自当前源码快照。
  - **没有运行任何东西**：未运行 `node --test`、未构建、未连库、未真机。我引用 `reportPresentation.test.cjs` 只说明它写了什么，不表示它现在通过；`docs/server-fix-batch1.md:31` 的运行记录是历史证据，我未能复现（且 `@vue/compiler-sfc` 并未在 `package.json` 中直接声明，仅靠依赖提升存在）。
  - **未验证（需运行时/真机才能确认）**：小程序端 `readFile`+`uni.request` 发送 50MB `ArrayBuffer` 的大小/内存限制；H5 预签名 PUT 的 CORS 预检与 `uni.openDocument`/`uni.saveFile` 在 H5 的可用性；`getCurrentPages().at(-1).options.id` 在 H5 是否可用（影响 401 后的回跳，`h5Auth.ts:24-27`）；`input` 的 `cancel` 事件浏览器兼容性（`choose.ts:44-47`）；导出作业真实耗时是否常超 60s；`report.notes` 在当前数据库里的实际出现频率。
  - **未覆盖范围**：`diting-admin` 全部，`diting-server` 除契约相关 controller/service/DTO 之外的实现（管线、额度、规则、生命周期细节）；根目录无 CI 配置可查，无法确认这些 app 侧检查是否被任何流水线执行。
  - **已核对但未上报（无具体后果）**：`history` 的 `onShow(load)` 与 `loadMore` 竞态窗口极窄；`loadUserInfo` 无人调用；`getToken` 在 H5 落 localStorage；`h5Auth` 未给 `pages/agreement/index` 生成 resume。
- 结论：F1–F6 为本轮必须先处理的发布前问题（其中 F1、F2 直接产生错误结论与错误计费），F7–F15 为报告项。历史问题中，B1/B2/B3/H1/H2（报告页）/M1/M3/M5/M7 已核实修复；M2 的语义与服务端冻结策略一致；M6 只修了「停」没有修「恢复」（F4），H2 的同类缺陷在列表层仍然存在（F1）。

Merge verdict: BLOCK