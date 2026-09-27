# S16 图片上传与 OCR 判读 · 记录

版本：v1.0 · 日期：2026-09-24 · 阶段：S16（需求补充轮）
规格依据：用户 2026-09-24 需求（PDF 标红/页脚 + 图片上传）；[开发文档](./开发文档.md)、[开发计划](./开发计划.md)。

**结论摘要**：PDF 的两项要求已落地并验证（标红、页脚免责声明）。图片上传按用户确认的口径实施：
**图片先 OCR 出文字，再按违规规则判读**；每次上传算一份额度；记录页与后台都能看，导出时一起打包。
数据模型复用录音那套（同一张任务/句子/报告链路），避免出现两套报告与导出逻辑。

---

## 1. 需求确认（用户回答，2026-09-24）

| 问题 | 用户口径 | 落地方式 |
|---|---|---|
| 图片与录音的关系 | **图片需要 OCR 解析出文字，再按违规规则判定** | 图片与音频都进同一条任务的 `sentences`，走同一套规则引擎与报告版本 |
| 额度 | 每次上传算一份额度（含只传图片） | 沿用「提交登记 1 份」的既有口径，图片提交同样占 1 份 |
| 展示与导出 | 小程序记录页与后台都能看；导出 ZIP 一起打包（PDF 附图片清单） | 新增资产查询接口 + 报告 DTO 带图片元数据；ZIP 打包图片；PDF 增「图片清单」小节 |
| PDF 免责声明位置 | 保持文档末尾 | 已实现（正文后留一空行） |

## 2. 设计决策

1. **一条提交 = 一条记录**，可含「音频 ≤1 + 图片 ≤6」；`media_kind` 取 `AUDIO/IMAGE/MIXED`。
2. **复用既有链路**：OCR 出的文字按「文字块」写成 `sentences`（`sequence` 在任务内唯一，
   `source_asset_id` 指向来源图片），因此规则引擎、报告版本、高亮、PDF/ZIP、保留期与删除传播全部复用。
3. **纯图片记录跳过 `WAITING_ROLE`**：图片没有说话人，角色确认无意义；
   按开发文档 §5.2「无需角色规则或角色已确认可跳过 WAITING_ROLE」直接 `PARSING → ANALYZING → 发布 → COMPLETED`，
   角色依赖规则按既有口径跳过并在报告里说明。
4. **混合记录（音频+图片）**：先转写音频并落库（`source_asset_id IS NULL`），再 OCR 图片**追加**句子
   （不覆盖已有句子，序号续排），最后进 `WAITING_ROLE` 等用户确认角色。
5. **图片与音频同源不同限**：图片单张 ≤10MB（OCR 官方上限）、单任务 ≤6 张、格式按 OCR 支持范围
   （PNG/JPG/JPEG/BMP/GIF/TIFF/WebP）；音频仍按既有大小与格式白名单。
6. **资产与隐私**：图片是用户内容，随记录一起到期（30 天，从上传时间起算）、一起被删除/抹除；
   对象 key 服务端生成、不返回给客户端；回显走短期预签名。

## 3. 阿里云 OCR 官方契约核对（初版核验：2026-09-24；**2026-09-27 迁到统一识别**）

