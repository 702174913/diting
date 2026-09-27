# S05 听悟适配测试资源

| 文件 | 用途 |
|---|---|
| `run_s05_guard_e2e.sh` | 守护验证：未配置云凭据时必须 503 NOT_CONFIGURED，不得给出假地址/假结果（7 项断言） |
| `get-task-completed.json` | 脱敏契约样本：GetTaskInfo 已完成响应（Data.TaskStatus / Data.Result.Transcription） |
| `transcription-sample.json` | 脱敏契约样本：转写结果（Paragraphs / Words / SpeakerId / 毫秒时间戳），含乱序与多说话人 |

单元测试在 `src/test/java/com/diting/service/`：
`tingwu/TranscriptionParserTest`（ASR-04/05/06/07/09/10）、`TingwuServiceTest`（ASR-01/02/03）、
`tingwu/ResultUrlPolicyTest`（ASR-08 地址策略）、`tingwu/TranscriptionDownloaderTest`（体积/重定向/非 2xx）。

## 守护验证前置条件

```bash
export MYSQL_PWD='<库密码>'
mysql -h127.0.0.1 -uroot -e "DROP DATABASE IF EXISTS diting_s05_test; CREATE DATABASE diting_s05_test DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
sed -e '/^CREATE DATABASE/d' -e '/^USE /d' src/main/resources/sql/schema.sql | mysql -h127.0.0.1 -uroot -D diting_s05_test
mysql -h127.0.0.1 -uroot -D diting_s05_test < src/main/resources/sql/migration/V2__S01_schema_foundation.sql

# 关键：**不带** test profile、**不带**云凭据（application.yml 里保持「请替换」）
java -jar target/diting-server-0.0.1-SNAPSHOT.jar --server.port=8084 \
  --spring.datasource.url='jdbc:mysql://127.0.0.1:3306/diting_s05_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai' \
  --diting.jwt.secret='<测试专用强密钥>'

MYSQL_PWD='<密码>' bash run_s05_guard_e2e.sh
```

## 契约样本的使用与边界

- 样本字段名与层级按官方离线转写结果整理，**数值与文本全部为合成内容**，不含任何真实通话信息。
- 解析器对未知结构**不猜字段**：缺 `Transcription` / `Paragraphs` / `Words` / 毫秒时间戳一律明确报错；
  空 `Paragraphs` 视为空音频返回空结果，由调用方判定 INVALID。
- 真实云端返回若与样本不同，属契约差异：应在 S14 POC 用真实响应更新样本并修适配，
  **不能靠“多认几个候选键”糊过去**。
