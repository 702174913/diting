package com.diting.config;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 启动自检的判定逻辑：缺台账或缺失键对象都要能抓出来，失败信息要带上要执行的脚本。
 * 背景：V12/V13「脚本已备好、未在库上执行」时服务照常启动，第一次请求才 500
 * （Table 'diting.rule_legal_clauses' doesn't exist）。
 */
class StartupSchemaCheckTest {

    @Test void passesWhenLedgerAndObjectsAreComplete() {
        assertThat(StartupSchemaCheck.problems(allVersions(), allObjects())).isEmpty();
    }

    @Test void reportsMissingMigrationAndItsObjects() {
        Set<String> versions = allVersions();
        versions.remove("V13");
        Set<String> objects = allObjects();
        objects.remove("table:legal_clauses");
        objects.remove("table:rule_legal_clauses");

        List<String> problems = StartupSchemaCheck.problems(versions, objects);

        assertThat(problems).hasSize(3);
        assertThat(problems.get(0)).contains("V13").contains("V13__legal_clauses.sql");
        assertThat(problems).anySatisfy(p -> assertThat(p).contains("表 legal_clauses 不存在"));
        assertThat(StartupSchemaCheck.failureMessage(versions, objects, problems))
            .contains("拒绝启动").contains("V13__legal_clauses.sql").contains("legal_clauses")
            .contains("DITING_STARTUP_SCHEMA_CHECK=false");
    }

    @Test void reportsMissingColumnEvenWhenLedgerHasTheRow() {
        // 台账撒谎的典型场景：DDL 半途失败 / 列被手工删掉 —— 只有对象核对能发现
        Set<String> objects = allObjects();
        objects.remove("column:recognition_tasks.parse_failures");

        List<String> problems = StartupSchemaCheck.problems(allVersions(), objects);

        assertThat(problems).containsExactly("列 recognition_tasks.parse_failures 不存在（应由 V12 创建）");
        assertThat(StartupSchemaCheck.failureMessage(allVersions(), objects, problems))
            .contains("V12__quality_parse_failures.sql");
    }

    @Test void listsOnlyScriptsThatNeedAttention() {
        Set<String> versions = allVersions();
        versions.remove("V12");

        assertThat(StartupSchemaCheck.missingScripts(versions, allObjects()))
            .containsExactly("V12__quality_parse_failures.sql");
    }

    private static Set<String> allVersions() {
        return StartupSchemaCheck.REQUIRED_MIGRATIONS.stream()
            .map(StartupSchemaCheck.Migration::version)
            .collect(Collectors.toCollection(HashSet::new));
    }

    private static Set<String> allObjects() {
        return StartupSchemaCheck.REQUIRED_OBJECTS.stream()
            .map(o -> o.kind() + ":" + o.name())
            .collect(Collectors.toCollection(HashSet::new));
    }
}
