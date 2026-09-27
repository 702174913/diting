# 总览统计测试资源

| 文件 | 用途 |
| --- | --- |
| `run_s12_stats_e2e.sh` | 固定数据集下的 COUNT 核对、零分母、去重、显式区间、投资人权限，以及「已下线分组/接口必须 404」的收敛断言（39 项断言） |
| `../../java/com/diting/service/stats/StatsServiceTest.java` | 口径纪律单元测试：零分母、可追溯、补零序列、无个体标识、下线分组 404（无需数据库） |

统计面已收敛为 `/api/admin/stats/overview` 一个：growth / finance / ops / violations / quality / compliance
六个分组与 `GET /api/admin/ops/queue` 已随页面下线。上面的脚本与单测都把这批 404 当成**契约**钉住 ——
它们的前缀以前是能返回 200 的。

## 前置条件

```bash
export MYSQL_PWD='<库密码>'
mysql -h127.0.0.1 -uroot -e "DROP DATABASE IF EXISTS diting_s12_test; CREATE DATABASE diting_s12_test DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
sed -e '/^CREATE DATABASE/d' -e '/^USE /d' src/main/resources/sql/schema.sql | mysql -h127.0.0.1 -uroot -D diting_s12_test
for f in V2__S01_schema_foundation.sql V3__S06_status_migration.sql; do
  mysql -h127.0.0.1 -uroot -D diting_s12_test < src/main/resources/sql/migration/$f
done

DITING_ADMIN_INIT_USERNAME=ops_admin DITING_ADMIN_INIT_PASSWORD='<临时强口令>' \
java -jar target/diting-server-0.0.1-SNAPSHOT.jar --server.port=8901 \
  --spring.datasource.url='jdbc:mysql://127.0.0.1:3306/diting_s12_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai' \
  --diting.jwt.secret='<测试专用强密钥>'

MYSQL_PWD='<口令>' S12_ADMIN_PASSWORD='<口令>' bash run_s12_stats_e2e.sh
```

脚本开头会先清理自己上一轮造的夹具数据（user_id=9001 及其报告、上传会话、审计留痕），
因此可以重复执行且 COUNT 断言稳定。

只跑口径逻辑（不需要数据库，几秒结束）：

```bash
JAVA_HOME=<jdk17+> mvn -Dtest=StatsServiceTest test
```

## 断言覆盖

- STAT-01 固定数据 COUNT 核对：提交量、完成量、失败量（只算 FAILED）、无有效语音单列、失败率与分母、
  当前队列（时点库存）、平均耗时样本量、有效报告量按 task 去重、每个指标都带来源表
- STAT-01 漏斗与序列：上传率分母=区间新增注册、确认覆盖率分母=完成量、漏斗六环按用户去重、
  按日序列补零到整窗口
- STAT-02 空区间零分母 → `null`（不伪造 100%）
- STAT-04 角色重算不增加报告数：同一 task 两个发布版本只算 1 份
- STAT-06 相同筛选前后端一致：显式 from/to 与 period 口径相同，区间回显
- STAT-07 投资人可读聚合、响应不含个体标识，且仍拿不到 `/api/admin/recordings` 明细（403）
- 收敛契约：六个下线分组、`/api/admin/ops/queue`、`/api/admin/growth`、`/api/admin/finance` 全部 404；
  `/api/admin/dashboard` 作为权限探针仍保留 200

## 执行现状（重要）

`run_s12_stats_e2e.sh` **需要 MySQL 实例与库口令才能执行**。本轮改造后的脚本**未执行**
（执行环境未提供库口令），状态是**待执行**，不能算作已验证证据。

已执行的验证是：

- `StatsServiceTest` + `AdminOpsControllerTest`（口径与结构纪律，不依赖数据库）
- `python3 -m unittest tests.stats_first_publication_sql_test`（StatsMapper 文本与静态 SQL 探针对照）

它们**不能**替代真实 SQL 的端到端断言。
