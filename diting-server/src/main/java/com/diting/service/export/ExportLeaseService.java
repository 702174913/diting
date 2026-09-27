package com.diting.service.export;

import com.diting.common.BizException;
import com.diting.common.BusinessTime;
import com.diting.common.ResultCode;
import com.diting.entity.ReportVersion;
import com.diting.mapper.ExportJobMapper;
import com.diting.mapper.RecognitionTaskMapper;
import com.diting.service.report.ReportService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;

/** 导出对象上传在事务外；认领与发布必须有独立短事务、工作者令牌和任务墓碑门禁。 */
@Service
public class ExportLeaseService {
    private final ExportJobMapper jobs;
    private final RecognitionTaskMapper tasks;
    private final ReportService reports;

    public ExportLeaseService(ExportJobMapper jobs, RecognitionTaskMapper tasks, ReportService reports) {
        this.jobs = jobs;
        this.tasks = tasks;
        this.reports = reports;
    }

    public boolean claim(Long id, String token, int leaseSeconds, int maxAttempts) {
        if (jobs.claim(id, token, BusinessTime.now().plusSeconds(leaseSeconds), maxAttempts) == 1) return true;
        jobs.exhaust(id, maxAttempts);
        return false;
    }

    public void fail(Long id, String token, String error, boolean retry, int delaySeconds) {
        LocalDateTime now = BusinessTime.now();
        jobs.finishFailure(id, token, retry ? "RETRY_WAIT" : "FAILED", error,
            retry ? now.plusSeconds(delaySeconds) : null, retry ? null : now);
    }

    @Transactional
    public void publish(Long id, String token, Long versionId, String key, LocalDateTime expires) {
        ReportVersion version = reports.resolveVersionById(versionId);
        if (version == null || !"PUBLISHED".equals(version.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "报告版本已失效，导出未发布");
        }
        var task = tasks.lockById(version.getTaskId());
        if (task == null || task.getDeletedAt() != null) {
            throw new BizException(ResultCode.CONFLICT, "任务已删除，导出未发布");
        }
        if (jobs.completeClaim(id, token, key, expires) != 1) {
            throw new BizException(ResultCode.CONFLICT, "导出租约已失效，拒绝旧工作者发布");
        }
    }
}
