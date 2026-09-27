package com.diting.controller;

import com.diting.common.AdminRole;
import com.diting.common.BizException;
import com.diting.common.R;
import com.diting.common.ResultCode;
import com.diting.config.LoginUserHolder;
import com.diting.config.RequireRole;
import com.diting.entity.ExportJob;
import com.diting.entity.RecognitionTask;
import com.diting.service.RecognitionTaskService;
import com.diting.service.admin.AuditLogService;
import com.diting.service.export.ExportService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 导出接口（开发文档 §7.2、§7.3）。
 * C 端与后台是**独立路由、独立身份域**，共用同一套服务；后台 token 不能调 C 端路由，
 * 反之亦然（由身份域拦截器强制）。
 */
@RestController
public class ExportController {

    private final ExportService exportService;
    private final RecognitionTaskService taskService;
    private final AuditLogService auditLogService;

    public ExportController(ExportService exportService, RecognitionTaskService taskService,
                            AuditLogService auditLogService) {
        this.exportService = exportService;
        this.taskService = taskService;
        this.auditLogService = auditLogService;
    }

    public record CreateRequest(@NotNull(message = "reportVersionId 不能为空") Long reportVersionId,
                                Boolean withAudio) {
    }

    // ---------- C 端 ----------

    @PostMapping("/api/report/{id}/exports")
    public R<Map<String, Object>> createForUser(@PathVariable Long id,
                                                @Valid @RequestBody CreateRequest req) {
        Long userId = LoginUserHolder.getUserId();
        RecognitionTask task = taskService.requireOwned(id, userId);
        ExportJob job = exportService.create(ExportService.DOMAIN_USER, userId, task.getId(),
            req.reportVersionId(), Boolean.TRUE.equals(req.withAudio()));
        auditLogService.success("USER", userId, "EXPORT_CREATE", "TASK", String.valueOf(id),
            AuditLogService.detail("exportId", job.getId(), "withAudio", job.getWithAudio()));
        return R.ok(describe(job));
    }

    @GetMapping("/api/exports/{exportId}")
    public R<Map<String, Object>> getForUser(@PathVariable Long exportId) {
        Long userId = LoginUserHolder.getUserId();
        ExportJob job = exportService.requireOwnedJob(ExportService.DOMAIN_USER, userId, exportId);
        return R.ok(describe(job));
    }

    /**
     * C 端下载：显式签发短时下载地址并留痕（P0-2 修复）。
     * 状态查询不再签发、不写审计，轮询不会产生审计噪声；
     * 只有用户真正点下载时才签发一次、记一条 EXPORT_DOWNLOAD。
     */
    @GetMapping("/api/exports/{exportId}/download")
    public R<Map<String, Object>> downloadForUser(@PathVariable Long exportId) {
        Long userId = LoginUserHolder.getUserId();
        ExportJob job = exportService.requireOwnedJob(ExportService.DOMAIN_USER, userId, exportId);
        String url = exportService.presignArtifact(job);
        auditLogService.success(ExportService.DOMAIN_USER, userId, "EXPORT_DOWNLOAD", "EXPORT",
            String.valueOf(job.getId()), AuditLogService.detail("fileType", exportService.fileType(job)));
        return R.ok(downloadPayload(job, url));
    }

    // ---------- 后台 ----------

    @PostMapping("/api/admin/recordings/{id}/exports")
    @RequireRole(AdminRole.ADMIN)
    public R<Map<String, Object>> createForAdmin(@PathVariable Long id,
                                                 @Valid @RequestBody CreateRequest req) {
        var actor = LoginUserHolder.get();
        boolean withAudio = Boolean.TRUE.equals(req.withAudio());
        // 后台导出仅 ADMIN（开发文档 §7.3/§9）：withAudio 校验保留作纵深防御
        if (withAudio && actor.role() != AdminRole.ADMIN) {
            throw new BizException(ResultCode.FORBIDDEN, "只有管理员可以导出原始录音");
        }
        RecognitionTask task = taskService.requireVisibleForAdmin(id);
        ExportJob job = exportService.create(ExportService.DOMAIN_ADMIN, actor.id(), task.getId(),
            req.reportVersionId(), withAudio);
        auditLogService.success(actor.domain().name(), actor.id(), "EXPORT_CREATE", "TASK",
            String.valueOf(id), AuditLogService.detail("exportId", job.getId(),
                "withAudio", job.getWithAudio()));
        return R.ok(describe(job));
    }

    @GetMapping("/api/admin/exports/{exportId}")
    @RequireRole(AdminRole.ADMIN)
    public R<Map<String, Object>> getForAdmin(@PathVariable Long exportId) {
        var actor = LoginUserHolder.get();
        ExportJob job = exportService.requireOwnedJob(ExportService.DOMAIN_ADMIN, actor.id(), exportId);
        return R.ok(describe(job));
    }

    /**
     * 后台下载：显式签发并留痕（与 C 端同理，状态轮询不再产生审计）。
     */
    @GetMapping("/api/admin/exports/{exportId}/download")
    @RequireRole(AdminRole.ADMIN)
    public R<Map<String, Object>> downloadForAdmin(@PathVariable Long exportId) {
        var actor = LoginUserHolder.get();
        ExportJob job = exportService.requireOwnedJob(ExportService.DOMAIN_ADMIN, actor.id(), exportId);
        String url = exportService.presignArtifact(job);
        auditLogService.success(actor.domain().name(), actor.id(), "EXPORT_DOWNLOAD", "EXPORT",
            String.valueOf(job.getId()), AuditLogService.detail("fileType", exportService.fileType(job)));
        return R.ok(downloadPayload(job, url));
    }

    /**
     * 状态查询：纯状态，不签发下载地址、不写审计（P0-2 修复）。
     * 轮询每 2-3 秒一次，若每次都签发并写审计，审计表会被轮询噪声淹没；
     * 下载地址只由 /download 接口显式签发。
     */
    private Map<String, Object> describe(ExportJob job) {
        Map<String, Object> data = new HashMap<>();
        data.put("exportId", job.getId());
        data.put("status", job.getStatus());
        data.put("withAudio", Boolean.TRUE.equals(job.getWithAudio()));
        data.put("fileType", exportService.fileType(job));
        data.put("errorCode", job.getErrorCode());
        data.put("createdAt", job.getCreatedAt() == null ? null : job.getCreatedAt().toString());
        data.put("expiresAt", job.getExpiresAt() == null ? null : job.getExpiresAt().toString());
        return data;
    }

    /** 下载载荷：短时地址与期限；审计只记签发事实，不记链接本身 */
    private Map<String, Object> downloadPayload(ExportJob job, String url) {
        Map<String, Object> data = new HashMap<>();
        data.put("exportId", job.getId());
        data.put("fileType", exportService.fileType(job));
        data.put("downloadUrl", url);
        data.put("expiresAt", job.getExpiresAt() == null ? null : job.getExpiresAt().toString());
        return data;
    }
}
