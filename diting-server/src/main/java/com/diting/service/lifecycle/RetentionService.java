package com.diting.service.lifecycle;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.diting.common.BusinessTime;
import com.diting.config.DitingProperties;
import com.diting.entity.DeletionJob;
import com.diting.entity.ExportJob;
import com.diting.entity.RecognitionTask;
import com.diting.entity.ReportVersion;
import com.diting.entity.Sentence;
import com.diting.entity.UploadSession;
import com.diting.entity.Violation;
import com.diting.mapper.DeletionJobMapper;
import com.diting.mapper.ExportJobMapper;
import com.diting.mapper.RecognitionTaskMapper;
import com.diting.mapper.ReportVersionMapper;
import com.diting.mapper.SentenceMapper;
import com.diting.mapper.UploadSessionMapper;
import com.diting.mapper.UserMapper;
import com.diting.mapper.ViolationMapper;
import com.diting.service.admin.AuditLogService;
import com.diting.service.storage.ObjectStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 30 天保留期与清理执行器（开发文档 §5.3、§9、§13）。
 *
 * 三条边界：
 * 1. **可见性先撤销**：删除请求写入墓碑那一刻起就不可读，清理只是把外部对象与明细真正抹掉；
 * 2. **清理失败可重试、可告警**：有限退避重试，超限标 FAILED 并打 ERROR 日志（便于接告警）；
 * 3. **只清原件与产物，不动文本保留策略**：文本/审计保留时长未确认前，不宣称「全部 30 天删除」，
 *    原件到期只做 `audio_purged` 标记与对象删除。
 */
@Slf4j
@Service
public class RetentionService {

    public static final String JOB_QUEUED = "QUEUED";
    public static final String JOB_RUNNING = "RUNNING";
    public static final String JOB_RETRY = "RETRY_WAIT";
    public static final String JOB_COMPLETED = "COMPLETED";
    public static final String JOB_FAILED = "FAILED";

    public static final String TARGET_TASK = "TASK";
    public static final String TARGET_ERASE = "ERASE";

    private final DeletionJobMapper deletionJobMapper;
    private final RecognitionTaskMapper taskMapper;
    private final SentenceMapper sentenceMapper;
    private final com.diting.mapper.SpeakerMappingMapper speakerMappingMapper;
    private final ViolationMapper violationMapper;
    private final ReportVersionMapper reportVersionMapper;
    private final ExportJobMapper exportJobMapper;
    private final UploadSessionMapper uploadSessionMapper;
    private final com.diting.mapper.TaskAssetMapper taskAssetMapper;
    private final UserMapper userMapper;
    private final ObjectStorage objectStorage;
    private final AuditLogService auditLogService;
    private final DitingProperties props;
    private final DeletionCleanupTransaction cleanupTransaction;

    public RetentionService(DeletionJobMapper deletionJobMapper, RecognitionTaskMapper taskMapper,
                            SentenceMapper sentenceMapper,
                            com.diting.mapper.SpeakerMappingMapper speakerMappingMapper,
                            ViolationMapper violationMapper,
                            ReportVersionMapper reportVersionMapper, ExportJobMapper exportJobMapper,
                            UploadSessionMapper uploadSessionMapper,
                            com.diting.mapper.TaskAssetMapper taskAssetMapper, UserMapper userMapper,
                            ObjectStorage objectStorage, AuditLogService auditLogService,
                            DitingProperties props, DeletionCleanupTransaction cleanupTransaction) {
        this.deletionJobMapper = deletionJobMapper;
        this.taskMapper = taskMapper;
        this.sentenceMapper = sentenceMapper;
        this.speakerMappingMapper = speakerMappingMapper;
        this.violationMapper = violationMapper;
        this.reportVersionMapper = reportVersionMapper;
        this.exportJobMapper = exportJobMapper;
        this.uploadSessionMapper = uploadSessionMapper;
        this.taskAssetMapper = taskAssetMapper;
        this.userMapper = userMapper;
        this.objectStorage = objectStorage;
        this.auditLogService = auditLogService;
        this.props = props;
        this.cleanupTransaction = cleanupTransaction;
    }

    /** 一轮清理：作业 → 到期原件 → 孤立上传 → 到期产物 */
    public int tick() {
        int done = 0;
        done += processDeletionJobs();
        done += purgeExpiredOriginals();
        done += cleanupOrphanUploads();
        done += cleanupExpiredArtifacts();
        return done;
    }

    private int processDeletionJobs() {
        int done = 0;
        for (DeletionJob job : deletionJobMapper.selectDue(props.getRetention().getBatchSize())) {
            if (processJob(job)) {
                done++;
            }
        }
        return done;
    }

