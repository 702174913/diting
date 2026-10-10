package com.diting.service.rules;

import com.diting.common.BizException;
import com.diting.common.ResultCode;
import com.diting.dto.RuleResponse;
import com.diting.dto.RuleSaveRequest;
import com.diting.entity.AuditRule;
import com.diting.entity.AuditRuleVersion;
import com.diting.entity.RuleSet;
import com.diting.mapper.AuditRuleMapper;
import com.diting.mapper.AuditRuleVersionMapper;
import com.diting.mapper.RuleSetMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 规则库：参数校验、版本推进、启停、集合快照与历史可复算（RULE-11 的基础）。
 * mapper 用内存桩，逻辑用真实实现。
 */
class RuleServiceTest {

    private final Map<Long, AuditRule> rules = new HashMap<>();
    private final Map<Long, AuditRuleVersion> versions = new HashMap<>();
    private final List<RuleSet> ruleSets = new ArrayList<>();
    private final AtomicLong idSeq = new AtomicLong(100);
    private RuleService service;

    @BeforeEach
    void setUp() {
        var configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, RuleSet.class);
        rules.clear();
        versions.clear();
        ruleSets.clear();
        idSeq.set(100);

        AuditRuleMapper ruleMapper = mock(AuditRuleMapper.class);
        AuditRuleVersionMapper versionMapper = mock(AuditRuleVersionMapper.class);
        RuleSetMapper ruleSetMapper = mock(RuleSetMapper.class);

