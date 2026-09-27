# Lane B · diting-admin 审核（原始报告，未经整理）

读完了主要证据（未提交 diff + 变更清单、新增文件、三端契约对照源码）。以下是结论。

## Review

**F1 · P1 · 顶部「时间范围」控件没有消费方：所有看板页硬编码 `period="30d"`（历史 V 系列待办第 9 条，本轮仍未修）**
位置：`diting-admin/src/layouts/AdminLayout.vue:119-124`、`diting-admin/src/views/overview/index.vue:32`（同型共 8 页）
证据：`AdminLayout.vue:119 const range = computed({ get: () => view.range, set: (value) => { if (...) view.setRange(value) } })`——全库 grep `range` 只有此处读 `view.range`；而页面一律写死：`overview/index.vue:32 <MetricsPanel section="overview" period="30d" />`（growth/finance/ops/map/quality/compliance 同为 `period="30d"`）。`docs/S15-多agent审核与阻断修复记录.md:80` 已把「时间范围选择器无消费方」列为未修待办。
影响：操作者点「今日 / 本季度」后按钮高亮、写进 localStorage，但取数始终是 `period=30d`（服务端 `StatsService.java:695-700` 明确支持 today/30d/quarter）。在合规/尽调语境下，界面声称的时间窗与实际数据窗口不一致，等于用一个无效控件把 30 天数据标示成「今日」。
最小修法：让 MetricsPanel 接 `view.range`（`TODAY→'today'`、`30D→'30d'`、`QUARTER→'quarter'`）并在 range 变化时 `metricsPanelRef.load()`；若本轮不打算接线，就删掉该 ToggleGroup 与 `Range` 类型。

**F2 · P1 · 协议富文本：客户端零清洗直接 `innerHTML`，服务端清洗是可绕过的正则黑名单**
位置：`diting-admin/src/components/RichTextEditor.vue:27`（注释主张见 `:5`）、`diting-server/src/main/java/com/diting/service/AgreementService.java:119-123`
证据：`RichTextEditor.vue:27 editor.value.innerHTML = props.modelValue || ''`（组件注释 `:5` 写「输出 HTML 由服务端二次清洗（去 script/事件属性）」，客户端不做任何渲染期清洗）；服务端 `sanitize` 为 `replaceAll("(?is)\\s+on\\w+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)", "")` 与 `(href|src)\\s*=\\s*"\\s*javascript:`——前者要求 `on*` 前有空白，故 `<svg/onload=alert(1)>`（`/` 分隔）不匹配；后者要求引号，故 `href=javascript:alert(1)` 不匹配，两者都会原样落库（`content_html` MEDIUMTEXT，`V6__S19_agreements.sql`），再由后台 innerHTML 与小程序 `rich-text` 渲染。
影响：协议正文是全站注册必看内容，写路径仅 ADMIN（`AdminAgreementController.java:56 @RequireRole(ADMIN)`），所以外部不可直接写入；但一旦经 API（或任何绕开浏览器序列化的路径）写入，后台就在 innerHTML 处执行脚本——注释宣称的安全边界并不存在。**可达性未验证**：经编辑器粘贴时浏览器会把属性序列化成 ` name="value"`，上述两种绕过写法在那条路径上反而会被拦掉。
最小修法：服务端改用白名单清洗（引入 jsoup `Safelist.basic()` 或等价物）再落库；同时在 `syncFromModel` 前用 DOMPurify 类库对 `modelValue` 做一次客户端清洗，形成第二层。

**F3 · P1 · 协议保存成功后 `active.contentHtml` 不回填，`dirty` 永远为真**
位置：`diting-admin/src/views/agreements/index.vue:42-47`（dirty）、`:84-90`（save 回写）
证据：`dirty = draft.title !== active.title || draft.contentHtml !== active.contentHtml`；save 只回写 title/version：`:86 active.value.title = saved.title`、`:87 active.value.version = saved.version`、`:88 drafts.value[active.value.key] = { title: saved.title, contentHtml: draft.value.contentHtml }`——`active.value.contentHtml` 从未更新，且 `drafts` 回填的是客户端自己的 HTML 而不是服务端清洗后的正文。
影响：每次保存后 `dirty` 仍为 true，保存按钮一直可点（`:116 :disabled="!canEdit || !dirty || saving"`），操作者无法从界面判断「是否已保存」；若服务端清洗改动过正文，编辑器长期显示的是与服务端/用户实际签署版本不同的文本（版本号却已 +1）。
最小修法：`saveAgreement` 返回值加 `contentHtml`，成功后同时写回 `active.value.contentHtml` 与 `drafts[key]`（或保存成功后 `await load()` 并保留其他 tab 的未保存草稿）。

