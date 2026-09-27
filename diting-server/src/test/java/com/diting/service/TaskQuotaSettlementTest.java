package com.diting.service;

import com.diting.common.BizException;
import com.diting.entity.QuotaLedger;
import com.diting.entity.RecognitionTask;
import com.diting.mapper.RecognitionTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 模拟事务代理的 commit/rollback 与任务行 CAS；不把 Mockito 当真实 MySQL 并发验收。 */
class TaskQuotaSettlementTest {
    private RecognitionTaskMapper tasks;
    private QuotaService quota;
    private PlatformTransactionManager manager;
    private TransactionStatus status;
    private TaskQuotaSettlement settlement;
    private RecognitionTask live;

    private static RecognitionTask task(String state, long version, String remoteId) {
        RecognitionTask task = new RecognitionTask();
        task.setId(42L);
        task.setVersion(version);
        task.setStatus(state);
        task.setTaskId(remoteId);
        return task;
    }

    @BeforeEach
    void setUp() {
        tasks = mock(RecognitionTaskMapper.class);
        quota = mock(QuotaService.class);
        manager = mock(PlatformTransactionManager.class);
        status = mock(TransactionStatus.class);
        when(manager.getTransaction(any())).thenReturn(status);
        var factory = new ProxyFactory(new TaskQuotaSettlement(tasks, quota));
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        settlement = (TaskQuotaSettlement) factory.getProxy();
        live = task("SUBMITTING", 2L, null);
        when(tasks.lockById(42L)).thenReturn(live);
    }

    @Test
    void acceptedTaskAndQuotaCommitAsOneUnit() {
        RecognitionTask snapshot = task("SUBMITTING", 2L, null);
        when(tasks.markSubmitted(eq(42L), eq(2L), eq("remote-42"), any())).thenReturn(1);
        when(quota.consume(42L)).thenReturn(true);
        assertThat(settlement.confirmAccepted(snapshot, "remote-42", 10)).isTrue();
        verify(manager).commit(status);
        verify(tasks).markSubmitted(eq(42L), eq(2L), eq("remote-42"), any());
        assertThat(snapshot.getVersion()).isEqualTo(3L);
        assertThat(snapshot.getTaskId()).isEqualTo("remote-42");
    }

    @Test
    void failedAcceptedQuotaRollsBackRemoteStatusInsteadOfLeavingHalfSettlement() {
        RecognitionTask snapshot = task("SUBMITTING", 2L, null);
        when(tasks.markSubmitted(eq(42L), eq(2L), eq("remote-42"), any())).thenReturn(1);
        assertThatThrownBy(() -> settlement.confirmAccepted(snapshot, "remote-42", 10))
            .isInstanceOf(BizException.class);
        verify(manager).rollback(status);
        verify(manager, never()).commit(any());
        assertThat(snapshot.getStatus()).isEqualTo("SUBMITTING");
    }

    @Test
    void rejectedTaskAndRefundCommitAsOneUnit() {
        RecognitionTask snapshot = task("SUBMITTING", 2L, null);
        when(tasks.advance(eq(42L), eq(2L), eq("FAILED"), any(), any(), any(), any())).thenReturn(1);
        when(quota.release(42L)).thenReturn(true);
        assertThat(settlement.rejectAndRelease(snapshot, "SUBMIT_REJECTED", "明确拒绝")).isTrue();
        verify(manager).commit(status);
        assertThat(snapshot.getStatus()).isEqualTo("FAILED");
    }

    @Test
    void failedRefundRollsBackTaskFailureSoNextWorkerCanRetryMarker() {
        RecognitionTask snapshot = task("SUBMITTING", 2L, null);
        when(tasks.advance(eq(42L), eq(2L), eq("FAILED"), any(), any(), any(), any())).thenReturn(1);
        assertThatThrownBy(() -> settlement.rejectAndRelease(snapshot, "SUBMIT_REJECTED", "明确拒绝"))
            .isInstanceOf(BizException.class);
        verify(manager).rollback(status);
        assertThat(snapshot.getStatus()).isEqualTo("SUBMITTING");
    }

    @Test
    void staleWorkerCannotTouchLedger() {
        RecognitionTask snapshot = task("SUBMITTING", 1L, null);
        assertThat(settlement.rejectAndRelease(snapshot, "SUBMIT_REJECTED", "明确拒绝")).isFalse();
        verify(tasks, never()).advance(anyLong(), anyLong(), any(), any(), any(), any(), any());
        verify(quota, never()).release(anyLong());
    }

    @Test
    void acceptedLegacyReservationMustSettleBeforePollingRemote() {
        RecognitionTask snapshot = task("TRANSCRIBING", 3L, "remote-42");
        live = task("TRANSCRIBING", 3L, "remote-42");
        when(tasks.lockById(42L)).thenReturn(live);
        QuotaLedger ledger = new QuotaLedger();
        ledger.setState("RESERVED");
        when(quota.ledgerOf(42L)).thenReturn(ledger);
        when(quota.consume(42L)).thenReturn(true);
        settlement.ensureAcceptedConsumed(snapshot);
        verify(quota).consume(42L);
        verify(manager).commit(status);
    }

    @Test
    void releasedOrMissingLedgerBlocksAcceptedRemoteTask() {
        RecognitionTask snapshot = task("TRANSCRIBING", 3L, "remote-42");
        live = task("TRANSCRIBING", 3L, "remote-42");
        when(tasks.lockById(42L)).thenReturn(live);
        assertThatThrownBy(() -> settlement.ensureAcceptedConsumed(snapshot)).isInstanceOf(BizException.class);
        QuotaLedger ledger = new QuotaLedger();
        ledger.setState("RELEASED");
        when(quota.ledgerOf(42L)).thenReturn(ledger);
        assertThatThrownBy(() -> settlement.ensureAcceptedConsumed(snapshot)).isInstanceOf(BizException.class);
        verify(quota, never()).consume(42L);
    }
}
