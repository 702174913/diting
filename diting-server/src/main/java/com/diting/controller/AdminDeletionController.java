package com.diting.controller;

import com.diting.common.AdminRole;
import com.diting.common.R;
import com.diting.config.RequireRole;
import com.diting.entity.DeletionJob;
import com.diting.mapper.DeletionJobMapper;
import com.diting.service.admin.AuditLogService;
import com.diting.service.lifecycle.RetentionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 后台清理状态查询（开发文档 §7.3）：仅 ADMIN。
 * 与 C 端一样只返回最小状态元数据：状态、尝试次数、待清理项数，
 * **不返回已删原文或对象 key**。
 */
@RestController
@RequestMapping("/api/admin/deletions")
public class AdminDeletionController {

    private final DeletionJobMapper deletionJobMapper;
    private final AuditLogService auditLogService;

    public AdminDeletionController(DeletionJobMapper deletionJobMapper, AuditLogService auditLogService) {
        this.deletionJobMapper = deletionJobMapper;
        this.auditLogService = auditLogService;
    }

    @GetMapping("/{deletionJobId}")
    @RequireRole(AdminRole.ADMIN)
    public R<Map<String, Object>> status(@PathVariable Long deletionJobId) {
        DeletionJob job = deletionJobMapper.selectById(deletionJobId);
        if (job == null) {
            return R.fail(com.diting.common.ResultCode.NOT_FOUND, "清理作业不存在");
        }
        Map<String, Object> data = new HashMap<>();
        data.put("deletionJobId", job.getId());
        data.put("targetType", job.getTargetType());
        data.put("targetId", job.getTargetId());
        data.put("status", job.getStatus());
        data.put("attemptCount", job.getAttemptCount());
        data.put("errorCode", job.getErrorCode());
        data.put("createdAt", job.getCreatedAt() == null ? null : job.getCreatedAt().toString());
        data.put("finishedAt", job.getFinishedAt() == null ? null : job.getFinishedAt().toString());
        return R.ok(data);
    }
}