    /** 领取并执行一个清理作业；失败走有限退避，超限标记 FAILED 并告警 */
    public boolean processJob(DeletionJob job) {
        String token = java.util.UUID.randomUUID().toString();
        LocalDateTime leaseUntil = BusinessTime.now().plusSeconds(props.getRetention().getLeaseSeconds());
        if (deletionJobMapper.claim(job.getId(), token, leaseUntil) == 0) {
            // 已被别的实例领走或状态已变
            return false;
        }
        try {
            // 通过独立 Bean 的事务代理执行；自身 cleanupTask/cleanupUser 的自调用不会开启事务。
            if (cleanupTransaction.run(this, job.getId(), token)) {
                // 已在同一事务把作业挂起到签名失效后，完成状态必须等二次删除才可写。
                return false;
            }
            if (deletionJobMapper.markCompletedRunning(job.getId(), token) != 1) {
                // 本轮租约被用户 rearm 或被其它工作者夺取；不能虚报完成，也不能清新一轮的对象清单。
                return false;
            }
            auditLogService.success("SYSTEM", null, "DELETION_COMPLETED", job.getTargetType(),
                String.valueOf(job.getTargetId()), AuditLogService.detail(
                    "deletionJobId", job.getId(), "attempts", job.getAttemptCount() + 1));
            return true;
        }
        catch (Exception e) {
            int attempts = (job.getAttemptCount() == null ? 0 : job.getAttemptCount()) + 1;
            int maxAttempts = props.getRetention().getMaxAttempts();
            boolean updated;
            if (attempts >= maxAttempts) {
                updated = deletionJobMapper.failClaim(job.getId(), token, JOB_FAILED, null, "CLEANUP_FAILED") == 1;
                if (updated) {
                    log.error("清理作业 {} 连续 {} 次失败，已标记 FAILED：{}", job.getId(), attempts, e.getMessage());
                    auditLogService.record("SYSTEM", null, "DELETION_FAILED", job.getTargetType(),
                        String.valueOf(job.getTargetId()), AuditLogService.RESULT_FAILED,
                        AuditLogService.detail("deletionJobId", job.getId(), "attempts", attempts));
                }
            }
            else {
                long backoff = (long) props.getRetention().getBackoffSeconds() * attempts;
                updated = deletionJobMapper.failClaim(job.getId(), token, JOB_RETRY,
                    BusinessTime.now().plusSeconds(backoff), "CLEANUP_RETRY") == 1;
                if (updated) {
                    log.warn("清理作业 {} 第 {} 次失败，{} 秒后重试：{}", job.getId(), attempts, backoff, e.getMessage());
                }
            }
            // 失败或等待重试都不是清理完成；tick() 不应把作业状态更新计为完成项。
            return false;
        }
    }

    /** 单条任务清理：对象 → 导出产物 → 命中 → 报告版本 → 句子；任务行保留最小墓碑 */
    @Transactional
    public void cleanupTask(Long taskId) {
        RecognitionTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        if (task.getOssObject() != null) {
            safeDelete(task.getOssObject());
        }
        // 图片等资产：对象删掉、行也删掉（资产表是按任务回溯的，留空行没有意义）
        for (com.diting.entity.TaskAsset asset : taskAssetMapper.selectByTask(taskId)) {
            safeDelete(asset.getObjectKey());
        }
        taskAssetMapper.delete(new LambdaQueryWrapper<com.diting.entity.TaskAsset>()
            .eq(com.diting.entity.TaskAsset::getTaskId, taskId));
        List<ReportVersion> versions = reportVersionMapper.selectList(new LambdaQueryWrapper<ReportVersion>()
            .eq(ReportVersion::getTaskId, taskId));
        for (ReportVersion version : versions) {
            List<ExportJob> exports = exportJobMapper.selectList(new LambdaQueryWrapper<ExportJob>()
                .eq(ExportJob::getReportVersionId, version.getId()));
            for (ExportJob export : exports) {
                if (export.getArtifactKey() != null) {
                    safeDelete(export.getArtifactKey());
                }
                exportJobMapper.deleteById(export.getId());
            }
            violationMapper.delete(new LambdaQueryWrapper<Violation>()
                .eq(Violation::getReportVersionId, version.getId()));
        }
        reportVersionMapper.delete(new LambdaQueryWrapper<ReportVersion>()
            .eq(ReportVersion::getTaskId, taskId));
        sentenceMapper.delete(new LambdaQueryWrapper<Sentence>()
            .eq(Sentence::getTaskId, taskId));
        speakerMappingMapper.delete(new LambdaQueryWrapper<com.diting.entity.SpeakerMapping>()
            .eq(com.diting.entity.SpeakerMapping::getTaskId, taskId));
        taskMapper.clearObjectKey(taskId);
        taskMapper.markAudioPurged(taskId);
        // 清理完整完成后才从 DELETING 推进 DELETED；任一步失败交给清理作业重试。
        if (task.getDeletedAt() != null && !"DELETED".equals(task.getStatus())
            && taskMapper.markDeletedAfterCleanup(taskId) != 1) {
            throw new IllegalStateException("删除墓碑状态已变更");
        }
    }

