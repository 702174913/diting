package com.diting.service.export;

import com.diting.common.BizException;
import com.diting.config.DitingProperties;
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

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExportPublicationGuardTest {
    private ExportJobMapper jobs;
    private RecognitionTaskMapper tasks;
    private ReportService reports;
    private ObjectStorage storage;
    private ReportPdfRenderer renderer;
    private ExportService service;
    private ReportVersion version;
    private RecognitionTask task;

    @BeforeEach
    void setup() {
        jobs = mock(ExportJobMapper.class);
        tasks = mock(RecognitionTaskMapper.class);
        reports = mock(ReportService.class);
        storage = mock(ObjectStorage.class);
        renderer = mock(ReportPdfRenderer.class);
        service = new ExportService(jobs, tasks, reports, renderer, storage,
            mock(TaskAssetMapper.class), new DitingProperties(), new ExportLeaseService(jobs, tasks, reports));
        when(jobs.claim(eq(20L), anyString(), any(), anyInt())).thenReturn(1);
        task = new RecognitionTask(); task.setId(1L); task.setUserId(7L);
        version = new ReportVersion(); version.setId(10L); version.setTaskId(1L); version.setStatus("FAILED");
        when(tasks.selectById(1L)).thenReturn(task);
        when(reports.resolveVersionById(10L)).thenReturn(version);
    }

    @Test
    void failedOrAnalyzingReportCannotCreateExport() {
        for (String status : new String[]{"FAILED", "ANALYZING"}) {
            version.setStatus(status);
            assertThatThrownBy(() -> service.create("USER", 7L, 1L, 10L, false))
                .isInstanceOf(BizException.class).hasMessageContaining("尚未成功发布");
        }
        verifyNoInteractions(jobs, storage, renderer);
    }

    @Test
    void existingQueuedExportOfFailedReportCannotRender() {
        ExportJob job = job("QUEUED");
        when(jobs.selectById(20L)).thenReturn(job);
        service.generate(job);
        verify(jobs).finishFailure(eq(20L), anyString(), eq("FAILED"),
            eq("REPORT_NOT_PUBLISHED"), isNull(), any());
        verifyNoInteractions(renderer, storage);
    }

    @Test
    void oldCompletedArtifactOfFailedReportCannotBeDownloaded() {
        assertThatThrownBy(() -> service.presignArtifact(job("COMPLETED")))
            .isInstanceOf(BizException.class).hasMessageContaining("尚未成功发布");
        verifyNoInteractions(storage);
    }

    @Test
    void deletedTaskCannotIssueNewDownloadUrl() {
        version.setStatus("PUBLISHED");
        task.setDeletedAt(LocalDateTime.now());
        assertThatThrownBy(() -> service.presignArtifact(job("COMPLETED"))).isInstanceOf(BizException.class);
        verifyNoInteractions(storage);
    }

    private ExportJob job(String state) {
        ExportJob job = new ExportJob(); job.setId(20L); job.setReportVersionId(10L);
        job.setStatus(state); job.setArtifactKey("exports/test.pdf");
        job.setExpiresAt(LocalDateTime.now().plusDays(1));
        return job;
    }
}
