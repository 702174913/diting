# diting-app 接口文档

本文整理前端 `src/api` 对接的全部服务端接口，包含请求方式、路径、入参与返回参数。

## 1. 通用约定

### 1.1 基址与请求头

- 基址：`import.meta.env.VITE_BASE_URL`（见 `src/api/request.ts`）。
- 所有业务请求经 `src/api/request.ts` 的 `request()` 封装，底层使用 `uni.request`。
- 请求头：

| Header | 值 | 说明 |
|---|---|---|
| `Content-Type` | `application/json` | 固定 |
| `Authorization` | `Bearer {token}` | 有登录态时携带 |

### 1.2 统一响应信封

服务端所有接口返回如下结构 `Envelope<T>`：

```ts
interface Envelope<T> {
  code: number        // 0 表示成功，其余为业务错误码
  message: string     // 提示信息
  data: T             // 业务数据，成功时才有意义
  symbol?: string | null // 业务标识，如 LOGIN_FAILED / QUOTA_EXCEEDED
}
```

处理规则（`request()`）：

- `code === 0`：`resolve(data)`。
- `code !== 0`：`reject(ApiError(code, message, symbol))`。
- `code === 401` 且使用当前本地 token：清除本地登录态并触发重新登录。
- 失败时 HTTP 状态码与 `code` 一致（`401/403/404/409/422/429/503`）。
- 客户端网络层异常 `code = 0`。

### 1.3 错误类型

```ts
class ApiError extends Error {
  code: number            // 业务错误码 / 0 表示网络层失败
  symbol: string | null   // 业务标识
  message: string
}
```

常见 `symbol` 与用户提示（`src/utils/errors.ts`）：

| symbol | 含义 |
|---|---|
| `QUOTA_EXCEEDED` | 当日免费额度用完 |
| `AUDIO_EXPIRED` | 原始录音已过保留期 |
| `NOT_CONFIGURED` | 服务未配置 |
| `UNAUTHORIZED` | 登录过期 |
| `FORBIDDEN` | 无权限 |
| `CONFLICT` | 状态已变化，需刷新 |
| `PROCESSING` | 处理中，稍后再试 |
| `UNPROCESSABLE` | 文件不可处理 |
| `LOGIN_FAILED` | 登录失败 |

### 1.4 枚举

**TaskStatus（任务状态）**

```
UPLOADED | QUEUED | SUBMITTING | TRANSCRIBING | PARSING |
WAITING_ROLE | ANALYZING | COMPLETED | FAILED | INVALID | DELETING | DELETED
```

**DeletionStatus.status（清理作业状态）**

```
QUEUED | RUNNING | RETRY_WAIT | COMPLETED | FAILED
```

**ExportStatus.status（导出作业状态）**

```
QUEUED | RUNNING | COMPLETED | FAILED
```

---

## 2. 认证 `src/api/auth.ts`

### 2.1 微信登录

- **请求**：`POST /api/auth/wechat`
- **入参（body）**：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `code` | string | 是 | 微信登录 code |
| `agreed` | boolean | 否 | 是否同意协议，默认 `false` |
| `seenVersions` | Record<string, number> | 否 | 已阅读的协议版本快照 |

- **返回**：`LoginResult`

### 2.2 密码登录

- **请求**：`POST /api/auth/login`
- **入参（body）**：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `username` | string | 是 | 用户名 |
| `password` | string | 是 | 密码 |

- **返回**：`LoginResult`

### 2.3 注册账号

- **请求**：`POST /api/auth/register`
- **入参（body）**：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `username` | string | 是 | 用户名 |
| `password` | string | 是 | 密码 |
| `agreed` | boolean | 是 | 是否同意协议 |
| `seenVersions` | Record<string, number> | 是 | 已阅读的协议版本快照 |

- **返回**：`LoginResult`

### 2.4 绑定微信

- **请求**：`POST /api/auth/bind-wechat`
- **入参（body）**：`code`（string，微信 code）
- **请求头**：需携带显式传入的 `token`
- **返回**：`UserInfo`

