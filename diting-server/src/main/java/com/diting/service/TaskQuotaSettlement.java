package com.diting.service;

import com.diting.common.BizException;
import com.diting.common.BusinessTime;
import com.diting.common.QuotaState;
import com.diting.common.ResultCode;
import com.diting.entity.QuotaLedger;
import com.diting.entity.RecognitionTask;
import com.diting.mapper.RecognitionTaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 云端结果已确定时，同一数据库事务发布任务状态和额度流水；外部云调用永远不在事务内。
 * 先锁任务行，再做 ledger CAS，避免删除、回调和重领的旧工作者跨代结算。
 */
@Service
public class TaskQuotaSettlement {
    private final RecognitionTaskMapper tasks;
    private final QuotaService quota;

    public TaskQuotaSettlement(RecognitionTaskMapper tasks, QuotaService quota) {
        this.tasks = tasks;
        this.quota = quota;
    }

    @Transactional
    public boolean confirmAccepted(RecognitionTask snapshot, String remoteTaskId, int pollIntervalSeconds) {
        if (!matches(snapshot, "SUBMITTING", false)) {
            return false;
        }
        LocalDateTime next = BusinessTime.now().plusSeconds(pollIntervalSeconds);
        if (tasks.markSubmitted(snapshot.getId(), snapshot.getVersion(), remoteTaskId, next) != 1) {
            return false;
        }
        if (!quota.consume(snapshot.getId())) {
            throw new BizException(ResultCode.CONFLICT, "远端已受理，但额度流水未能确认；任务状态已回滚");
        }
        snapshot.setVersion(snapshot.getVersion() + 1);
        snapshot.setStatus("TRANSCRIBING");
        snapshot.setTaskId(remoteTaskId);
        return true;
    }

    @Transactional
    public boolean rejectAndRelease(RecognitionTask snapshot, String errorCode, String message) {
        if (!matches(snapshot, "SUBMITTING", false)) {
            return false;
        }
        if (tasks.advance(snapshot.getId(), snapshot.getVersion(), "FAILED", null,
            errorCode, message, BusinessTime.now()) != 1) {
            return false;
        }
        if (!quota.release(snapshot.getId())) {
            throw new BizException(ResultCode.CONFLICT, "明确拒绝的额度未能退还；任务状态已回滚");
        }
        snapshot.setVersion(snapshot.getVersion() + 1);
        snapshot.setStatus("FAILED");
        return true;
    }

    /** 兼容旧版或单边写入留下的「有 TaskId、额度仍 RESERVED」；结算不成功就不推进解析。 */
    @Transactional
    public void ensureAcceptedConsumed(RecognitionTask snapshot) {
        RecognitionTask live = tasks.lockById(snapshot.getId());
        if (live == null || live.getDeletedAt() != null
            || !Objects.equals(live.getVersion(), snapshot.getVersion())
            || !Objects.equals(live.getTaskId(), snapshot.getTaskId()) || live.getTaskId() == null) {
            throw new BizException(ResultCode.CONFLICT, "任务已变化，不能补结额度");
        }
        QuotaLedger ledger = quota.ledgerOf(snapshot.getId());
        if (ledger == null) {
            throw new BizException(ResultCode.CONFLICT, "缺少额度流水，停止推进远端任务");
        }
        if (QuotaState.RESERVED.name().equals(ledger.getState())) {
            if (!quota.consume(snapshot.getId())) {
                throw new BizException(ResultCode.CONFLICT, "额度补结失败，稍后重试");
            }
        }
        else if (!QuotaState.CONSUMED.name().equals(ledger.getState())) {
            throw new BizException(ResultCode.CONFLICT, "远端已受理，但额度不是预占或已消费状态");
        }
    }

    private boolean matches(RecognitionTask snapshot, String status, boolean hasRemoteId) {
        RecognitionTask live = tasks.lockById(snapshot.getId());
        return live != null && live.getDeletedAt() == null
            && Objects.equals(live.getVersion(), snapshot.getVersion())
            && status.equals(live.getStatus()) && (live.getTaskId() != null) == hasRemoteId;
    }
}
