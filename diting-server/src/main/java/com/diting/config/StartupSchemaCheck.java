package com.diting.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 启动自检：库结构落后于代码时**拒绝启动**（fail-fast）。
 *
 * <p>背景（2026-10-12）：V12/V13 长期是「脚本已备好、没在库上执行」，缺表不会在启动时暴露，
 * 而是第一次请求打到 {@code rule_legal_clauses} 才抛 BadSqlGrammarException
 * （{@code Table 'diting.rule_legal_clauses' doesn't exist}）。部署清单此前写
 * 「服务不允许在缺这 5 个迁移的库上启动」，但那句话在代码里没有对应实现。
 *
 * <p>只读 {@code schema_migrations} 与 {@code information_schema}，不执行任何 DDL。
 * 台账与关键对象**交叉验证**：MySQL DDL 隐式提交，脚本半途失败时台账与真实结构可能不一致。
 *
 * <p>关闭自检：{@code DITING_STARTUP_SCHEMA_CHECK=false}（见 application.yml）。
 */
@Slf4j
@Component
public class StartupSchemaCheck {

    /** 代码依赖的迁移版本 → 脚本文件 + 说明；缺任何一个都拒绝启动。 */
    static final List<Migration> REQUIRED_MIGRATIONS = List.of(
        new Migration("S01", "V2__S01_schema_foundation.sql", "数据基础增量迁移"),
        new Migration("S06", "V3__S06_status_migration.sql", "任务状态枚举迁移"),
        new Migration("V4", "V4__S16_media_assets.sql", "S16 图片上传与 OCR"),
        new Migration("S18", "V5__S18_account_soft_delete.sql", "后台账号软删除"),
        new Migration("S19", "V6__S19_agreements.sql", "用户协议"),
        new Migration("V7", "V7__quality_write_guards.sql", "上传引用唯一与公共锁"),
        new Migration("V8", "V8__quality_ocr_leases.sql", "OCR 租约令牌与有限重试"),
        new Migration("V9", "V9__quality_export_leases.sql", "导出作业领取栅栏与故障恢复"),
        new Migration("V10", "V10__quality_user_erase_revocation.sql", "抹除撤销用户登录态"),
        new Migration("V11", "V11__quality_deletion_fencing.sql", "清理作业租约代际栅栏"),
        new Migration("V12", "V12__quality_parse_failures.sql", "结果下载/解析失败独立计数"),
        new Migration("V13", "V13__legal_clauses.sql", "法律条款库与规则绑定")
    );

    /**
     * 关键对象（表/列/索引），每个都标注由哪个迁移创建。
     * 只挑「代码一跑就要用、缺了必然 500」的；台账缺行但对象在（或反之）都会被抓出来。
     */
    static final List<SchemaObject> REQUIRED_OBJECTS = List.of(
        new SchemaObject("table", "task_assets", "V4"),
        new SchemaObject("table", "agreements", "S19"),
        new SchemaObject("column", "admin_users.deleted_at", "S18"),
        new SchemaObject("table", "mutation_locks", "V7"),
        new SchemaObject("index", "task_assets.uk_asset_upload", "V7"),
        new SchemaObject("column", "task_assets.ocr_attempts", "V8"),
        new SchemaObject("column", "task_assets.ocr_lease_token", "V8"),
        new SchemaObject("column", "task_assets.ocr_lease_until", "V8"),
        new SchemaObject("column", "export_jobs.lease_token", "V9"),
        new SchemaObject("column", "export_jobs.lease_until", "V9"),
        new SchemaObject("column", "export_jobs.next_attempt_at", "V9"),
        new SchemaObject("column", "users.erased_at", "V10"),
        new SchemaObject("column", "deletion_jobs.lease_token", "V11"),
        new SchemaObject("column", "recognition_tasks.parse_failures", "V12"),
        new SchemaObject("table", "legal_clauses", "V13"),
        new SchemaObject("table", "rule_legal_clauses", "V13")
    );

    private final DataSource dataSource;
    private final DitingProperties props;

    public StartupSchemaCheck(DataSource dataSource, DitingProperties props) {
        this.dataSource = dataSource;
        this.props = props;
    }

    @PostConstruct
    void verify() {
        if (!props.getStartup().getSchemaCheck().isEnabled()) {
            log.warn("启动自检已关闭（diting.startup.schema-check.enabled=false）：缺迁移时可能要等第一次请求才报错");
            return;
        }
        Set<String> versions;
        Set<String> objects;
        try (Connection conn = dataSource.getConnection()) {
            versions = readLedger(conn);
            objects = readObjects(conn);
        }
        catch (SQLException e) {
            throw new IllegalStateException("启动自检读不到数据库结构：" + e.getMessage()
                + "（先确认 DITING_DB_* 与 MySQL 连通、库已按 docs/上线部署清单.md §4 初始化；"
                + "确认不需要自检时可设 DITING_STARTUP_SCHEMA_CHECK=false）", e);
        }
        List<String> problems = problems(versions, objects);
        if (!problems.isEmpty()) {
            throw new IllegalStateException(failureMessage(versions, objects, problems));
        }
        log.info("启动自检通过：迁移台账 {} 个版本齐备、关键对象 {} 项均在", versions.size(), REQUIRED_OBJECTS.size());
    }

    /** 纯判断：给定台账版本与已存在的对象 key（{@code kind:name}），返回人话问题列表；为空即通过。 */
    static List<String> problems(Set<String> versions, Set<String> objects) {
        List<String> problems = new ArrayList<>();
        for (Migration m : REQUIRED_MIGRATIONS) {
            if (!versions.contains(m.version())) {
                problems.add("迁移 " + m.version() + "（" + m.label() + "，脚本 " + m.script() + "）没有台账记录");
            }
        }
        for (SchemaObject o : REQUIRED_OBJECTS) {
            if (!objects.contains(o.kind() + ":" + o.name())) {
                problems.add(objectKindLabel(o.kind()) + " " + o.name() + " 不存在（应由 " + o.version() + " 创建）");
            }
        }
        return problems;
    }

    /**
     * 需要核对的脚本文件名（去重、按 REQUIRED_MIGRATIONS 顺序）：
     * 既含台账缺行的迁移，也含对象缺失所属的迁移——后者是「台账有行但结构不对」的情况，
     * 只按台账算会漏报脚本。
     */
    static List<String> missingScripts(Set<String> versions, Set<String> objects) {
        Set<String> versionsToFix = new LinkedHashSet<>();
        for (Migration m : REQUIRED_MIGRATIONS) {
            if (!versions.contains(m.version())) {
                versionsToFix.add(m.version());
            }
        }
        for (SchemaObject o : REQUIRED_OBJECTS) {
            if (!objects.contains(o.kind() + ":" + o.name())) {
                versionsToFix.add(o.version());
            }
        }
        Set<String> scripts = new LinkedHashSet<>();
        for (Migration m : REQUIRED_MIGRATIONS) {
            if (versionsToFix.contains(m.version())) {
                scripts.add(m.script());
            }
        }
        return new ArrayList<>(scripts);
    }

    /** 失败信息：说清缺什么、脚本怎么跑；不猜、不自动改库。 */
    static String failureMessage(Set<String> versions, Set<String> objects, List<String> problems) {
        List<String> missingScripts = missingScripts(versions, objects);
        StringBuilder sb = new StringBuilder();
        sb.append("启动自检未通过：库结构落后于代码，拒绝启动（否则要等第一次请求才 500）。\n");
        for (String p : problems) {
            sb.append("  - ").append(p).append('\n');
        }
        if (!missingScripts.isEmpty()) {
            sb.append("需要核对的脚本（缺台账行或对象缺失）：").append(String.join(" ", missingScripts)).append('\n')
              .append("执行方式（必须用 mysql 客户端：脚本含 DELIMITER/存储过程时驱动会失败）：\n")
              .append("  cd diting-server && for v in ")
              .append(String.join(" ", missingScripts))
              .append("; do mysql -h<host> -u<user> -D<库名> < src/main/resources/sql/migration/$v.sql; done\n")
              .append("脚本非幂等（如 V12 是普通 ALTER）：先查 information_schema 与 schema_migrations，")
              .append("对象已存在只是台账缺行时只补台账 INSERT，不要重跑 DDL。\n");
        }
        sb.append("详情见 docs/上线部署清单.md §4；确认无需自检时可设 DITING_STARTUP_SCHEMA_CHECK=false。");
        return sb.toString();
    }

    private static String objectKindLabel(String kind) {
        return switch (kind) {
            case "table" -> "表";
            case "column" -> "列";
            case "index" -> "索引";
            default -> kind;
        };
    }

    private static Set<String> readLedger(Connection conn) throws SQLException {
        Set<String> versions = new LinkedHashSet<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT version FROM schema_migrations")) {
            while (rs.next()) {
                versions.add(rs.getString(1));
            }
        }
        return versions;
    }

    private static Set<String> readObjects(Connection conn) throws SQLException {
        Set<String> objects = new LinkedHashSet<>();
        readKeys(conn, "SELECT LOWER(table_name) FROM information_schema.tables "
            + "WHERE table_schema = DATABASE()", "table", objects);
        readKeys(conn, "SELECT LOWER(CONCAT(table_name, '.', column_name)) FROM information_schema.columns "
            + "WHERE table_schema = DATABASE()", "column", objects);
        readKeys(conn, "SELECT LOWER(CONCAT(table_name, '.', index_name)) FROM information_schema.statistics "
            + "WHERE table_schema = DATABASE()", "index", objects);
        return objects;
    }

    private static void readKeys(Connection conn, String sql, String kind, Set<String> into) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                into.add(kind + ":" + rs.getString(1));
            }
        }
    }

    /** 迁移版本 → 脚本文件 + 说明 */
    record Migration(String version, String script, String label) {
    }

    /** kind = table / column / index，name 不带库名（列与索引带表名前缀） */
    record SchemaObject(String kind, String name, String version) {
    }
}
