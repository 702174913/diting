# S11 导出测试资源

| 文件 | 用途 |
|---|---|
| `run_s11_export_e2e.sh` | 作业生命周期、复用、权限、原件过期、导出中删除、产物过期、ZIP 内容（28 项断言） |

PDF 渲染（中文、分页、注入内容、脱敏）由 `src/test/java/com/diting/service/export/PdfBoxReportRendererTest`
直接对生成的 PDF 做**文本抽取**验证，不依赖人工看图。

## 前置条件

```bash
export MYSQL_PWD='<库密码>'
mysql -h127.0.0.1 -uroot -e "DROP DATABASE IF EXISTS diting_s11_test; CREATE DATABASE diting_s11_test DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
sed -e '/^CREATE DATABASE/d' -e '/^USE /d' src/main/resources/sql/schema.sql | mysql -h127.0.0.1 -uroot -D diting_s11_test
for f in V2__S01_schema_foundation.sql V3__S06_status_migration.sql; do
  mysql -h127.0.0.1 -uroot -D diting_s11_test < src/main/resources/sql/migration/$f
done

DITING_ADMIN_INIT_USERNAME=ops_admin DITING_ADMIN_INIT_PASSWORD='<临时强密码>' \
java -jar target/diting-server-0.0.1-SNAPSHOT.jar --server.port=8090 \
  --spring.profiles.active=test --diting.storage.type=memory \
  --diting.aliyun.tingwu.type=stub --diting.pipeline.enabled=false \
  --diting.export.enabled=false \
  --spring.datasource.url='jdbc:mysql://127.0.0.1:3306/diting_s11_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai' \
  --diting.jwt.secret='<测试专用强密钥>'

MYSQL_PWD='<密码>' S11_ADMIN_PASSWORD='<密码>' bash run_s11_export_e2e.sh
```

`diting.export.enabled=false` 只关闭定时调度；E2E 用 `POST /api/test/pipeline/export-tick` 手动推进，
断言因此是确定性的。

## 断言覆盖

- 跨用户/跨身份域：他人记录 404、用户 token 调后台导出路由 403、C 端读后台作业 404、后台读 C 端作业 404
- 作业生命周期：创建 → QUEUED → 推进 → COMPLETED 并给出短时下载地址与期限
- 复用与去重：同 owner + 版本 + 选项只保留一个有效产物
- ZIP（EXPORT-10）：解压后含 `report.pdf` 与 `audio.m4a`，PDF 头正确，**音频 md5 与上传时一致**
- 原件过期（EXPORT-05）：拒绝打包音频；纯文本 PDF 不受影响
- 导出中删除（EXPORT-08）：复查墓碑，作业标记 `TASK_DELETED` 且不发布产物
- 产物过期（EXPORT-09）：下载地址不再签发
- 审计：导出创建与签发都留痕，且日志里不含链接
