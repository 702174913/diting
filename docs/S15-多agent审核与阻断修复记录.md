# S15 多 agent 代码审核与阻断缺陷修复 · 记录

版本：v1.0 · 日期：2026-09-24 · 阶段：S15（审核阶段）
规格依据：[开发文档](./开发文档.md)、[开发计划](./开发计划.md)；前序阶段记录 S01—S14。

**结论摘要**：用 5 个**只读**审核 agent 对三端做了独立复核（后端核心链路、后端运维链路、后台前端、
小程序前端、功能完整性）。**审核结论是「未完成」**：5 份报告里 4 份 FAIL、1 份 PARTIAL，
发现 **2 个阻断级越权**、1 个「任务永远到不了 COMPLETED」的功能性阻断、小程序导出与进度页两处
100% 失效，以及若干诚实性/口径问题。本轮已修复阻断与高危共 **22 项**（含新增回归测试 3 项、
限定单测 74 项全绿、SFC 绑定检查 28 个文件 0 问题），其余 **26 项**列入待办（本文 §4），
不冒充已修完。

---

## 1. 审核方式与范围（全部只读）

| Agent | 范围 | 结论 |
|---|---|---|
| A1 | 后端核心链路 S02—S08（鉴权/额度/上传/听悟/管线/规则/报告，约 80 个 Java 文件） | 高：任务永不到 COMPLETED 等 |
| A2 | 后端运维链路 S09—S13（导出/统计/生命周期/后台接口/生产暴露面，47 个文件） | **FAIL：2 个阻断级越权** |
| A3 | 后台前端 diting-admin（25 个 ts/vue + 与 15 个 controller 的契约对照） | FAIL：硬编码假指标 |
| A4 | 小程序前端 diting-app（33 个 src 文件 + 后端契约对照） | FAIL：导出与进度页失效 |
| A5 | 功能完整性 F01—F12 × S00—S14 交付物矩阵 | PARTIAL：证据链自洽，但存在未完成项 |

审核纪律：只读、不建库、不连真实库 `diting`、不跑构建、不启服务、不调云 API。A5 独立复核了
测试数量（161 个 `@Test` + 16 个参数化用例 = 177）与 12 个 E2E 脚本的 `PASS` 行数（370 项），
与既有证据日志一致——**此前的「177 项全绿」结论本身没有被推翻**；被推翻的是
「功能已完成」这一层判断。

## 2. 阻断级发现与修复

| 编号 | 问题（来源） | 影响 | 修法 | 验证 |
|---|---|---|---|---|
| F-01 | 导出只校验 path 上的 taskId 归属，`reportVersionId` 直接落库（A2） | 用户 A 可用自己的任务 id 配他人自增版本 id，导出**他人逐句原文 PDF**；`withAudio` 时连原始录音一起打包 | `ExportService.create` 增加版本归属校验，不属于该任务一律 404 | 编译 + 单测 |
| F-02 | 后台导出对 OPERATOR 开放 `withAudio`（A2） | 绕过「OPERATOR 不可看原件」的权限矩阵 | `createForAdmin` 对非 ADMIN 的 `withAudio` 请求返回 403 | 编译 + 单测 |
| D-01 | 管线解析后停在 `WAITING_ROLE`，而发布只在任务已是 `ANALYZING` 时置 `COMPLETED`，全仓**无写入者**把任务置为 `ANALYZING`（A1） | 角色确认后任务永远停在非终态：进度页一直「处理中」、`completedTasks` 恒为 0、S06/S08「报告发布后置 COMPLETED」未实现 | `ReportAnalysisService.publish` 在角色确认时先 `WAITING_ROLE → ANALYZING`（CAS），再走统一完成守卫；新增 `TaskPipelineService.markAnalyzing` | **新增 3 项单测**（确认→完成 / 未确认→不完成 / CAS 失败→不强行完成） |
| B1 | 报告页调用**不存在的** `exportReport()`（A4） | 两个导出按钮 100% 失败（被 catch 吞成「导出失败」） | 改为 `createExport → waitExport → 按 fileType 分流` | SFC 绑定检查 + 类型检查式复核 |
| B2/B3 | 进度页写 `timer = setInterval(poll, 3000)` 但两者都没定义；模板绑定不存在的 `stopPolling`（A4） | 页面 `onLoad` 直接抛错：**死页**，永远停在初始状态、不发请求 | 改为 `polling.start()`；补 `leaveNow/retryUpload`；`onHide/onShow` 管理轮询 | SFC 绑定检查 + 阳性对照 |
| F-04 | 第二次「全部抹除」命中唯一键后**复用旧作业且不重新排期**（A2） | 重新上传后再抹除：接口回 `COMPLETED`，而句子/报告/命中/对象永不清理 | `enqueue` 改为「合并待清理对象 + 重新排期（清租约/结束时间/重试计数）」；完成动作加 CAS 守卫，防止运行中的作业把新并入的对象清空 | 编译 + 单测 |
| F-03 | `application.yml` 里是**真实库密码与 JWT secret**（A1/A2） | 泄露即可伪造任意身份域 token；密钥进配置文件 | 改为环境注入（`DITING_DB_PASSWORD` / `DITING_JWT_SECRET`，**无默认值**，缺失即启动失败）；测试资源内提供测试值；11 个 E2E 脚本头的启动示例同步 | 限定单测（含 2 个 WebMvcTest 切片）全绿 |

