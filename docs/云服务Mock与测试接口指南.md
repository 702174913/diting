# 云服务（OSS / 听悟 / OCR）Mock 与测试接口指南

日期：2026-09-25 · 依据：S04 / S05 / S16 记录 + 主源码核对（`StubTingwuApi` / `StubOcrApi` / `InMemoryObjectStorage` / `TestPipelineController` / `TestObjectStorageController` / `OcrParser` / `TranscriptionParser`）

---

## 1. 契约核对结论（都对了文档）

| 云服务 | 契约来源 | 核对结论 |
|---|---|---|
| OSS 上传 | S04（开发文档 §5.1/§6.2） | ✅ 服务端签发预签名 + 客户端**原始字节 PUT**（非 multipart）；objectKey 服务端生成 `recordings/{userId}/{uuid}.{ext}`；complete 用 HEAD + 文件头嗅探复核，ETag 存 `content_hash` |
| 听悟 | S05（官方 CreateTask/GetTaskInfo） | ✅ 参数在 `Parameters.Transcription`；响应统一读 **`Data` 层**；只有 `COMPLETED` 才读 `Result.Transcription`；未完成返回 409 PROCESSING；结果下载仅 HTTPS + 私网拒绝 |
| OCR | S16（官方 RecognizeGeneral 2021-07-07） | ✅ 走 **body 二进制**（≤10MB，PNG/JPG/JPEG/BMP/GIF/TIFF/WebP，**不支持 HEIC/PDF**）；`Data` 是**字符串**，内嵌 JSON |

⚠️ 唯一阻塞项：**V4 迁移未执行**（`diting` 库无 `task_assets` 表），图片/OCR 链路落库会失败。执行方式见 §5。

## 2. 三态策略（和文档一致，不伪造成功）

`application.yml` 中 `diting.storage.type` / `diting.aliyun.tingwu.type` / `diting.aliyun.ocr.type`：

| 取值 | 行为 |
|---|---|
| `auto`（默认） | 凭据像真值 → 真实云；否则 `NotConfigured*` → **503 NOT_CONFIGURED** |
| `oss` / `aliyun` | 强制真实云，缺凭据启动报错 |
| `memory` / `stub` | **仅测试**：内存存储 / 确定性桩（启动 WARN） |

## 3. 返回格式（mock 数据模板）

### 3.1 通用响应包装 `R<T>`

```json
{ "code": 0, "message": "ok", "data": { ... } }
```

### 3.2 听悟 CreateTask / GetTaskInfo（`StubTingwuApi` 可编排的形状）

CreateTask 响应：
```json
{ "Code": "0", "Message": "success", "Data": { "TaskId": "tw-stub-task" } }
```

GetTaskInfo 响应（状态：`QUEUEING/ONGOING/COMPLETED/FAILED`；只有 COMPLETED 带 Result）：
```json
{
  "Code": "0", "Message": "success",
  "Data": {
    "TaskId": "tw-task-0001",
    "TaskStatus": "COMPLETED",
    "Result": { "Transcription": "https://.../tw-task-0001.json" }
  }
}
```

转写结果文件（`Result.Transcription` 指向的 JSON，解析器严格校验）：
```json
{
  "Transcription": {
    "Paragraphs": [
      { "ParagraphId": 0, "SpeakerId": "2",
        "Words": [ { "Word": "喂", "Start": 900, "End": 1200, "Punctuation": "，" },
                   { "Word": "你好", "Start": 1200, "End": 1800, "Punctuation": "。" } ] },
      { "ParagraphId": 1, "SpeakerId": "1",
        "Words": [ { "Word": "我今天核实一下你的还款情况。", "Start": 2000, "End": 4200, "Punctuation": "" } ] }
    ]
  }
}
```
解析规则：按 `Start` 时间排序、`sequence` 从 1 连续；缺 `Paragraphs/Words/Start/End`、时间倒挂、`SpeakerId` 非数字 → 422；`Paragraphs` 为空 → 空结果（任务 INVALID）。样本：`src/test/resources/tingwu/transcription-sample.json`。

