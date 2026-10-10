package com.diting.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.diting.common.AdminRole;
import com.diting.common.BizException;
import com.diting.common.BusinessTime;
import com.diting.common.PageQuery;
import com.diting.common.PageResult;
import com.diting.common.R;
import com.diting.common.ResultCode;
import com.diting.config.LoginUserHolder;
import com.diting.config.RequireRole;
import com.diting.dto.ReportDetailResponse;
import com.diting.entity.RecognitionTask;
import com.diting.entity.ReportVersion;
import com.diting.mapper.RecognitionTaskMapper;
import com.diting.service.DeletionService;
import com.diting.service.OssService;
import com.diting.service.admin.AuditLogService;
import com.diting.service.admin.RedactionService;
import com.diting.service.report.ReportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URL;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * 后台录音资产（开发文档 §4.2、§7.3、§9）。
 *
 * 权限口径（后端强制，前端菜单只是可见性）：
 * - ADMIN：可读个体报告、可签发**未过期原件**、可删除；
 * - OPERATOR：可读**脱敏**报告，拿不到原件；
 * - INVESTOR：连这个控制器都进不来（无个体数据权限）。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/recordings")
public class AdminRecordingController {

    /** 原件签发链接的有效期（分钟） */
    private static final int AUDIO_TTL_MINUTES = 15;

    private final RecognitionTaskMapper taskMapper;
    private final ReportService reportService;
    private final DeletionService deletionService;
    private final OssService ossService;
    private final AuditLogService auditLogService;

    public AdminRecordingController(RecognitionTaskMapper taskMapper, ReportService reportService,
                                    DeletionService deletionService, OssService ossService,
                                    AuditLogService auditLogService) {
        this.taskMapper = taskMapper;
        this.reportService = reportService;
        this.deletionService = deletionService;
        this.ossService = ossService;
        this.auditLogService = auditLogService;
    }

    public record RecordingItem(Long id, Long userId, String status, Integer durationSec,
                                String createdAt, boolean audioAvailable) {
    }

