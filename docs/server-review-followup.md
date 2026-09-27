# Server 深入审查：并发、隐私和前后端闭环

本轮按用户选择继续审查，不修业务代码或原有测试。承接 [首轮审查](./server-review.md)，编号继续使用 R13—R24。

> 后续用户已授权修复。以下保留审查时的缺陷与测试历史；最新进展见 [第一批修复记录](./server-fix-batch1.md)。首批五类复现问题已修改并补回归：后端限定 28 类、220 项通过，前端 8 项通过。不代表本文所有问题均已关闭，也不代表真实数据库/云端/真机验收通过。

## 审查边界

- 新增检查：抹除后的身份与在途发布、配置并发、上传会话消费、后台最后管理员保护，以及小程序首页/历史/上传/进度/报告/隐私、后台录音/策略的接口使用。
- 没有调用云服务、连接业务数据库、修改数据库、操作真实用户记录或运行全量类型检查。
- 并发问题按源码与 SQL 条件分析，尚未在真实 MySQL 多事务环境复现。
- 额外启动了临时纯内存 Java/前端表达式探针：Mapper 和云服务均 mock，直接调用当前已编译服务类；不是全链路验收。探针结果在本文最后单独记录。
- 首轮限定测试的 **199 项、190 通过、2 失败、7 错误** 仍是现状；本轮不通过删断言或改测试来掩盖它们。

Java 路径简写相对于 `diting-server/src/main/java/com/diting/`。

## 新发现

### R13 · P1：有违规的记录在首页/历史中仍显示“没有发现”

定位：`service/RecognitionTaskService.java:290-305`、`service/TaskPipelineService.java:291,422`、`service/report/ReportAnalysisService.java:77-98`；`diting-app/src/components/RecordCard.vue:21-51`。

列表 flaggedCount 来自 `SUM(sentences.flagged)`；新句子一律写 false，报告发布只写 violations/report_versions，没有将句子缓存标志回填。AST 搜索生产 Java 中所有 `setFlagged` 调用仅发现上述两处 false，未发现其他 SQL 更新该字段。

因此新任务即使报告命中违规，列表仍得到 flaggedCount=0；前端 COMPLETED 分支渲染绿色“没有发现要留意的话”。这不是单纯数字小误差，而是首页结论与详情相反。

修复方向：列表按最新已发布版本聚合违规；不能只使用 flaggedCount 判断通话级缺失项是否存在。

### R14 · P1：失败报告和通话级缺失项被渲染成“没问题”

定位：`diting-app/src/pages/report/detail.vue:35-61,328-331,393-405`；`service/report/ReportService.java:158-192`；`service/export/ExportService.java:76-81,245-250`。

两条独立触发路径：

1. 首次分析失败时，后端 resolveVersion 找不到成功版，会返回 FAILED 版本。它仍有 reportVersionId；前端 waitingReport 只判断 ID 是否存在，因此当成已生成记录展示。零证据又触发“没有发现要留意的话”，导出按钮也被解锁。导出后端只检查版本属于任务，没有要求 PUBLISHED。
2. must-have 缺失项的 sentenceId/ranges 可以为空，flaggedCount=0 但 violationCount/riskScore>0。页面只遍历有 notes 的句子，不展示 `report.violations` 的通话级说明；最多显示“含缺失项”的数量，主标题仍是“没有发现”。

此外前端版本文案看的是当前展示版 status，不是 latestStatus，且没有展示报告顶层 notes；最新重算失败但回退旧成功版时，用户也可能不知道失败。

修复方向：正式报告要求 status=PUBLISHED；将任务状态、当前展示版、最新分析状态分开；独立呈现通话级违规，并在服务端阻止失败版作为正式产物导出。

### R15 · P1：任务未转写完成也能确认伪造说话人并发布空报告

定位：`controller/ReportController.java:50-55`、`service/report/ReportService.java:63-98,105-130`、`service/report/ReportAnalysisService.java:56-110`。

saveMapping 只验证非空、revision 与角色枚举，不检查任务是否已经进入可分析状态，也不验证 speakerId 是否存在于任务句子中。analyze 允许空句子。

于是本人刚提交的 QUEUED 任务，也可提交 `{999: AGENT}` 之类映射并得到 PUBLISHED 空报告；发布路径没有强制任务须进入 ANALYZING 才能发布。对于已转写任务，遗漏实际说话人或填虚构说话人还会使角色依赖规则失去适用对象，却被当作“角色已确认”。

