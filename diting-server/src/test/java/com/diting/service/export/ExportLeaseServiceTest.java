package com.diting.service.export;

import com.diting.common.BizException;
import com.diting.entity.RecognitionTask;
import com.diting.entity.ReportVersion;
import com.diting.mapper.ExportJobMapper;
import com.diting.mapper.RecognitionTaskMapper;
import com.diting.service.report.ReportService;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExportLeaseServiceTest {
    private final ExportJobMapper jobs = mock(ExportJobMapper.class);
    private final RecognitionTaskMapper tasks = mock(RecognitionTaskMapper.class);
    private final ReportService reports = mock(ReportService.class);
    private final ExportLeaseService service = new ExportLeaseService(jobs, tasks, reports);

    private void versionAndTask() {
        ReportVersion version = new ReportVersion(); version.setId(10L); version.setTaskId(1L); version.setStatus("PUBLISHED");
        RecognitionTask task = new RecognitionTask(); task.setId(1L);
        when(reports.resolveVersionById(10L)).thenReturn(version);
        when(tasks.lockById(1L)).thenReturn(task);
    }

    @Test void claimRejectsOtherWorkerAndExhaustsExpiredJob() {
        assertThat(service.claim(5L, "worker", 300, 3)).isFalse();
        verify(jobs).claim(eq(5L), eq("worker"), any(), eq(3));
        verify(jobs).exhaust(5L, 3);
    }

    @Test void completionLocksTaskThenComparesToken() {
        versionAndTask();
        when(jobs.completeClaim(eq(5L), eq("worker"), eq("key"), any())).thenReturn(1);
        service.publish(5L, "worker", 10L, "key", LocalDateTime.now().plusHours(1));
        var order = inOrder(tasks, jobs);
        order.verify(tasks).lockById(1L);
        order.verify(jobs).completeClaim(eq(5L), eq("worker"), eq("key"), any());
    }

    @Test void deletedTaskCannotPublish() {
        versionAndTask();
        tasks.lockById(1L).setDeletedAt(LocalDateTime.now());
        assertThatThrownBy(() -> service.publish(5L, "worker", 10L, "key", LocalDateTime.now()))
            .isInstanceOf(BizException.class);
        verify(jobs, never()).completeClaim(any(), any(), any(), any());
    }

    @Test void staleTokenCannotPublishEvenIfJobRunningAgain() {
        versionAndTask();
        assertThatThrownBy(() -> service.publish(5L, "stale", 10L, "stale-key", LocalDateTime.now()))
            .isInstanceOf(BizException.class).hasMessageContaining("租约");
    }

    @Test void failedCompletionRollsBackTransactionalPublication() {
        versionAndTask();
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(tx.getTransaction(any())).thenReturn(status);
        ProxyFactory proxy = new ProxyFactory(service);
        proxy.addAdvice(new TransactionInterceptor(tx, new AnnotationTransactionAttributeSource()));
        ExportLeaseService proxied = (ExportLeaseService) proxy.getProxy();
        assertThatThrownBy(() -> proxied.publish(5L, "stale", 10L, "key", LocalDateTime.now()))
            .isInstanceOf(BizException.class);
        verify(tx).rollback(status);
    }
}
