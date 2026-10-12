package com.diting.service.rules;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.diting.common.BizException;
import com.diting.common.BusinessTime;
import com.diting.common.PageQuery;
import com.diting.common.PageResult;
import com.diting.common.ResultCode;
import com.diting.dto.LegalClauseResponse;
import com.diting.dto.LegalClauseSaveRequest;
import com.diting.entity.AuditRuleVersion;
import com.diting.entity.LegalClause;
import com.diting.entity.RuleLegalClause;
import com.diting.mapper.AuditRuleMapper;
import com.diting.mapper.AuditRuleVersionMapper;
import com.diting.mapper.LegalClauseMapper;
import com.diting.mapper.RuleLegalClauseMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 法律条款库与规则绑定（V13）。
 *
 * 语义与边界：
 * - 条款主数据：法规名称 + 条款号（同法规下唯一）+ 原文；停用不物理删除，
 *   已绑定与历史报告引用不因停用消失；
 * - 绑定按 audit_rules.id（非版本）存储：修改绑定实时生效，**不生成规则新版本**，
 *   历史报告重读时看到的是当前绑定（已确认口径，与规则版本的不可变语义不同）；
 * - 绑定为替换式：一次提交给出完整列表，空列表即全部解绑；
 * - 只允许绑定「存在且启用」的条款，停用条款不能被新绑定。
 */
@Service
public class LegalClauseService {

    private final LegalClauseMapper clauseMapper;
    private final RuleLegalClauseMapper bindingMapper;
    private final AuditRuleMapper ruleMapper;
    private final AuditRuleVersionMapper versionMapper;

    public LegalClauseService(LegalClauseMapper clauseMapper, RuleLegalClauseMapper bindingMapper,
                              AuditRuleMapper ruleMapper, AuditRuleVersionMapper versionMapper) {
        this.clauseMapper = clauseMapper;
        this.bindingMapper = bindingMapper;
        this.ruleMapper = ruleMapper;
        this.versionMapper = versionMapper;
    }

    public PageResult<LegalClauseResponse> list(PageQuery query, String keyword, Boolean enabled) {
        boolean hasKeyword = keyword != null && !keyword.isBlank();
        LambdaQueryWrapper<LegalClause> wrapper = new LambdaQueryWrapper<LegalClause>()
            .eq(enabled != null, LegalClause::getEnabled, enabled)
            .and(hasKeyword, nested -> nested
                .like(LegalClause::getLawName, keyword)
                .or().like(LegalClause::getArticleNo, keyword)
                .or().like(LegalClause::getContent, keyword))
            .orderByAsc(LegalClause::getLawName)
            .orderByAsc(LegalClause::getArticleNo)
            .orderByAsc(LegalClause::getId);
        Page<LegalClause> page = clauseMapper.selectPage(Page.of(query.page(), query.pageSize()), wrapper);
        return PageResult.of(page.getRecords().stream().map(this::toResponse).toList(), page.getTotal(), query);
    }

    public LegalClauseResponse get(Long id) {
        return toResponse(requireClause(id));
    }

    @Transactional
    public LegalClauseResponse create(LegalClauseSaveRequest req) {
        String lawName = req.lawName().trim();
        String articleNo = req.articleNo().trim();
        assertUnique(lawName, articleNo, null);
        LocalDateTime now = BusinessTime.now();
        LegalClause clause = new LegalClause();
        clause.setLawName(lawName);
        clause.setArticleNo(articleNo);
        clause.setContent(req.content().trim());
        clause.setRemark(blankToNull(req.remark()));
        // 新建默认启用；enabled 传 false 才建为停用态
        clause.setEnabled(!Boolean.FALSE.equals(req.enabled()));
        clause.setCreatedAt(now);
        clause.setUpdatedAt(now);
        clauseMapper.insert(clause);
        return toResponse(clause);
    }

    /** 编辑条款只能改文本与状态，不能改 id；重复的法规+条款号返回 409 */
    @Transactional
    public LegalClauseResponse update(Long id, LegalClauseSaveRequest req) {
        LegalClause clause = requireClause(id);
        String lawName = req.lawName().trim();
        String articleNo = req.articleNo().trim();
        assertUnique(lawName, articleNo, id);
        // 显式 SET 保证空备注写入 NULL；未提交 enabled 时不覆盖并发启停。
        clauseMapper.update(null, new LambdaUpdateWrapper<LegalClause>()
            .eq(LegalClause::getId, clause.getId())
            .set(LegalClause::getLawName, lawName)
            .set(LegalClause::getArticleNo, articleNo)
            .set(LegalClause::getContent, req.content().trim())
            .set(LegalClause::getRemark, blankToNull(req.remark()))
            .set(req.enabled() != null, LegalClause::getEnabled, req.enabled())
            .set(LegalClause::getUpdatedAt, BusinessTime.now()));
        return get(id);
    }

    /** 停用/启用：已有绑定与历史报告引用不变 */
    @Transactional
    public LegalClauseResponse setEnabled(Long id, boolean enabled) {
        requireClause(id);
        // 只改状态，不能把读到的旧原文、法规名或备注回写覆盖并发编辑。
        clauseMapper.update(null, new LambdaUpdateWrapper<LegalClause>()
            .eq(LegalClause::getId, id)
            .set(LegalClause::getEnabled, enabled)
            .set(LegalClause::getUpdatedAt, BusinessTime.now()));
        return get(id);
    }

