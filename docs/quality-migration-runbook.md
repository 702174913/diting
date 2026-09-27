# V7–V11 真实业务库迁移门禁与执行记录（已执行，待部署验收）

当前代码依赖 `V7`（上传唯一键/管理员锁）、`V8`（OCR 租约）、`V9`（导出租约）、`V10`（用户抹除令牌撤销）、`V11`（清理作业栅栏）。**服务不得在缺少这五个迁移的库上启动。** 历史代码有无令牌工作者；切换前先停止写入、等待/终止旧作业，避免旧版代码绕过令牌栅栏。

1. 由已继承 `DITING_DB_USERNAME`/`DITING_DB_PASSWORD` 的 Pi 进程连接；不要在聊天、命令行参数、日志里打印密码。使用 `--protocol=TCP --host=127.0.0.1 --port=3306 --database=diting` 与 `application.yml` 核对，先只读查询 `SELECT DATABASE(), CURRENT_USER(), @@hostname, @@port, @@version, @@read_only;`，人工确认是指定的真实业务库。连接错误立即停止。
2. 只读检查 `schema_migrations` 中 V7–V11 是否已有记录及对应表/列/索引；查询 `SELECT upload_id, COUNT(*) FROM task_assets WHERE upload_id IS NOT NULL GROUP BY upload_id HAVING COUNT(*)>1;`。非空则停下核实归属，不自动去重。核对 `task_assets`、`export_jobs` 中 `RUNNING` 状态数以及 `users` 现有匿名化账号（`nickname='已注销'` 且用户名、密码哈希、微信标识均为 NULL），逐条判定 V10 是否需要历史回填；不可仅凭昵称自动批量标记。还要确认当前 `schema_migrations.version` 唯一性和表结构与脚本假设一致。
3. 在停写窗口以最小权限账号做**完整备份**（含 schema/data/routines/triggers/events，单事务一致性；另存数据库元信息），备份文件权限 `0600`，校验非空、可读取及抽样恢复到**隔离库**。不要把真实用户数据归档进仓库、日志或聊天。备份失败就停。
4. 审核当前脚本和执行计划、历史重复上传、匿名账号归属与回填方式；在隔离库按 V7→V8→V9→V10→V11 顺序演练，跑针对 MySQL 的读写/并发复验。清理作业还需核查历史 `remaining_objects` 是否覆盖音频、图片及上传会话 key；缺 key 的旧作业不能仅凭状态当作对象已清空。用隔离对象存储模拟迟到 PUT 与失败重试，验证 `SIGNED_URL_PENDING` 到期复扫、代际锁与墓碑，不使用真实付费云。上述各项尚未满足，不执行真实库迁移。
5. 正式窗口按顺序执行，**每个脚本单独执行且错误即停**（不要用 mysql `--force`）。MySQL DDL 隐式提交：部分成功不能直接重跑；检查 `information_schema` 和 `schema_migrations` 后人工制定补救。每一步核验新列、索引、台账及关键数据不丢，再继续下一步。最后部署相应版本并核对真实库上的租约/删除/导出状态；若验收失败先停写并按备份及人工方案恢复，不能盲目反向 DROP。

### 额度与远端受理的隔离库/上线后核查（只读，不自动改账）

在完成身份核对和备份后，以聚合计数核查历史半结算；不在日志输出用户信息、远端凭据或逐行数据：

```sql
SELECT t.status, t.error_code, t.media_kind, l.state, COUNT(*) AS cases
FROM quota_ledger l JOIN recognition_tasks t ON t.id = l.task_id
WHERE (l.state = 'RESERVED' AND t.status IN ('FAILED','INVALID','TRANSCRIBING','PARSING','WAITING_ROLE','COMPLETED'))
   OR (l.state = 'RELEASED' AND t.task_id IS NOT NULL)
GROUP BY t.status, t.error_code, t.media_kind, l.state;
```

`TRANSCRIBING` 且已持有 TaskId 的 RESERVED 可由新管线在下轮受任务版本/墓碑门禁补结；明确拒绝的 `SUBMIT_REJECTED` 保留标记并在下轮尝试把终态与退额一并提交。`SUBMIT_UNRECONCILED`、纯图片 OCR 最终失败等没有可靠未受理证据时，**不得按终态直接退还或重提云端**；先在隔离环境核对账单、回调及对象侧证据，由授权人员逐单核销。官方 GetTaskInfo 要求 TaskId，不能凭 TaskKey 推断没有受理。

