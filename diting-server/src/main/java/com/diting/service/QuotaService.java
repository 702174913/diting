package com.diting.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.diting.common.BusinessTime;
import com.diting.common.BizException;
import com.diting.common.QuotaState;
import com.diting.common.ResultCode;
import com.diting.entity.DailyQuota;
import com.diting.entity.QuotaLedger;
import com.diting.mapper.DailyQuotaMapper;
import com.diting.mapper.QuotaLedgerMapper;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 每日额度：预占 / 消费 / 退还（开发文档 §6.4、§8）。
 *
 * 关键口径：
 * - 记账维度是「业务日（Asia/Shanghai）+ 用户」，跨日不共享额度；
 * - 所有增减都用**条件更新 + 影响行数**（CAS），不做「先查再改」，避免并发超卖；
 * - 一个任务只对应一条流水（uk_task），预约、转消费、退还都由状态机约束，
 *   重复调用不会二次扣减或二次退还；
 * - 预占必须与任务登记在同一事务内调用；确定未被云端受理才能退还，
 *   提交结果不确定时保持 RESERVED 等对账（S06 负责对账）。
 */
@Service
public class QuotaService {

    private final DailyQuotaMapper dailyQuotaMapper;
    private final QuotaLedgerMapper quotaLedgerMapper;
    private final ConfigService configService;
    private Clock clock = Clock.systemUTC();

    public QuotaService(DailyQuotaMapper dailyQuotaMapper, QuotaLedgerMapper quotaLedgerMapper,
                        ConfigService configService) {
        this.dailyQuotaMapper = dailyQuotaMapper;
        this.quotaLedgerMapper = quotaLedgerMapper;
        this.configService = configService;
    }

    /** 仅测试使用：注入可控时钟 */
    void useClock(Clock testClock) {
        this.clock = testClock;
    }

    /** 额度概览：剩余 = 上限 - 预占 - 已消费 */
    public QuotaSnapshot snapshot(Long userId) {
        ConfigService.PolicyConfig config = configService.load();
        LocalDate date = businessDate();
        DailyQuota row = find(userId, date);
        int reserved = row == null || row.getReserved() == null ? 0 : row.getReserved();
        int consumed = row == null || row.getConsumed() == null ? 0 : row.getConsumed();
        int remaining = Math.max(0, config.dailyFreeQuota() - reserved - consumed);
        return new QuotaSnapshot(date.toString(), config.dailyFreeQuota(), reserved, consumed,
            remaining, config.maxRecordingMinutes(), config.riskThreshold());
    }

    /**
     * 预占一份额度，额度不足抛 429。
     * 必须在任务登记的事务里调用：任一步失败整体回滚，不会留下「扣了份数没有任务」。
     */
    @org.springframework.transaction.annotation.Transactional
    public void reserve(Long userId, Long taskId, int limit) {
        LocalDate date = businessDate();
        dailyQuotaMapper.ensureRow(userId, date);
        int affected = dailyQuotaMapper.tryReserve(userId, date, limit);
        if (affected == 0) {
            throw new BizException(ResultCode.QUOTA_EXCEEDED,
                "今日可用份数已用完（上限 " + limit + " 份）");
        }
        LocalDateTime now = BusinessTime.now(clock);
        QuotaLedger ledger = new QuotaLedger();
        ledger.setUserId(userId);
        ledger.setTaskId(taskId);
        ledger.setBusinessDate(date);
        ledger.setState(QuotaState.RESERVED.name());
        ledger.setCreatedAt(now);
        ledger.setUpdatedAt(now);
        quotaLedgerMapper.insert(ledger);
    }

    /** 云端已确认受理：预占转已消费；重复调用返回 false 且不再改动计数 */
    @org.springframework.transaction.annotation.Transactional
    public boolean consume(Long taskId) {
        QuotaLedger ledger = ledgerOf(taskId);
        if (ledger == null) {
            return false;
        }
        int cas = quotaLedgerMapper.casState(taskId, QuotaState.RESERVED.name(),
            QuotaState.CONSUMED.name(), BusinessTime.now(clock));
        if (cas == 0) {
            return false;
        }
        if (dailyQuotaMapper.consumeReserved(ledger.getUserId(), ledger.getBusinessDate()) != 1) {
            throw new BizException(ResultCode.CONFLICT, "额度账务不一致，消费已回滚");
        }
        return true;
    }

    /**
     * 确定未被云端受理：退还预占。
     * CAS 命中 RESERVED 才退计数，重复退还（或已消费的任务）一律不改动计数。
     */
    @org.springframework.transaction.annotation.Transactional
    public boolean release(Long taskId) {
        QuotaLedger ledger = ledgerOf(taskId);
        if (ledger == null) {
            return false;
        }
        int cas = quotaLedgerMapper.casState(taskId, QuotaState.RESERVED.name(),
            QuotaState.RELEASED.name(), BusinessTime.now(clock));
        if (cas == 0) {
            return false;
        }
        if (dailyQuotaMapper.releaseReserved(ledger.getUserId(), ledger.getBusinessDate()) != 1) {
            throw new BizException(ResultCode.CONFLICT, "额度账务不一致，退还已回滚");
        }
        return true;
    }

    public QuotaLedger ledgerOf(Long taskId) {
        return quotaLedgerMapper.selectOne(new LambdaQueryWrapper<QuotaLedger>()
            .eq(QuotaLedger::getTaskId, taskId));
    }

    public LocalDate businessDate() {
        return ConfigService.businessDate(clock);
    }

    private DailyQuota find(Long userId, LocalDate date) {
        return dailyQuotaMapper.selectOne(new LambdaQueryWrapper<DailyQuota>()
            .eq(DailyQuota::getUserId, userId)
            .eq(DailyQuota::getBusinessDate, date));
    }

    /** 额度概览（对应 GET /api/task/quota） */
    public record QuotaSnapshot(String businessDate, int limit, int reserved, int consumed,
                                int remaining, int maxRecordingMinutes, int riskThreshold) {
    }
}
