# S04 上传协议测试资源

| 文件 | 用途 |
|---|---|
| `run_s04_upload_e2e.sh` | 端到端验证 UPLOAD-01..UPLOAD-08 的离线可验部分（43 项断言） |

单元测试在 `src/test/java/com/diting/service/`：`UploadSessionServiceTest`（签发形态、同意、预检、
对象不存在/空文件/超限、伪扩展名、归属、刷新、状态机）、`storage/AudioFormatSnifferTest`（文件头嗅探）。
前端上传链路（PUT + 原始字节 + 无 multipart）由 `docs/evidence/S04/fe-upload-harness.mjs` 验证。

## 前置条件

```bash
export MYSQL_PWD='<库密码>'
mysql -h127.0.0.1 -uroot -e "DROP DATABASE IF EXISTS diting_s04_test; CREATE DATABASE diting_s04_test DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
sed -e '/^CREATE DATABASE/d' -e '/^USE /d' src/main/resources/sql/schema.sql | mysql -h127.0.0.1 -uroot -D diting_s04_test
mysql -h127.0.0.1 -uroot -D diting_s04_test < src/main/resources/sql/migration/V2__S01_schema_foundation.sql

# 关键：test profile + 内存对象存储（生产不会加载这两者）
DITING_ADMIN_INIT_USERNAME=ops_admin DITING_ADMIN_INIT_PASSWORD='<临时强密码>' \
java -jar target/diting-server-0.0.1-SNAPSHOT.jar --server.port=8083 \
  --spring.profiles.active=test --diting.storage.type=memory \
  --spring.datasource.url='jdbc:mysql://127.0.0.1:3306/diting_s04_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai' \
  --diting.jwt.secret='<测试专用强密钥>' \
  --diting.aliyun.access-key-id=test-key --diting.aliyun.access-key-secret=test-secret \
  --diting.aliyun.oss.bucket=test-bucket --diting.wechat.appid=test-appid --diting.wechat.secret=test-secret

MYSQL_PWD='<密码>' S04_ADMIN_PASSWORD='<密码>' bash run_s04_upload_e2e.sh
```

可选覆盖：`S04_BASE_URL`、`S04_TEST_DB`（必须以 `_test` 结尾）、`MYSQL_HOST`、`MYSQL_USER`。
需要 `python3` 生成最小文件头载荷（m4a / 伪装的 mp3）。

## 断言覆盖

- UPLOAD-01 签发形态：`method=PUT`、objectKey 带 owner 前缀、返回 uploadId/有效期；校验通过后记录真实格式
- UPLOAD-02 伪扩展名：内容与后缀不一致时拒绝，并报出真实格式；未通过校验的会话不能登记
- UPLOAD-03 对象不存在 / 空文件 / 超大小 / 声明超限
- UPLOAD-04 签名刷新：objectKey 与 uploadId 不变；**已消费的会话不能刷新**
- UPLOAD-06 归属：他人 complete / refresh / 登记一律 404；不存在或直接传 objectKey 都被拒
- UPLOAD-07 上传成功但登记失败：不产生额度流水、会话保持 VERIFIED 可重试；修正后成功
- UPLOAD-08 未同意（缺失或空白）直接 400
- 幂等：同一 uploadId 重复提交返回同一任务且只扣一份额度

## 未覆盖（需要真机与云凭据，属 S14）

- UPLOAD-05 断网上传重试的真机行为
- UPLOAD-09 微信端大文件内存与超时边界
- UPLOAD-10 云端长度/hash 一致性（真实 OSS ETag 比对）

这些项未验证前，上传只标“离线协议通过”，不写端到端可用。
