# S06 作业管线测试资源

| 文件 | 用途 |
|---|---|
| `run_s06_job_e2e.sh` | 端到端验证 JOB-01..JOB-09 中需要真实数据库的部分（29 项断言） |

单元测试在 `src/test/java/com/diting/`：
`common/TaskStatusTest`（状态机合法/非法迁移、终态判定、旧值映射）、
`service/TaskPipelineServiceTest`（领取、提交、对账超限、解析落库、空音频、远端失败、结果失效、
墓碑、领取竞争、未配置、COMPLETED 守卫、租约与退避时间）。

## 前置条件

```bash
export MYSQL_PWD='<库密码>'
mysql -h127.0.0.1 -uroot -e "DROP DATABASE IF EXISTS diting_s06_test; CREATE DATABASE diting_s06_test DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
sed -e '/^CREATE DATABASE/d' -e '/^USE /d' src/main/resources/sql/schema.sql | mysql -h127.0.0.1 -uroot -D diting_s06_test
for f in V2__S01_schema_foundation.sql V3__S06_status_migration.sql; do
  mysql -h127.0.0.1 -uroot -D diting_s06_test < src/main/resources/sql/migration/$f
done

# 关键：test profile（测试端点）+ 桩听悟 + 内存对象存储 + 关掉定时器（由脚本手动 tick）
DITING_ADMIN_INIT_USERNAME=ops_admin DITING_ADMIN_INIT_PASSWORD='<临时强密码>' \
java -jar target/diting-server-0.0.1-SNAPSHOT.jar --server.port=8085 \
  --spring.profiles.active=test --diting.storage.type=memory \
  --diting.aliyun.tingwu.type=stub --diting.pipeline.enabled=false \
  --spring.datasource.url='jdbc:mysql://127.0.0.1:3306/diting_s06_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai' \
  --diting.jwt.secret='<测试专用强密钥>'

MYSQL_PWD='<密码>' bash run_s06_job_e2e.sh
```

`diting.pipeline.enabled=false` 只关闭定时调度；手动 `POST /api/test/pipeline/tick` 仍可驱动，
这样断言是确定性的。生产由 `TaskPipelineScheduler` 按 5s 间隔驱动同一套逻辑。

## 断言覆盖

- JOB-01 关闭小程序仍推进：一轮调度完成提交、登记远端任务号、额度转已消费
- JOB-02 重复/乱序/未知任务回调：一律 200、只唤醒不改状态、不采信回调里的结果地址
- JOB-03 并发 tick 争抢同一任务：合计只推进一次，尝试次数只 +1
- JOB-04 租约未过期不重领、过期后可重领（进程中断恢复）
- JOB-06 结果地址失效：停在 PARSING 并记 `PARSE_FAILED`，不误判为失败
- JOB-07 版本 CAS：过期写入者影响 0 行且改不动状态
- JOB-08 删除后迟到回调：不复活、不再被领取
- V3 状态迁移：PENDING→QUEUED、ONGOING→TRANSCRIBING、库中不再残留旧值、台账记录

脚本开头会把上一轮遗留的到期任务停泊到明天，保证 `progressed` 计数只反映本轮任务。
