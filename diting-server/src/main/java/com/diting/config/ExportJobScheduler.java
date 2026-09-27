package com.diting.config;

import com.diting.service.export.ExportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 导出作业调度：定时把 QUEUED 作业生成产物。
 * 与任务管线同一风格：单实例串行，靠数据库状态保证幂等（同作业不会被并发生成）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "diting.export.enabled", havingValue = "true", matchIfMissing = true)
public class ExportJobScheduler {

    private final ExportService exportService;

    public ExportJobScheduler(ExportService exportService) {
        this.exportService = exportService;
    }

    @Scheduled(fixedDelayString = "${diting.export.tick-interval-ms:3000}")
    public void tick() {
        try {
            int done = exportService.tick();
            if (done > 0) {
                log.debug("导出作业完成 {} 个", done);
            }
        }
        catch (Exception e) {
            log.warn("导出调度异常：{}", e.getMessage());
        }
    }
}