    /** 签发过 PUT 的 key 即使首次删掉也能在到期前被重新上传；最后一次删除必须晚于签名过期。 */
    LocalDateTime activeSignedPutUntil(List<String> objectKeys) {
        if (objectKeys.isEmpty()) {
            return null;
        }
        LocalDateTime now = BusinessTime.now();
        LocalDateTime latest = null;
        for (UploadSession session : uploadSessionMapper.selectList(new LambdaQueryWrapper<UploadSession>()
            .in(UploadSession::getObjectKey, objectKeys))) {
            LocalDateTime safeUntil = session.getSignedUntil() == null
                ? null : session.getSignedUntil().plusMinutes(1); // 留一分钟容纳存储/业务时钟偏差
            if (safeUntil != null && safeUntil.isAfter(now)
                && (latest == null || safeUntil.isAfter(latest))) {
                latest = safeUntil;
            }
        }
        return latest;
    }

    /** 清理任务表/资产表删行后，仍以作业清单再次删除原 key，防迟到 PUT 复活对象。 */
    void deletePendingObjects(List<String> objectKeys) {
        for (String key : objectKeys) {
            safeDelete(key);
        }
    }

    /** 全部抹除：本人所有任务 + 孤立上传；账号做匿名化（删除账号的最终口径待政策确认） */
    @Transactional
    public void cleanupUser(Long userId) {
        List<RecognitionTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<RecognitionTask>()
            .eq(RecognitionTask::getUserId, userId));
        for (RecognitionTask task : tasks) {
            cleanupTask(task.getId());
        }
        List<UploadSession> sessions = uploadSessionMapper.selectList(new LambdaQueryWrapper<UploadSession>()
            .eq(UploadSession::getUserId, userId));
        for (UploadSession session : sessions) {
            safeDelete(session.getObjectKey());
            uploadSessionMapper.deleteById(session.getId());
        }
        // 显式 SQL 匿名化：updateById 会跳过 null 字段，清不掉 PII
        userMapper.anonymize(userId);
    }

    /** 到期原件：删对象 + 标记已清理（文本是否删除另议，不在此处理） */
    private int purgeExpiredOriginals() {
        int done = 0;
        for (RecognitionTask task : taskMapper.selectExpiredOriginals(props.getRetention().getBatchSize())) {
            try {
                // 音频原件（纯图片任务为空）
                if (task.getOssObject() != null) {
                    safeDelete(task.getOssObject());
                    taskMapper.clearObjectKey(task.getId());
                    taskMapper.markAudioPurged(task.getId());
                }
                // 图片原件：与音频同一保留期，一起清理
                purgeAssetObjects(task.getId());
                done++;
            }
            catch (Exception e) {
                log.warn("原件到期清理失败（task {}）：{}", task.getId(), e.getMessage());
            }
        }
        return done;
    }

    /** 删除任务下尚未清理的资产对象并标记已清理（对象已不在时也照样标记，避免反复重试） */
    private void purgeAssetObjects(Long taskId) {
        for (com.diting.entity.TaskAsset asset : taskAssetMapper.selectPurgeable(taskId)) {
            safeDelete(asset.getObjectKey());
            taskAssetMapper.markObjectPurged(asset.getId());
        }
    }

    /** 孤立上传：超过 cleanup_after 仍未登记任务的对象与会话 */
    private int cleanupOrphanUploads() {
        int done = 0;
        for (UploadSession session : uploadSessionMapper.selectOrphans(props.getRetention().getBatchSize())) {
            try {
                safeDelete(session.getObjectKey());
                uploadSessionMapper.deleteById(session.getId());
                done++;
            }
            catch (Exception e) {
                log.warn("孤立上传清理失败（session {}）：{}", session.getId(), e.getMessage());
            }
        }
        return done;
    }

    /** 到期产物：删除对象并清空 key（作业记录保留，便于追溯） */
    private int cleanupExpiredArtifacts() {
        int done = 0;
        for (ExportJob job : exportJobMapper.selectExpiredArtifacts(props.getRetention().getBatchSize())) {
            try {
                safeDelete(job.getArtifactKey());
                exportJobMapper.clearArtifactKey(job.getId());
                done++;
            }
            catch (Exception e) {
                log.warn("到期产物清理失败（export {}）：{}", job.getId(), e.getMessage());
            }
        }
        return done;
    }

    /** 对象删除：不存在视为成功；其它异常向上抛，由作业重试机制处理 */
    private void safeDelete(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return;
        }
        objectStorage.delete(objectKey);
    }

    /** 到期时间：从上传时间起算（开发文档 §9「原件从上传时间保留 30 天」） */
    public LocalDateTime expiresAt(LocalDateTime uploadedAt) {
        return uploadedAt.plusDays(props.getRetention().getAudioDays());
    }
}
