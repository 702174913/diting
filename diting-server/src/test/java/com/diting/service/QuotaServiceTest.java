package com.diting.service;

import com.diting.common.BizException;
import com.diting.common.QuotaState;
import com.diting.common.ResultCode;
import com.diting.entity.DailyQuota;
import com.diting.entity.QuotaLedger;
import com.diting.mapper.DailyQuotaMapper;
import com.diting.mapper.QuotaLedgerMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S03 额度侧：预占、转消费、退还的 CAS 语义（不连库，验证服务端判定逻辑）。
 * 数据库层的条件更新真实效果由 run_s03_quota_e2e.sh 在独立测试库上验证。
 */
class QuotaServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-24T02:00:00Z");

    private DailyQuotaMapper dailyQuotaMapper;
    private QuotaLedgerMapper quotaLedgerMapper;
    private QuotaService service;

    @BeforeEach
    void setUp() {
        dailyQuotaMapper = mock(DailyQuotaMapper.class);
        quotaLedgerMapper = mock(QuotaLedgerMapper.class);
        when(dailyQuotaMapper.consumeReserved(any(), any())).thenReturn(1);
        when(dailyQuotaMapper.releaseReserved(any(), any())).thenReturn(1);
        ConfigService configService = mock(ConfigService.class);
        when(configService.load()).thenReturn(new ConfigService.PolicyConfig(1L, 3, 60, 10));
        service = new QuotaService(dailyQuotaMapper, quotaLedgerMapper, configService);
        service.useClock(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private QuotaLedger ledger(String state) {
        QuotaLedger row = new QuotaLedger();
        row.setId(1L);
        row.setUserId(7L);
        row.setTaskId(100L);
        row.setBusinessDate(LocalDate.of(2026, 9, 24));
        row.setState(state);
        return row;
    }

    @Test
    @DisplayName("QUOTA-02-1 预占成功：先幂等建行、条件更新命中、再写流水")
    void reserveSucceeds() {
        when(dailyQuotaMapper.tryReserve(7L, LocalDate.of(2026, 9, 24), 3)).thenReturn(1);
        when(quotaLedgerMapper.insert(any(QuotaLedger.class))).thenReturn(1);

        service.reserve(7L, 100L, 3);

        verify(dailyQuotaMapper).ensureRow(7L, LocalDate.of(2026, 9, 24));
        ArgumentCaptor<QuotaLedger> captor = ArgumentCaptor.forClass(QuotaLedger.class);
        verify(quotaLedgerMapper).insert(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(QuotaState.RESERVED.name());
        assertThat(captor.getValue().getTaskId()).isEqualTo(100L);
    }

    @Test
    @DisplayName("QUOTA-02-2 额度不足：条件更新 0 行 → 429，且不写流水")
    void reserveFailsWhenExhausted() {
        when(dailyQuotaMapper.tryReserve(any(), any(), anyInt())).thenReturn(0);

        assertThatThrownBy(() -> service.reserve(7L, 100L, 3))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getResultCode())
            .isEqualTo(ResultCode.QUOTA_EXCEEDED);
        verify(quotaLedgerMapper, never()).insert(any(QuotaLedger.class));
    }

    @Test
    @DisplayName("QUOTA-07-1 退还：CAS 命中才减计数，重复退还不再改动")
    void releaseIsIdempotent() {
        when(quotaLedgerMapper.selectOne(any())).thenReturn(ledger(QuotaState.RESERVED.name()));
        when(quotaLedgerMapper.casState(eq(100L), eq(QuotaState.RESERVED.name()),
            eq(QuotaState.RELEASED.name()), any())).thenReturn(1, 0);

        assertThat(service.release(100L)).isTrue();
        assertThat(service.release(100L)).isFalse();

        verify(dailyQuotaMapper, times(1)).releaseReserved(7L, LocalDate.of(2026, 9, 24));
    }

    @Test
    @DisplayName("QUOTA-07-2 已消费的任务不能被退还")
    void consumedCannotBeReleased() {
        when(quotaLedgerMapper.selectOne(any())).thenReturn(ledger(QuotaState.CONSUMED.name()));
        when(quotaLedgerMapper.casState(any(), anyString(), anyString(), any())).thenReturn(0);

        assertThat(service.release(100L)).isFalse();
        verify(dailyQuotaMapper, never()).releaseReserved(any(), any());
    }

    @Test
    @DisplayName("转消费：CAS 命中只转一次，重复调用不再改动计数")
    void consumeIsIdempotent() {
        when(quotaLedgerMapper.selectOne(any())).thenReturn(ledger(QuotaState.RESERVED.name()));
        when(quotaLedgerMapper.casState(eq(100L), eq(QuotaState.RESERVED.name()),
            eq(QuotaState.CONSUMED.name()), any())).thenReturn(1, 0);

        assertThat(service.consume(100L)).isTrue();
        assertThat(service.consume(100L)).isFalse();

        verify(dailyQuotaMapper, times(1)).consumeReserved(7L, LocalDate.of(2026, 9, 24));
    }

    @Test
    void counterMismatchThrowsAndTransactionRollsBack() {
        when(quotaLedgerMapper.selectOne(any())).thenReturn(ledger(QuotaState.RESERVED.name()));
        when(quotaLedgerMapper.casState(any(), anyString(), anyString(), any())).thenReturn(1);
        when(dailyQuotaMapper.consumeReserved(any(), any())).thenReturn(0);
        var tx = mock(org.springframework.transaction.PlatformTransactionManager.class);
        var status = mock(org.springframework.transaction.TransactionStatus.class);
        when(tx.getTransaction(any())).thenReturn(status);
        var proxy = new org.springframework.aop.framework.ProxyFactory(service);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(tx,
            new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        QuotaService proxied = (QuotaService) proxy.getProxy();
        assertThatThrownBy(() -> proxied.consume(100L)).isInstanceOf(BizException.class);
        verify(tx).rollback(status);
        verify(tx, never()).commit(any());
    }

    @Test
    @DisplayName("额度概览：剩余 = 上限 - 预占 - 已消费，且不为负；业务日按东八区")
    void snapshotComputesRemaining() {
        DailyQuota row = new DailyQuota();
        row.setUserId(7L);
        row.setBusinessDate(LocalDate.of(2026, 9, 24));
        row.setReserved(1);
        row.setConsumed(1);
        when(dailyQuotaMapper.selectOne(any())).thenReturn(row);

        QuotaService.QuotaSnapshot snapshot = service.snapshot(7L);

        assertThat(snapshot.businessDate()).isEqualTo("2026-09-24");
        assertThat(snapshot.limit()).isEqualTo(3);
        assertThat(snapshot.remaining()).isEqualTo(1);
        assertThat(snapshot.maxRecordingMinutes()).isEqualTo(10);

        row.setReserved(5);
        assertThat(service.snapshot(7L).remaining()).isZero();
    }

    @Test
    @DisplayName("没有额度行时概览按全量剩余展示，不报错")
    void snapshotWithoutRow() {
        when(dailyQuotaMapper.selectOne(any())).thenReturn(null);

        QuotaService.QuotaSnapshot snapshot = service.snapshot(7L);

        assertThat(snapshot.reserved()).isZero();
        assertThat(snapshot.consumed()).isZero();
        assertThat(snapshot.remaining()).isEqualTo(3);
    }
}
