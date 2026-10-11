package com.diting.controller;

import com.diting.common.AdminRole;
import com.diting.common.PageQuery;
import com.diting.common.PageResult;
import com.diting.common.R;
import com.diting.config.RequireRole;
import com.diting.dto.LegalClauseResponse;
import com.diting.dto.RuleImportApply;
import com.diting.dto.RuleImportPrecheck;
import com.diting.dto.RuleLegalClauseBindRequest;
import com.diting.dto.RuleResponse;
import com.diting.dto.RuleSaveRequest;
import com.diting.service.rules.RuleImportService;
import com.diting.service.rules.RuleModels;
import com.diting.service.rules.RuleService;
import com.diting.service.admin.AuditLogService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 规则库（开发文档 §7.3）。
 * 读：ADMIN/OPERATOR；写：仅 ADMIN。修改必须带 expectedVersion，冲突 409。
 * 删除即停用：历史版本与历史报告不受影响。
 */
@RestController
@RequestMapping("/api/admin/rules")
public class AdminRuleController {

    private final RuleService ruleService;
    private final RuleImportService ruleImportService;
    private final AuditLogService auditLogService;

    public AdminRuleController(RuleService ruleService, RuleImportService ruleImportService,
                               AuditLogService auditLogService) {
        this.ruleService = ruleService;
        this.ruleImportService = ruleImportService;
        this.auditLogService = auditLogService;
    }

    /** 导入请求体：直接用文本传 CSV，避免为一次导入引入 multipart 解析 */
    public record RuleImportRequest(@NotBlank(message = "导入内容不能为空") String content) {
    }

    public record RuleUpdateRequest(
        @NotNull(message = "expectedVersion 不能为空") Long expectedVersion,
        @Valid @NotNull(message = "规则内容不能为空") RuleSaveRequest rule
    ) {
    }

    @GetMapping
    @RequireRole({AdminRole.ADMIN, AdminRole.OPERATOR})
    public R<PageResult<RuleResponse>> list(@RequestParam(required = false) Integer page,
                                            @RequestParam(required = false) Integer pageSize,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(required = false) Boolean enabled) {
        return R.ok(ruleService.list(PageQuery.of(page, pageSize), keyword, enabled));
    }

    @GetMapping("/{id}")
    @RequireRole({AdminRole.ADMIN, AdminRole.OPERATOR})
    public R<RuleResponse> get(@PathVariable Long id) {
        return R.ok(ruleService.get(id));
    }

    /** 版本历史（新到旧）：历史报告引用的是某一版，改规则只会追加新行 */
    @GetMapping("/{id}/versions")
    @RequireRole({AdminRole.ADMIN, AdminRole.OPERATOR})
    public R<List<RuleResponse>> versions(@PathVariable Long id) {
        return R.ok(ruleService.versions(id));
    }

    /**
     * 批量导入预检：只看不写。
     * 先把「新增几行 / 改版几行 / 哪一行报错」摆出来，确认后才落库。
     */
    @PostMapping("/import/precheck")
    @RequireRole(AdminRole.ADMIN)
    public R<RuleImportPrecheck> precheckImport(@Valid @RequestBody RuleImportRequest req) {
        var actor = com.diting.config.LoginUserHolder.get();
        RuleImportPrecheck report = ruleImportService.precheck(req.content());
        auditLogService.success(actor.domain().name(), actor.id(), "RULE_IMPORT_PRECHECK", "AUDIT_RULE", "-",
            AuditLogService.detail("total", report.total(), "create", report.createCount(),
                "update", report.updateCount(), "error", report.errorCount()));
        return R.ok(report);
    }

    /**
     * 批量导入落库：重新解析（不信前端传来的预检结果）后逐行落库。
     * 同 code 走乐观锁追加新版本；单行失败不影响其他行，失败行连同原因返回。
     */
    @PostMapping("/import")
    @RequireRole(AdminRole.ADMIN)
    public R<RuleImportApply> applyImport(@Valid @RequestBody RuleImportRequest req) {
        var actor = com.diting.config.LoginUserHolder.get();
        RuleImportApply result = ruleImportService.apply(req.content());
        auditLogService.success(actor.domain().name(), actor.id(), "RULE_IMPORT", "AUDIT_RULE", "-",
            AuditLogService.detail("created", result.created(), "updated", result.updated(),
                "failed", result.failed()));
        return R.ok(result);
    }

    @PostMapping
    @RequireRole(AdminRole.ADMIN)
    public R<RuleResponse> create(@Valid @RequestBody RuleSaveRequest req) {
        RuleResponse created = ruleService.create(req);
        audit("RULE_CREATE", created);
        return R.ok(created);
    }

    @PutMapping("/{id}")
    @RequireRole(AdminRole.ADMIN)
    public R<RuleResponse> update(@PathVariable Long id, @Valid @RequestBody RuleUpdateRequest req) {
        RuleResponse updated = ruleService.update(id, req.expectedVersion(), req.rule());
        audit("RULE_UPDATE", updated);
        return R.ok(updated);
    }

    @DeleteMapping("/{id}")
    @RequireRole(AdminRole.ADMIN)
    public R<RuleResponse> disable(@PathVariable Long id) {
        RuleResponse disabled = ruleService.setEnabled(id, false);
        audit("RULE_DISABLE", disabled);
        return R.ok(disabled);
    }

    /**
     * 绑定法律条款（V13）：替换式提交，空数组解绑全部。
     * 绑定实时生效、不生成规则新版本，故不参与 expectedVersion 乐观锁。
     */
    @PutMapping("/{id}/legal-clauses")
    @RequireRole(AdminRole.ADMIN)
    public R<List<LegalClauseResponse>> bindLegalClauses(@PathVariable Long id,
                                                         @Valid @RequestBody RuleLegalClauseBindRequest req) {
        List<LegalClauseResponse> bound = ruleService.bindLegalClauses(id, req.clauseIds());
        var actor = com.diting.config.LoginUserHolder.get();
        auditLogService.success(actor.domain().name(), actor.id(), AuditLogService.ACTION_RULE_LEGAL_CLAUSE_BIND,
            "AUDIT_RULE", String.valueOf(id), AuditLogService.detail(
                "clauseCount", bound.size(),
                "clauseIds", bound.stream().map(LegalClauseResponse::id).toList()));
        return R.ok(bound);
    }

    /** 规则变更留痕：只记编码/版本/扣分这类元数据，不记关键词以外的自由文本 */
    private void audit(String action, RuleResponse rule) {
        var actor = com.diting.config.LoginUserHolder.get();
        auditLogService.success(actor.domain().name(), actor.id(), action, "AUDIT_RULE",
            String.valueOf(rule.ruleId()), AuditLogService.detail(
                "code", rule.code(),
                "version", rule.version(),
                "matchType", rule.matchType(),
                "enabled", rule.enabled()));
    }

    /** 冻结当前启用规则的集合快照（分析时会自动冻结，这里供运营核对） */
    @PostMapping("/snapshot")
    @RequireRole(AdminRole.ADMIN)
    public R<Map<String, Object>> snapshot() {
        RuleService.RuleSetSnapshot snapshot = ruleService.snapshotEnabledRules();
        List<Map<String, Object>> rules = snapshot.rules().stream()
            .map(rule -> Map.<String, Object>of(
                "ruleId", rule.ruleId(),
                "ruleVersionId", rule.ruleVersionId(),
                "name", rule.name(),
                "matchType", rule.matchType().value(),
                "deduct", rule.deduct()))
            .toList();
        return R.ok(Map.of("ruleSetVersion", snapshot.ruleSetVersion(), "rules", rules));
    }
}
