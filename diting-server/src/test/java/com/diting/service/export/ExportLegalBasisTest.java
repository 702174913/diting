package com.diting.service.export;

import com.diting.common.BusinessTime;
import com.diting.config.DitingProperties;
import com.diting.dto.ReportDetailResponse;
import com.diting.dto.ReportLegalClauseResponse;
import com.diting.dto.ViolationResponse;
import com.diting.entity.ExportJob;
import com.diting.entity.RecognitionTask;
import com.diting.entity.ReportVersion;
import com.diting.entity.Violation;
import com.diting.mapper.ExportJobMapper;
import com.diting.mapper.RecognitionTaskMapper;
import com.diting.mapper.TaskAssetMapper;
import com.diting.service.report.ReportService;
import com.diting.service.storage.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 实时法律依据不受报告版本冻结约束，PDF 和 ZIP 均不能复用旧依据。 */
class ExportLegalBasisTest {
    private final ExportJobMapper jobs = mock(ExportJobMapper.class);
    private final RecognitionTaskMapper tasks = mock(RecognitionTaskMapper.class);
    private final ReportService reports = mock(ReportService.class);
    private final ReportPdfRenderer renderer = mock(ReportPdfRenderer.class);
    private final TaskAssetMapper assets = mock(TaskAssetMapper.class);
    private final ObjectStorage storage = mock(ObjectStorage.class);
    private final ExportLeaseService leases = mock(ExportLeaseService.class);
    private final ExportService service = new ExportService(jobs, tasks, reports, renderer, storage,
        assets, new DitingProperties(), leases);
    private final RecognitionTask task = new RecognitionTask();
    private final ReportVersion version = new ReportVersion();
    private final ExportJob cached = new ExportJob();

    @BeforeEach
    void setUp() {
        task.setId(1L);
        task.setUserId(7L);
        task.setOssObject("audio/1.m4a");
        version.setId(10L);
        version.setTaskId(1L);
        version.setStatus("PUBLISHED");
        cached.setId(4L);
        cached.setReportVersionId(10L);
        cached.setStatus("COMPLETED");
        cached.setArtifactKey("exports/old.pdf");
        cached.setExpiresAt(BusinessTime.now().plusHours(12));
        when(tasks.selectById(1L)).thenReturn(task);
        when(reports.resolveVersionById(10L)).thenReturn(version);
        when(jobs.selectList(any())).thenReturn(List.of(cached));
        when(jobs.insert(any(ExportJob.class))).thenAnswer(inv -> {
            ExportJob job = inv.getArgument(0);
            job.setId(5L);
            return 1;
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reportsWithViolationsAlwaysQueueFreshExportEvenWhenCurrentlyUnbound(boolean withAudio) {
        when(reports.violationsOf(10L)).thenReturn(List.of(new Violation()));

        ExportJob fresh = service.create("USER", 7L, 1L, 10L, withAudio);

        assertThat(fresh).isNotSameAs(cached);
        assertThat(fresh.getStatus()).isEqualTo("QUEUED");
        assertThat(fresh.getWithAudio()).isEqualTo(withAudio);
        verify(jobs).insert(fresh);
        verify(jobs, never()).selectList(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reportsWithoutViolationsStillReuseValidArtifact(boolean withAudio) {
        when(reports.violationsOf(10L)).thenReturn(List.of());

        assertThat(service.create("USER", 7L, 1L, 10L, withAudio)).isSameAs(cached);

        verify(jobs, never()).insert(any(ExportJob.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void freshExportRendersCurrentEditedOrUnboundClauses(boolean unbound) {
        when(reports.violationsOf(10L)).thenReturn(List.of(new Violation()));
        ExportJob fresh = service.create("USER", 7L, 1L, 10L, false);
        when(leases.claim(eq(5L), anyString(), anyInt(), anyInt())).thenReturn(true);
        when(jobs.selectById(5L)).thenReturn(fresh);
        when(renderer.render(any())).thenReturn(new byte[]{1, 2, 3});
        var clauses = unbound ? List.<ReportLegalClauseResponse>of()
            : List.of(new ReportLegalClauseResponse(3L, "新法规", "第二条", "修改后的最新原文"));
        var detail = mock(ReportDetailResponse.class);
        when(detail.reportNo()).thenReturn("R1");
        when(detail.sentences()).thenReturn(List.of());
        when(detail.violations()).thenReturn(List.of(new ViolationResponse(1L, 2L, "规则", "hit", "ALL",
            null, null, List.of(), List.of(), 10, "说明", clauses)));
        when(reports.detailOf(task, version)).thenReturn(detail);

        service.generate(fresh);

        var report = ArgumentCaptor.forClass(ReportPdfRenderer.PdfReport.class);
        verify(renderer).render(report.capture());
        var text = report.getValue().sections().stream().flatMap(section -> section.lines().stream())
            .flatMap(line -> line.spans().stream()).map(ReportPdfRenderer.Span::text)
            .collect(java.util.stream.Collectors.joining("\n"));
        if (unbound) {
            assertThat(text).doesNotContain("新法规", "修改后的最新原文");
        } else {
            assertThat(text).contains("新法规", "第二条", "修改后的最新原文");
        }
    }
}