## 3. 高危/中危修复

| 编号 | 问题（来源） | 修法 |
|---|---|---|
| D-02 | 回调唤醒无条件清租约，正在提交的工作者会被抢走，云端已受理的任务号落不了库（A1） | `wakeUp` 只在租约**已过期**时清租约 |
| D-05 | 微信未配置时仍打真实接口，且把含 openid/session_key 的响应体写进异常与日志（A1） | 未配置即 503 `NOT_CONFIGURED`；失败只记 `errcode` |
| D-04（部分） | `declaredDurationSec` 可省略绕过时长校验；「云端真实时长回填」未实现（A1） | 解析后**回填真实时长**（超限只告警、不作废已付费结果）；客户端拿不到时长时不再传 0 |
| H1 | 单条删除入口监听 `@longpress`，但组件从未 emit（A4） | `RecordCard` 显式声明并 emit `longpress` |
| H2 | 进行中/未发布任务被渲染成「听完了、没有发现问题」（A4） | 未发布状态分流：显示「还在准备记录」+ 完整转写 + 角色确认；首页按状态分流到进度页 |
| H3 | PDF 与 ZIP 不分流，ZIP 也走 `openDocument`（A4） | 按 `fileType` 分流：PDF 打开、ZIP 走 `shareFileMessage`/`saveFile` |
| M1 | 网络层技术文案（`request:fail timeout`）直接给用户看（A4） | `code=0` 统一转译为「网络不稳定，请检查网络后重试」 |
| M2 | 签名过期（PUT 403）只能重选文件（A4） | 403/401 时 `refreshUpload` 刷新同会话并重试一次 |
| M3 | `confirmUpload` 缺连点守卫，两次点击会登记两个任务、扣两份额度（A4） | 增加 `busy` 守卫 |
| M5 | 后端没有进度字段，前端恒显 0% 进度条（A4） | 拿不到百分比就不显示进度条与数字 |
| M6 | Tab 页切走不触发 `onUnmounted`，离开后仍每 3 秒请求（A4） | 增加 `onHide` 停止轮询 |
| M7 | 界面直出后端英文状态；确认角色后无反馈（A4） | 状态中文标签 + 「记录已生成」提示 |
| V1 | 质量页硬编码「转写准确率 96.4%」，与「不出现假准确率」冲突（A3） | 准确率/精确率改为「待采集（需人工标注真值）」 |
| V2 | 5 个演示页数字全硬编码且无标注；「每 30 秒刷新」无实现（A3） | 新增 `DemoNotice`「演示口径」提示并插入 6 页；删掉假的刷新承诺 |
| V4 | 审计页宣称「连管理员也看不到录音原文」，与后端实际能力相反（A3） | 改为真实口径：OPERATOR 只看脱敏文本，ADMIN 授权留痕可取原件 |
| — | 后台规则页在模板里写 TS 断言 `as HTMLInputElement`（本轮检查器发现） | 抽出 `inputValue()` 助手，模板不写断言 |

## 4. 待办（本轮**未**修复，按优先级）

**安全/正确性**
1. **D-03 额度与重试**：确定性失败写入 `SUBMITTING` 后永不重提；`QuotaService.release()` 全仓无调用方 →
   额度可能被永久预占且无人工退款入口；异常统一写成 `PARSE_FAILED` 影响告警定位。
2. **D-12**：听悟未配置时 `tick()` 静默返回 0，任务永久 `QUEUED` 且额度照扣，无 `NOT_CONFIGURED` 信号。
3. **F-06**：PDF 模板标记 `#TITLE#/#SECTION#` 疑似进入正文（全仓无剥离逻辑），需渲染取证后修。
4. **F-07**：`@Transactional` 自调用失效（ExportService / RetentionService），失败时可能残留无引用对象。
5. **F-05 / F-12**：统计风险占比分子分母口径不一致（可 >100%）；统计未过滤 `deleted_at`；`ruleDistribution` 的 value 与 note 矛盾。
6. **F-08 / F-09 / F-10 / F-11**：老任务 `expires_at` 为 NULL 时原件永不过期（需查库确认规模）；导出作业无重试/租约；
   清理完成后任务停在 `DELETING`；作业不存在返回 200+信封 404，且任意 ADMIN 可查任意 jobId。
