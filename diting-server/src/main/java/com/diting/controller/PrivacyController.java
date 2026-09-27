package com.diting.controller;

import com.diting.common.R;
import com.diting.config.LoginUserHolder;
import com.diting.entity.DeletionJob;
import com.diting.service.DeletionService;
import com.diting.service.admin.AuditLogService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 隐私与数据管理（开发文档 §7.2）。
 * 全部抹除：先把本人所有记录写墓碑并立即不可读，再登记清理作业；
 * 查询接口只返回最小状态元数据，不返回已删原文或对象 key。
 */
@RestController
@RequestMapping("/api")
public class PrivacyController {

    private final DeletionService deletionService;
    private final AuditLogService auditLogService;

    public PrivacyController(DeletionService deletionService, AuditLogService auditLogService) {
        this.deletionService = deletionService;
        this.auditLogService = auditLogService;
    }

    @DeleteMapping("/privacy/erase")
    public R<Map<String, Object>> erase() {
        Long userId = LoginUserHolder.getUserId();
        DeletionJob job = deletionService.eraseAll(userId);
        auditLogService.success("USER", userId, AuditLogService.ACTION_ERASE_ALL, "USER",
            String.valueOf(userId), AuditLogService.detail("deletionJobId", job.getId()));
        return R.ok(describe(job));
    }

    @GetMapping("/deletions/{deletionJobId}")
    public R<Map<String, Object>> deletion(@PathVariable Long deletionJobId) {
        Long userId = LoginUserHolder.getUserId();
        return R.ok(describe(deletionService.requireOwnedJob(userId, deletionJobId)));
    }

    /**
     * 只给状态与计数，不给原文、对象 key 或内部错误细节。
     * P2 修复（幽灵 pending）：无原件任务的 remaining 是 '[""]'（空串占位），
     * 旧的逗号计数会数出 1，前端永远显示“还有 1 个对象待清理”。
     * 这里复用与 DeletionService 同口径的解析：空串不计入。
     */
    private static Map<String, Object> describe(DeletionJob job) {
        Map<String, Object> data = new HashMap<>();
        data.put("deletionJobId", job.getId());
        data.put("targetType", job.getTargetType());
        data.put("status", job.getStatus());
        data.put("attemptCount", job.getAttemptCount());
        data.put("pendingObjects", DeletionService.pendingObjects(job.getRemainingObjects()).size());
        data.put("createdAt", job.getCreatedAt() == null ? null : job.getCreatedAt().toString());
        data.put("finishedAt", job.getFinishedAt() == null ? null : job.getFinishedAt().toString());
        return data;
    }
}
