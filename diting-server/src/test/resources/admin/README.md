# S10 后台与审计测试资源

| 文件 | 用途 |
|---|---|
| `run_s10_admin_e2e.sh` | 三角色权限矩阵、脱敏、原件签发留痕、日志无敏感内容（37 项断言） |

## 前置条件

```bash
export MYSQL_PWD='<库密码>'
mysql -h127.0.0.1 -uroot -e "DROP DATABASE IF EXISTS diting_s10_test; CREATE DATABASE diting_s10_test DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
sed -e '/^CREATE DATABASE/d' -e '/^USE /d' src/main/resources/sql/schema.sql | mysql -h127.0.0.1 -uroot -D diting_s10_test
for f in V2__S01_schema_foundation.sql V3__S06_status_migration.sql; do
  mysql -h127.0.0.1 -uroot -D diting_s10_test < src/main/resources/sql/migration/$f
done

DITING_ADMIN_INIT_USERNAME=ops_admin DITING_ADMIN_INIT_PASSWORD='<临时强密码>' \
java -jar target/diting-server-0.0.1-SNAPSHOT.jar --server.port=8089 \
  --spring.profiles.active=test --diting.storage.type=memory \
  --diting.aliyun.tingwu.type=stub --diting.pipeline.enabled=false \
  --spring.datasource.url='jdbc:mysql://127.0.0.1:3306/diting_s10_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai' \
  --diting.jwt.secret='<测试专用强密钥>'

MYSQL_PWD='<密码>' S10_ADMIN_PASSWORD='<密码>' bash run_s10_admin_e2e.sh
```

## 断言覆盖

- 三角色矩阵：录音资产/详情/原件/角色确认/删除/审计各自的可与不可（INVESTOR 无个体数据、OPERATOR 无原件与审计、改 URL 也拒绝）
- 脱敏：OPERATOR 看到等长掩码文本，手机号与身份证原文不出现，**高亮区间仍然有效**
- 原件签发：仅 ADMIN，过期/已清理会被拒；签发动作写入审计
- 审计纪律：日志不含对话原文、不含 `http` 链接、不含密码字段；配置/规则/账号变更都留痕
- 角色变更即时生效：禁用后旧 token 立即 403；改角色后立刻失去个体数据权限
