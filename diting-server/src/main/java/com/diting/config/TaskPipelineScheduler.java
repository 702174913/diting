package com.diting.config;

import com.diting.service.TaskPipelineService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 作业调度入口：定时驱动 {@link TaskPipelineService#tick()}。
 *
 * 说明：
 * - 客户端轮询**不会**触发这里的调度，页面上的查询只读状态（开发文档 §5.3）；
 * - 单实例内用 fixedDelay 串行执行，避免自己和自己抢同一个任务的租约；
 * - 多实例部署时靠数据库租约 + 版本 CAS 保证同一任务只有一个处理者。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "diting.pipeline.enabled", havingValue = "true", matchIfMissing = true)
public class TaskPipelineScheduler {

    private final TaskPipelineService pipeline;

    public TaskPipelineScheduler(TaskPipelineService pipeline) {
        this.pipeline = pipeline;
    }

    @Scheduled(fixedDelayString = "${diting.pipeline.tick-interval-ms:5000}")
    public void tick() {
        try {
            int progressed = pipeline.tick();
            if (progressed > 0) {
                log.debug("作业管线推进了 {} 个任务", progressed);
            }
        }
        catch (Exception e) {
            // 调度线程不能因为单个异常退出
            log.warn("作业管线调度异常：{}", e.getMessage());
        }
    }
}
