package com.diting.service.lifecycle;

import com.diting.common.BizException;
import com.diting.common.BusinessTime;
import com.diting.entity.DeletionJob;
import com.diting.mapper.DeletionJobMapper;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 模拟 Spring 事务代理；仅验证事务边界，不等同数据库/OSS 并发验收。 */
class DeletionCleanupTransactionTest {
    private DeletionJobMapper jobs;
    private RetentionService cleanup;
    private PlatformTransactionManager manager;
    private TransactionStatus status;
    private DeletionCleanupTransaction runner;

    @BeforeEach
    void setUp() {
        jobs = mock(DeletionJobMapper.class);
        cleanup = mock(RetentionService.class);
        manager = mock(PlatformTransactionManager.class);
        status = mock(TransactionStatus.class);
        when(manager.getTransaction(any())).thenReturn(status);
        var factory = new ProxyFactory(new DeletionCleanupTransaction(jobs));
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        runner = (DeletionCleanupTransaction) factory.getProxy();
    }

    private static DeletionJob claimed(String target) {
        DeletionJob job = new DeletionJob();
        job.setId(4L);
        job.setTargetType(target);
        job.setTargetId(10L);
        job.setUserId(7L);
        return job;
    }

    @Test
    void successfulCleanupCommitsUnderClaimedGenerationLock() {
        when(jobs.lockClaimed(4L, "owner-A")).thenReturn(claimed("ERASE"));
        runner.run(cleanup, 4L, "owner-A");
        verify(jobs).lockClaimed(4L, "owner-A");
        verify(cleanup).cleanupUser(7L);
        verify(manager).commit(status);
    }

    @Test
    void activePutSignatureDefersCompletionInSameCleanupTransaction() {
        DeletionJob job = claimed("TASK");
        job.setRemainingObjects("[\"recordings/7/a.m4a\"]");
        var keys = java.util.List.of("recordings/7/a.m4a");
        var until = BusinessTime.now().plusMinutes(2);
        when(jobs.lockClaimed(4L, "owner-A")).thenReturn(job);
        when(cleanup.activeSignedPutUntil(keys)).thenReturn(until);
        when(jobs.deferClaim(4L, "owner-A", until)).thenReturn(1);
        assertThat(runner.run(cleanup, 4L, "owner-A")).isTrue();
        verify(cleanup).cleanupTask(10L);
        verify(cleanup).deletePendingObjects(keys);
        verify(jobs).deferClaim(4L, "owner-A", until);
        verify(manager).commit(status);
    }

    @Test
    void deferralCasFailureRollsBackSqlInsteadOfClaimingCleanupComplete() {
        DeletionJob job = claimed("ERASE");
        job.setRemainingObjects("[\"uploads/7/a.m4a\"]");
        var keys = java.util.List.of("uploads/7/a.m4a");
        var until = BusinessTime.now().plusMinutes(2);
        when(jobs.lockClaimed(4L, "owner-A")).thenReturn(job);
        when(cleanup.activeSignedPutUntil(keys)).thenReturn(until);
        assertThatThrownBy(() -> runner.run(cleanup, 4L, "owner-A"))
            .isInstanceOf(BizException.class);
        verify(manager).rollback(status);
        verify(manager, never()).commit(any());
    }

    @Test
    void databaseFailureRollsBackAllCleanupSql() {
        when(jobs.lockClaimed(4L, "owner-A")).thenReturn(claimed("TASK"));
        doThrow(new IllegalStateException("simulated SQL failure")).when(cleanup).cleanupTask(10L);
        assertThatThrownBy(() -> runner.run(cleanup, 4L, "owner-A"))
            .isInstanceOf(IllegalStateException.class);
        verify(manager).rollback(status);
        verify(manager, never()).commit(any());
    }

    @Test
    void rearmedGenerationCannotDeleteUnderOldToken() {
        assertThatThrownBy(() -> runner.run(cleanup, 4L, "old-owner"))
            .isInstanceOf(BizException.class);
        verifyNoInteractions(cleanup);
        verify(manager).rollback(status);
    }
}