修复方向：验证可分析状态、文本就绪、说话人集合与允许的 UNKNOWN 策略；避免任意非空映射即表示完整确认。

### R16 · P1：重算失败后，前后端陷入永久 revision 冲突

定位：`service/report/ReportService.java:74-83,171-179,231,307`；`diting-app/src/pages/report/detail.vue:99`；`diting-admin/src/views/recordings/index.vue:217`。

场景：

1. 成功报告 v1 引用映射 revision=1。
2. 用户提交 revision=2，映射先保存成功，但报告分析失败，记录失败版。
3. 详情默认回退 v1，同时返回 `mappingRevision=1`，却用 currentRoles 返回 revision=2 的角色。
4. 两端继续用返回的 mappingRevision=1 提交，服务端 currentRevision 已是 2，必定 409。
5. 刷新仍回退 v1，无法通过当前 UI 恢复。

这与首轮 R07 的历史角色错配相关，但新增影响是**正常的失败重试入口不可用**。

修复方向：显式区分 currentMappingRevision 与 displayedReportMappingRevision；编辑 CAS 使用当前映射版本，旧报告展示仍用旧映射。

### R17 · P1：删除后，在途工作者仍可重新落库文本和报告

定位：`service/report/ReportAnalysisService.java:77-110`、`service/TaskPipelineService.java:405-430,459-478`、`service/lifecycle/RetentionService.java:150-188`。

首轮 R06 检查的是导出；本轮确认相同问题也存在于转写/报告本身：

- 在途管线的 persistSentences 不检查墓碑或工作者版本，直接删/插句子。
- 报告先写 PUBLISHED，再尝试推进任务；`markAnalyzing()` CAS 返回 false 不会抛异常，发布事务仍可提交。markCompleted 的底层 advance 影响零行也没有阻止发布。
- 已 COMPLETED 任务的重算更没有必须成功的任务 CAS。

若删除/清理在工作者拿到快照后完成，旧工作者仍可能重新插入敏感文本、报告、违规。任务仍不可见不代表内容已被清除；清理作业已完成后也未必会再清这一批新落库数据。

修复方向：以任务墓碑/代际版本与发布事务建立原子门禁，拒绝旧工作者写入；删除等待或阻断在途发布。不要仅在领取和 HTTP 入口检查一次。

### R18 · P1：“删除账号数据”后旧 token 仍能写入并重新绑定账号

定位：`config/UserAuthInterceptor.java:21-28`、`service/TokenService.java:38-75`、`mapper/UserMapper.java:15-17`、`service/AuthService.java:99-112`；`diting-app/src/pages/privacy/index.vue:33-70`。

全部抹除后 user 行只清身份字段，仍保留 id；USER 拦截器只验证 JWT，未查询账号是否注销，也没有 tokenVersion/revokedAt。前端 clear 仅删除本地 token，不能使已保存的 token 失效。

另一设备或保留 token 的调用方，在默认 72 小时有效期内仍能上传、提交、调用 bind-wechat 将新微信重新绑回已匿名化账号。删除执行期间还可以持续创建数据，与首轮发现的 cleanupUser 扫描范围问题叠加。

修复方向：先明确“只删历史数据”还是“注销账号”的产品语义；按当前界面承诺，需要注销标志/会话撤销、写入口禁用，并设计清理状态查询凭据，不能只清客户端存储。

### R19 · P1：策略配置的 expectedVersion 不是原子乐观锁

定位：`service/ConfigService.java:94-111,145-155`、`entity/AppConfig.java:24-25`、`config/MybatisPlusConfig.java:23-26`。

save 先普通查询版本，校验 expectedVersion 后无条件 updateById。version 无 @Version，插件也只有分页，没有隐式乐观锁。

两个请求可同时读取 v1 并通过检查，然后依次把同三行写成各自配置、版本都为 v2；两个请求都成功，先提交的修改被静默覆盖。事务保证三行一组提交，但不能替代“版本匹配条件更新”。

修复方向：锁定统一配置版本行，或使用原子 CAS 再写完整配置快照；补真实数据库两个保存请求竞争测试。

### R20 · P1：同一图片上传会话可被并发登记到两个任务

定位：`service/UploadSessionService.java:192-205`、`service/RecognitionTaskService.java:118-159,164-179`；`src/main/resources/sql/migration/V4__S16_media_assets.sql:85-108`。

