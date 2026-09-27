package com.diting.config;

import com.diting.service.lifecycle.RetentionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 保留期与清理调度（开发文档 §13）：默认 60 秒一轮，处理删除作业、到期原件、
 * 孤立上传与到期产物。开关只控制调度器是否装载，手动 tick 不受影响。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "diting.retention.enabled", havingValue = "true", matchIfMissing = true)
public class RetentionScheduler {

    private final RetentionService retentionService;

    public RetentionScheduler(RetentionService retentionService) {
        this.retentionService = retentionService;
    }

    @Scheduled(fixedDelayString = "${diting.retention.tick-interval-ms:60000}")
    public void tick() {
        try {
            int done = retentionService.tick();
            if (done > 0) {
                log.info("清理任务完成 {} 项", done);
            }
        }
        catch (Exception e) {
            log.warn("清理调度异常：{}", e.getMessage());
        }
    }
}
