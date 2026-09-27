# 服务端审查修复：第一批

## 范围与边界

本批针对 `server-review-followup.md` 中已用隔离探针复现的五类问题：非法角色确认生成空报告、报告发布后任务 CAS 失败、混合任务空音频吞掉图片、首次失败报告被当作成功、通话级违规未展示。不是 R01—R24 全部关闭。

没有连接真实业务数据库、调用计费云服务或执行全量前端类型检查。没有真实 MySQL 并发、微信真机或云端验收证据。

## 修改

- `RecognitionTaskMapper.lockById`：提供事务内任务行锁。
- `ReportAnalysisService`：锁定任务后校验删除标记、快照版本、合法状态及非空文本；进入分析和完成状态均需 CAS 成功，否则抛出异常，由发布事务回滚报告及证据写入。
- `ReportService.saveMapping`：事务内锁任务；仅允许待确认或已完成任务；请求说话人必须与真实音频说话人集合完全一致；全部角色预校验后再写入。映射与报告发布整体提交或整体回滚。分析按指定映射 revision 取角色。图片文本的空说话人不再传入不接受 null key 的不可变 Map。
- **失败语义变化**：角色重算失败会直接返回错误，映射与报告均回滚，不再先提交映射、另存 FAILED 版本冒充本次操作已完成。旧成功版本保留。历史 FAILED 版本仍可能存在，展示和导出必须继续防守。
- `TaskPipelineService`：混合任务空音频后继续 OCR；只要图片有有效文本就可发布。自动分析失败回到可重领的 PARSING 路径，不在发布事务外提前进入 ANALYZING。完成 CAS 失败不再静默忽略。
- `ExportService`：创建、生成和下载均要求 PUBLISHED；签发下载前检查任务未删除。这不是对导出全部竞态的修复。
- `diting-app/src/utils/reportPresentation.ts` 与 `pages/report/detail.vue`：成功状态而非版本 ID 决定正式报告展示/导出；通话级违规独立展示说明，不伪造首句证据；旧成功版本与最新失败/分析中状态分开说明。隐藏无真实说话人或非法状态下的角色操作；纯图片可在有可用原件时发起 ZIP。

## 回归与已知测试修正

新增/扩充：`ReportAnalysisServiceTest`、`ReportServiceTest`、`TaskPipelineImageTest`、`TaskPipelineServiceTest`、`ExportPublicationGuardTest`，以及 `diting-app/tests/reportPresentation.test.cjs`。

既有回归阻塞修正：

- `AuthEndpointSecurityTest` 补充控制器新增依赖 AgreementService 的切片 mock；没有修改生产鉴权逻辑。
- `TaskPipelineServiceTest` 对齐“仍查询队列但跳过未配置音频任务”的语义。
- `RuleServiceTest` 验证相同规则内容复用快照、变更内容推进版本且旧定义不变；修正原本无条件返回最新 RuleSet 的 mock，未修改规则生产逻辑。

首轮后端 5 类测试：53 项，51 通过、2 个测试桩配置错误；异常发生在 Mockito 覆盖已有 answer 时，尚未到业务断言。已改用 doReturn，随后扩大到显式限定 28 类测试并通过。首轮日志：`.pi/tasks/01a0dd82-07a0-730f-ad96-71f9142243cd-36885/b36c5a627.output`。

前端运行命令：`cd diting-app && node --test tests/reportPresentation.test.cjs`。覆盖失败首版、通话级缺失、旧版回退、未发布不可导出、正常无命中限定性表述、识别失败以及当前 Vue SFC 脚本/模板编译与绑定检查；不等同于浏览器或微信端交互验收。

前端定向回归已完成：8 项全部通过，0 失败、0 跳过；日志 `.pi/tasks/01a0dd82-07a0-730f-ad96-71f9142243cd-36885/b065b94f8.output`。后端显式限定 28 类回归已完成：220 项全部通过，0 失败、0 错误、0 跳过，BUILD SUCCESS；包含新增图片报告空说话人读取测试。日志 `.pi/tasks/01a0dd82-07a0-730f-ad96-71f9142243cd-36885/b3212dc08.output`。没有执行未限定的 `mvn test`。

最终定向 LSP 检查 7 个生产文件：返回 0 个错误诊断，但仅 1 个确认 clean，6 个未确认（5 个超时、1 个服务端未确认）。不能将其表述为全部通过；Java 编译证据来自上述 Maven 回归，前端脚本/模板编译证据来自定向测试。

## 尚未关闭

资产音频授权、OCR RUNNING 崩溃恢复、导出领取/恢复/删除竞态、已在途句子删除后重写、历史报告展示角色漂移、额度原子性、上传会话/原件冻结、统计口径、账号抹除后令牌、配置 CAS、最后管理员并发和清理作业 fencing 等仍需后续批次。不要把本批单元回归通过解释为系统可上线。

行锁与 Spring 事务控制的测试使用 mock，并包含代理事务回滚调用验证；没有证明真实数据库多事务交错下的行为。旧数据（如已卡死 ANALYZING、历史失败版本或不一致映射）没有自动迁移/修复。
