package com.diting.service.rules;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.diting.common.BizException;
import com.diting.common.BusinessTime;
import com.diting.common.PageQuery;
import com.diting.common.PageResult;
import com.diting.common.ResultCode;
import com.diting.dto.LegalClauseResponse;
import com.diting.dto.RuleResponse;
import com.diting.dto.RuleSaveRequest;
import com.diting.entity.AuditRule;
import com.diting.entity.AuditRuleVersion;
import com.diting.entity.RuleSet;
import com.diting.mapper.AuditRuleMapper;
import com.diting.mapper.AuditRuleVersionMapper;
import com.diting.mapper.RuleSetMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * 规则库：CRUD + 版本 + 集合快照（开发文档 §6.3、§7.3、§8）。
 *
 * 版本语义：
 * - 每次修改生成**新版本**，历史版本不删除、不覆盖；
 * - 启停只改 audit_rules.enabled，**只影响未来**的规则集合，历史报告引用的版本不受影响；
 * - 一次分析固定用一份规则集合快照（rule_sets），因此规则更新不会改变历史报告的判读结果。
 */
@Service
public class RuleService {

    private static final List<String> ROLE_SCOPES = List.of("ANY", "AGENT", "CUSTOMER", "UNKNOWN");

    private final AuditRuleMapper ruleMapper;
    private final AuditRuleVersionMapper versionMapper;
    private final RuleSetMapper ruleSetMapper;
    private final LegalClauseService legalClauseService;
    private final ObjectMapper json;

    public RuleService(AuditRuleMapper ruleMapper, AuditRuleVersionMapper versionMapper,
                       RuleSetMapper ruleSetMapper, LegalClauseService legalClauseService,
                       ObjectMapper json) {
        this.ruleMapper = ruleMapper;
        this.versionMapper = versionMapper;
        this.ruleSetMapper = ruleSetMapper;
        this.legalClauseService = legalClauseService;
        this.json = json;
    }

    public PageResult<RuleResponse> list(PageQuery query, String keyword, Boolean enabled) {
        LambdaQueryWrapper<AuditRule> wrapper = new LambdaQueryWrapper<AuditRule>()
            .like(keyword != null && !keyword.isBlank(), AuditRule::getCode, keyword)
            .eq(enabled != null, AuditRule::getEnabled, enabled)
            .orderByAsc(AuditRule::getId);
        Page<AuditRule> page = ruleMapper.selectPage(Page.of(query.page(), query.pageSize()), wrapper);
        // 条款绑定按规则批量取，列表不逐行查（N+1）
        Map<Long, List<LegalClauseResponse>> clauses = legalClauseService
            .clausesByRuleIds(page.getRecords().stream().map(AuditRule::getId).toList());
        List<RuleResponse> items = page.getRecords().stream()
            .map(rule -> toResponse(rule, currentVersion(rule), clauses.getOrDefault(rule.getId(), List.of())))
            .toList();
        return PageResult.of(items, page.getTotal(), query);
    }

    public RuleResponse get(Long ruleId) {
        return toResponse(requireRule(ruleId));
    }

    /**
     * 版本历史（新到旧）。
     * 修改只会插入新的版本行，历史行永不改写 —— 这是「改规则不影响历史报告」能成立的前提。
     * 注意：enabled 是 audit_rules 上的当前状态，表结构本就不对状态做版本化，所以历史行里它回不去。
     */
    public List<RuleResponse> versions(Long ruleId) {
        AuditRule rule = requireRule(ruleId);
        List<AuditRuleVersion> rows = versionMapper.selectList(new LambdaQueryWrapper<AuditRuleVersion>()
            .eq(AuditRuleVersion::getRuleId, ruleId)
            .orderByDesc(AuditRuleVersion::getVersion));
        // 版本历史不返回条款绑定：绑定不属于版本快照（V13 口径）
        return rows.stream().map(row -> toResponse(rule, row, List.of())).toList();
    }

    /** 按编码查当前规则：批量导入预检靠它判断一行是「新增」还是「改版」 */
    public RuleResponse findByCode(String code) {
        AuditRule rule = ruleMapper.selectOne(new LambdaQueryWrapper<AuditRule>()
            .eq(AuditRule::getCode, code));
        return rule == null ? null : toResponse(rule);
    }

