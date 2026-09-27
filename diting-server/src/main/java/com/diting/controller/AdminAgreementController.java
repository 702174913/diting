package com.diting.controller;

import com.diting.common.AdminRole;
import com.diting.common.R;
import com.diting.config.RequireRole;
import com.diting.entity.Agreement;
import com.diting.service.AgreementService;
import com.diting.service.admin.AuditLogService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 后台协议配置（S19）：读 ADMIN/OPERATOR（运营需要看到线上版本），
 * 写仅 ADMIN（与策略设置一致），每次保存记审计。
 */
@RestController
@RequestMapping("/api/admin/agreements")
public class AdminAgreementController {

    private final AgreementService agreementService;
    private final AuditLogService auditLogService;

    public AdminAgreementController(AgreementService agreementService, AuditLogService auditLogService) {
        this.agreementService = agreementService;
        this.auditLogService = auditLogService;
    }

    @GetMapping
    @RequireRole({AdminRole.ADMIN, AdminRole.OPERATOR})
    public R<List<Map<String, Object>>> list() {
        return R.ok(agreementService.current().stream()
            .map(a -> Map.<String, Object>of(
                "key", a.getAgreementKey(),
                "title", a.getTitle(),
                "version", a.getVersion(),
                "contentHtml", a.getContentHtml()))
            .toList());
    }

    public record AgreementSaveRequest(
        @NotBlank(message = "协议标题不能为空") String title,
        @NotBlank(message = "协议正文不能为空") String contentHtml
    ) {
    }

    @PutMapping("/{key}")
    @RequireRole(AdminRole.ADMIN)
    public R<Map<String, Object>> save(@PathVariable String key,
                                       @Valid @RequestBody AgreementSaveRequest req) {
        Agreement saved = agreementService.save(key, req.title(), req.contentHtml());
        var actor = com.diting.config.LoginUserHolder.get();
        auditLogService.success(actor.domain().name(), actor.id(), AuditLogService.ACTION_CONFIG_SAVE,
            "AGREEMENTS", saved.getAgreementKey(), AuditLogService.detail(
                "key", saved.getAgreementKey(),
                "version", saved.getVersion(),
                "title", saved.getTitle()));
        return R.ok(Map.of(
            "key", saved.getAgreementKey(),
            "title", saved.getTitle(),
            "version", saved.getVersion(),
            "contentHtml", saved.getContentHtml()));
    }
}
