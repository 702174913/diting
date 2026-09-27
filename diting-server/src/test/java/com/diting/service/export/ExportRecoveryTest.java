package com.diting.service.export;

import com.diting.config.DitingProperties;
import com.diting.dto.ReportDetailResponse;
import com.diting.entity.ExportJob;
import com.diting.entity.RecognitionTask;
import com.diting.entity.ReportVersion;
import com.diting.mapper.ExportJobMapper;
import com.diting.mapper.RecognitionTaskMapper;
import com.diting.mapper.TaskAssetMapper;
import com.diting.service.report.ReportService;
import com.diting.service.storage.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExportRecoveryTest {
    private final ExportJobMapper jobs = mock(ExportJobMapper.class);
    private final RecognitionTaskMapper tasks = mock(RecognitionTaskMapper.class);
    private final TaskAssetMapper assets = mock(TaskAssetMapper.class);
    private final ReportService reports = mock(ReportService.class);
    private final ReportPdfRenderer renderer = mock(ReportPdfRenderer.class);
    private final ObjectStorage storage = mock(ObjectStorage.class);
    private final ExportService service = new ExportService(jobs, tasks, reports, renderer, storage, assets,
        new DitingProperties(), new ExportLeaseService(jobs, tasks, reports));
    private final ExportJob job = new ExportJob();
    private final RecognitionTask task = new RecognitionTask();

    @BeforeEach void setup() {
        job.setId(4L); job.setReportVersionId(10L); job.setStatus("QUEUED");
        job.setOwnerDomain("USER"); job.setOwnerId(7L); job.setAttemptCount(0); job.setWithAudio(false);
        task.setId(1L); task.setUserId(7L);
        ReportVersion version = new ReportVersion(); version.setId(10L); version.setTaskId(1L); version.setStatus("PUBLISHED");
        when(jobs.selectById(4L)).thenReturn(job);
        when(jobs.selectDue(anyInt())).thenReturn(List.of(job));
        when(jobs.claim(eq(4L), anyString(), any(), anyInt())).thenReturn(1);
        when(reports.resolveVersionById(10L)).thenReturn(version);
        when(tasks.selectById(1L)).thenReturn(task);
        when(tasks.lockById(1L)).thenReturn(task);
        ReportDetailResponse detail = mock(ReportDetailResponse.class);
        when(detail.sentences()).thenReturn(List.of());
        when(detail.violations()).thenReturn(List.of());
        when(detail.reportNo()).thenReturn("R1");
        when(detail.reportVersion()).thenReturn(1);
        when(reports.detailOf(task, version)).thenReturn(detail);
        when(renderer.render(any())).thenReturn(new byte[]{1, 2, 3});
    }

    @Test void ossFailureRequeuesInsteadOfLeavingRunningForever() {
        doThrow(new IllegalStateException("OSS timeout")).when(storage).put(anyString(), any(), anyString());
        assertThat(service.tick()).isEqualTo(1);
        verify(jobs).finishFailure(eq(4L), anyString(), eq("RETRY_WAIT"), eq("EXPORT_FAILED"), any(), isNull());
        verify(jobs, never()).completeClaim(any(), any(), any(), any());
    }

    @Test void everyClaimUsesUniqueKeyAndPublishesOnlyOwnToken() {
        List<String> keys = new ArrayList<>();
        doAnswer(inv -> {
            keys.add(inv.getArgument(0));
            if (keys.size() == 1) throw new IllegalStateException("first failed");
            return null;
        }).when(storage).put(anyString(), any(), anyString());
        when(jobs.completeClaim(eq(4L), anyString(), anyString(), any())).thenReturn(1);
        service.generate(job);
        service.generate(job);
        assertThat(keys).hasSize(2);
        assertThat(keys.get(0)).isNotEqualTo(keys.get(1));
        verify(jobs).completeClaim(eq(4L), anyString(), eq(keys.get(1)), any());
    }

    @Test void lostLeaseAfterUploadDeletesUnpublishedArtifact() {
        service.generate(job);
        verify(storage).delete(anyString());
        verify(jobs).finishFailure(eq(4L), anyString(), eq("FAILED"), eq("CONFLICT"), isNull(), any());
    }
}