    @Transactional
    public RuleResponse create(RuleSaveRequest req) {
        assertValid(req);
        String code = req.code().trim();
        if (ruleMapper.selectOne(new LambdaQueryWrapper<AuditRule>().eq(AuditRule::getCode, code)) != null) {
            throw new BizException(ResultCode.CONFLICT, "规则编码已存在：" + code);
        }
        LocalDateTime now = BusinessTime.now();
        AuditRule rule = new AuditRule();
        rule.setCode(code);
        rule.setCurrentVersion(1);
        rule.setEnabled(true);
        rule.setCreatedAt(now);
        rule.setUpdatedAt(now);
        ruleMapper.insert(rule);

        AuditRuleVersion version = newVersion(rule.getId(), 1, req, now);
        versionMapper.insert(version);
        return toResponse(rule, version, List.of());
    }

    /** 修改规则：带 expectedVersion 的乐观锁，冲突返回 409，并生成新版本 */
    @Transactional
    public RuleResponse update(Long ruleId, long expectedVersion, RuleSaveRequest req) {
        assertValid(req);
        AuditRule rule = requireRule(ruleId);
        if (expectedVersion != rule.getCurrentVersion()) {
            throw new BizException(ResultCode.CONFLICT,
                "规则已被修改（当前版本 " + rule.getCurrentVersion() + "），请刷新后重试");
        }
        int nextVersion = rule.getCurrentVersion() + 1;
        LocalDateTime now = BusinessTime.now();
        AuditRuleVersion version = newVersion(ruleId, nextVersion, req, now);
        versionMapper.insert(version);

        // 版本号用条件更新推进，避免两个并发修改都写成功
        AuditRule patch = new AuditRule();
        patch.setId(ruleId);
        patch.setCurrentVersion(nextVersion);
        patch.setUpdatedAt(now);
        int updated = ruleMapper.update(patch, new LambdaQueryWrapper<AuditRule>()
            .eq(AuditRule::getId, ruleId)
            .eq(AuditRule::getCurrentVersion, rule.getCurrentVersion()));
        if (updated == 0) {
            throw new BizException(ResultCode.CONFLICT, "规则版本推进冲突，请重试");
        }
        rule.setCurrentVersion(nextVersion);
        return toResponse(rule, version, legalClauseService.clausesOfRule(ruleId));
    }

    /**
     * 绑定法律条款（V13）：替换式，空列表解绑全部。
     * 绑定实时生效、**不生成规则新版本**——不碰 audit_rule_versions 与 current_version。
     */
    @Transactional
    public List<LegalClauseResponse> bindLegalClauses(Long ruleId, List<Long> clauseIds) {
        return legalClauseService.bindRuleClauses(ruleId, clauseIds);
    }

    /** 启用/停用：只影响未来的规则集合，历史版本与历史报告保持不变 */
    @Transactional
    public RuleResponse setEnabled(Long ruleId, boolean enabled) {
        AuditRule rule = requireRule(ruleId);
        rule.setEnabled(enabled);
        rule.setUpdatedAt(BusinessTime.now());
        ruleMapper.updateById(rule);
        return toResponse(rule);
    }

    /**
     * 冻结当前启用规则的集合快照：返回快照版本与规则定义。
     * 分析时必须先取快照，后续规则改动不会影响这次判读（也让历史报告可复算）。
     * P2 修复：同一规则集合内容复用同一快照行（按 rule_version_ids 查找），
     * 否则每次角色确认都插一行，快照表无界膨胀。
     */
    @Transactional
    public RuleSetSnapshot snapshotEnabledRules() {
        List<AuditRule> enabled = ruleMapper.selectList(new LambdaQueryWrapper<AuditRule>()
            .eq(AuditRule::getEnabled, true)
            .orderByAsc(AuditRule::getId));
        List<AuditRuleVersion> versions = new ArrayList<>();
        for (AuditRule rule : enabled) {
            AuditRuleVersion version = versionMapper.selectOne(new LambdaQueryWrapper<AuditRuleVersion>()
                .eq(AuditRuleVersion::getRuleId, rule.getId())
                .eq(AuditRuleVersion::getVersion, rule.getCurrentVersion()));
            if (version != null) {
                versions.add(version);
            }
        }

        String content = toJson(versions.stream().map(AuditRuleVersion::getId).toList());
        RuleSet existing = ruleSetMapper.selectOne(new LambdaQueryWrapper<RuleSet>()
            .eq(RuleSet::getRuleVersionIds, content)
            .orderByDesc(RuleSet::getVersion)
            .last("LIMIT 1"));
        if (existing != null) {
            return new RuleSetSnapshot(existing.getVersion(),
                versions.stream().map(this::toDefinition).toList());
        }

        long nextVersion = nextRuleSetVersion();
        RuleSet ruleSet = new RuleSet();
        ruleSet.setVersion(nextVersion);
        ruleSet.setRuleVersionIds(content);
        ruleSet.setCreatedAt(BusinessTime.now());
        ruleSetMapper.insert(ruleSet);

        return new RuleSetSnapshot(nextVersion, versions.stream().map(this::toDefinition).toList());
    }

