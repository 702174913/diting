# S01 数据层测试资源

配合 `src/main/resources/sql/` 下的 V1 基线（`schema.sql`）与 V2 迁移
（`migration/V2__S01_schema_foundation.sql`）使用。**所有脚本都不保存数据库密码**，
密码通过环境变量 `MYSQL_PWD` 注入。

## 运行限定迁移测试（DB-01..DB-06）

```bash
export MYSQL_PWD='<密码>'
bash src/test/resources/sql/run_s01_db_tests.sh
```

- 默认测试库 `diting_s01_test`（可用 `S01_TEST_DB` 改，**必须以 `_test` 结尾**，脚本会强制校验），
  运行结束自动删除；`S01_KEEP_TEST_DB=1` 可保留现场。
- 其余可覆盖变量：`MYSQL_HOST`、`MYSQL_PORT`、`MYSQL_USER`。
- 覆盖用例：DB-01 空库建表、DB-02 样本迁移内容不丢、DB-03 重复迁移保护、
  DB-04 约束有效性（正/负例）、DB-05 回滚演练与护栏、DB-06 索引覆盖（含 300 行数据下的 EXPLAIN）。
- 输出为逐条 `PASS/FAIL`，全部通过时退出码 0。

注意：`run_s01_db_tests.sh` 加载 V1 基线时会先剥掉 `schema.sql` 里的 `CREATE DATABASE` 与 `USE` 语句，
否则会把测试数据写进真实库。

## 其他脚本

| 脚本 | 用途 |
|---|---|
| `baseline_snapshot.sql` | 迁移前只读快照：DDL、行数、状态分布、结构指纹（列/索引/CHECK） |
| `v1_content_checksum.sql` | V1 已存在列的内容校验和，用于“迁移前后逐字节比对” |
| `seed_pre_migration.sql` | 合成样本数据（含 `PENDING/ONGOING` 旧状态、同 `start_ms` 需按 id 兜底的排序场景） |
| `check_entity_schema_mapping.sh` | 扫描 entity 类字段并换算 snake_case，与实际库列比对，抓字段名漂移 |

```bash
# 例：迁移前基线
mysql -h127.0.0.1 -u<user> -D diting --batch < baseline_snapshot.sql

# 例：实体↔库一致性（只读）
MYSQL_PWD='<密码>' bash check_entity_schema_mapping.sh diting
```

## 约定

- 这些脚本属于**限定测试**：不替代全量构建，也不代表云端/真机可用。
- 只允许在独立测试库或本地开发库上运行；不要指向生产库。
- 迁移与回滚的执行说明见 `docs/S01-字段变更记录.md` §5。