    /**
     * 替换式绑定：提交完整条款 id 列表（空 = 解绑全部）。
     * 只校验「规则存在 + 条款存在且启用」；不推进规则版本、不写 audit_rule_versions。
     */
    @Transactional
    public List<LegalClauseResponse> bindRuleClauses(Long ruleId, List<Long> clauseIds) {
        if (ruleMapper.selectById(ruleId) == null) {
            throw new BizException(ResultCode.NOT_FOUND, "规则不存在");
        }
        List<Long> wanted = clauseIds == null ? List.of() : clauseIds.stream()
            .filter(Objects::nonNull).distinct().toList();
        Map<Long, LegalClause> byId = wanted.isEmpty() ? Map.of() : clauseMapper.selectBatchIds(wanted).stream()
            .collect(Collectors.toMap(LegalClause::getId, clause -> clause));
        List<String> missing = wanted.stream().filter(id -> !byId.containsKey(id))
            .map(String::valueOf).toList();
        if (!missing.isEmpty()) {
            throw new BizException(ResultCode.UNPROCESSABLE, "法律条款不存在：" + String.join("、", missing));
        }
        List<String> disabled = wanted.stream().map(byId::get)
            .filter(clause -> !Boolean.TRUE.equals(clause.getEnabled()))
            .map(LegalClause::getLawName).distinct().toList();
        if (!disabled.isEmpty()) {
            throw new BizException(ResultCode.UNPROCESSABLE,
                "法律条款已停用，不能绑定：" + String.join("、", disabled));
        }

        bindingMapper.delete(new LambdaQueryWrapper<RuleLegalClause>()
            .eq(RuleLegalClause::getRuleId, ruleId));
        LocalDateTime now = BusinessTime.now();
        int order = 0;
        for (Long clauseId : wanted) {
            RuleLegalClause binding = new RuleLegalClause();
            binding.setRuleId(ruleId);
            binding.setClauseId(clauseId);
            binding.setSortOrder(order++);
            binding.setCreatedAt(now);
            bindingMapper.insert(binding);
        }
        return wanted.stream().map(byId::get).map(this::toResponse).toList();
    }

    public List<LegalClauseResponse> clausesOfRule(Long ruleId) {
        return clausesByRuleIds(List.of(ruleId)).getOrDefault(ruleId, List.of());
    }

    /** 批量取规则绑定（列表页避免 N+1）：ruleId → 条款列表（按绑定顺序） */
    public Map<Long, List<LegalClauseResponse>> clausesByRuleIds(Collection<Long> ruleIds) {
        if (ruleIds == null || ruleIds.isEmpty()) {
            return Map.of();
        }
        List<RuleLegalClause> bindings = bindingMapper.selectList(new LambdaQueryWrapper<RuleLegalClause>()
            .in(RuleLegalClause::getRuleId, ruleIds)
            .orderByAsc(RuleLegalClause::getSortOrder)
            .orderByAsc(RuleLegalClause::getId));
        if (bindings.isEmpty()) {
            return Map.of();
        }
        Map<Long, LegalClause> clauses = clauseMapper
            .selectBatchIds(bindings.stream().map(RuleLegalClause::getClauseId).distinct().toList())
            .stream().collect(Collectors.toMap(LegalClause::getId, clause -> clause));
        Map<Long, List<LegalClauseResponse>> result = new LinkedHashMap<>();
        for (RuleLegalClause binding : bindings) {
            LegalClause clause = clauses.get(binding.getClauseId());
            if (clause == null) {
                continue; // 条款行缺失（仅异常运维场景）：跳过，不造空引用
            }
            result.computeIfAbsent(binding.getRuleId(), key -> new ArrayList<>()).add(toResponse(clause));
        }
        return result;
    }

    /** 报告展示用：ruleVersionId → 所绑条款（经版本行回到规则，再查当前绑定） */
    public Map<Long, List<LegalClauseResponse>> clausesByRuleVersionIds(Collection<Long> versionIds) {
        if (versionIds == null || versionIds.isEmpty()) {
            return Map.of();
        }
        List<AuditRuleVersion> versions = versionMapper.selectBatchIds(versionIds);
        Map<Long, Long> ruleByVersion = new LinkedHashMap<>();
        for (AuditRuleVersion version : versions) {
            ruleByVersion.put(version.getId(), version.getRuleId());
        }
        Map<Long, List<LegalClauseResponse>> byRule = clausesByRuleIds(
            ruleByVersion.values().stream().distinct().toList());
        Map<Long, List<LegalClauseResponse>> result = new LinkedHashMap<>();
        ruleByVersion.forEach((versionId, ruleId) ->
            result.put(versionId, byRule.getOrDefault(ruleId, List.of())));
        return result;
    }

    private LegalClause requireClause(Long id) {
        LegalClause clause = clauseMapper.selectById(id);
        if (clause == null) {
            throw new BizException(ResultCode.NOT_FOUND, "法律条款不存在");
        }
        return clause;
    }

    private void assertUnique(String lawName, String articleNo, Long selfId) {
        LegalClause existing = clauseMapper.selectOne(new LambdaQueryWrapper<LegalClause>()
            .eq(LegalClause::getLawName, lawName)
            .eq(LegalClause::getArticleNo, articleNo));
        if (existing != null && !existing.getId().equals(selfId)) {
            throw new BizException(ResultCode.CONFLICT,
                "同一法规下条款号已存在：" + lawName + " " + articleNo);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private LegalClauseResponse toResponse(LegalClause clause) {
        return new LegalClauseResponse(
            clause.getId(),
            clause.getLawName(),
            clause.getArticleNo(),
            clause.getContent(),
            clause.getRemark(),
            Boolean.TRUE.equals(clause.getEnabled()),
            clause.getCreatedAt(),
            clause.getUpdatedAt()
        );
    }
}
