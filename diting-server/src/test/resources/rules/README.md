# S07 规则库测试资源

| 文件 | 用途 |
|---|---|
| `run_s07_rules_e2e.sh` | 端到端验证规则 CRUD、版本推进、启停、集合快照与权限（30 项断言） |

单元测试在 `src/test/java/com/diting/service/rules/`：
`TextNormalizerTest`（RULE-06/07：全半角、空白折叠、emoji 代理对、原文位置映射）、
`RuleMatchServiceTest`（RULE-01..05、08、09、10：hit 正反例、require 窗口与错误角色、
must-have 存在/缺失、重复命中不重复扣分、多规则同句、阈值边界、目标角色隐藏态按任意角色判读、确定性）、
`RuleServiceTest`（参数校验、版本推进与冲突、启停、集合快照、历史快照可复算）。

## 前置条件

```bash
export MYSQL_PWD='<库密码>'
mysql -h127.0.0.1 -uroot -e "DROP DATABASE IF EXISTS diting_s07_test; CREATE DATABASE diting_s07_test DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
sed -e '/^CREATE DATABASE/d' -e '/^USE /d' src/main/resources/sql/schema.sql | mysql -h127.0.0.1 -uroot -D diting_s07_test
for f in V2__S01_schema_foundation.sql V3__S06_status_migration.sql; do
  mysql -h127.0.0.1 -uroot -D diting_s07_test < src/main/resources/sql/migration/$f
done

# 规则库不依赖云凭据，普通配置即可（占位凭据只会让上传/转写返回 503）
DITING_ADMIN_INIT_USERNAME=ops_admin DITING_ADMIN_INIT_PASSWORD='<临时强密码>' \
java -jar target/diting-server-0.0.1-SNAPSHOT.jar --server.port=8086 \
  --spring.datasource.url='jdbc:mysql://127.0.0.1:3306/diting_s07_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai' \
  --diting.jwt.secret='<测试专用强密钥>'

MYSQL_PWD='<密码>' S07_ADMIN_PASSWORD='<密码>' bash run_s07_rules_e2e.sh
```

## 断言覆盖

- 权限：OPERATOR 只读可查、不可新增/停用；用户 token 调后台规则接口 403；分页 page=0 → 422
- 参数校验：角色范围非法、扣分越界、require 缺触发角色 → 422；编码重复 → 409
- 版本：过期 expectedVersion 修改 409、正确版本修改推进到 2 且**旧版本保留**
- 集合快照：落库 `rule_sets`、只含启用规则的当前版本；停用后新快照不含该规则
- 历史可复算：停用/更新都不删除历史版本（RULE-11 的基础）
