package com.diting.controller;

import com.diting.common.AdminRole;
import com.diting.common.PageQuery;
import com.diting.common.PageResult;
import com.diting.common.R;
import com.diting.config.RequireRole;
import com.diting.entity.AuditLog;
import com.diting.service.admin.AuditLogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 审计日志查询（开发文档 §7.3）：仅 ADMIN。
 * 返回的是元数据（动作/目标/结果/时间），不含对话原文与签名链接。
 */
@RestController
@RequestMapping("/api/admin/audit-logs")
public class AdminAuditController {

    private final AuditLogService auditLogService;

    public AdminAuditController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    public record AuditItem(Long id, String actorDomain, Long actorId, String action, String targetType,
                            String targetId, String result, Map<String, Object> detail, String createdAt) {
    }

    @GetMapping
    @RequireRole(AdminRole.ADMIN)
    public R<PageResult<AuditItem>> list(@RequestParam(required = false) Integer page,
                                        @RequestParam(required = false) Integer pageSize,
                                        @RequestParam(required = false) String action,
                                        @RequestParam(required = false) String actorDomain) {
        PageResult<AuditLog> result = auditLogService.list(PageQuery.of(page, pageSize), action, actorDomain);
        List<AuditItem> items = result.items().stream()
            .map(row -> new AuditItem(row.getId(), row.getActorDomain(), row.getActorId(), row.getAction(),
                row.getTargetType(), row.getTargetId(), row.getResult(),
                auditLogService.detailOf(row),
                row.getCreatedAt() == null ? null : row.getCreatedAt().toString()))
            .toList();
        return R.ok(PageResult.of(items, result.total(), PageQuery.of(page, pageSize)));
    }
}
