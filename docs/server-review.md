# Server 代码审查与功能完成度复核

后续深入审查见 [server-review-followup.md](./server-review-followup.md)：新增 R13—R24，覆盖结果误展示、并发约束、抹除后会话、在途发布和图片/重试闭环。

## 结论与边界

**主要业务模块已有实现，但尚不能认定“全部功能闭环”或“可上线”。** 当前更接近功能齐备、仍需可靠性与安全收尾的版本，不是仅补云凭据即可交付。

本轮以当前源码为准，对照 `开发文档.md` 的 F01—F12，并补看图片 OCR、后台账号软删除、协议管理。既有 S14/S15 文档只作背景，不把历史测试记录当成本轮验证结果。

- 检查范围：服务端控制器权限、上传/额度、任务管线、角色/报告、规则版本、导出、删除/保留期、统计、协议注册；抽查小程序报告/导出 API 契约。
- 没有修改业务代码，没有连接真实业务库，没有调用计费云 API，没有做浏览器或微信真机验收。
- 以下问题是源码路径确认，不声称已在生产复现；并发、故障恢复与云行为需要专项集成测试。
- 没有对前端全部页面逐一验收，不能把本报告当成三端全量验收。

路径简写：下文 Java 路径均相对于 `diting-server/src/main/java/com/diting/`。

## 优先修复的问题

### R01 · P1：OPERATOR 可绕过录音原件权限

位置：`controller/AssetController.java:45-49`、`service/TaskAssetService.java:56-66`；对照 `controller/AdminRecordingController.java:142-143`。

`/api/admin/recordings/{id}/audio` 限 ADMIN，但 `/api/admin/recordings/{id}/assets` 允许 OPERATOR。资产服务遍历所有资产并签发 GET URL，没有区分 AUDIO/IMAGE，也没有接收操作者角色。

因此含音频资产的任务，OPERATOR 可以通过资产列表取得原始录音地址。即使允许操作员查看图片，也不应顺带开放音频。

建议：按角色和资产类型过滤签发；补同一录音在 `/audio` 与 `/assets` 两条路径的权限矩阵回归。

### R02 · P1：OCR 的 RUNNING 状态无法在失败或重启后恢复

位置：`service/TaskPipelineService.java:192-225`、`mapper/TaskAssetMapper.java:17-38`。

先将资产置 RUNNING，再执行 `objectStorage.read()`，而读取位于局部 try/catch 之外。OSS 读取异常或进程中断后，资产保留 RUNNING。后续查询会再次取到该资产，但领取 SQL 只接受 PENDING/FAILED，永远无法重新领取。

结果：任务持续重试，图片永远识别不完；任务级租约到期也救不回资产级状态。

建议：为资产增加租约/领取令牌与过期恢复；把读取异常纳入失败处理，避免无条件重做不确定的计费请求。

### R03 · P1：图片报告发布失败后永久停在 ANALYZING

位置：`service/TaskPipelineService.java:237-254`、`mapper/RecognitionTaskMapper.java:23`、`common/TaskStatus.java:66-68`。

图片收尾先在报告事务外执行 `markAnalyzing()`，再调用报告发布。发布异常后写回 ANALYZING 并标注“稍后重试”，但队列 SQL 与 `needsWorker()` 都排除了 ANALYZING。

结果：一次临时数据库/规则快照异常就能让图片任务永久处理中，重启也不会继续。

建议：增加可恢复的分析作业，或将状态变更与发布原子化并保留真正可领取的重试状态。

### R04 · P1：任务的有限重试、未配置提示与对账未闭环

位置：`service/TaskPipelineService.java:103-111,305-339,367-370`。

- 未配置听悟/OCR 时直接跳过，但提交路径仍可登记任务并预占额度；还可能由队列前部的不可处理任务占满批次，阻挡后方可处理任务。
- `maxAttempts` 只在 `reconcileSubmit()` 检查，OCR、解析与远端查询错误可以无限重试；OCR 重复调用可能持续计费。
- 明确未受理的 BizException 仍写 SUBMITTING，下一轮只走“等待对账”，不会按注释退回队列重试。
- `reconcileSubmit()` 本身不做远端对账；没有远端任务号时只等待再失败。也未发现正常后台人工核销/退款接口。

