# S08 报告测试资源

| 文件 | 用途 |
|---|---|
| `run_s08_report_e2e.sh` | 端到端验证角色确认与版本化报告（32 项断言） |

单元测试在 `src/test/java/com/diting/service/report/ReportServiceTest.java`：
角色映射版本冲突、版本选取与历史读取、计数口径（flagged 去重 vs violation 含缺失）、
重算失败保留旧版本、只有失败版本时不伪造分数、XSS 文本透传、原件清理后仍可读。

## 前置条件

```bash
export MYSQL_PWD='<库密码>'
mysql -h127.0.0.1 -uroot -e "DROP DATABASE IF EXISTS diting_s08_test; CREATE DATABASE diting_s08_test DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
sed -e '/^CREATE DATABASE/d' -e '/^USE /d' src/main/resources/sql/schema.sql | mysql -h127.0.0.1 -uroot -D diting_s08_test
for f in V2__S01_schema_foundation.sql V3__S06_status_migration.sql; do
  mysql -h127.0.0.1 -uroot -D diting_s08_test < src/main/resources/sql/migration/$f
done

DITING_ADMIN_INIT_USERNAME=ops_admin DITING_ADMIN_INIT_PASSWORD='<临时强密码>' \
java -jar target/diting-server-0.0.1-SNAPSHOT.jar --server.port=8087 \
  --spring.profiles.active=test --diting.storage.type=memory \
  --diting.aliyun.tingwu.type=stub --diting.pipeline.enabled=false \
  --spring.datasource.url='jdbc:mysql://127.0.0.1:3306/diting_s08_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai' \
  --diting.jwt.secret='<测试专用强密钥>'

MYSQL_PWD='<密码>' S08_ADMIN_PASSWORD='<密码>' bash run_s08_report_e2e.sh
```

## 断言覆盖

- 未确认角色：能读到逐句原文，但角色为空并给出说明（不按奇偶句猜身份）
- 角色确认：生成映射版本 1 并**立即发布报告版本 1**，随后任务才置为 COMPLETED
- 版本冲突：过期 expectedRevision → 409，且不写入任何映射
- 对调角色：生成版本 2，风险分从 v1 的 20 变为 v2 的 50（可解释变化），**旧版本仍可读**
- 计数口径：`flaggedCount` 按命中句去重、`violationCount` 含通话级缺失项；`compliance = 100 - risk`
- 证据：命中区间为原文 UTF-16 偏移；通话级缺失 `sentenceId` 为空
- 历史与失败：按版本读取、读不存在版本 404；最新一次重算 FAILED 时仍展示最近成功版本并提示
- 原件清理后文本仍可读；他人读取报告 404
