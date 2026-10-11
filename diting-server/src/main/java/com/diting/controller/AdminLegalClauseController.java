package com.diting.controller;

import com.diting.common.AdminRole;
import com.diting.common.PageQuery;
import com.diting.common.PageResult;
import com.diting.common.R;
import com.diting.config.RequireRole;
import com.diting.dto.LegalClauseResponse;
import com.diting.dto.LegalClauseSaveRequest;
import com.diting.service.admin.AuditLogService;
import com.diting.service.rules.LegalClauseService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 法律条款库（V13）。读：ADMIN/OPERATOR；写：仅 ADMIN。
 * 删除即停用：已有规则绑定与历史报告引用不受影响。
 */
@RestController
@RequestMapping("/api/admin/legal-clauses")
public class AdminLegalClauseController {

    private final LegalClauseService legalClauseService;
    private final AuditLogService auditLogService;

    public AdminLegalClauseController(LegalClauseService legalClauseService, AuditLogService auditLogService) {
        this.legalClauseService = legalClauseService;
        this.auditLogService = auditLogService;
    }

    @GetMapping
    @RequireRole({AdminRole.ADMIN, AdminRole.OPERATOR})
    public R<PageResult<LegalClauseResponse>> list(@RequestParam(required = false) Integer page,
                                                   @RequestParam(required = false) Integer pageSize,
                                                   @RequestParam(required = false) String keyword,
                                                   @RequestParam(required = false) Boolean enabled) {
        return R.ok(legalClauseService.list(PageQuery.of(page, pageSize), keyword, enabled));
    }

    @GetMapping("/{id}")
    @RequireRole({AdminRole.ADMIN, AdminRole.OPERATOR})
    public R<LegalClauseResponse> get(@PathVariable Long id) {
        return R.ok(legalClauseService.get(id));
    }

    @PostMapping
    @RequireRole(AdminRole.ADMIN)
    public R<LegalClauseResponse> create(@Valid @RequestBody LegalClauseSaveRequest req) {
        LegalClauseResponse created = legalClauseService.create(req);
        audit("LEGAL_CLAUSE_CREATE", created);
        return R.ok(created);
    }

    @PutMapping("/{id}")
    @RequireRole(AdminRole.ADMIN)
    public R<LegalClauseResponse> update(@PathVariable Long id, @Valid @RequestBody LegalClauseSaveRequest req) {
        LegalClauseResponse updated = legalClauseService.update(id, req);
        audit("LEGAL_CLAUSE_UPDATE", updated);
        return R.ok(updated);
    }

    @DeleteMapping("/{id}")
    @RequireRole(AdminRole.ADMIN)
    public R<LegalClauseResponse> disable(@PathVariable Long id) {
        LegalClauseResponse disabled = legalClauseService.setEnabled(id, false);
        audit("LEGAL_CLAUSE_DISABLE", disabled);
        return R.ok(disabled);
    }

    /** 审计只记法规名/条款号/状态这类公开元数据 */
    private void audit(String action, LegalClauseResponse clause) {
        var actor = com.diting.config.LoginUserHolder.get();
        auditLogService.success(actor.domain().name(), actor.id(), action, "LEGAL_CLAUSE",
            String.valueOf(clause.id()), AuditLogService.detail(
                "lawName", clause.lawName(),
                "articleNo", clause.articleNo(),
                "enabled", clause.enabled()));
    }
}
