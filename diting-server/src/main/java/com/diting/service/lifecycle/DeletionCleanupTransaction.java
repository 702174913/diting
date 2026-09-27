package com.diting.service.lifecycle;

import com.diting.common.BizException;
import com.diting.common.ResultCode;
import com.diting.service.DeletionService;

import java.time.LocalDateTime;
import java.util.List;
import com.diting.entity.DeletionJob;
import com.diting.mapper.DeletionJobMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 调度器在另一个 Spring Bean 上进入真实事务代理：持有清理作业代际行锁，
 * 让任务/账号明细的 SQL 删除原子回滚。对象删除不受数据库事务保护，必须保持幂等并可重试。
 */
@Service
public class DeletionCleanupTransaction {
    private final DeletionJobMapper jobs;

    public DeletionCleanupTransaction(DeletionJobMapper jobs) {
        this.jobs = jobs;
    }

    @Transactional
    public boolean run(RetentionService cleanup, Long jobId, String token) {
        DeletionJob claimed = jobs.lockClaimed(jobId, token);
        if (claimed == null) {
            throw new BizException(ResultCode.CONFLICT, "清理租约已变更，旧工作者不得继续删除");
        }
        List<String> keys = DeletionService.pendingObjects(claimed.getRemainingObjects());
        LocalDateTime signedUntil = cleanup.activeSignedPutUntil(keys);
        if (RetentionService.TARGET_ERASE.equals(claimed.getTargetType())) {
            cleanup.cleanupUser(claimed.getUserId());
        }
        else if (RetentionService.TARGET_TASK.equals(claimed.getTargetType())) {
            cleanup.cleanupTask(claimed.getTargetId());
        }
        else {
            throw new BizException(ResultCode.CONFLICT, "清理目标类型不受支持");
        }
        cleanup.deletePendingObjects(keys);
        if (signedUntil == null) {
            return false;
        }
        // 与明细删除同事务持久化等待期限；进程崩溃不会把仅删除一次的签名对象误标完成。
        if (jobs.deferClaim(jobId, token, signedUntil) != 1) {
            throw new BizException(ResultCode.CONFLICT, "清理租约已变化，等待签名到期失败");
        }
        return true;
    }
}