建议：区分不可用、确定未受理、受理不确定和处理失败；按阶段计数、有限退避、终止告警；提供审计化人工恢复入口。

### R05 · P1：导出没有可靠领取与中断恢复

位置：`service/export/ExportService.java:100-105,157-176`、`config/ExportJobScheduler.java:25-38`。

领取采用先查 QUEUED 再 `updateById(RUNNING)`，不是条件 CAS。多实例可同时生成。上传发生在局部异常处理之外，一旦上传失败、完成写库失败或进程退出，作业留在 RUNNING，而调度器只扫描 QUEUED。外层调度只是记日志，没有重新排期。

结果：正常用户的一次导出可能永久卡住；注释中“失败由上层重试”并未实现。

建议：租约 + 条件领取 + 次数/退避 + 中断恢复；对象上传与数据库发布之间建立可追踪补偿。

### R06 · P1：删除墓碑没有覆盖已有导出下载与在途发布

位置：`service/export/ExportService.java:163-176,184-220`、`service/lifecycle/RetentionService.java:150-188`。

已有导出下载仅校验作业归属、到期时间和完成状态，没有回查所属任务的 `deleted_at`。任务写墓碑到异步清理完成之间，仍可签发新的下载链接。

导出生成仅在领取时检查墓碑；渲染/上传期间发生删除，`completeWithArtifact()` 不复查。若清理已删除导出行，完成方法直接返回，已上传的对象没有数据库引用，常规产物过期扫描也无法发现。

建议：下载和发布都检查墓碑；以条件发布/锁或代际版本协调删除；发布失效时登记对象删除补偿。已对外签发的旧 URL 不承诺立即撤销，但不应继续签发新 URL。

### R07 · P1：历史报告使用最新角色映射，版本不再固定

位置：`service/report/ReportService.java:226-232,307`。

`detailOf(task, version)` 用指定版本的违规证据和分数，却用 `currentRoles(taskId)` 获取最新角色，而不是 `version.mappingRevision`。导出同样复用该方法。

复现场景：v1 时 speaker 1 为 AGENT；对调角色生成 v2；再读/导出 v1，角色显示已变成 v2 的映射，证据和分数仍来自 v1。

建议：所有历史详情与导出按报告记录的 mappingRevision 取映射；增加“对调后读取旧版”的测试。

### R08 · P1：关键批量写入的事务因同类自调用而失效

位置：`service/report/ReportService.java:74,105-132`、`service/TaskPipelineService.java:259-295,374,405-430`。

`saveMapping()` 直接调用本类 `@Transactional persistMapping()`；任务调度也直接调用本类的 `persistSentences()` / `refreshAssetSentences()`。这些入口没有外层事务，Spring 代理无法拦截同类自调用。

具体影响：
- 映射边校验边写：第一条合法、第二条非法时，第一条已提交；请求返回失败，映射版本却可能已经前进。
- 转写/OCR 句子先删后插，途中异常会留下半份或空文本。
- 并发角色确认只有先读版本，没有整体 CAS/锁；唯一键冲突未统一转换为 409，部分情况下会产生混合映射。

建议：独立事务写入服务；先完整校验再写入；对任务/映射 revision 做原子并发控制。不能只验证注解存在。

### R09 · P1：额度结算与补偿不完整

位置：`service/QuotaService.java:85-117`、`service/TaskPipelineService.java:316-322`。

- consume/release 各自更新流水和日额度两张表，却没有自身事务；当前 consume 的调用入口也没有事务。中间异常会出现“流水已消费、日额度仍预占”，重试又因流水 CAS 失败无法修复。
- 全部生产源码仅音频提交成功处调用 `quotaService.consume()`，纯图片任务成功也没有结算，长期显示 RESERVED。
- 全部生产源码未发现 `quotaService.release()` 调用；明确未受理、未提交即删除等情况没有额度补偿闭环。
- 远端任务号写入和额度消费也是分离的；两者之间失败需要恢复扫描。

建议：账本/计数事务化；定义图片计费结算点；建立明确未受理退还与异常账目对账。