**F4 · P2 · 账号被禁用/角色被降级时（403），客户端不清理登录态也不刷新角色**
位置：`diting-admin/src/api/request.ts:50-53`、`diting-server/src/main/java/com/diting/config/AdminAuthInterceptor.java:41-44`
证据：`request.ts:50 if (envelope.code === 401) { handleUnauthorized() }`——只识别 401；服务端禁用账号抛的是 `ResultCode.ACCOUNT_DISABLED(403)`（`AdminAuthInterceptor.java:43`、`ResultCode.java:22`），角色被改后越权也只是 `FORBIDDEN(403)`。客户端 `role` 只在登录时写入 localStorage（`stores/user.ts:9-15`），全库无任何刷新角色的接口调用。
影响：被禁用或被降级的后台账号仍停留在后台界面，`isAdmin` 仍为 true，菜单与「签发原件 / 导出含原件 ZIP / 删除 / 新增账号」按钮照常渲染，每个操作只弹一句 toast；界面继续声称使用者是管理员，只能靠人工刷新/重登恢复。（服务端每次都回库复核，越权写不会成功，因此不是越权漏洞。）
最小修法：`toApiError` 里对 `envelope.code === 403 && envelope.symbol === 'ACCOUNT_DISABLED'`（以及 `FORBIDDEN` 且当前路由受角色保护）走 `handleUnauthorized()` 同款清理与跳转。

**F5 · P2 · 短时签名下载地址被写进 localStorage，与其自身注释矛盾，且该字段已无任何消费方**
位置：`diting-admin/src/stores/exportTask.ts:27-28`、`:150-154`
证据：类型注释 `:27 /** 下载地址只在用户点下载时签发，不随状态轮询下发、不持久化 */`；但 `:152 jobs.value[index] = { ...jobs.value[index], downloadUrl: result.downloadUrl }` 紧跟 `:153 persist()`（写 `STORAGE_KEY = 'diting_admin_export_jobs'`）。全库 grep `downloadUrl` 只有 api/admin.ts:368、stores/exportTask.ts:28/152/155——弹窗已改为按 `status === 'COMPLETED'` 渲染按钮，没有任何地方读这个字段。
影响：携带签名的短时链接（指向含逐句原文的报告/ZIP）被留在 localStorage 直到退出登录，任何同源脚本都能读到；同时这是一份永不渲染的死状态，容易让后来者以为它承担了状态恢复功能。
最小修法：删掉 `TrackedExport.downloadUrl` 与 `:152` 的写入（download 只 `return result.downloadUrl`），或改为仅内存态不入 `persist()`。

**F6 · P2 · 三个 `@deprecated` 看板函数用 GET 携带 `data`，一旦被调用必然 400，且声明的响应类型是假的**
位置：`diting-admin/src/api/stats.ts:13-25`、`diting-admin/src/types/api.ts:50-67`
证据：`fetchDashboard/fetchGrowth/fetchFinance` 均 `request<DashboardData>({ url: '/api/admin/dashboard', data: { period }, token })`——未传 `method` 即 GET，axios 只把 `params` 拼进 query，`data` 作为请求体不会变成查询串；服务端 `AdminController.java:77 dashboard(@RequestParam String period)` 是必填查询参数。且这 3 个函数 + 3 个类型全库无引用（grep 仅命中定义处），而它们调用的是占位实现 `return R.ok(Map.of("period", period, "deprecated", true))`（`AdminController.java:79`），与 `DashboardData` 的字段毫无关系。`docs/S15:80` 已记录「`api/stats.ts` 用 GET 的 `data` 传参（启用即 400）」为未修待办。
影响：注释说「保留函数签名避免历史引用报错」，实际保留的是一个必然失败、且类型签名会掩盖真实错误的入口——调用方拿到的 `DashboardData` 在编译期全绿、运行期永远走 catch。**未验证**：400 的具体响应码未实际请求验证，按 axios/Spring 契约推断。
最小修法：删除这 3 个函数与 `DashboardData/GrowthData/FinanceData`（无引用），或改为 `params: { period }` 并把返回类型改成 `{ period: string, deprecated: boolean }`。

