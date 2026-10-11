package com.diting.service.rules;

import com.diting.common.BizException;
import com.diting.common.ResultCode;
import com.diting.dto.LegalClauseResponse;
import com.diting.dto.LegalClauseSaveRequest;
import com.diting.entity.AuditRule;
import com.diting.entity.AuditRuleVersion;
import com.diting.entity.LegalClause;
import com.diting.entity.RuleLegalClause;
import com.diting.mapper.AuditRuleMapper;
import com.diting.mapper.AuditRuleVersionMapper;
import com.diting.mapper.LegalClauseMapper;
import com.diting.mapper.RuleLegalClauseMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 法律条款库（V13）：唯一性、启停、替换式绑定与查询口径。
 * mapper 用内存桩，逻辑用真实实现；绑定不生成规则版本是明确断言点。
 */
class LegalClauseServiceTest {

    private final Map<Long, LegalClause> clauses = new HashMap<>();
    private final Map<Long, AuditRuleVersion> versions = new HashMap<>();
    private final List<RuleLegalClause> bindings = new ArrayList<>();
    private final AtomicLong idSeq = new AtomicLong(500);
    private LegalClauseService service;

    @BeforeEach
    void setUp() {
        // 纯单测里要展开 LambdaQueryWrapper 的 SQL 片段，先注册实体元信息
        var configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, LegalClause.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, RuleLegalClause.class);

        clauses.clear();
        versions.clear();
        bindings.clear();
        idSeq.set(500);

        AuditRule rule = new AuditRule();
        rule.setId(5L);
        rule.setCode("DJ-001");
        rule.setCurrentVersion(1);
        rule.setEnabled(true);

        LegalClauseMapper clauseMapper = mock(LegalClauseMapper.class);
        RuleLegalClauseMapper bindingMapper = mock(RuleLegalClauseMapper.class);
        AuditRuleMapper ruleMapper = mock(AuditRuleMapper.class);
        AuditRuleVersionMapper versionMapper = mock(AuditRuleVersionMapper.class);