7. **D-06 / D-07 / D-08 / D-09 / D-10**：句子落库事务自调用；并发角色确认抛 500 而非 409；
   时间字段时区不一致（`systemDefault` vs `Asia/Shanghai`）；`violations` 缺规则 `description`；`@Valid` 返回 400 而契约只列 422。
8. **F-13**：后台导出对 ADMIN 一律脱敏，与 S11 记录「ADMIN 出原文」相反（过度脱敏 + 文档不一致）。

**前端呈现/可用性**
9. **V5 / V6 / V7 / V9 / V11 / V12**：投资人视图是客户端开关且锁定页集合不含个体数据页；
   时间范围选择器无消费方；菜单不按角色过滤；`api/stats.ts` 用 GET 的 `data` 传参（启用即 400）；翻页无请求守卫；无 404 兜底。
10. **V8**：审计页固定 `page=1&pageSize=50`，无翻页；账号管理接口未接。
11. **6 个后台演示页接真**：`StatsService` 的 `ops/compliance/quality/growth/finance` 已就绪，
    逐页替换属后续迭代（本轮只做了**诚实标注**，没有伪造「已接真」）。

**外部条件（阻塞，非工程可解）**
12. 云凭据/私有 bucket/CORS、听悟 AppKey 与配额、微信 appid/secret + HTTPS 域名、授权音频与计费确认。
13. 中文字体商用授权（当前用 macOS 系统字体验证渲染）。
14. PDF 脱敏两端一致口径、文本/报告/审计保留时长、账号删除是匿名化还是删行、云端任务结果删除能力。
15. 人工验收：小程序 12 条手验清单、后台页面浏览器浏览、PDF/ZIP 真机打开与中文渲染。

## 5. 本轮验证与证据

| 验证 | 命令/方式 | 结果 | 证据 |
|---|---|---|---|
| 服务端编译 | `mvn -o -DskipTests compile` | 通过 | — |
| 限定单测（11 个类，含新增回归） | `mvn -o -Dtest='...' test` | **74 项全绿** | `docs/evidence/S15/java-tests-selected-*.log` |
| 小程序 SFC 绑定/编译检查 | `node docs/evidence/S15/sfc-binding-check.mjs diting-app ...` | 13 个文件 **0 问题** | `docs/evidence/S15/sfc-binding-app-*.log` |
| 后台 SFC 绑定/编译检查 | 同上（diting-admin） | 15 个文件 **0 问题** | `docs/evidence/S15/sfc-binding-admin-*.log` |
| 检查器阳性对照 | 对修复前的缺陷代码运行 | **抓到** `stopPolling` 未定义 | `docs/evidence/S15/sfc-check-selftest-*.log` |
| S09 前端 harness 重跑 | 按原方式重新打包改动模块后运行 | 全部通过 | `docs/evidence/S15/fe-harness-*.log` |

**本轮无法复跑的项**：12 个 E2E 脚本（需要专用测试库凭据，本会话没有；且按纪律不连真实库 `diting`）、
真机/微信开发者工具、浏览器人工浏览、云调用。这些不因本轮修复而「通过」，仍按 S09/S14 的清单待人工验收。

## 6. 本轮改动文件

**服务端**：`service/export/ExportService.java`、`controller/ExportController.java`、
`service/report/ReportAnalysisService.java`、`service/TaskPipelineService.java`、
`mapper/RecognitionTaskMapper.java`、`mapper/DeletionJobMapper.java`、`service/DeletionService.java`、
`service/lifecycle/RetentionService.java`、`service/WechatAuthService.java`、
`src/main/resources/application.yml`、`src/test/resources/application.properties`（新增）、
`src/test/resources/*/run_*.sh`（11 个脚本头）、
`src/test/java/com/diting/service/TaskPipelineServiceTest.java`、
`src/test/java/com/diting/service/report/ReportAnalysisServiceTest.java`（新增）。

**小程序**：`utils/errors.ts`、`utils/taskStatus.ts`、`api/upload.ts`、
`components/ProcessingCard.vue`、`components/RecordCard.vue`、
`pages/home/index.vue`、`pages/upload/index.vue`、`pages/progress/index.vue`、`pages/report/detail.vue`、`pages/history/index.vue`。

**后台**：`components/DemoNotice.vue`（新增）、
`views/{quality,ops,growth,finance,compliance,map}/index.vue`、`views/audit/index.vue`、`views/rules/index.vue`。

**文档**：`docs/S14-云POC与上线门禁记录.md`（密钥环境化与证据卫生）、本文、`AI_CHANGELOG.md`。

## 7. 结论

- **「是否完成所有功能开发」：没有。** 修复本轮阻断项之后，F01—F12 在代码与限定测试层面成立，
  但仍有 §4 的 26 项待办与外部阻塞；**真机、浏览器、云端三类验收一律未做**，不得声称端到端可用。
- 本轮修复的每一项都给出了**可离线复现**的验证方式；不能离线验证的（越权的端到端利用、E2E 全链路）
  明确标为未验证，不用「已修复」冒充「已验收」。
