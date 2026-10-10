package com.diting.service;

import com.diting.common.BizException;
import com.diting.common.BusinessTime;
import com.diting.common.ResultCode;
import com.diting.common.QuotaState;
import com.diting.entity.RecognitionTask;
import com.diting.mapper.RecognitionTaskMapper;
import com.diting.mapper.TaskAssetMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** OCR 网络调用不持有事务；领取及结果提交分别用任务墓碑/版本 + 资产租约令牌保护。 */
@Service
public class TaskOcrWriter {
    private final RecognitionTaskMapper tasks;
    private final TaskAssetMapper assets;
    private final QuotaService quota;

    public TaskOcrWriter(RecognitionTaskMapper tasks, TaskAssetMapper assets, QuotaService quota) {
        this.tasks = tasks; this.assets = assets; this.quota = quota;
    }
    private void lock(RecognitionTask snapshot) {
        RecognitionTask live = tasks.lockById(snapshot.getId());
        if (live == null || live.getDeletedAt() != null
            || !java.util.Objects.equals(live.getVersion(), snapshot.getVersion())
            || !("TRANSCRIBING".equals(live.getStatus()) || "PARSING".equals(live.getStatus()))) {
            throw new BizException(ResultCode.CONFLICT, "OCR工作者已失效，拒绝写入识别结果");
        }
    }
    @Transactional
    public boolean claim(RecognitionTask task, Long assetId, String token, int leaseSeconds, int maxAttempts) {
        lock(task);
        return assets.claimOcr(assetId, task.getId(), token,
            BusinessTime.now().plusSeconds(leaseSeconds), maxAttempts) == 1;
    }
    @Transactional
    public void finish(RecognitionTask task, Long assetId, String token, String status, String text, String error) {
        lock(task);
        if (assets.finishOcr(assetId, task.getId(), token, status, text, error) != 1) {
            throw new BizException(ResultCode.CONFLICT, "OCR租约已失效，丢弃迟到结果");
        }
        if ("DONE".equals(status) || "EMPTY".equals(status)) {
            // 成功响应已确认云端受理；资产结果与额度结算同事务，崩溃不留半份账。
            if (!quota.consume(task.getId())) {
                var ledger = quota.ledgerOf(task.getId());
                if (ledger == null || !QuotaState.CONSUMED.name().equals(ledger.getState())) {
                    throw new BizException(ResultCode.CONFLICT, "OCR 已受理但额度未结算，拒绝提交结果");
                }
            }
        }
    }
    @Transactional
    public boolean exhaust(RecognitionTask task, Long assetId, int maxAttempts) {
        lock(task);
        return assets.exhaustOcr(assetId, task.getId(), maxAttempts) == 1;
    }

    /**
     * 云端明确拒绝（未开通/无权限/图片本身）：当次即置终态，不再等重试次数用尽。
     * 与 {@link #exhaust} 的区别是「不重试」这个结论来自云端的错误码，而不是次数耗尽。
     */
    @Transactional
    public boolean abandon(RecognitionTask task, Long assetId, String token, int maxAttempts, String error) {
        lock(task);
        return assets.abandonOcr(assetId, task.getId(), token, maxAttempts, error) == 1;
    }
}
