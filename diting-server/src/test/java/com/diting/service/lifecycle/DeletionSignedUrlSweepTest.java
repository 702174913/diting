package com.diting.service.lifecycle;

import com.diting.common.BusinessTime;
import com.diting.config.DitingProperties;
import com.diting.entity.DeletionJob;
import com.diting.entity.UploadSession;
import com.diting.mapper.*;
import com.diting.service.admin.AuditLogService;
import com.diting.service.storage.InMemoryObjectStorage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 内存对象存储模拟迟到 PUT；调度到期条件/事务提交仍须在真实 MySQL/OSS 上验收。 */
class DeletionSignedUrlSweepTest {
    @Test
    void queuedAndRearmedJobsCannotRunBeforePersistedSignatureDeadline() throws Exception {
        var due = DeletionJobMapper.class.getMethod("selectDue", int.class)
            .getAnnotation(org.apache.ibatis.annotations.Select.class);
        var claim = DeletionJobMapper.class.getMethod("claim", Long.class, String.class, LocalDateTime.class)
            .getAnnotation(org.apache.ibatis.annotations.Update.class);
        var rearm = DeletionJobMapper.class.getMethod("rearm", Long.class, String.class)
            .getAnnotation(org.apache.ibatis.annotations.Update.class);
        assertThat(String.join("", due.value())).contains("status = 'QUEUED' AND (next_attempt_at IS NULL");
        assertThat(String.join("", claim.value())).contains("status='QUEUED' AND (next_attempt_at IS NULL");
        assertThat(String.join("", rearm.value())).contains("error_code='SIGNED_URL_PENDING'");
    }

    @Test
    void storageFailureKeepsJobUnfinishedAndRetriesAlreadyDeletedKeysIdempotently() {
        String first = "uploads/7/first.m4a";
        String second = "uploads/7/second.m4a";
        AtomicBoolean failOnce = new AtomicBoolean(true);
        InMemoryObjectStorage storage = new InMemoryObjectStorage("http://memory-storage.test") {
            @Override public void delete(String key) {
                if (second.equals(key) && failOnce.getAndSet(false)) {
                    throw new IllegalStateException("simulated storage timeout");
                }
                super.delete(key);
            }
        };
        storage.putObject(first, new byte[]{1}, "audio/mp4");
        storage.putObject(second, new byte[]{2}, "audio/mp4");
        DeletionJob job = new DeletionJob();
        job.setId(3L); job.setUserId(7L); job.setTargetType("ERASE");
        job.setTargetId(7L); job.setStatus("RUNNING"); job.setAttemptCount(0);
        job.setRemainingObjects("[\"" + first + "\",\"" + second + "\"]");
        DeletionJobMapper jobs = mock(DeletionJobMapper.class);
        when(jobs.claim(eq(3L), anyString(), any())).thenReturn(1);
        when(jobs.lockClaimed(eq(3L), anyString())).thenReturn(job);
        when(jobs.failClaim(eq(3L), anyString(), eq("RETRY_WAIT"), any(), eq("CLEANUP_RETRY")))
            .thenReturn(1);
        when(jobs.markCompletedRunning(eq(3L), anyString())).thenReturn(1);
        RetentionService service = new RetentionService(jobs, mock(RecognitionTaskMapper.class),
            mock(SentenceMapper.class), mock(SpeakerMappingMapper.class), mock(ViolationMapper.class),
            mock(ReportVersionMapper.class), mock(ExportJobMapper.class), mock(UploadSessionMapper.class),
            mock(TaskAssetMapper.class), mock(UserMapper.class), storage, mock(AuditLogService.class),
            new DitingProperties(), new DeletionCleanupTransaction(jobs), mock(OrphanUploadCleanup.class));

        assertThat(service.processJob(job)).isFalse();
        assertThat(storage.exists(first)).isFalse();
        assertThat(storage.exists(second)).isTrue();
        verify(jobs, times(1)).failClaim(eq(3L), anyString(), eq("RETRY_WAIT"), any(), eq("CLEANUP_RETRY"));
        verify(jobs, org.mockito.Mockito.never()).markCompletedRunning(eq(3L), anyString());
        // 仅模拟数据库退避期已过后的再次领取；对象删除本身必须可重复。
        assertThat(service.processJob(job)).isTrue();
        assertThat(storage.exists(first)).isFalse();
        assertThat(storage.exists(second)).isFalse();
        verify(jobs, times(1)).markCompletedRunning(eq(3L), anyString());
    }

    @Test
    void latePutAfterFirstDeleteIsRemovedBeforeJobMayComplete() {
        String key = "uploads/7/signed.m4a";
        InMemoryObjectStorage storage = new InMemoryObjectStorage("http://memory-storage.test");
        storage.putObject(key, new byte[]{1}, "audio/mp4");
        DeletionJobMapper jobs = mock(DeletionJobMapper.class);
        RecognitionTaskMapper tasks = mock(RecognitionTaskMapper.class);
        UploadSessionMapper sessions = mock(UploadSessionMapper.class);
        DeletionJob job = new DeletionJob();
        job.setId(3L);
        job.setUserId(7L);
        job.setTargetType("ERASE");
        job.setTargetId(7L);
        job.setStatus("RUNNING");
        job.setAttemptCount(0);
        job.setRemainingObjects("[\"" + key + "\"]");
        UploadSession signed = new UploadSession();
        signed.setId(9L);
        signed.setUserId(7L);
        signed.setObjectKey(key);
        LocalDateTime expires = BusinessTime.now().plusMinutes(3);
        signed.setSignedUntil(expires);
        AtomicInteger reads = new AtomicInteger();
        when(sessions.selectList(any())).thenAnswer(inv -> reads.incrementAndGet() <= 2
            ? List.of(signed) : List.of());
        when(jobs.claim(eq(3L), anyString(), any())).thenReturn(1);
        when(jobs.lockClaimed(eq(3L), anyString())).thenReturn(job);
        when(jobs.deferClaim(eq(3L), anyString(), any())).thenReturn(1);
        when(jobs.markCompletedRunning(eq(3L), anyString())).thenReturn(1);
        RetentionService service = new RetentionService(jobs, tasks, mock(SentenceMapper.class),
            mock(SpeakerMappingMapper.class), mock(ViolationMapper.class), mock(ReportVersionMapper.class),
            mock(ExportJobMapper.class), sessions, mock(TaskAssetMapper.class), mock(UserMapper.class),
            storage, mock(AuditLogService.class), new DitingProperties(), new DeletionCleanupTransaction(jobs),
            mock(OrphanUploadCleanup.class));

        assertThat(service.processJob(job)).isFalse();
        assertThat(storage.exists(key)).isFalse();
        ArgumentCaptor<LocalDateTime> until = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(jobs).deferClaim(eq(3L), anyString(), until.capture());
        assertThat(until.getValue()).isAfter(expires);
        // 模拟此前签发的 PUT 在第一次删除后、签名到期前重建对象；数据库只会到期后领取第二轮。
        storage.putObject(key, new byte[]{2}, "audio/mp4");
        assertThat(storage.exists(key)).isTrue();
        assertThat(service.processJob(job)).isTrue();
        assertThat(storage.exists(key)).isFalse();
        verify(jobs, times(1)).markCompletedRunning(eq(3L), anyString());
        verify(sessions, times(1)).deleteById(9L);
    }
}