    /** 录音资产分页：按用户、状态、时间筛选；已删除记录不出现 */
    @GetMapping
    @RequireRole({AdminRole.ADMIN, AdminRole.OPERATOR})
    public R<PageResult<RecordingItem>> list(@RequestParam(required = false) Integer page,
                                            @RequestParam(required = false) Integer pageSize,
                                            @RequestParam(required = false) Long userId,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) String from,
                                            @RequestParam(required = false) String to) {
        PageQuery query = PageQuery.of(page, pageSize);
        LambdaQueryWrapper<RecognitionTask> wrapper = new LambdaQueryWrapper<RecognitionTask>()
            .isNull(RecognitionTask::getDeletedAt)
            .eq(userId != null, RecognitionTask::getUserId, userId)
            .eq(status != null && !status.isBlank(), RecognitionTask::getStatus,
                status == null ? null : status.trim().toUpperCase(java.util.Locale.ROOT));
        // 注意：条件表达式的参数会先求值，日期解析必须放进分支里，否则空参数会直接 500
        if (from != null && !from.isBlank()) {
            wrapper.ge(RecognitionTask::getCreatedAt, startOf(from));
        }
        if (to != null && !to.isBlank()) {
            wrapper.lt(RecognitionTask::getCreatedAt, endOf(to));
        }
        wrapper.orderByDesc(RecognitionTask::getCreatedAt);
        Page<RecognitionTask> result = taskMapper.selectPage(Page.of(query.page(), query.pageSize()), wrapper);
        List<RecordingItem> items = result.getRecords().stream()
            .map(task -> new RecordingItem(task.getId(), task.getUserId(), task.getStatus(),
                task.getDurationSec(),
                task.getCreatedAt() == null ? null : task.getCreatedAt().toString(),
                audioAvailable(task)))
            .toList();
        return R.ok(PageResult.of(items, result.getTotal(), query));
    }

    /** 详情：ADMIN 看原文；OPERATOR 看脱敏文本（掩码等长，高亮区间不错位） */
    @GetMapping("/{id}")
    @RequireRole({AdminRole.ADMIN, AdminRole.OPERATOR})
    public R<ReportDetailResponse> detail(@PathVariable Long id,
                                          @RequestParam(required = false) Long version) {
        RecognitionTask task = requireVisible(id);
        ReportDetailResponse detail = reportService.detail(task, version);
        if (LoginUserHolder.get().role() == AdminRole.ADMIN) {
            return R.ok(detail);
        }
        return R.ok(redact(detail));
    }

    /** 原件签发：仅 ADMIN；过期/已清理/已删除一律拒绝；审计只记签发事实，不记链接 */
    @GetMapping("/{id}/audio")
    @RequireRole(AdminRole.ADMIN)
    public R<Map<String, Object>> audio(@PathVariable Long id) {
        RecognitionTask task = requireVisible(id);
        if (!audioAvailable(task)) {
            throw new BizException(ResultCode.AUDIO_EXPIRED, "原始录音已过保留期或已清理");
        }
        // 账实一致：链接真实 TTL 与返回/审计的 ttlMinutes 必须是同一个值
        URL url = ossService.presignDownload(task.getOssObject(),
            java.time.Duration.ofMinutes(AUDIO_TTL_MINUTES));
        var actor = LoginUserHolder.get();
        auditLogService.success(actor.domain().name(), actor.id(), AuditLogService.ACTION_AUDIO_PRESIGN,
            "TASK", String.valueOf(id), AuditLogService.detail(
                "ttlMinutes", AUDIO_TTL_MINUTES,
                "audioPurged", Boolean.TRUE.equals(task.getAudioPurged())));
        return R.ok(Map.of(
            "url", url.toString(),
            "expiresAt", BusinessTime.now().plusMinutes(AUDIO_TTL_MINUTES).toString(),
            "ttlMinutes", AUDIO_TTL_MINUTES));
    }

    /**
     * 重新判读：仅 ADMIN。按**当前启用的规则集**重算并发布新报告版本，旧版本与旧证据一律保留。
     *
     * 用途：规则改版（或修好判读口径）后把历史记录按新规则刷一遍——
     * 不改文本、不改归属、不重跑听悟，只重算判读；重复调用会持续追加版本号。
     * 状态门禁在 `ReportAnalysisService.publish` 里（未确认角色时允许从 COMPLETED 重判）。
     */
    @PostMapping("/{id}/reanalyze")
    @RequireRole(AdminRole.ADMIN)
    public R<Map<String, Object>> reanalyze(@PathVariable Long id) {
        RecognitionTask task = requireVisible(id);
        ReportVersion version = reportService.analyzeWithoutRoles(task);
        var actor = LoginUserHolder.get();
        auditLogService.success(actor.domain().name(), actor.id(), AuditLogService.ACTION_REPORT_REANALYZE,
            "TASK", String.valueOf(id), AuditLogService.detail(
                "reportVersion", version.getVersion(),
                "ruleSetVersion", version.getRuleSetVersion() == null ? "-" : version.getRuleSetVersion(),
                "riskScore", version.getRiskScore() == null ? "-" : version.getRiskScore()));
        // 用 LinkedHashMap：分数与规则集版本理论上不为空，但 Map.of 遇到 null 会 NPE，能把一次成功的重判变成 500
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("reportVersion", version.getVersion());
        payload.put("riskScore", version.getRiskScore());
        payload.put("complianceScore", version.getComplianceScore());
        payload.put("ruleSetVersion", version.getRuleSetVersion());
        return R.ok(payload);
    }

    /** 后台删除：仅 ADMIN；写墓碑 + 登记清理作业 + 审计 */
    @DeleteMapping("/{id}")
    @RequireRole(AdminRole.ADMIN)
    public R<Map<String, Object>> delete(@PathVariable Long id) {
        RecognitionTask task = requireVisible(id);
        var actor = LoginUserHolder.get();
        var job = deletionService.deleteTask(task.getUserId(), id);
        auditLogService.success(actor.domain().name(), actor.id(), AuditLogService.ACTION_TASK_DELETE,
            "TASK", String.valueOf(id), AuditLogService.detail(
                "deletionJobId", job.getId(), "ownerUserId", task.getUserId()));
        return R.ok(Map.of("deletionJobId", job.getId(), "status", job.getStatus()));
    }

    private RecognitionTask requireVisible(Long id) {
        RecognitionTask task = taskMapper.selectById(id);
        if (task == null || task.getDeletedAt() != null) {
            throw new BizException(ResultCode.NOT_FOUND, "记录不存在");
        }
        return task;
    }

    static boolean audioAvailable(RecognitionTask task) {
        return !Boolean.TRUE.equals(task.getAudioPurged())
            && task.getOssObject() != null
            && (task.getExpiresAt() == null || task.getExpiresAt().isAfter(BusinessTime.now()));
    }

    /** 等长掩码：区间仍然有效，前端不需要重算偏移 */
    private static ReportDetailResponse redact(ReportDetailResponse detail) {
        List<com.diting.dto.SentenceResponse> sentences = detail.sentences().stream()
            .map(sentence -> new com.diting.dto.SentenceResponse(
                sentence.id(), sentence.sequence(), sentence.sourceAssetId(), sentence.speakerId(), sentence.role(),
                sentence.startMs(), sentence.endMs(), RedactionService.redact(sentence.text()),
                sentence.speechSpeed(), sentence.highlights(), sentence.ruleVersionIds(),
                sentence.notes().stream().map(RedactionService::redact).toList()))
            .toList();
        List<com.diting.dto.ViolationResponse> violations = detail.violations().stream()
            .map(v -> new com.diting.dto.ViolationResponse(v.id(), v.ruleVersionId(),
                RedactionService.redact(v.ruleName()), v.matchType(), v.scope(), v.sentenceId(),
                v.triggerSentenceId(), v.evidenceSentenceIds(), v.ranges(), v.deduct(),
                RedactionService.redact(v.note())))
            .toList();
        return new ReportDetailResponse(detail.id(), detail.taskId(), detail.reportNo(), detail.taskStatus(),
            detail.durationSec(), detail.createdAt(), detail.reportVersionId(), detail.reportVersion(),
            detail.status(), detail.latestStatus(), detail.mappingRevision(), detail.speakerMapping(),
            detail.rolesConfirmed(), detail.sentenceCount(), detail.flaggedCount(), detail.violationCount(),
            detail.riskScore(), detail.complianceScore(), detail.threshold(), detail.riskFlagged(),
            detail.publishedAt(), detail.audioAvailable(), true,
            detail.notes().stream().map(RedactionService::redact).toList(), sentences, violations);
    }

    private static LocalDateTime startOf(String date) {
        return LocalDate.parse(date).atStartOfDay();
    }

    private static LocalDateTime endOf(String date) {
        return LocalDateTime.of(LocalDate.parse(date).plusDays(1), LocalTime.MIN);
    }
}