    /** 按快照版本取回规则定义（历史报告复算用） */
    public List<RuleModels.RuleDefinition> definitionsOf(long ruleSetVersion) {
        RuleSet ruleSet = ruleSetMapper.selectOne(new LambdaQueryWrapper<RuleSet>()
            .eq(RuleSet::getVersion, ruleSetVersion));
        if (ruleSet == null) {
            throw new BizException(ResultCode.NOT_FOUND, "规则集合版本不存在：" + ruleSetVersion);
        }
        List<Long> ids = readJsonLongs(ruleSet.getRuleVersionIds());
        List<RuleModels.RuleDefinition> definitions = new ArrayList<>();
        for (Long id : ids) {
            AuditRuleVersion version = versionMapper.selectById(id);
            if (version != null) {
                definitions.add(toDefinition(version));
            }
        }
        return definitions;
    }

    /** 规则版本 id → 名称与匹配类型（报告展示用，避免前端再查一次） */
    public Map<Long, RuleVersionInfo> ruleVersions(java.util.Collection<Long> versionIds) {
        if (versionIds == null || versionIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, RuleVersionInfo> info = new java.util.LinkedHashMap<>();
        List<AuditRuleVersion> rows = versionMapper.selectBatchIds(versionIds);
        for (AuditRuleVersion row : rows) {
            info.put(row.getId(), new RuleVersionInfo(row.getName(), row.getMatchType(), row.getRoleScope()));
        }
        return info;
    }

    /** 展示用的规则版本摘要 */
    public record RuleVersionInfo(String name, String matchType, String roleScope) {
    }

    private long nextRuleSetVersion() {
        List<RuleSet> rows = ruleSetMapper.selectList(new LambdaQueryWrapper<RuleSet>()
            .orderByDesc(RuleSet::getVersion).last("LIMIT 1"));
        return rows.isEmpty() || rows.get(0).getVersion() == null ? 1L : rows.get(0).getVersion() + 1;
    }

    private AuditRule requireRule(Long ruleId) {
        AuditRule rule = ruleMapper.selectById(ruleId);
        if (rule == null) {
            throw new BizException(ResultCode.NOT_FOUND, "规则不存在");
        }
        return rule;
    }

    /**
     * 业务校验：返回全部问题（空表示通过）。
     * assertValid 与批量导入预检共用同一份规则 —— 否则导入会变成第二套校验，迟早与单条编辑不一致。
     */
    List<String> validate(RuleSaveRequest req) {
        List<String> problems = new ArrayList<>();
        if (req.code() == null || req.code().isBlank() || req.code().trim().length() > 64) {
            problems.add("规则编码需为 1-64 位");
        }
        if (req.name() == null || req.name().isBlank()) {
            problems.add("规则名称不能为空");
        }
        if (req.description() == null || req.description().isBlank()) {
            problems.add("规则说明不能为空（要写清平台规则依据）");
        }
        MatchType type = MatchType.of(req.matchType());
        if (type == null) {
            problems.add("规则类型只能是 " + String.join("/", MatchType.allowedValues()));
        }
        String scope = req.roleScope() == null ? "ANY" : req.roleScope().trim().toUpperCase(Locale.ROOT);
        if (!ROLE_SCOPES.contains(scope)) {
            problems.add("角色范围只能是 " + String.join("/", ROLE_SCOPES));
        }
        if (req.deduct() == null || req.deduct() < 0 || req.deduct() > 100) {
            problems.add("扣分需为 0-100 的整数");
        }
        if (clean(req.keywords()).isEmpty()) {
            problems.add("至少需要一个关键词");
        }
        if (type == MatchType.REQUIRE) {
            String triggerRole = req.triggerRole() == null ? null : req.triggerRole().trim().toUpperCase(Locale.ROOT);
            // ANY：任意说话人说出触发词即可（目标角色功能隐藏期间，编辑器固定下发 ANY）
            if (triggerRole == null || !ROLE_SCOPES.contains(triggerRole)) {
                problems.add("require 规则必须指定触发角色（可以是 " + String.join("/", ROLE_SCOPES) + "）");
            }
            if (req.windowSize() == null || req.windowSize() < 1 || req.windowSize() > 20) {
                problems.add("require 窗口需为 1-20 条目标角色发言");
            }
            if (clean(req.expectedKeywords()).isEmpty()) {
                problems.add("require 规则必须配置应答词");
            }
        }
        return problems;
    }

    private void assertValid(RuleSaveRequest req) {
        List<String> problems = validate(req);
        if (!problems.isEmpty()) {
            throw new BizException(ResultCode.UNPROCESSABLE, problems.get(0));
        }
    }

    private AuditRuleVersion newVersion(Long ruleId, int version, RuleSaveRequest req, LocalDateTime now) {
        AuditRuleVersion row = new AuditRuleVersion();
        row.setRuleId(ruleId);
        row.setVersion(version);
        row.setGroupName(req.groupName() == null || req.groupName().isBlank() ? "默认分组" : req.groupName().trim());
        row.setName(req.name().trim());
        row.setDescription(req.description().trim());
        row.setMatchType(MatchType.of(req.matchType()).value());
        row.setRoleScope(req.roleScope() == null || req.roleScope().isBlank()
            ? "ANY" : req.roleScope().trim().toUpperCase(Locale.ROOT));
        row.setTriggerRole(req.triggerRole() == null || req.triggerRole().isBlank()
            ? null : req.triggerRole().trim().toUpperCase(Locale.ROOT));
        row.setKeywords(toJson(clean(req.keywords())));
        row.setExpectedKeywords(toJson(clean(req.expectedKeywords())));
        row.setWindowSize(req.windowSize());
        row.setDeduct(req.deduct());
        row.setCreatedAt(now);
        return row;
    }

    private static List<String> clean(List<String> values) {
        return values == null ? List.of() : values.stream()
            .filter(v -> v != null && !v.isBlank())
            .map(String::trim)
            .distinct()
            .toList();
    }

    private AuditRuleVersion currentVersion(AuditRule rule) {
        return versionMapper.selectOne(new LambdaQueryWrapper<AuditRuleVersion>()
            .eq(AuditRuleVersion::getRuleId, rule.getId())
            .eq(AuditRuleVersion::getVersion, rule.getCurrentVersion()));
    }

    private RuleResponse toResponse(AuditRule rule) {
        return toResponse(rule, currentVersion(rule), legalClauseService.clausesOfRule(rule.getId()));
    }

    private RuleResponse toResponse(AuditRule rule, AuditRuleVersion version, List<LegalClauseResponse> legalClauses) {
        return new RuleResponse(
            rule.getId(),
            rule.getCode(),
            version == null ? rule.getCurrentVersion() : version.getVersion(),
            version == null ? null : version.getGroupName(),
            version == null ? null : version.getName(),
            version == null ? null : version.getDescription(),
            version == null ? null : version.getMatchType(),
            version == null ? null : version.getRoleScope(),
            version == null ? null : version.getTriggerRole(),
            version == null ? List.of() : fromJson(version.getKeywords()),
            version == null ? List.of() : fromJson(version.getExpectedKeywords()),
            version == null ? null : version.getWindowSize(),
            version == null ? 0 : version.getDeduct(),
            Boolean.TRUE.equals(rule.getEnabled()),
            rule.getCreatedAt(),
            rule.getUpdatedAt(),
            legalClauses == null ? List.of() : legalClauses
        );
    }

    private RuleModels.RuleDefinition toDefinition(AuditRuleVersion version) {
        return new RuleModels.RuleDefinition(
            version.getRuleId(),
            version.getId(),
            null,
            version.getGroupName(),
            version.getName(),
            version.getDescription(),
            MatchType.of(version.getMatchType()),
            version.getRoleScope(),
            version.getTriggerRole(),
            readJson(version.getKeywords()),
            readJson(version.getExpectedKeywords()),
            version.getWindowSize(),
            version.getDeduct() == null ? 0 : version.getDeduct()
        );
    }

    private String toJson(List<?> values) {
        try {
            return json.writeValueAsString(values == null ? List.of() : values);
        }
        catch (Exception e) {
            throw new IllegalStateException("规则字段序列化失败", e);
        }
    }

    private List<String> fromJson(String value) {
        return readJson(value);
    }

    private List<Long> readJsonLongs(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(value, new TypeReference<List<Long>>() {
            });
        }
        catch (Exception e) {
            return List.of();
        }
    }

    private List<String> readJson(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(value, new TypeReference<List<String>>() {
            });
        }
        catch (Exception e) {
            return List.of();
        }
    }

    /** 集合快照结果：版本号 + 本次生效的规则定义 */
    public record RuleSetSnapshot(long ruleSetVersion, List<RuleModels.RuleDefinition> rules) {
    }
}
