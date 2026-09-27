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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 导出下载的预签名参数：必须带**下载文件名**。
 *
 * 不带 `response-content-disposition=attachment` 时 PDF 会被浏览器内联打开，
 * 「导出完成后自动下载」就形同虚设——这是这么一条容易漏、又只能靠断言钉住的约定。
 */
class ExportDownloadDispositionTest {

    private static final String ARTIFACT_KEY = "exports/admin/9/1044-abc123.pdf";

    private ExportJobMapper exportJobMapper;
    private RecognitionTaskMapper taskMapper;
    private ReportService reportService;
    private TaskAssetMapper taskAssetMapper;
    private ObjectStorage storage;
    private ExportService service;

    @BeforeEach
    void setUp() {
        exportJobMapper = mock(ExportJobMapper.class);
        taskMapper = mock(RecognitionTaskMapper.class);
        reportService = mock(ReportService.class);
        taskAssetMapper = mock(TaskAssetMapper.class);
        storage = mock(ObjectStorage.class);
        service = new ExportService(exportJobMapper, taskMapper, reportService, mock(ReportPdfRenderer.class),
            storage, taskAssetMapper, new DitingProperties(), mock(ExportLeaseService.class));
        when(storage.presignGet(any(), any(), any())).thenReturn(
            new ObjectStorage.PresignedRequest("https://oss.test/signed", "GET", Map.of(), Instant.now().plusSeconds(900)));
    }

    private static RecognitionTask task() {
        RecognitionTask task = new RecognitionTask();
        task.setId(1046L);
        task.setUserId(2L);
        task.setCreatedAt(LocalDateTime.of(2026, 9, 27, 12, 46));
        return task;
    }

    private static ReportVersion version() {
        ReportVersion version = new ReportVersion();
        version.setId(1044L);
        version.setTaskId(1046L);
        version.setVersion(2L);
        version.setStatus("PUBLISHED");
        return version;
    }

    private static ExportJob job(boolean withAudio, LocalDateTime expiresAt) {
        ExportJob job = new ExportJob();
        job.setId(9L);
        job.setOwnerDomain(ExportService.DOMAIN_ADMIN);
        job.setOwnerId(1L);
        job.setReportVersionId(1044L);
        job.setStatus(ExportService.STATUS_COMPLETED);
        job.setArtifactKey(ARTIFACT_KEY);
        job.setWithAudio(withAudio);
        job.setExpiresAt(expiresAt);
        return job;
    }

    @Test
    @DisplayName("PDF 下载：预签名带 报告编号-v版本.pdf 的另存为文件名")
    void pdfDownloadCarriesFileName() {
        when(reportService.resolveVersionById(1044L)).thenReturn(version());
        when(taskMapper.selectById(1046L)).thenReturn(task());

        assertThat(service.presignArtifact(job(false, LocalDateTime.now().plusHours(12))))
            .isEqualTo("https://oss.test/signed");

        verify(storage).presignGet(eq(ARTIFACT_KEY), any(), eq("DT-2026-001046-v2.pdf"));
    }

    @Test
    @DisplayName("含原件 ZIP 下载：扩展名为 zip，其余不变")
    void zipDownloadCarriesZipFileName() {
        when(reportService.resolveVersionById(1044L)).thenReturn(version());
        when(taskMapper.selectById(1046L)).thenReturn(task());

        service.presignArtifact(job(true, LocalDateTime.now().plusHours(12)));

        verify(storage).presignGet(eq(ARTIFACT_KEY), any(), eq("DT-2026-001046-v2.zip"));
    }

    @Test
    @DisplayName("产物已过期：拒绝签发，且不碰对象存储（过期产物对用户就是不存在）")
    void expiredArtifactIsNotSigned() {
        when(reportService.resolveVersionById(1044L)).thenReturn(version());
        when(taskMapper.selectById(1046L)).thenReturn(task());

        assertThatThrownBy(() -> service.presignArtifact(job(false, LocalDateTime.now().minusMinutes(1))))
            .isInstanceOf(BizException.class);

        verify(storage, never()).presignGet(any(), any(), any());
        verify(storage, never()).presignGet(any(), any());
    }

    @Test
    @DisplayName("未完成/无产物的作业：不签发（PROCESSING），不碰对象存储")
    void incompleteJobIsNotSigned() {
        when(reportService.resolveVersionById(1044L)).thenReturn(version());
        when(taskMapper.selectById(1046L)).thenReturn(task());
        ExportJob running = job(false, LocalDateTime.now().plusHours(12));
        running.setStatus(ExportService.STATUS_RUNNING);
        running.setArtifactKey(null);

        assertThatThrownBy(() -> service.presignArtifact(running)).isInstanceOf(BizException.class);

        verifyNoInteractions(storage);
    }
}