### 2026-09-27 只读预检记录（异常即停）

用户确认 `127.0.0.1:3306/diting` 为真实业务库；当前 Pi 进程凭据可见，已通过环境变量而非命令参数只读连接。实例为 `wuweideMacBook-Pro-2.local`、MySQL 9.3.0、`root@localhost`、`@@read_only=0`。迁移台账仅 S01/S06/V4/S18/S19（5 行、版本唯一），V7–V11 均不存在；对应新列/索引和 `mutation_locks` 也不存在。

只读聚合：`users=2`、`recognition_tasks=3`、`task_assets=0`、`upload_sessions=0`、`export_jobs=4`、`deletion_jobs=0`、`quota_ledger=0`；`task_assets.upload_id` 重复组 0，匿名化昵称候选 0，孤立资产引用 0，额度半结算聚合 0。**`export_jobs` 存在 1 行 RUNNING（创建于 2026-09-27 00:35:35）**，另外 3 行 FAILED；尚不能判断 RUNNING 是否有旧工作者或是否需要保全/恢复。按异常即停门禁，先核查并停写/停旧工作者、确认维护窗口，不自动重置任何作业状态。

用户随后确认维护窗口，授权向仍监听 8080 的旧服务 PID 32686 发 SIGTERM；确认进程退出、8080 无监听、其他业务库连接数为 0，未改动历史 RUNNING 行。首次备份尝试 `b634c9a2a` 失败（mysqldump 9.3 不识别 `--connect-timeout=5`，退出 7），当时仅有 0 字节 `.partial`；经用户授权移除该参数后 `b2e43a3a6` 重试成功。业务库转储位于 `/Users/wuwei/.diting-business-backups/diting-20260927T012227Z/diting.sql`（146191 字节，`0600`，目录 `0700`），包含数据库创建、21 条建表及结束标记；元信息和 SHA256 校验文件同目录、权限 `0600`，`shasum -a 256 -c SHA256SUMS` 两项通过。独立 MySQL 9.3 实例在 `/Users/wuwei/.diting-isolated-rehearsal-20260927T012227Z/` 初始化并通过私有 Unix socket 启动（`@@skip_networking=1`，不接受 TCP）；`b9f5fed2d` 用备份 SQL 恢复退出 0。恢复后 21 张表、5 个迁移版本、用户/任务/导出等行数与生产只读快照一致，导出状态指纹、全库列结构及例程/触发器/事件数量均与生产一致；已证明这份备份可恢复到独立实例。生产历史 `RUNNING` 导出 `attempt_count=0`，不自动改动。

隔离库 `b475f32b0` 按 V7→V8→V9→V10→V11 **逐脚本演练全部成功**，每版迁移台账均为 1 行；新增 8 列、3 个预期索引和 1 行管理员公共锁，原有 1 行 RUNNING 导出（attempt_count=0）及 3 行 FAILED 保持原状态。隔离库 MySQL 实际探针：先前双工作者测试因第二客户端就绪等待超时失败，确认无残留租约后修正测试；`b0fe94614` 两独立客户端真实行锁交错通过（第二个等待，提交后仅第一代领取成功，旧代发布被拒、当前代可发布并恢复原行）。事务回滚探针验证 V7 相同 `upload_id` 重复引用被唯一键拦截、V8 OCR 租约旧代写入失败、V11 清理令牌旧代不能完成、签名延期 rearm 保留未来截止且不可提前领取；测试数据均回滚，源历史行保持不变。生产迁移后又在独立实例运行 `b476b5539` 清理作业双工作者行锁交错：新一轮 rearm 在旧工作者事务锁后等待，提交后旧令牌不能完成、新代可领取；合成行已删除。`bcdd6bde3` 隔离 MySQL 双工作者上传消费 CAS 及管理员公共锁交错也通过：后手等待行锁，前手提交后上传不能重复消费，成员变更锁串行；合成上传行已删除。`b1668f15f` 隔离 OCR 双工作者任务行锁/资产租约 CAS 通过：第二个领取等待后失败，旧代不能完成，当前代可以完成；合成资产已删除。仅验证这些 MySQL SQL 路径，不代表实际 OSS/云端和全部多工作者业务流程。