### R10 · P1：已验证上传仍能被原 PUT 签名覆盖

位置：`service/UploadSessionService.java:129-210`、`service/storage/AliyunOssObjectStorage.java:41-54`。

禁止 VERIFIED 刷新签名与提交前 ETag 比对是有效防护，但最初的 PUT 签名在有效期内仍可复用。提交完成后、worker 读取或云端拉取前，同一个 objectKey 仍可被覆盖。签名实现没有禁止覆盖约束，也没有将验证后的对象转入不可写的正式 key。

结果：后续处理的内容可能不是 complete 验过的字节；格式/大小校验和证据不可变性不成立。另录音真实时长仍仅在付费转写后回填告警，不能强制执行提交前时长上限。

建议：验证后生成不可变对象或绑定对象版本；禁止覆盖必须作为签名约束，不只靠客户端承诺。真实 OSS 行为需专项验证。

### R11 · P2：统计虽然接真，但分子分母与“首个报告”口径不一致

位置：`mapper/StatsMapper.java:24-28,71-75,214-224`、`service/stats/StatsService.java:125-164`。

- publishedReports 按 task 去重，riskyReports 按报告版本计数；一条任务有三个风险版本即可出现 300% 风险占比。
- “首个发布”先筛时间窗口再 MIN，同一任务在不同窗口重算时会被再次计入新增有效报告。
- 上传率分母是区间新增注册人数，分子是区间所有上传用户，未限定同一注册 cohort；漏斗文案“逐环是上一步子集”不成立。

建议：先确定 cohort/首版/末版/事件量，再统一 SQL。当前 StatsService mock 单测不能证明 SQL 的实际聚合口径正确。

### R12 · P1/P2：协议同意和版本审计没有覆盖所有注册路径

位置：`controller/AuthController.java:37-50`、`service/AuthService.java:41-53,74-80`、`service/AgreementService.java:64-80,102-112`。

- 微信首次登录直接建号，不校验四份协议同意，也不写 consent；密码注册才有该保护（P1）。
- 注册与四条 consent 写入没有整体事务；写 consent 失败会留下已创建但审计不完整的账号（P1）。
- 校验版本与写 consent 分两次读取当前协议，更新并发下可能记录用户未实际看到的版本（P2）。
- 协议编辑原地覆盖正文，只存当前版本；consent 仅存 key/version，不能还原历史版本正文（P2）。

建议：所有新用户入口统一同意流程；注册与同意快照原子落库；维护不可变协议版本正文。

## 其他需要收尾的点

- `RetentionService.cleanupTask()` 未清理 speaker_mappings，也没有把任务墓碑从 DELETING 推进到 DELETED；完整抹除范围需要明确。
- `cleanupUser()` 在执行时重新扫描所有用户任务，不只处理请求时已写墓碑的任务；用户在异步等待期间新上传，可能清理到未标记删除的新任务。应冻结抹除范围或暂停账号写入。
- 听悟回调使用自定义 HMAC 请求头协议，不能因为配置 secret 就认定与真实云回调兼容；仍需官方契约及实际请求验证。
- `AgreementService.sanitize()` 是正则黑名单而非声明的标签白名单；例如未加引号的 javascript 链接未覆盖。富文本建议采用维护中的 HTML sanitizer，并测实际渲染端。
- 登录/回调限流信任客户端 X-Forwarded-For，需由可信代理覆盖并限定可信代理来源，否则直接访问服务时可伪造 IP 绕过计数。
- PDF 字体配置是 macOS 本机绝对路径；生产需打包授权字体并执行中文导出回归。

## 功能完成度矩阵

“基本实现”只指有相应代码路径，不代表真实云端/真机验收完成。