**F7 · P2 · 审计页权限矩阵声称「与服务端 @RequireRole 一一对应」，但列了 6 个服务端不存在的角色；授权时长文案与后端常量不符**
位置：`diting-admin/src/views/audit/index.vue:155`、`:85-89`、`:192`
证据：`note="静态口径：与服务端 @RequireRole 一一对应，不是实时数据"`，矩阵行却包含「管理层 / 质检复核员 / 客服」（`:86-89`）；服务端枚举只有 3 个角色（`AdminRole.java:15-20 ADMIN/OPERATOR/INVESTOR`），且 `@RequireRole` 只有这三种组合。`:192` 写「（授权默认 30 分钟过期）」，而原件签发 TTL 是 `AdminRecordingController.java:43 AUDIO_TTL_MINUTES = 15`。
影响：审计/合规页是「权限可被检查」的凭据，这里的矩阵对不存在的角色承诺了读明细/导出能力，并按错误的 30 分钟理解原件链接寿命，会被带进尽调或内部审计材料。
最小修法：把矩阵收敛为 ADMIN/OPERATOR/INVESTOR 三行并按 `@RequireRole` 逐格核对；`30 分钟` 改为引用后端常量值 15 分钟（或改为「短时有效，以签发时返回的 ttlMinutes 为准」）。

**F8 · P2 · MetricsPanel 删除错误展示后，加载失败时整个面板什么都不渲染且无重试入口**
位置：`diting-admin/src/components/MetricsPanel.vue:253-260`、`:431`
证据：catch 里 `:254 error.value = ...` + `:259 toast.error(error.value)`，模板中 error 只用于 `:431 v-if="!loading && !hasData && !error"` 隐藏空态；`v-if="loading && !hasData"` 与 `metrics.length/series.length/breakdown.length` 在失败时均为假 → 面板零输出（本次 diff 删掉了原来的 `<p v-if="error">{{ error }}</p>`）。对比同一 diff 里的 `settings/index.vue:115`（失败时给 EmptyState + 「重新读取」按钮）。
影响：网络抖动/500 时操作者看到一块空白面板，唯一的解释是一条会自动消失的 toast，且页面没有任何重新加载入口（`defineExpose({ load })` 无调用方），只能整页刷新；同为「读失败」的两种页面处理不一致。
最小修法：在失败且无数据时渲染一行错误提示 + 「重新加载」按钮（复用 `load()`），不要只靠 toast。

**F9 · P2 · 图表换库后丢掉了「全 0 序列仍有可见高度」的处理，其被删掉的注释正是为防这一项**
位置：`diting-admin/src/components/MetricsPanel.vue:170-174`
证据：本次 diff 删除了 `function barHeight(value, max) { if (max <= 0) return '2px' }` 及其注释「全 0 时给一个可见的最小高度，避免看起来像没渲染」；新实现 `chartPoints` 把 `null` 按 `0` 画，全 0 序列的柱高即 0。而 `api/stats.ts` 明确「value 允许为 0（补零后的真实 0，不是缺数据）」，新部署/某区间无数据时序列正好全 0。
影响：全 0 序列的面板里只剩坐标轴与标题，与「图表渲染失败」不可区分；早期使用阶段恰是这种数据形态最集中的时候。**未验证**：Unovis 在全 0 域下的实际渲染（是否零高柱/是否退化成空 SVG）需浏览器确认。
最小修法：给 `ChartPoint.value` 为 0 的点保留一个最小可见高度（例如绘图前把 0 映射到一个极小的正值并在 `tick-format` 中仍显示 0），或在序列的 `note` 里显式写「区间内全为 0」。

**F10 · P2 · 查询串拼接与「第 X / Y 页」口径在多处重复实现，已有 5 份 / 4 份拷贝**
位置：`diting-admin/src/api/admin.ts:117-121`、`:180-184`、`:278-282`、`:301-305`、`diting-admin/src/api/stats.ts:97-101`；`diting-admin/src/views/rules/index.vue:302`、`views/accounts/index.vue:274`、`views/audit/index.vue:250`、`views/recordings/index.vue:102`
证据：`Object.entries(params).filter(([, value]) => value !== undefined && value !== '').map(...encodeURIComponent...).join('&')` 五份逐字相同；分页文案/`pageCount` 由 4 个页面各自用 `Math.max(1, Math.ceil(total / pageSize))` 复算，而 `components/DataTablePagination.vue:23` 已经算过同一个 `pageCount`。
影响：一次过滤/编码口径修正（例如未来要支持数组参数或区分 `0`/`false`）必须在 5 处同步，漏改就会让某个列表的筛选静默变化；页码「X / Y」与翻页按钮的 `pageCount` 是两份实现，存在漂移风险。
最小修法：把查询串拼接收敛成 `buildQuery(params)` 放到 `api/request.ts`，并让 `DataTablePagination` 自己渲染「第 X / Y 页」，页面只保留 `#footer` 里的左侧信息。