        when(clauseMapper.insert(any(LegalClause.class))).thenAnswer(inv -> {
            LegalClause row = inv.getArgument(0);
            row.setId(idSeq.incrementAndGet());
            clauses.put(row.getId(), row);
            return 1;
        });
        when(clauseMapper.selectById(any())).thenAnswer(inv -> clauses.get(inv.getArgument(0)));
        when(clauseMapper.updateById(any(LegalClause.class))).thenAnswer(inv -> {
            LegalClause row = inv.getArgument(0);
            clauses.put(row.getId(), row);
            return 1;
        });
        // 唯一性检查：wrapper 的两个 eq 是 lawName + articleNo，按参数值匹配
        when(clauseMapper.selectOne(any())).thenAnswer(inv -> {
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LegalClause> query = inv.getArgument(0);
            query.getSqlSegment();
            var values = query.getParamNameValuePairs().values();
            return clauses.values().stream()
                .filter(c -> values.contains(c.getLawName()) && values.contains(c.getArticleNo()))
                .findFirst().orElse(null);
        });
        when(clauseMapper.selectBatchIds(any())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            return clauses.values().stream().filter(c -> ids.contains(c.getId())).toList();
        });

        when(bindingMapper.insert(any(RuleLegalClause.class))).thenAnswer(inv -> {
            RuleLegalClause row = inv.getArgument(0);
            row.setId(idSeq.incrementAndGet());
            bindings.add(row);
            return 1;
        });
        when(bindingMapper.delete(any())).thenAnswer(inv -> {
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RuleLegalClause> query = inv.getArgument(0);
            query.getSqlSegment();
            var values = query.getParamNameValuePairs().values();
            int before = bindings.size();
            bindings.removeIf(binding -> matches(values, binding.getRuleId()));
            return before - bindings.size();
        });
        when(bindingMapper.selectList(any())).thenAnswer(inv -> {
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RuleLegalClause> query = inv.getArgument(0);
            query.getSqlSegment();
            var values = query.getParamNameValuePairs().values();
            return bindings.stream()
                .filter(binding -> matches(values, binding.getRuleId()))
                .sorted(Comparator.comparing(RuleLegalClause::getSortOrder)
                    .thenComparing(RuleLegalClause::getId))
                .collect(Collectors.toCollection(ArrayList::new));
        });

        when(ruleMapper.selectById(any()))
            .thenAnswer(inv -> java.util.Objects.equals(5L, inv.getArgument(0)) ? rule : null);

        when(versionMapper.selectBatchIds(any())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            return versions.values().stream().filter(v -> ids.contains(v.getId())).toList();
        });

        service = new LegalClauseService(clauseMapper, bindingMapper, ruleMapper, versionMapper);
    }

    private static boolean matches(Collection<Object> values, Long target) {
        return values.stream().anyMatch(value -> value instanceof Collection<?> collection
            ? collection.contains(target) : target.equals(value));
    }

    private static LegalClauseSaveRequest req(String lawName, String articleNo, String content) {
        return new LegalClauseSaveRequest(lawName, articleNo, content, null, null);
    }

    private LegalClauseResponse create(String lawName, String articleNo, String content, Boolean enabled) {
        return service.create(new LegalClauseSaveRequest(lawName, articleNo, content, null, enabled));
    }

    @Test
    @DisplayName("新增：默认启用；同一法规下条款号重复 409；不同法规同条款号允许")
    void createAndDuplicate() {
        LegalClauseResponse created = create("个人信息保护法", "第十三条", "条款原文", null);
        assertThat(created.enabled()).isTrue();
        assertThat(created.lawName()).isEqualTo("个人信息保护法");
        assertThat(created.articleNo()).isEqualTo("第十三条");

        assertThatThrownBy(() -> create("个人信息保护法", "第十三条", "另一段原文", null))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getResultCode())
            .isEqualTo(ResultCode.CONFLICT);

        LegalClauseResponse other = create("网络安全法", "第十三条", "另一部法规的原文", null);
        assertThat(other.id()).isNotEqualTo(created.id());
    }

    @Test
    @DisplayName("编辑：改文本成功；编辑自身不冲突；停用后 enabled=false 但行仍在")
    void updateAndDisable() {
        LegalClauseResponse created = create("民法典", "第一条", "原文A", null);

        LegalClauseResponse updated = service.update(created.id(),
            new LegalClauseSaveRequest("民法典", "第一条", "原文B", "备注", null));
        assertThat(updated.content()).isEqualTo("原文B");
        assertThat(updated.remark()).isEqualTo("备注");
        assertThat(updated.enabled()).isTrue();

        LegalClauseResponse disabled = service.setEnabled(created.id(), false);
        assertThat(disabled.enabled()).isFalse();
        assertThat(clauses).hasSize(1);
    }

    @Test
    @DisplayName("绑定：替换式生效且保留提交顺序；空列表解绑全部；全程不产生规则版本")
    void bindReplacement() {
        LegalClauseResponse first = create("民法典", "第一条", "原文1", null);
        LegalClauseResponse second = create("民法典", "第二条", "原文2", null);

        List<LegalClauseResponse> bound = service.bindRuleClauses(5L, List.of(second.id(), first.id()));
        assertThat(bound).extracting(LegalClauseResponse::id).containsExactly(second.id(), first.id());
        assertThat(bindings).hasSize(2);
        assertThat(bindings.stream().map(RuleLegalClause::getSortOrder)).containsExactly(0, 1);

        List<LegalClauseResponse> replaced = service.bindRuleClauses(5L, List.of(first.id()));
        assertThat(replaced).hasSize(1);
        assertThat(bindings).hasSize(1);
        assertThat(bindings.get(0).getClauseId()).isEqualTo(first.id());

        service.bindRuleClauses(5L, List.of());
        assertThat(bindings).isEmpty();
        // 绑定不生成规则新版本：版本表始终为空
        assertThat(versions).isEmpty();
    }

    @Test
    @DisplayName("绑定校验：规则不存在 404；条款不存在 422；停用条款不能绑定 422")
    void bindValidation() {
        assertThatThrownBy(() -> service.bindRuleClauses(9L, List.of()))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getResultCode())
            .isEqualTo(ResultCode.NOT_FOUND);

        LegalClauseResponse disabled = create("个人信息保护法", "第二十九条", "单独同意条款", false);
        assertThatThrownBy(() -> service.bindRuleClauses(5L, List.of(disabled.id())))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getResultCode())
            .isEqualTo(ResultCode.UNPROCESSABLE);
        assertThatThrownBy(() -> service.bindRuleClauses(5L, List.of(999L)))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("不存在");
        assertThat(bindings).isEmpty();
    }

    @Test
    @DisplayName("查询：批量取规则绑定按顺序；版本 id 经规则回溯到当前绑定")
    void queries() {
        LegalClauseResponse first = create("民法典", "第一条", "原文1", null);
        LegalClauseResponse second = create("民法典", "第二条", "原文2", null);
        service.bindRuleClauses(5L, List.of(first.id(), second.id()));

        Map<Long, List<LegalClauseResponse>> byRule = service.clausesByRuleIds(List.of(5L));
        assertThat(byRule.get(5L)).extracting(LegalClauseResponse::id).containsExactly(first.id(), second.id());
        assertThat(service.clausesByRuleIds(List.of(404L))).isEmpty();

        AuditRuleVersion version = new AuditRuleVersion();
        version.setId(77L);
        version.setRuleId(5L);
        versions.put(77L, version);
        Map<Long, List<LegalClauseResponse>> byVersion = service.clausesByRuleVersionIds(List.of(77L));
        assertThat(byVersion.get(77L)).hasSize(2);
    }
}
