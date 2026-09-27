package com.diting.service.lifecycle;

import com.diting.config.DitingProperties;
import com.diting.entity.DeletionJob;
import com.diting.entity.RecognitionTask;
import com.diting.mapper.*;
import com.diting.service.admin.AuditLogService;
import com.diting.service.storage.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeletionFencingTest {
    private final DeletionJobMapper jobs = mock(DeletionJobMapper.class);
    private final RecognitionTaskMapper tasks = mock(RecognitionTaskMapper.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final SpeakerMappingMapper mappings = mock(SpeakerMappingMapper.class);
    private final TaskAssetMapper assets = mock(TaskAssetMapper.class);
    private final ReportVersionMapper versions = mock(ReportVersionMapper.class);
    private final RetentionService service = new RetentionService(jobs, tasks, mock(SentenceMapper.class),
        mappings, mock(ViolationMapper.class), versions, mock(ExportJobMapper.class),
        mock(UploadSessionMapper.class), assets, mock(UserMapper.class),
        mock(ObjectStorage.class), audit, new DitingProperties(), new DeletionCleanupTransaction(jobs));

    private static DeletionJob queued() {
        DeletionJob job = new DeletionJob(); job.setId(3L); job.setTargetType("TASK");
        job.setTargetId(10L); job.setStatus("QUEUED"); job.setAttemptCount(0);
        return job;
    }

    @BeforeEach void setUp() {
        when(jobs.lockClaimed(eq(3L), anyString())).thenReturn(queued());
    }

    @Test void rearmedOrReclaimedJobCannotBeCompletedByOldWorker() {
        when(jobs.claim(eq(3L), anyString(), any())).thenReturn(1);
        assertThat(service.processJob(queued())).isFalse();
        verify(jobs).markCompletedRunning(eq(3L), anyString());
        verifyNoInteractions(audit);
    }

    @Test void onlyCurrentWorkerRecordsCompleted() {
        when(jobs.claim(eq(3L), anyString(), any())).thenReturn(1);
        when(jobs.markCompletedRunning(eq(3L), anyString())).thenReturn(1);
        assertThat(service.processJob(queued())).isTrue();
        verify(audit).success(eq("SYSTEM"), isNull(), eq("DELETION_COMPLETED"), eq("TASK"), eq("10"), any());
    }

    @Test void rejectedClaimMustNotRunCleanupOrAudit() {
        assertThat(service.processJob(queued())).isFalse();
        verifyNoInteractions(tasks, audit);
        verify(jobs, never()).lockClaimed(anyLong(), anyString());
    }

    @Test void rearmedJobCannotRunOldGenerationCleanup() {
        when(jobs.claim(eq(3L), anyString(), any())).thenReturn(1);
        when(jobs.lockClaimed(eq(3L), anyString())).thenReturn(null);
        assertThat(service.processJob(queued())).isFalse();
        verifyNoInteractions(tasks, assets, mappings, audit);
        verify(jobs, never()).markCompletedRunning(anyLong(), anyString());
    }

    @Test void cleanupRemovesSpeakerMappingAndAdvancesDeletedTombstone() {
        RecognitionTask task = new RecognitionTask(); task.setId(10L); task.setStatus("DELETING");
        task.setDeletedAt(java.time.LocalDateTime.now());
        when(tasks.selectById(10L)).thenReturn(task);
        when(assets.selectByTask(10L)).thenReturn(java.util.List.of());
        when(versions.selectList(any())).thenReturn(java.util.List.of());
        when(tasks.markDeletedAfterCleanup(10L)).thenReturn(1);
        service.cleanupTask(10L);
        var order = inOrder(mappings, tasks);
        order.verify(mappings).delete(any());
        order.verify(tasks).markDeletedAfterCleanup(10L);
    }

    @Test void staleWorkerFailureCannotResetNewGeneration() {
        when(jobs.claim(eq(3L), anyString(), any())).thenReturn(1);
        when(tasks.selectById(10L)).thenThrow(new IllegalStateException("temporary"));
        assertThat(service.processJob(queued())).isFalse();
        verify(jobs).failClaim(eq(3L), anyString(), eq("RETRY_WAIT"), any(), eq("CLEANUP_RETRY"));
        verifyNoInteractions(audit);
    }
}
