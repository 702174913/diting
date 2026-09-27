# S03 额度与策略配置测试资源

| 文件 | 用途 |
|---|---|
| `run_s03_quota_e2e.sh` | 端到端验证 QUOTA-01..QUOTA-08 中需要真实数据库的部分（32 项断言） |

单元测试在 `src/test/java/com/diting/service/`：`ConfigServiceTest`、`QuotaServiceTest`、
`TaskSubmitQuotaTest`（范围校验、版本冲突、业务日边界、CAS 幂等、幂等键派生、时长上限）。
需要真实库的原子性/并发/回滚断言放在本脚本里。

## 前置条件

```bash
export MYSQL_PWD='<库密码>'
mysql -h127.0.0.1 -uroot -e "DROP DATABASE IF EXISTS diting_s03_test; CREATE DATABASE diting_s03_test DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
sed -e '/^CREATE DATABASE/d' -e '/^USE /d' src/main/resources/sql/schema.sql | mysql -h127.0.0.1 -uroot -D diting_s03_test
mysql -h127.0.0.1 -uroot -D diting_s03_test < src/main/resources/sql/migration/V2__S01_schema_foundation.sql

DITING_ADMIN_INIT_USERNAME=ops_admin DITING_ADMIN_INIT_PASSWORD='<临时强密码>' \
java -jar target/diting-server-0.0.1-SNAPSHOT.jar --server.port=8082 \
  --spring.datasource.url='jdbc:mysql://127.0.0.1:3306/diting_s03_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai' \
  --diting.jwt.secret='<测试专用强密钥>' \
  --diting.aliyun.access-key-id=test-key --diting.aliyun.access-key-secret=test-secret \
  --diting.aliyun.oss.bucket=test-bucket --diting.wechat.appid=test-appid --diting.wechat.secret=test-secret

S03_ADMIN_PASSWORD='<与初始化一致的密码>' bash run_s03_quota_e2e.sh
```

可选覆盖：`S03_BASE_URL`、`S03_TEST_DB`（必须以 `_test` 结尾）、`S03_ADMIN_USER`、`MYSQL_HOST`、`MYSQL_USER`。

## 断言覆盖

- QUOTA-01 额度 0 不可提交；QUOTA-02 达到上限后 429、剩余归零
- QUOTA-03 跨日隔离（昨天用满不影响今天，业务日按东八区写入）
- QUOTA-04 幂等键与「按上传对象去重」都不重复扣份数、不重复登记
- QUOTA-05 两个并发请求争最后一份：只一个 200，另一个 429
- QUOTA-06 登记与预占同事务，回滚后任务与预占一起消失
- QUOTA-07 状态机 CAS：重复退还影响 0 行
- QUOTA-08 无权限保存 403、范围越界 422、版本冲突 409 且不覆盖
- 服务端时长上限：601s 拒绝、600s 通过（前端预检被绕过也拦得住）

注意：配置版本每次保存都会前进，脚本每次都先 `GET /api/admin/config` 取当前版本再保存，
因此可以重复执行；用户名带随机后缀，不会互相冲突。