**F11 · P2 · 无 404 兜底路由（历史 V 系列待办 V12 未闭环）**
位置：`diting-admin/src/router/index.ts:8-92`
证据：routes 只有 `/login` 与 `/` 的 children（最后一条 `accounts` 在 `:87-91`），没有 `/:pathMatch(.*)*`；守卫 `:104-117` 只处理未登录与投资人锁定。
影响：手输/粘贴过期 URL 时进入 AdminLayout 且 `<router-view>` 为空，页面没有任何「页面不存在」提示，也没有跳回总览之外的出口，普通操作者会以为是系统坏了。
最小修法：加一条 `{ path: '/:pathMatch(.*)*', redirect: '/overview' }`（或一个 404 视图）。

### Correct（本轮核实为「确实已按历史记录修好」的部分）
- **新协议接口契约一致**：`api/admin.ts:382-392` 的 `GET /api/admin/agreements` 与 `PUT /api/admin/agreements/{key}`、字段 `key/title/version/contentHtml`，与 `AdminAgreementController.java:37-64`、`V6__S19_agreements.sql`（`agreement_key/title/content_html/version`）逐字段对齐；写权限前端 `isAdmin` 与服务端 `@RequireRole(ADMIN)` 一致。
- **导出下载改造契约一致**：`api/admin.ts:367-372` 的 `/api/admin/exports/{exportId}/download` 真实存在（`ExportController.java:116-120`），返回 `{exportId,fileType,downloadUrl,expiresAt}` 与 `downloadPayload` 一致；同时状态接口确实不再返回 `downloadUrl`（`ExportController.java:135-146`），客户端 `ExportJob` 删掉该字段与按钮改判 `status === 'COMPLETED'` 是对的，历史「轮询既签发又写审计」的噪声问题从两端都关掉了。
- **历史 V1/V2（假指标、假刷新承诺）确已修实**：`DemoNotice` 已不存在，8 个看板页统一走 `/api/admin/stats/{section}`；`overview/finance/compliance/growth/map` 页内的 KPI、漏斗、渠道、MRR 表均已删除并写明「没有数据源，不编」。
- **历史 S18「同步查询串」模式已消失**：只剩 `recordings/index.vue:106-186` 把 `?detail=<id>` 同步到地址栏（合理用途），筛选条件不再与 URL 双向同步。
- **定时器/监听器无泄漏**：全库唯一 `setInterval` 在 `stores/exportTask.ts:116`，`pendingCount === 0` 时 `stopPolling()`，登出经 `AdminLayout.vue:145-149 logout() → exportStore.clear() → stopPolling()`；`AdminLayout.vue:139` 的 700ms `setTimeout` 只是提示闪一下。SSE 无。无 `addEventListener`/`onUnmounted` 缺口。
- **类型纪律**：全库无 `as any` / `@ts-ignore` / `@ts-expect-error` / `eslint-disable`；`src/components/ui/chart/**` 无 XSS 面（`ChartTooltipContent.vue` 全走插值，`--chart-1` 令牌在 `style.css:68` 存在，颜色链路可解析）。

- Limitations：
  1. **无运行时证据**：本轮不能跑 `vue-tsc` / `eslint` / `vite build`、不能开浏览器、不能连库，所有结论均来自源码静态阅读。因此 F9（全 0 序列的实际渲染）、F2（payload 在真实浏览器中的执行/被拦行为）、F6（400 的实际响应）、Unovis 图表几何与 tooltip HTML 字符串（`components/ui/chart/utils.ts:31-42`）均为**未验证**推断。
  2. **未覆盖**：diting-server / diting-app 两端的逐行审核不在本 lane 范围；F2 中「小程序 `rich-text` 渲染同一批 HTML」（`diting-app/src/pages/agreement/index.vue:38`）的下游后果未展开评估，需对应 lane 确认。
  3. 未做视觉/交互验收：图表配色、横轴稀疏刻度、Tab 切换时 `RichTextEditor` 复用与 `syncing` 标志（`RichTextEditor.vue:21-40`）在真实浏览器下的行为未验证。
  4. 已识别但按「宁少勿滥」未列为 Finding 的低价值项：`views/ops/index.vue:128` 面板名「实时队列」而实际是 `onMounted` 一次性快照（仅手动「刷新队列」）；`lib/enums.ts` 中 `METRIC_SOURCE_TEXT/REPORT_STATUS_VARIANT/MEDIA_KIND_TEXT/UPLOAD_STATUS_TEXT/QUOTA_STATE_TEXT` 等无引用导出；`MetricsPanel.vue:265 defineExpose({ load })` 无调用方（但正是 F1 的接线点）。
  5. 未读：`views/audit`、`rules` 之外未逐行核对的 shadcn-vue 生成组件（按指令不逐条挑刺）。

未发现 P0。F1/F2/F3 三条 P1 建议在发布前修完（F1 是历史待办未闭环，F2/F3 全部落在本轮新增/未跟踪文件上）。

`Merge verdict: OK with notes`