consumeForSubmit 是先读 VERIFIED，再无条件 updateById 为 CONSUMED，没有 CAS/行锁。使用不同幂等键的两个并发请求可都读到 VERIFIED，分别完成消费与任务登记。

音频由 recognition_tasks.upload_id 唯一约束部分兜底；纯图片任务该列为空，task_assets.upload_id 又没有唯一约束，因而图片缺少数据库兜底。两个任务随后共享同一个 objectKey，重复 OCR/占额度；删除其中一个还会删掉另一个仍引用的图片原件。

修复方向：会话消费做条件更新并检查影响行数；为一次上传只属一个任务增加数据库约束，清理不得删除其他活跃引用。

### R21 · P1：弱网重试在前端重新生成幂等键，可能重复扣额度和计费

定位：`diting-app/src/pages/upload/index.vue:270-310`。

attemptKey 与 uploadIds 都是单次 confirmUpload 的局部变量。若 submitTask 已在服务端成功，但响应丢失，catch 只隐藏进度，仍保留本地文件。用户再点上传时生成新 key、创建新上传会话并再次登记，服务端无法识别是上次操作的重试。

代码注释“弱网重试只会登记一个任务”不成立：连点守卫只防同时点击，不能防响应不确定后的再次点击。

修复方向：把逻辑提交的幂等键与已验证 uploadIds 保留到结果确认；网络不确定时先查/重发同一提交，而不是重新上传。

### R22 · P1/P2：图片能力尚未贯通所有下游流程

定位：`service/TaskPipelineService.java:371-380`、`dto/SentenceResponse.java:15-47`；`diting-app/src/pages/report/detail.vue:341-367,422-427`、`diting-app/src/components/SentenceItem.vue:59-60`。

- **P1，混合任务遗漏有效图片**：音频解析结果为空时直接 INVALID，未进入图片 OCR；即使图片有有效文字也不会处理。
- **P2，纯图片 ZIP 无入口**：后端支持 withAudio 打包图片原件，但小程序以 !audioAvailable 禁用 ZIP；纯图片永远 false，还被错误提示“原始录音已过保留期”。
- **P2，纯图片仍要求确认说话人**：报告页始终显示角色确认按钮，纯图片没有 speakerId，提交空映射只会 422。
- **P2，证据来源丢失**：Sentence 实体有 sourceAssetId，但 DTO 和客户端 Sentence 缺该字段；图片句子被显示成 00:00/说话人未知，无法定位“图片几”的证据来源。

修复方向：按媒体类型决定处理、状态与 UI；混合输入应定义部分有效/部分失败规则；把 sourceAssetId/媒体来源贯穿报告与导出。

### R23 · P1：并发操作可突破“至少保留一个 ADMIN”

定位：`service/AdminUserService.java:120-155,168-188`。

后台 update/softDelete 通过普通 COUNT 检查启用 ADMIN 数，没有公共锁/串行化控制。只有两个管理员时，两个请求分别禁用或删除不同账号，都可能读到 count=2，然后都成功，最终零个启用 ADMIN。

修复方向：对管理员成员变更建立同一把数据库锁或锁定公共约束行；事务本身不保证跨行不变量。并发场景需真实库验证。

### R24 · P1：清理作业租约恢复没有工作者版本栅栏

定位：`mapper/DeletionJobMapper.java:14-30,44-55`、`service/lifecycle/RetentionService.java:106-142`。

领取与完成只比较 status：

- 两个实例同时看见过期 RUNNING 后，都可执行 RUNNING→RUNNING，因为 UPDATE 不复核旧租约，也不增加并校验领取版本。
- 用户 rearm 把作业改回 QUEUED 后，新工作者再次领取成 RUNNING；旧工作者随后执行 markCompletedRunning，也能把新一轮作业提前标成 COMPLETED（状态经历一轮变化后又相同，旧工作者无法识别）。

结果：可能重复清理、旧工作者覆盖新任务进度，或报告“清理完成”但新一轮未实际完成。

修复方向：每次领取/重排增加 generation，完成与失败均按 token/version CAS；领取时复核租约到期条件。只比较 RUNNING 状态不足以证明所有权。

## 其他前端闭环缺口（暂不另计编号）