### 3.3 OCR RecognizeGeneral（`StubOcrApi` 固定返回）

`recognize()` 返回**字符串**，内容为 JSON（与官方 `Data` 同构）：
```json
{
  "content": "我保证能减免利息。\n请你今天把款还上。",
  "prism_wordsInfo": [
    { "word": "我保证能减免利息。", "pos": [{ "x": 20, "y": 30 }], "height": 24, "width": 200 },
    { "word": "请你今天把款还上。", "pos": [{ "x": 20, "y": 70 }], "height": 24, "width": 180 }
  ],
  "prism_wnum": 2, "height": 200, "width": 400
}
```
解析规则：取每个 `word` + `pos[0]` 坐标，按先 y 后 x 排成阅读顺序；缺 `prism_wordsInfo` 或坐标非法 → `OcrParseException`。桩里第一行「我保证能减免利息。」是刻意命中的违规话术，可触发规则判读。

### 3.4 OSS 上传协议（`memory` 存储同构）

```text
POST /api/upload/sign     {filename, consentVersion, declaredSize}
  → {uploadId, objectKey, uploadUrl, method:"PUT", headers, expiresAt, cleanupAfter}
PUT <uploadUrl>           原始字节（headers 严格照用）
POST /api/upload/complete {uploadId}
  → {status:"VERIFIED", sizeBytes, mediaFormat, contentHash, durationMs:null}
POST /api/upload/refresh  {uploadId}   → 新签名（objectKey 不变）
POST /api/task/submit     {uploadId, ...}
```
会话状态机：`SIGNED → VERIFIED → CONSUMED`；归属校验失败一律 404。

## 4. 测试接口（仅 `test` profile 加载，生产不注册）

### 4.1 TestPipelineController（`/api/test/pipeline/*`，需 `tingwu.type=stub`）

| 接口 | 作用 |
|---|---|
| `POST /api/test/pipeline/tick` | 手动推进一轮作业调度（返回 `{progressed:n}`，不依赖定时器） |
| `POST /api/test/pipeline/export-tick` | 手动推进一轮导出作业（`{generated:n}`） |
| `POST /api/test/pipeline/retention-tick` | 手动推进一轮到期清理（`{processed:n}`） |
| `POST /api/test/pipeline/tingwu-script` | **编排桩听悟响应**：`{createTaskId?, status?, transcriptionUrl?}` → 下次 Create/Get 返回该脚本（如 `status:"COMPLETED"` + 结果地址） |

### 4.2 TestObjectStorageController（`/api/test/object-storage/*`，需 `storage.type=memory`）

| 接口 | 作用 |
|---|---|
| `POST /api/test/object-storage/put` | `{uploadId, contentBase64}` → 把字节放进内存存储（模拟客户端 PUT） |
| `GET  /api/test/object-storage/object?key=` | 读回对象（base64），供导出内容断言 |

### 4.3 测试模式启动方式

```bash
cd diting-server
export DITING_DB_PASSWORD='lc110...' DITING_JWT_SECRET="$(openssl rand -hex 32)"
/opt/homebrew/opt/openjdk@21/bin/java -jar target/diting-server-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=test \
  --diting.storage.type=memory \
  --diting.aliyun.tingwu.type=stub \
  --diting.aliyun.ocr.type=stub \
  --server.port=8083
```

离线全链路（无任何云凭据）：`sign → test/put → complete → task/submit → tingwu-script(COMPLETED+结果地址) → tick → tick → 报告/导出`。

## 5. 待办：V4 迁移（图片/OCR 链路的硬前提）

`diting` 库当前缺 `task_assets` 表（S16 记录里也写明「迁移未执行」）。先备份再执行：

```bash
mysqldump -uroot -p diting > diting-backup-$(date +%F).sql
mysql -uroot -p diting < diting-server/src/main/resources/sql/migration/V4__S16_media_assets.sql
```

回滚脚本：`V4__S16_media_assets_rollback.sql`。执行后 S16 的图片上传 → OCR → 判读 → 导出链路才能在真实库上验收。
