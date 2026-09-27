# S09 小程序闭环测试资源

| 文件 | 用途 |
|---|---|
| `run_s09_closure_e2e.sh` | 服务端契约验证：分页、状态筛选、额度、删除墓碑、抹除与清理状态（26 项断言） |
| `docs/evidence/S09/fe-app-logic-harness.mjs` | 小程序纯逻辑验证：轮询生命周期、分页去重、幂等键、状态分流、错误文案 |

## 前置条件

```bash
export MYSQL_PWD='<库密码>'
mysql -h127.0.0.1 -uroot -e "DROP DATABASE IF EXISTS diting_s09_test; CREATE DATABASE diting_s09_test DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
sed -e '/^CREATE DATABASE/d' -e '/^USE /d' src/main/resources/sql/schema.sql | mysql -h127.0.0.1 -uroot -D diting_s09_test
for f in V2__S01_schema_foundation.sql V3__S06_status_migration.sql; do
  mysql -h127.0.0.1 -uroot -D diting_s09_test < src/main/resources/sql/migration/$f
done

DITING_ADMIN_INIT_USERNAME=ops_admin DITING_ADMIN_INIT_PASSWORD='<临时强密码>' \
java -jar target/diting-server-0.0.1-SNAPSHOT.jar --server.port=8088 \
  --spring.profiles.active=test --diting.storage.type=memory \
  --diting.aliyun.tingwu.type=stub --diting.pipeline.enabled=false \
  --spring.datasource.url='jdbc:mysql://127.0.0.1:3306/diting_s09_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai' \
  --diting.jwt.secret='<测试专用强密钥>'

MYSQL_PWD='<密码>' bash run_s09_closure_e2e.sh
```

小程序侧逻辑用小程序的 esbuild 打包后由 node 跑（不引入新依赖）：

```bash
cd diting-app
for f in polling paging idempotency taskStatus; do ./node_modules/.bin/esbuild src/utils/$f.ts --bundle --format=esm --outfile=/tmp/s09-fe/$f.mjs; done
./node_modules/.bin/esbuild /tmp/s09-fe/entry.js --bundle --format=esm --outfile=/tmp/s09-fe/app-errors.mjs --define:import.meta.env.VITE_BASE_URL='"http://test.local"'
node docs/evidence/S09/fe-app-logic-harness.mjs
```

## 断言覆盖

- 分页：page 从 1 起、`{items,total,page,pageSize}`、page=0 → 422、按状态筛选
- 额度：3 份用满后 `remaining=0`
- 删除：写墓碑 → **立即 404**（报告与状态都读不到）→ 列表不再出现 → 重复删除幂等返回同一作业 → 清理状态可查且只含最小元数据
- 抹除：全部记录写墓碑、列表清空、返回 ERASE 作业
- 归属：他人清理作业 404；新用户空列表