### 2.5 获取用户信息

- **请求**：`GET /api/auth/userinfo`
- **请求头**：需携带 `token`
- **返回**：`UserInfo`

### 2.6 返回类型

```ts
interface UserInfo {
  id: number
  username: string
  nickname: string
  hasPassword: boolean
  hasWechat: boolean
}

interface LoginResult {
  token: string
  userInfo: UserInfo
  isNewUser: boolean
}
```

---

## 3. 协议 `src/api/agreement.ts`（公开接口）

### 3.1 协议列表

- **请求**：`GET /api/agreements`
- **入参**：无
- **返回**：`AgreementItem[]`

### 3.2 协议版本号快照

- **请求**：`GET /api/agreements/versions`
- **入参**：无
- **返回**：`Record<string, number>`（key → version）

### 3.3 协议正文

- **请求**：`GET /api/agreements/{key}`
- **入参（path）**：`key`（string）
- **返回**：`AgreementDetail`

```ts
interface AgreementItem {
  key: string
  title: string
  version: number
}

interface AgreementDetail extends AgreementItem {
  contentHtml: string
}
```

---

## 4. 上传 `src/api/upload.ts`

上传协议：**申请签名 → 原始字节 PUT 直传 → complete 校验 → 用 uploadId 登记任务**。

### 4.1 额度预检

- **请求**：`GET /api/task/quota`
- **请求头**：`token`
- **返回**：`QuotaSnapshot`

```ts
interface QuotaSnapshot {
  businessDate: string       // 业务日期
  limit: number              // 上限
  reserved: number           // 已预占
  consumed: number           // 已消费
  remaining: number          // 剩余 = limit - reserved - consumed
  maxRecordingMinutes: number
  riskThreshold: number
}
```

### 4.2 申请上传签名

- **请求**：`POST /api/upload/sign`
- **入参（body）**：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `filename` | string | 是 | 文件名 |
| `consentVersion` | string | 是 | 同意条款版本，当前 `v1`（`CONSENT_VERSION`） |
| `declaredSize` | number | 是 | 声明大小（字节） |

- **返回**：`UploadSign`

```ts
interface UploadSign {
  uploadId: number
  objectKey: string
  uploadUrl: string          // 预签名上传地址
  method: string             // 固定 'PUT'
  headers: Record<string, string>
  expiresAt: string
  cleanupAfter: string
}
```

### 4.3 刷新上传签名

- **请求**：`POST /api/upload/refresh`
- **入参（body）**：`uploadId`（number）
- **返回**：`UploadSign`（同一会话，`objectKey` 不变）

### 4.4 上传完成校验

- **请求**：`POST /api/upload/complete`
- **入参（body）**：`uploadId`（number）
- **返回**：`UploadComplete`

```ts
interface UploadComplete {
  uploadId: number
  status: 'SIGNED' | 'VERIFIED' | 'CONSUMED'
  sizeBytes: number | null
  durationMs: number | null       // 上传阶段为 null，由云端回填
  mediaFormat: string | null
  filename: string
  contentHash: string | null
}
```

### 4.5 登记识别任务

- **请求**：`POST /api/task/submit`
- **入参（body）**：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `uploadIds` | number[] | 是 | 音频 ≤1 + 图片 ≤6 |
| `declaredDurationSec` | number \| null | 否 | 客户端确知时长时才传，否则 `null` |
| `idempotencyKey` | string | 否 | 幂等键 |

- **返回**：`{ id: number }`（任务 ID）

### 4.6 原始字节直传（对象存储）

- **请求**：`PUT {sign.uploadUrl}`
- **请求头**：严格使用 `sign.headers`
- **Body**：原始字节（微信小程序走 ArrayBuffer，H5 走 Blob/XHR）
- **说明**：非 multipart，不使用 `uni.uploadFile`；H5 可上报进度。
- **返回**：HTTP `2xx` 表示成功。

**常量**：

