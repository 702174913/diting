package com.diting.controller;

import com.diting.common.AdminRole;
import com.diting.common.R;
import com.diting.config.RequireRole;
import com.diting.dto.PolicyConfigSaveRequest;
import com.diting.service.ConfigService;
import com.diting.service.admin.AuditLogService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 策略配置（开发文档 §7.3）。
 * 读取：ADMIN/OPERATOR 只读；保存：仅 ADMIN，且必须带 expectedVersion 防覆盖（冲突 409）。
 * 审计写入属 S10，本轮只做权限与版本控制。
 */
@RestController
@RequestMapping("/api/admin/config")
public class AdminConfigController {

    private final ConfigService configService;
    private final AuditLogService auditLogService;

    public AdminConfigController(ConfigService configService, AuditLogService auditLogService) {
        this.configService = configService;
        this.auditLogService = auditLogService;
    }

    @GetMapping
    @RequireRole({AdminRole.ADMIN, AdminRole.OPERATOR})
    public R<Map<String, Object>> get() {
        ConfigService.PolicyConfig config = configService.load();
        return R.ok(Map.of(
            "version", config.version(),
            "dailyFreeQuota", config.dailyFreeQuota(),
            "riskThreshold", config.riskThreshold(),
            "maxRecordingMinutes", config.maxRecordingMinutes(),
            "businessDate", configService.businessDate().toString()
        ));
    }

    @PostMapping
    @RequireRole(AdminRole.ADMIN)
    public R<Map<String, Object>> save(@Valid @RequestBody PolicyConfigSaveRequest req) {
        ConfigService.PolicyConfig config = configService.save(req.expectedVersion(),
            req.dailyFreeQuota(), req.riskThreshold(), req.maxRecordingMinutes());
        var actor = com.diting.config.LoginUserHolder.get();
        auditLogService.success(actor.domain().name(), actor.id(), AuditLogService.ACTION_CONFIG_SAVE,
            "APP_CONFIG", String.valueOf(config.version()), AuditLogService.detail(
                "version", config.version(),
                "dailyFreeQuota", config.dailyFreeQuota(),
                "riskThreshold", config.riskThreshold(),
                "maxRecordingMinutes", config.maxRecordingMinutes()));
        return R.ok(Map.of(
            "version", config.version(),
            "dailyFreeQuota", config.dailyFreeQuota(),
            "riskThreshold", config.riskThreshold(),
            "maxRecordingMinutes", config.maxRecordingMinutes()
        ));
    }
}