| 功能 | 当前判断 | 主要缺口 |
|---|---|---|
| F01 用户身份 | 基本实现 | 微信真实登录待验；协议绕过/注册原子性 |
| F02 上传与额度 | 主路径实现，未闭环 | 上传不可变性、真实时长、额度结算/补偿 |
| F03 持久转写 | 主路径实现，可靠性不足 | 有限重试、对账、未配置失败提示 |
| F04 角色确认 | 已实现但一致性不足 | 映射事务/并发、历史版本读取 |
| F05 版本化规则库 | 基本实现 | 并发版本冲突处理与真实 DB 验证仍需补 |
| F06 规则判读与报告 | 基本实现 | 历史角色混用、异常发布恢复 |
| F07 PDF/ZIP | 有实现，未达到可靠可用 | 导出中断恢复、删除竞态、生产字体/真机 |
| F08 历史/删除/到期 | 基本实现，删除闭环不完整 | 新链接撤销、在途写入、抹除范围 |
| F09 后台身份与审计 | 基本实现，有权限缺口 | OPERATOR 通过资产接口取得音频 |
| F10 录音/图片资产 | 基本实现 | 按媒体类型隔离授权 |
| F11 策略配置 | 配置管理基本实现 | 最长时长未在付费前强制；额度补偿 |
| F12 真实统计 | 已接数据库，不能全部采信 | 比率、cohort、首版统计口径 |
| 图片 OCR/混合任务 | 正常路径实现 | RUNNING 恢复、分析卡死、次数上限、额度结算 |
| 后台账号管理/软删除 | 基本实现 | 真实角色矩阵与前端联调待验 |
| 协议管理与注册同意 | 已实现但审计链不完整 | 全入口覆盖、原子性、历史正文快照 |

财务收入、真实准确率、渠道归因等没有事实来源的指标应继续标记待采集；付费订阅、应用内录音和大模型判读在原规格中明确不属于本期，不应计为缺失功能。

## 本轮验证

已执行离线限定 Maven 回归，显式指定 27 个测试类，覆盖鉴权、账号、协议、上传、额度、管线、报告、规则、统计、PDF、OCR 与听悟适配；没有运行未限定的 `mvn test`。

首次启动因 PATH 中没有 `mvn` 退出 127，未执行测试。随后按项目记录改用 `JAVA_HOME=/opt/homebrew/opt/openjdk@21` 和 `~/.m2/wrapper/dists/apache-maven-3.9.16/56ba1f9f/bin/mvn` 重跑。

**2026-09-26 本轮结果：199 项，190 通过、2 个断言失败、7 个执行错误、0 跳过；BUILD FAILURE。**

| 失败位置 | 原因与判断 |
|---|---|
| `AuthEndpointSecurityTest`：7 个错误 | 新增 AuthController 的 AgreementService 依赖后，WebMvcTest 没补对应 Bean/mock，导致切片上下文启动失败；这 7 个鉴权用例未能有效执行，不代表生产服务缺少该 Bean |
| `TaskPipelineServiceTest.notConfiguredDoesNothing:254` | 测试仍要求未配置听悟时不查询队列；实现已改成先查询再按音频/图片类型分别判配置。断言与新逻辑不同步，不能据此认定应恢复全局跳过 |
| `RuleServiceTest.snapshotCarriesDefinitions:205` | 测试要求相同规则集合第二次生成 version=2；实现已改为相同内容复用 version=1，旧断言未更新 |

以上失败以测试维护滞后为主，但当前回归确实不是全绿；本轮只审查，未擅自修改测试或业务代码。

证据：`.pi/tasks/01a0dd82-07a0-730f-ad96-71f9142243cd-36885/b668f2466.output`；逐类结果位于 `diting-server/target/surefire-reports/`（后续测试可能覆盖）。

测试通过也不代表上述缺陷已修复：大量测试 mock 掉 Mapper/云服务，不能覆盖 SQL 口径、Spring 事务代理、断点恢复和真实 OSS 签名行为。

云 POC、微信真机、MySQL 故障/并发集成、浏览器全页面验收本轮未执行；现有 S14 门禁记录仍有未完成项。

## 建议收尾顺序

1. 修权限与隐私边界：R01、R06、R10、R12 的注册入口保护。
2. 修可靠性和账务：R02—R05、R08—R09，并补失败/重启/并发测试。
3. 固定报告版本语义与统计口径：R07、R11，完善协议版本快照。
4. 用专用测试库走音频/图片/混合三条完整链路，再做真实 OSS/听悟/OCR 与微信真机门禁。

**不能将剩余工作概括为“只剩配置和上线”。建议按缺陷闭环收尾，而不是继续堆新页面。**