- `CONSENT_VERSION = 'v1'`
- `MAX_UPLOAD_BYTES = 50 * 1024 * 1024`（客户端预检上限，服务端为准：`diting.media.max-size-bytes`）

---

## 5. 记录 / 报告 / 导出 / 清理 `src/api/record.ts`

### 5.1 任务分页列表

- **请求**：`GET /api/task/list`
- **入参（query）**：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `page` | number | 否 | 从 1 起，默认 1 |
| `pageSize` | number | 否 | 默认 20 |
| `status` | TaskStatus | 否 | 按状态筛选 |

- **返回**：`Paged<TaskItem>`

### 5.2 任务状态

- **请求**：`GET /api/task/{id}/status`
- **入参（path）**：`id`（number）
- **返回**：`TaskItem`

### 5.3 任务资产

- **请求**：`GET /api/task/{taskId}/assets`
- **入参（path）**：`taskId`（number）
- **返回**：`AssetItem[]`（`url` 为短期地址，过期/清理后为空）

### 5.4 删除单条记录

- **请求**：`DELETE /api/task/{id}`
- **入参（path）**：`id`（number）
- **返回**：`{ deletionJobId: number, status: string }`

### 5.5 报告详情

- **请求**：`GET /api/report/{id}`
- **入参**：

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `id` | path | number | 是 | 报告 ID |
| `version` | query | number | 否 | 指定报告版本 |

- **返回**：`ReportDetail`

### 5.6 创建导出作业

- **请求**：`POST /api/report/{id}/exports`
- **入参**：path `id`（number）；body：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `reportVersionId` | number | 是 | 报告版本 ID |
| `withAudio` | boolean | 否 | 是否含音频，默认 `false` |

- **返回**：`{ exportId: number, status: string, fileType: string }`
- **说明**：异步作业，需轮询 5.7 获取完成状态。

### 5.7 查询导出作业状态

- **请求**：`GET /api/exports/{exportId}`
- **入参（path）**：`exportId`（number）
- **返回**：`ExportStatus`

```ts
interface ExportStatus {
  exportId: number
  status: 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED'
  withAudio: boolean
  fileType: 'pdf' | 'zip'
  errorCode: string | null
  expiresAt: string | null
}
```

### 5.8 签发导出下载地址

- **请求**：`GET /api/exports/{exportId}/download`
- **入参（path）**：`exportId`（number）
- **返回**：

```ts
{
  exportId: number
  fileType: string
  downloadUrl: string
  expiresAt: string | null
}
```

- **说明**：需作业完成后调用，每次调用记一条签发审计。

### 5.9 轮询导出作业（客户端辅助方法）

`waitExport(exportId, token, timeoutMs = 60000)`：每 2s 查询 5.7，直到 `COMPLETED` / `FAILED` 或超时，返回最后一次 `ExportStatus`。

### 5.10 查询清理作业状态

- **请求**：`GET /api/deletions/{deletionJobId}`
- **入参（path）**：`deletionJobId`（number）
- **返回**：`DeletionStatus`

```ts
interface DeletionStatus {
  deletionJobId: number
  targetType: 'TASK' | 'ERASE'
  status: 'QUEUED' | 'RUNNING' | 'RETRY_WAIT' | 'COMPLETED' | 'FAILED'
  attemptCount: number
  pendingObjects: number
  createdAt: string | null
  finishedAt: string | null
}
```

### 5.11 全部抹除

- **请求**：`DELETE /api/privacy/erase`
- **入参**：无（二次确认后调用）
- **返回**：`DeletionStatus`

### 5.12 返回类型

