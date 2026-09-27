# S13 生命周期测试资源

| 文件 | 用途 |
|---|---|
| `run_s13_lifecycle_e2e.sh` | 删除传播、30 天到期、清理重试与崩溃恢复、抹除（39 项断言） |

## 前置条件

```bash
export MYSQL_PWD='<库密码>'
mysql -h127.0.0.1 -uroot -e "DROP DATABASE IF EXISTS diting_s13_test; CREATE DATABASE diting_s13_test DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
sed -e '/^CREATE DATABASE/d' -e '/^USE /d' src/main/resources/sql/schema.sql | mysql -h127.0.0.1 -uroot -D diting_s13_test
for f in V2__S01_schema_foundation.sql V3__S06_status_migration.sql; do
  mysql -h127.0.0.1 -uroot -D diting_s13_test < src/main/resources/sql/migration/$f
done

DITING_ADMIN_INIT_USERNAME=ops_admin DITING_ADMIN_INIT_PASSWORD='<临时强密码>' \
java -jar target/diting-server-0.0.1-SNAPSHOT.jar --server.port=8902 \
  --spring.profiles.active=test --diting.storage.type=memory \
  --diting.aliyun.tingwu.type=stub --diting.pipeline.enabled=false \
  --diting.export.enabled=false --diting.retention.enabled=false \
  --spring.datasource.url='jdbc:mysql://127.0.0.1:3306/diting_s13_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai' \
  --diting.jwt.secret='<测试专用强密钥>'

MYSQL_PWD='<密码>' S13_ADMIN_PASSWORD='<密码>' bash run_s13_lifecycle_e2e.sh
```

脚本会先把每日额度调到 50（默认 3 份会被本脚本用满），并用 `POST /api/test/pipeline/retention-tick`
手动推进清理，保证断言确定性。`diting.retention.enabled=false` 只关定时器，不影响手动推进。

## 断言覆盖

- DELETE-01 保留期从**上传时间**起算；到期那一刻拒绝签发原件；文本仍可读；到期后对象被删除且标记 `audio_purged`
- DELETE-03 重复删除幂等（同一作业，不新建）
- DELETE-02 退避到期的重试作业被再次领取并完成；完成后清空待清理清单
- DELETE-04 删除传播：句子 / 命中 / 报告版本 / 导出作业与产物对象都被清理，任务行保留**最小墓碑**
- DELETE-05 删除后的迟到回调被忽略，不复活
- DELETE-06 **崩溃恢复**：租约过期的 RUNNING 作业被重新领取；租约未过期的不会被抢
- DELETE-07 到期导出产物被清理（含音频 ZIP 副本），且不再签发下载地址
- DELETE-08 抹除后 C 端与后台都不可读、句子与上传会话被清理、账号被**匿名化**（清空 username/openid 等 PII）