1. 上传 Tab `onHide(stopPolling)` 后，onShow 只做登录检查，不恢复轮询；切出去再回来可能永远停在旧状态。独立 progress 页有恢复逻辑，但 upload 页没有。
2. 首页/历史把 FAILED/INVALID 当“非运行”直接送报告页；首次无报告时又出现“确认角色”而不是明确失败恢复入口。
3. 正式报告只展示 flaggedSentences，“其余部分”只给一句无问题说明；未命中原文不再可查看，不满足完整对话复核需求。
4. 隐私页只查询一次清理状态，马上清 token，不保存清理 job 的可恢复入口；FAILED 也会被统一写成“正在清理”。
5. 首页 Tab 在 token 清空时 load 直接返回，没有清 records；同一客户端保留页面状态时可能仍展示上个账号的记录摘要。
6. 隐私/上传页面宣称“不向任何第三方提供”“不会给任何人看”，但服务实际使用阿里云处理，ADMIN 也有授权访问；应改为真实的数据处理/授权说明。存储加密是否启用还需要云端配置证据，不能仅凭页面宣称。

## 本轮验证与证据

### 静态证据

- 已对照实际 Mapper SQL、实体字段/注解、事务入口、Controller 权限、DTO、前端读取条件，不以注释当实现。
- AST 查询 `$OBJ.setFlagged($VALUE)` 在生产 Java 中仅返回两处 `false`；辅以 SQL 文本搜索确认未发现命中缓存更新。
- 核对 V4 资产迁移：唯一键为 `(task_id, sequence)`，未提供 `upload_id` 全局唯一约束。
- 明确区分真实可变状态（任务/映射 revision）与不可变历史报告引用。

### 隔离探针：5 个缺陷条件均复现

临时源文件：`/tmp/diting-review-probes.ZHSdfi/ReviewProbes.java`、`frontend-probes.mjs`。

Java 探针：
- 模拟任务推进 CAS 被拒，检查发布结果是否仍为 PUBLISHED。
- QUEUED 空文本任务，提交不存在的 speakerId=999，检查是否可发布。
- MIXED 任务空音频结果，检查是否在访问图片前直接 INVALID。

前端表达式探针直接提取当前 report/detail.vue 的 waitingReport/headline 表达式：
- FAILED 且存在 reportVersionId 时是否被当作可用报告。
- 通话级缺失项 riskScore=80、flaggedCount=0 时的标题。

**实际结果：后台任务 `b9f7b2b37` 退出 0，以上 3 个 Java 条件及 2 个前端条件均输出 CONFIRMED。**

| 探针 | 实际观察 | 对应问题 |
|---|---|---|
| 任务状态 CAS 被拒 | 报告仍返回 PUBLISHED，并执行版本更新 | R17：发布缺少原子门禁；删除场景本身未接真实数据库复现 |
| QUEUED + 空转写 + speakerId=999 | 接受映射并返回 PUBLISHED | R15 |
| MIXED + 空音频 | 直接 INVALID，OCR/资产 Mapper 均未被调用 | R22 |
| 首次 FAILED 报告有版本 ID | waitingReport=false，标题“没有发现要留意的话” | R14 |
| 通话级缺失项，风险分 80，命中句数 0 | 标题仍是“没有发现要留意的话” | R14 |

摘录证据：[isolated-probes.log](./evidence/server-review/isolated-probes.log)。完整原始日志：`.pi/tasks/01a0dd82-07a0-730f-ad96-71f9142243cd-36885/b9f7b2b37.output`。

**退出 0 表示成功复现了缺陷，不表示业务验收通过。探针不替代真机、浏览器、数据库并发或云端测试。**

## 完成度结论更新

相比首轮，新增证据说明问题不仅是故障路径：**正常首页结论、缺失项展示、图片原件导出和角色重算恢复，也没有形成一致闭环。**

可以说“业务模块和接口基本齐备”，不能说“所有功能差不多完成，只剩部署”。建议完成以下验收再评估交付：

1. 同一任务在首页、历史、详情、PDF 的结论一致，含 must-have/require 缺失项。
2. 首次分析失败、重算失败后都能明确展示错误并恢复，不能生成假成功报告。
3. 音频、纯图片、混合输入各有正常/部分失败/无内容/删除/ZIP 验收。
4. 真实库并发验证：配置、上传消费、角色映射、最后管理员、清理作业代际。
5. 抹除后旧会话不可继续写入，在途工作者不能重新落库敏感内容。