生产维护窗口：用户另行明确批准逐版执行。`b04ce9fc3` 生产 V7 成功，台账 V7=1、`task_assets.uk_asset_upload` 唯一索引=1、管理员公共锁行=1；执行前重新检查备份 SHA256、无 8080 监听、业务库其他连接=0、无重复上传引用。`b39d2d526` 生产 V8 成功，台账=1、OCR 新列=3、恢复索引=1。`b21007a6f` 生产 V9 成功，台账=1、导出租约新列=3、到期索引=1；旧 RUNNING 作业仍为 1 行（attempt_count=0，令牌为空），未重置或执行导出。`ba2a92b9e` 生产 V10 成功，台账=1、`users.erased_at` 列=1；历史用户仍 2 行、没有自动标记任何账号。`b3a702828` 生产 V11 成功，台账=1、清理令牌列=1，原有删除作业数仍为 0。迁移后只读审计：迁移台账 10 行且版本唯一（V7–V11 各 1），表 22 张、质量新增列 8 个/索引 3 个/管理员锁行 1 个；用户 2、任务 3、导出 4、资产/上传会话/删除作业/额度流水均 0，与迁移前一致。导出仍为 3 FAILED + 1 RUNNING（attempt_count=0、lease_token 为空），导出状态指纹与迁移前一致；已抹除用户数 0，8080 无监听、业务库其他连接 0。原备份的 SQL 与元信息 SHA256 再次校验通过；V7–V11 每步私有 stderr 文件均为空。**V7–V11 生产 DDL 已执行；没有重置历史作业或启动服务。** 当前环境 `DITING_JWT_SECRET` 有值，但回调密钥、微信/uni-app ID 和生产 API 域名未提供；阿里云/微信配置仍见占位项，恢复服务前须审核这些门禁和历史 RUNNING 导出处理策略，禁止意外调用真实付费云。 当前使用高权限身份且密码曾出现在聊天中，完成安全操作后须轮换；用户虽授权使用现有凭据，仍不得在聊天、参数或日志显示其内容。隔离测试结束后确认合成资产/上传/清理作业均为 0、历史 RUNNING 导出仍为原行；已通过指定私有 socket 正常停止隔离 MySQL 并删除其恢复数据目录，保留权限受限的演练日志和经过哈希校验的原始业务库备份。用户选择**继续保持生产服务停机**，先安全轮换数据库密码、提供真实生产云/回调与小程序配置、审核历史 RUNNING 导出后再单独决定新版本上线。不能把 schema 迁移或隔离 SQL 探针当真实 OSS、云计费、微信真机及生产运行验收。

### 2026-09-27 上传签名 500 后的停机与只读核查

用户报告 `/api/upload/sign` 返回 500；本轮发现新 Java 进程 PID 19282 自 10:47:56 起监听 8080，工作目录为 `diting-server`，启动时能看到 OSS/听悟及数据库环境变量（未读取或输出密钥值）。本轮没有发送新的 HTTP/云请求；用户明确选择停止后，对核实身份的 PID 发送 SIGTERM，确认 8080 已关闭。用户给出的 500 响应不包含异常栈，**具体根因尚未确认**。配置离线检查发现环境变量映射原先缺失，已在 `application.yml` 补映射；当前 `DITING_OSS_BUCKET` 含不符合 Bucket 命名规则的点号，`DITING_OSS_ENDPOINT` 未显式写 HTTPS，均需在安全渠道修正并重新进行隔离配置验证，不得直接重启业务服务。

停机后**只读**核对同一业务库：`upload_sessions=0`、`task_assets=0`、任务仍 3 条、`quota_ledger=0`；导出已从迁移后快照的 `RUNNING=1, FAILED=3` 变为 `RUNNING=0, FAILED=4`，其中原 0 次 RUNNING 行现为 `attempt_count=3`、`error_code=EXPORT_FAILED`、无产物 key、无租约。此状态变化可能与短暂启动期间的默认后台调度有关，尚无足够日志判定每次尝试是否访问或计费 OSS。**禁止自动重置、补发或还原这条导出；须先保全并核对运行日志、OSS 请求/账单与工单，再由业务方裁决。**此前迁移后快照中的 1 条 RUNNING 是当时事实，不再是当前状态。