        when(ruleMapper.insert(any(AuditRule.class))).thenAnswer(inv -> {
            AuditRule row = inv.getArgument(0);
            row.setId(idSeq.incrementAndGet());
            rules.put(row.getId(), row);
            return 1;
        });
        when(ruleMapper.selectById(any())).thenAnswer(inv -> rules.get(inv.getArgument(0)));
        when(ruleMapper.selectOne(any())).thenAnswer(inv -> rules.values().stream()
            .filter(r -> r.getCode() != null)
            .findFirst().orElse(null));
        when(ruleMapper.updateById(any(AuditRule.class))).thenAnswer(inv -> {
            AuditRule row = inv.getArgument(0);
            rules.put(row.getId(), row);
            return 1;
        });
        when(ruleMapper.update(any(AuditRule.class), any())).thenAnswer(inv -> {
            AuditRule row = inv.getArgument(0);
            AuditRule stored = rules.get(row.getId());
            stored.setCurrentVersion(row.getCurrentVersion());
            return 1;
        });
        // 桩按 enabled 过滤：快照只取启用规则（真实 SQL 由 wrapper 完成同样的事）
        when(ruleMapper.selectList(any())).thenAnswer(inv -> rules.values().stream()
            .filter(r -> Boolean.TRUE.equals(r.getEnabled()))
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new)));

        when(versionMapper.insert(any(AuditRuleVersion.class))).thenAnswer(inv -> {
            AuditRuleVersion row = inv.getArgument(0);
            row.setId(idSeq.incrementAndGet());
            versions.put(row.getId(), row);
            return 1;
        });
        when(versionMapper.selectById(any())).thenAnswer(inv -> versions.get(inv.getArgument(0)));
        // 桩取该规则的最高版本（服务按 current_version 查询）
        when(versionMapper.selectOne(any())).thenAnswer(inv -> versions.values().stream()
            .max(java.util.Comparator.comparingInt(AuditRuleVersion::getVersion))
            .orElse(null));

        when(ruleSetMapper.insert(any(RuleSet.class))).thenAnswer(inv -> {
            ruleSets.add(inv.getArgument(0));
            return 1;
        });
        when(ruleSetMapper.selectOne(any())).thenAnswer(inv -> {
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RuleSet> query = inv.getArgument(0);
            query.getSqlSegment(); // 触发参数绑定；按真实查询条件匹配，而不是无条件返回最新行。
            var values = query.getParamNameValuePairs().values();
            return ruleSets.stream().filter(row -> values.contains(row.getRuleVersionIds())
                || values.contains(row.getVersion())).findFirst().orElse(null);
        });
        when(ruleSetMapper.selectList(any())).thenAnswer(inv -> ruleSets.stream()
            .sorted(java.util.Comparator.comparing(RuleSet::getVersion).reversed()).toList());

        service = new RuleService(ruleMapper, versionMapper, ruleSetMapper, new com.fasterxml.jackson.databind.ObjectMapper());
    }

    private static RuleSaveRequest hitRule(String code, int deduct) {
        return new RuleSaveRequest(code, "话术", "禁用承诺", "平台规则依据第 3 条", "hit", "AGENT", null,
            List.of("保证免息"), List.of(), null, deduct);
    }

    @Test
    @DisplayName("参数校验：扣分越界、类型非法、require 缺配置、角色范围非法")
    void validation() {
        assertThatThrownBy(() -> service.create(hitRule("R1", 101)))
            .isInstanceOf(BizException.class).hasMessageContaining("扣分");
        assertThatThrownBy(() -> service.create(new RuleSaveRequest("R2", null, "n", "d", "fuzzy", "AGENT",
            null, List.of("x"), List.of(), null, 10)))
            .isInstanceOf(BizException.class).hasMessageContaining("规则类型");
        assertThatThrownBy(() -> service.create(new RuleSaveRequest("R3", null, "n", "d", "require", "CUSTOMER",
            null, List.of("x"), List.of("y"), 3, 10)))
            .isInstanceOf(BizException.class).hasMessageContaining("触发角色");
        assertThatThrownBy(() -> service.create(new RuleSaveRequest("R4", null, "n", "d", "require", "CUSTOMER",
            "AGENT", List.of("x"), List.of("y"), 0, 10)))
            .isInstanceOf(BizException.class).hasMessageContaining("窗口");
        assertThatThrownBy(() -> service.create(new RuleSaveRequest("R5", null, "n", "d", "hit", "BOSS",
            null, List.of("x"), List.of(), null, 10)))
            .isInstanceOf(BizException.class).hasMessageContaining("角色范围");
        assertThatThrownBy(() -> service.create(new RuleSaveRequest("R6", null, "n", "d", "hit", "AGENT",
            null, List.of(), List.of(), null, 10)))
            .isInstanceOf(BizException.class).hasMessageContaining("关键词");
        assertThatThrownBy(() -> service.create(new RuleSaveRequest("R7", null, "n", " ", "hit", "AGENT",
            null, List.of("x"), List.of(), null, 10)))
            .isInstanceOf(BizException.class).hasMessageContaining("规则说明");
    }

    @Test
    @DisplayName("创建：首个版本号为 1，编码重复冲突")
    void createRule() {
        RuleResponse created = service.create(hitRule("R-100", 20));

        assertThat(created.version()).isEqualTo(1);
        assertThat(created.enabled()).isTrue();
        assertThat(created.keywords()).containsExactly("保证免息");
        assertThat(versions).hasSize(1);

        assertThatThrownBy(() -> service.create(hitRule("R-100", 20)))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getResultCode())
            .isEqualTo(ResultCode.CONFLICT);
    }

    @Test
    @DisplayName("修改：expectedVersion 落后 409；成功则生成新版本且旧版本保留")
    void updateVersioning() {
        RuleResponse created = service.create(hitRule("R-200", 20));

        assertThatThrownBy(() -> service.update(created.ruleId(), 99L, hitRule("R-200", 50)))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getResultCode())
            .isEqualTo(ResultCode.CONFLICT);

        RuleResponse updated = service.update(created.ruleId(), 1L, hitRule("R-200", 50));
        assertThat(updated.version()).isEqualTo(2);
        assertThat(updated.deduct()).isEqualTo(50);
        assertThat(versions).hasSize(2);
        // 旧版本仍在，历史报告可复算
        assertThat(versions.values()).extracting(AuditRuleVersion::getVersion).containsExactlyInAnyOrder(1, 2);
    }

    @Test
    @DisplayName("RULE-11 基础：规则更新后，旧规则集合快照的判读依据不变")
    void snapshotIsImmutable() {
        RuleResponse created = service.create(hitRule("R-300", 20));
        RuleService.RuleSetSnapshot snapshot = service.snapshotEnabledRules();
        assertThat(snapshot.ruleSetVersion()).isEqualTo(1);
        assertThat(snapshot.rules()).hasSize(1);
        assertThat(snapshot.rules().get(0).deduct()).isEqualTo(20);

        service.update(created.ruleId(), 1L, hitRule("R-300", 60));

        // 旧快照仍按当时版本判读
        assertThat(service.definitionsOf(1L)).hasSize(1);
        assertThat(service.definitionsOf(1L).get(0).deduct()).isEqualTo(20);
        // 新快照拿到的是新版本
        RuleService.RuleSetSnapshot updated = service.snapshotEnabledRules();
        assertThat(updated.rules().get(0).deduct()).isEqualTo(60);
        assertThat(updated.ruleSetVersion()).isEqualTo(2L);
        assertThat(service.definitionsOf(1L).get(0).deduct()).isEqualTo(20);
    }

    @Test
    @DisplayName("启停只影响未来集合：停用后快照不含该规则，历史版本仍在")
    void disabledRuleExcludedFromSnapshot() {
        RuleResponse created = service.create(hitRule("R-400", 20));
        service.setEnabled(created.ruleId(), false);

        RuleService.RuleSetSnapshot snapshot = service.snapshotEnabledRules();

        assertThat(snapshot.rules()).isEmpty();
        assertThat(versions).hasSize(1);
        assertThat(service.get(created.ruleId()).enabled()).isFalse();
    }

    @Test
    @DisplayName("相同内容复用集合快照，规则定义带匹配类型与扣分")
    void snapshotCarriesDefinitions() {
        service.create(new RuleSaveRequest("R-500", "身份", "需自报家门", "平台规则依据第 1 条",
            "must-have", "AGENT", null, List.of("我是"), List.of(), null, 15));

        RuleService.RuleSetSnapshot first = service.snapshotEnabledRules();
        RuleService.RuleSetSnapshot second = service.snapshotEnabledRules();

        assertThat(first.ruleSetVersion()).isEqualTo(1);
        assertThat(second.ruleSetVersion()).isEqualTo(first.ruleSetVersion());
        assertThat(first.rules().get(0).matchType()).isEqualTo(MatchType.MUST_HAVE);
        assertThat(ruleSets).hasSize(1);
    }
}
