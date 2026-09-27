package com.diting.service;

import com.diting.common.BizException;
import com.diting.entity.RecognitionTask;
import com.diting.mapper.RecognitionTaskMapper;
import com.diting.mapper.TaskAssetMapper;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskOcrWriterTest {
    private final RecognitionTaskMapper tasks = mock(RecognitionTaskMapper.class);
    private final TaskAssetMapper assets = mock(TaskAssetMapper.class);
    private final QuotaService quota = mock(QuotaService.class);
    private final TaskOcrWriter writer = new TaskOcrWriter(tasks, assets, quota);

    private RecognitionTask snapshot() {
        RecognitionTask task = new RecognitionTask();
        task.setId(1L);
        task.setVersion(2L);
        task.setStatus("TRANSCRIBING");
        return task;
    }

    @Test void staleTaskCannotClaimOrCommitOcr() {
        RecognitionTask stale = snapshot();
        RecognitionTask live = snapshot();
        live.setVersion(3L);
        when(tasks.lockById(1L)).thenReturn(live);
        assertThatThrownBy(() -> writer.claim(stale, 5L, "token", 60, 3)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> writer.finish(stale, 5L, "token", "DONE", "text", null)).isInstanceOf(BizException.class);
        verifyNoInteractions(assets, quota);
    }

    @Test void deletedTaskCannotWriteOcr() {
        RecognitionTask live = snapshot();
        live.setDeletedAt(java.time.LocalDateTime.now());
        when(tasks.lockById(1L)).thenReturn(live);
        assertThatThrownBy(() -> writer.finish(snapshot(), 5L, "token", "DONE", "text", null)).isInstanceOf(BizException.class);
        verifyNoInteractions(assets, quota);
    }

    @Test void expiredAssetTokenCannotFinishOrConsume() {
        when(tasks.lockById(1L)).thenReturn(snapshot());
        assertThatThrownBy(() -> writer.finish(snapshot(), 5L, "old", "DONE", "text", null))
            .isInstanceOf(BizException.class).hasMessageContaining("租约");
        verify(assets).finishOcr(5L, 1L, "old", "DONE", "text", null);
        verifyNoInteractions(quota);
    }

    @Test void successfulOcrCommitsAndConsumesOnce() {
        when(tasks.lockById(1L)).thenReturn(snapshot());
        when(assets.claimOcr(eq(5L), eq(1L), eq("token"), any(), eq(3))).thenReturn(1);
        when(assets.finishOcr(5L, 1L, "token", "DONE", "text", null)).thenReturn(1);
        assertThat(writer.claim(snapshot(), 5L, "token", 60, 3)).isTrue();
        writer.finish(snapshot(), 5L, "token", "DONE", "text", null);
        verify(quota).consume(1L);
    }

    @Test void quotaFailureRollsBackOcrResultTransaction() {
        when(tasks.lockById(1L)).thenReturn(snapshot());
        when(assets.finishOcr(5L, 1L, "token", "DONE", "text", null)).thenReturn(1);
        doThrow(new IllegalStateException("quota ledger")).when(quota).consume(1L);
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(tx.getTransaction(any())).thenReturn(status);
        ProxyFactory proxy = new ProxyFactory(writer);
        proxy.addAdvice(new TransactionInterceptor(tx, new AnnotationTransactionAttributeSource()));
        TaskOcrWriter proxied = (TaskOcrWriter) proxy.getProxy();
        assertThatThrownBy(() -> proxied.finish(snapshot(), 5L, "token", "DONE", "text", null))
            .isInstanceOf(IllegalStateException.class);
        verify(tx).rollback(status);
        verify(tx, never()).commit(any());
    }
}