```ts
interface TaskItem {
  id: number
  taskId: string
  status: TaskStatus
  durationSec: number
  createdAt: string
  flaggedCount?: number        // 命中规则句子数，未完成时为空
  sentenceCount?: number       // 转写句子总数
  violationCount?: number      // 最新已发布版本违规总数
  reportPublished?: boolean    // 无已发布版本时为 false
}

interface Paged<T> {
  items: T[]
  total: number
  page: number
  pageSize: number
}

interface AssetItem {
  assetId: number
  kind: 'AUDIO' | 'IMAGE'
  sequence: number
  filename: string
  mediaFormat: string | null
  sizeBytes: number | null
  ocrStatus: string
  purged: boolean
  url: string | null           // 短期地址，过期/清理后为空
  urlExpiresAt: string | null
}

interface SentenceRange {
  sentenceId: number | null
  start: number                // UTF-16，左闭右开
  end: number
}

interface Sentence {
  id: number
  sequence: number
  sourceAssetId: number | null // OCR 图片资产 ID，音频句为 null
  speakerId: number | null
  role: 'AGENT' | 'CUSTOMER' | 'UNKNOWN' | null
  startMs: number
  endMs: number
  text: string
  speechSpeed: number | null
  highlights: SentenceRange[]
  ruleVersionIds: number[]
  notes: string[]
}

interface Violation {
  id: number
  ruleVersionId: number
  ruleName: string | null
  matchType: 'hit' | 'require' | 'must-have' | null
  scope: string | null
  sentenceId: number | null
  triggerSentenceId: number | null
  evidenceSentenceIds: number[]
  ranges: SentenceRange[]
  deduct: number | null
  note: string
}

interface SpeakerRole {
  speakerId: number
  role: 'AGENT' | 'CUSTOMER' | 'UNKNOWN'
}

interface ReportDetail {
  id: number
  taskId: string
  reportNo: string
  taskStatus: string
  durationSec: number | null
  createdAt: string
  reportVersionId: number | null
  reportVersion: number | null
  status: 'ANALYZING' | 'PUBLISHED' | 'FAILED' | null
  latestStatus: 'ANALYZING' | 'PUBLISHED' | 'FAILED' | null
  mappingRevision: number
  speakerMapping: SpeakerRole[]
  rolesConfirmed: boolean
  sentenceCount: number
  flaggedCount: number
  violationCount: number
  riskScore: number | null
  complianceScore: number | null
  threshold: number | null
  riskFlagged: boolean
  publishedAt: string | null
  audioAvailable: boolean
  notes: string[]
  sentences: Sentence[]
  violations: Violation[]
}
```

---

## 6. 接口总览

| # | 方法 | 路径 | 用途 |
|---|---|---|---|
| 1 | POST | `/api/auth/wechat` | 微信登录 |
| 2 | POST | `/api/auth/login` | 密码登录 |
| 3 | POST | `/api/auth/register` | 注册账号 |
| 4 | POST | `/api/auth/bind-wechat` | 绑定微信 |
| 5 | GET | `/api/auth/userinfo` | 获取用户信息 |
| 6 | GET | `/api/agreements` | 协议列表 |
| 7 | GET | `/api/agreements/versions` | 协议版本快照 |
| 8 | GET | `/api/agreements/{key}` | 协议正文 |
| 9 | GET | `/api/task/quota` | 额度预检 |
| 10 | POST | `/api/upload/sign` | 申请上传签名 |
| 11 | POST | `/api/upload/refresh` | 刷新上传签名 |
| 12 | POST | `/api/upload/complete` | 上传完成校验 |
| 13 | POST | `/api/task/submit` | 登记识别任务 |
| 14 | PUT | `{sign.uploadUrl}` | 原始字节直传对象存储 |
| 15 | GET | `/api/task/list` | 任务分页列表 |
| 16 | GET | `/api/task/{id}/status` | 任务状态 |
| 17 | GET | `/api/task/{taskId}/assets` | 任务资产 |
| 18 | DELETE | `/api/task/{id}` | 删除单条记录 |
| 19 | GET | `/api/report/{id}` | 报告详情 |
| 20 | POST | `/api/report/{id}/exports` | 创建导出作业 |
| 21 | GET | `/api/exports/{exportId}` | 查询导出状态 |
| 22 | GET | `/api/exports/{exportId}/download` | 签发下载地址 |
| 23 | GET | `/api/deletions/{deletionJobId}` | 查询清理作业状态 |
| 24 | DELETE | `/api/privacy/erase` | 全部抹除 |