**当前默认**：`RecognizeAllText`（[官方文档](https://help.aliyun.com/zh/ocr/developer-reference/api-ocr-api-2021-07-07-recognizealltext)，
统一识别，开通项 `ocr_unity_public_cn`），`Type=General`（通用文字识别基础版，官方**必填单选**）。
旧接口 `RecognizeGeneral` 保留一键回滚开关（`diting.aliyun.ocr.api=general`）；
两条路径解析出的「行」必须一致（`OcrParserTest` 锁住），换接口不改变判读口径。

| 项 | 官方取值（AllText + Type=General） | 与项目的关系 |
|---|---|---|
| 入参 | `Url` 或 `body` 二选一；`Type` **必填且单选** | 固定走 **body（二进制）**：图片在私有 OSS 里，交云端拉 URL 会受桶策略限制；`Type` 固定 `General` |
| 可选输出 | `OutputFigure/Qrcode/BarCode/Stamp/Coordinate/OutputOricoord/…` | **一个都不开**：官方参数表里 General 不支持 `OutputCoordinate` 与 `AdvancedConfig`（没坐标、没成行/分段/表格），其余增强项对催收截图无意义且增加响应时间 |
| 图片格式 | PNG/JPG/JPEG/BMP/GIF/TIFF/WebP**/PDF** | 白名单仍是图片那七类，**不收 PDF**（前端没有 PDF 选择与预览，导出/保留期/清理也没按 PDF 做过） |
| 图片大小 | 二进制 ≤10MB | 一致（单张 10MB、单任务 ≤6 张） |
| 尺寸 | 长宽 >15 且 <8192 像素，长宽比 <50 | 上传校验与调用前各预检一次（`ImageDimensionProbe`，阈值可配 `diting.media.min-image-pixels / max-image-pixels / max-image-aspect-ratio`） |
| 返回 | `Data` 是**对象**：`Content` 全文 + `Height/Width/SubImageCount/SubImages[].BlockInfo.BlockDetails[]{BlockId, BlockContent, BlockAngle, BlockConfidence}` | 行来自 `Content` 的换行；无换行时退回按 `BlockId` 顺序切文字块（General 不给坐标，所以不猜坐标）；SDK 已有完整类型化模型 |
| 授权 | RAM 策略 `AliyunOCRFullAccess`；需先开通「**OCR 统一识别**」 | 与听悟/OSS 同一 AccessKey；**上线前需确认已开通且有免费额度或资源包** |

> 旧接口 `RecognizeGeneral`（回滚开关）：`Data` 是**内嵌 JSON 的字符串**，含 `content` + `prism_wordsInfo[]`
> 词块与坐标；按坐标（先 y 后 x）排序归并行。保留该路径只为回滚，不再作为默认。

## 4. 已完成

- **PDF 标红与页脚**（本轮前半段，已验收）：
  - 命中处按报告版本的 UTF-16 区间**红色**绘制，区间重叠取并集、越界不越界切片；
  - 去掉 `#TITLE#`/`#SECTION#` 内部标记外泄（改为显式行样式枚举）；
  - 页脚按需求文案：`本服务仅记录通话内容，不构成法律意见，` / `不作为处理结果承诺。`
    （其后保留「所引规范条文请以现行有效版本为准。」）；
  - 证据：`docs/evidence/S16/pdf-sample-*.pdf|png`，单测 `PdfBoxReportRendererTest`（8）+ `ExportHitSpansTest`（5）。
- **V4 迁移脚本**：`V4__S16_media_assets.sql` + 回滚脚本（未执行，见 §6）。
- **后端与前端实现**（见 §5）：编译通过；**限定单测 204 项全绿**（含本阶段新增的 OCR 解析 5 项、
  图片嗅探 4 项、管线图片分支 7 项）；小程序与后台 SFC 绑定检查 28 个文件 0 问题（阳性对照仍能抓到缺陷）。
  证据：`docs/evidence/S16/java-tests-*.log`、`docs/evidence/S16/sfc-binding-*.log`。

## 5. 本轮已实施（后端 + 前端，全部离线可验）

| 层 | 内容 | 位置 |
|---|---|---|
| 数据 | `task_assets` 资产表、媒体类型字段、`oss_object` 可空、句子来源资产 | `V4__S16_media_assets.sql`（未执行） |
| 上传 | 会话按扩展名分型（音频/图片双白名单 + 图片 10MB），complete 用文件头复核 | `UploadSessionService` |
| 提交 | `uploadIds`（≤1 音频 + ≤6 图片）一次事务消费会话并写资产；额度仍每次 1 份 | `RecognitionTaskService.submit` |
| OCR | `OcrApi` 端口（aliyun / stub / not-configured）+ `OcrService` 预检 + `OcrParser` 阅读顺序解析 | `service/ocr/*`、`OcrService` |
| 管线 | 图片任务 `QUEUED → SUBMITTING（登记）→ TRANSCRIBING（OCR）→ PARSING → ANALYZING → COMPLETED`；混合记录在音频解析后**追加**图片句子再等角色确认；OCR 未配置时静默等待、失败按次数上限重试 | `TaskPipelineService` |
| 回显 | `GET /api/task/{id}/assets`（仅本人）与 `GET /api/admin/recordings/{id}/assets`（ADMIN/OPERATOR）；短期预签名 + 每次签发留痕；已清理/过期不给地址 | `AssetController`、`TaskAssetService` |
| 导出 | ZIP 含 `report.pdf` + `audio.*` + `images/<序号>-<文件名>`；PDF 增加「图片清单」小节（序号/文件名/格式/大小/识别情况） | `ExportService` |
| 保留与删除 | 到期清理覆盖图片对象（查询按「还有对象没清理」而不是音频标志）；单条删除/抹除传播到资产对象与资产行 | `RetentionService` |
| 小程序 | 上传页支持「录音 ≤1 + 图片 ≤6」任意组合，逐张上传后一次提交；记录页图片区（点开展开预览，已清理显示占位）；错误文案与额度预检沿用 | `pages/upload`、`pages/report/detail`、`utils/choose.ts` |
| 后台 | 录音详情展示图片清单（识别情况 + 短期链接） | `views/recordings` |

## 6. 待实施 / 待验证

1. **执行 V4 迁移**（阻塞：需要库密码）——先备份真实库，再按脚本头执行；本会话无法执行，因此下列行为**未经真实库验证**：
   资产表读写、纯图片任务的完整落库链路、到期清理与删除传播。
2. **端到端脚本**：S16 的 E2E 尚未编写；等库凭据可用时补 `run_s16_image_e2e.sh`（上传图片 → OCR → 判读 → 导出 ZIP 含图片）。
3. **真机验证**：`uni.chooseImage` 多选、图片预览、ZIP 保存/转发在真机上的表现（属 S09 手验清单扩展）。
4. **可选增强**：① 句子标注来源图片（「图 2」前缀）；② HEIC 在前端转换后再上传（当前按不支持处理，给出明确提示）；
   ③ 图片缩略图走对象存储的处理参数（当前直接给原图短期地址）。

## 6. 阻塞与已知约束

- **迁移未执行**：本会话没有 MySQL 密码（按密码纪律只走环境变量），无法应用 V4 或做迁移冒烟；
  执行方式见脚本头（先备份真实库，再 `mysql -D <db> < V4__S16_media_assets.sql`）。回滚脚本已具备护栏。
- **OCR 未联调**：需先开通「**OCR 统一识别**」（`RecognizeAllText` 的开通项，与旧的「通用文字识别」不同；
  `api=general` 回退时才需要后者）+ RAM 授权 `AliyunOCRFullAccess` + 计费确认；无凭据时按项目纪律返回 503，
  不伪造识别结果。**2026-09-27 已补强失败可观测性**：云端错误码（`ocrServiceNotOpen`/`noPermission` 等）
  现在会落到 `task_assets.ocr_error` 并在后台图片清单展示，联调时不必再猜「没开通」还是「图坏了」（见 §7）。
- **HEIC**：iPhone 默认拍照格式不在 OCR 支持范围，需前端在选择时转换或提示；
  本记录先按「只接受白名单格式」实现。
- **E2E 未跑**：S16 的端到端脚本需要专用测试库凭据，本会话无法执行；结论不冒充已通过。

---

## 7. 官方契约补强（2026-09-27，按官方 API 文档与错误码表逐条核对后修）

对照 [RecognizeGeneral](https://help.aliyun.com/zh/ocr/developer-reference/api-ocr-api-2021-07-07-recognizegeneral)
与[错误码表](https://help.aliyun.com/zh/ocr/developer-reference/api-ocr-api-2021-07-07-errorcodes)核对实现，
接口/字段层面全部对齐（SDK `ocr_api20210707:3.1.3` 的 `setBody(InputStream)` 与 `getBody().getData()`
用 `javap` 逐签名核过）。本轮修三处**文档要求但实现没跟上**的地方：

1. **尺寸硬约束不做预检（已修）**：官方要求长宽大于 15 像素、小于 8192 像素、长宽比小于 50，
   而此前只查大小与格式。超限的图云端只回 `illegalImageSize`——一次必然失败的付费调用，还会被重试。
   新增 `ImageDimensionProbe`（只读文件头/段头，支持 PNG/GIF/BMP/JPEG/WebP 三种块/TIFF，
   **探测不出就返回 null 不拦**），在 `UploadSessionService.complete`（上传校验）与 `OcrService.recognize`
   （调用云端前，用完整字节）各查一次；阈值可配 `diting.media.min-image-pixels / max-image-pixels /
   max-image-aspect-ratio`。注意错误码表里 `illegalImageSize` 写的下界是 5 像素，接口文档写 15，取更严的 15。
2. **云端错误码被吞掉（已修）**：以前 `Data` 为空就抛 `BizException(INTERNAL_ERROR)`，管线落库写的是常量
   `INTERNAL_ERROR`，而 `ocr_error` 列在三端界面都没展示——线上分不清「服务没开通」还是「图坏了」。
   新增 `OcrApiException`（带官方错误码 + 是否值得重试），`AliyunOcrApi` 同时处理「响应体带 `Code`」与
   「SDK 抛 `TeaException`」两条失败路径；管线据此分流：**可重试**（`Throttling.*`、`algorithmError`、
   `ServiceUnavailable`、超时、网络中断、未知码）记码后重试，**不可重试**（`noPermission`、
   `ocrServiceNotOpen`、`OcrServiceExpired`、`unsupportedImageFormat`、`illegalImageSize`、
   `exceededImageContent` 等）当次置 `EXHAUSTED`（新增 `TaskOcrWriter.abandon`，仍走租约令牌 CAS），
   任务判 `FAILED` 且 `errorCode=OCR_REJECTED`；本地预检拒绝（`UNPROCESSABLE`/`FORBIDDEN`/`BAD_REQUEST`）同样当次判死。
   错误码经 `GET /api/.../assets` 的 `ocrError` 字段下发，后台录音详情图片清单展示（未映射的错误码原样显示，
   不假装认识）；`OCR_STATUS_TEXT` 补上了此前会露英文码的 `EXHAUSTED`。
3. **耗时无留痕（已加）**：每张图识别完成打一条 info 日志（行数 + 毫秒），用于判断是否需要按官方建议
   对 >1.5MB 的图改用 `Url` 方式（当前固定走 `body`，因为图片在私有 OSS 里）。

**仍未做/未定论**：

- **真实联调**：一次都没跑过（无凭据）。上面三条都是「代码 ↔ 文档」对照的结论，不是实测。
- **旋转/竖排文字的阅读顺序（推断，未定论）**：官方 `prism_wordsInfo[].angle`（示例里就有 `-88`）与
  `direction` 我们仍未读取，`OcrParser.groupLines` 只按 `y` 主排序。整页旋转或竖排的图可能行归并错位，
  但**没有真实样本可证伪**，因此按纪律不改排序口径——等首次真实识别时特意传一张横拍图看结果再定。
- **计费对账**：官方明确有免费额度 + 需购资源包，上线清单要求首日对账，未做。

---

## 8. 迁到统一识别 RecognizeAllText（2026-09-27，按用户指定的官方文档）

- **接口**：默认 `RecognizeAllText`（`Type=General`，`diting.aliyun.ocr.image-type`），
  `diting.aliyun.ocr.api=alltext|general` 一键回滚到旧接口。选择 General 的理由：与旧接口同为「基础版」
  识别能力，但接口统一、结构更规整（带 BlockId/置信度）；**效果上不要期待比 RecognizeGeneral 更好**——
  想要更高识别率得换 `Type=Advanced` 高精版，那要同时核对官方参数表与计费。
- **解析**：`OcrParser` 两套结构都认（字段名大小写不敏感：官方报文 PascalCase、SDK 模型 camelCase），
  出口不变——「按阅读顺序排好的行」；中文之间的排版空格去掉（与旧接口词块拼接口径一致），英文单词间保留。
  General 无坐标，所以阅读顺序只看官方换行与 `BlockId`，不再做坐标归并。
- **顺带修掉的真实缺陷（已用代码执行证实 + 加了回归测试）**：`task_assets.ocr_text` 存的是**行文本**，
  而 `TaskTextWriter.rebuildImages` 曾把它当 `RecognizeGeneral` 的 `Data` JSON 二次解析 →
  `OcrParseException`，真实链路上图片句子永远重建不出来（任务在 `TRANSCRIBING/PARSING` 间反复失败），
  单测因 `selectByTask → List.of()` 的空桩而掩盖。现在两边统一为「`'\n'` 拼接的行文本」，
  旧数据（同样是行文本）无需迁移即可被正确切行；已加「落库口径闭环」回归测试并用变异验证能抓到旧行为。
- **未做**：真实云联调（无凭据）、PDF、`Advanced` 的坐标/成行/表格（General 用不上）。
