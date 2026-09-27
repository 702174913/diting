package com.diting.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.diting.common.PageQuery;
import com.diting.common.PageResult;
import com.diting.common.R;
import com.diting.config.LoginUserHolder;
import com.diting.entity.DeletionJob;
import com.diting.dto.TaskItemResponse;
import com.diting.dto.TaskSubmitRequest;
import com.diting.entity.RecognitionTask;
import com.diting.service.DeletionService;
import com.diting.service.admin.AuditLogService;
import com.diting.service.QuotaService;
import com.diting.service.RecognitionTaskService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/task")
public class TaskController {

    private final RecognitionTaskService taskService;
    private final QuotaService quotaService;
    private final DeletionService deletionService;
    private final AuditLogService auditLogService;

    public TaskController(RecognitionTaskService taskService, QuotaService quotaService,
                          DeletionService deletionService, AuditLogService auditLogService) {
        this.taskService = taskService;
        this.quotaService = quotaService;
        this.deletionService = deletionService;
        this.auditLogService = auditLogService;
    }

    /** 登记任务并预占额度（同一事务）；上传会话必须是本人已校验的 uploadId（S16：可多文件） */
    @PostMapping("/submit")
    public R<Map<String, Long>> submit(@Valid @RequestBody TaskSubmitRequest req) {
        Long userId = LoginUserHolder.getUserId();
        RecognitionTask task = taskService.submit(userId, req.effectiveUploadIds(),
            req.declaredDurationSec(), req.idempotencyKey());
        return R.ok(Map.of("id", task.getId()));
    }

    /** 分页列表：page 从 1 起；已删除的记录不再出现 */
    @GetMapping("/list")
    public R<PageResult<TaskItemResponse>> list(@RequestParam(required = false) Integer page,
                                               @RequestParam(required = false) Integer pageSize,
                                               @RequestParam(required = false) String status) {
        Long userId = LoginUserHolder.getUserId();
        PageQuery query = PageQuery.of(page, pageSize);
        Page<RecognitionTask> result = taskService.pageOfUser(userId, query, status);
        Map<Long, RecognitionTaskService.SentenceCounts> counts =
            taskService.countSentences(result.getRecords().stream().map(RecognitionTask::getId).toList());
        List<TaskItemResponse> items = result.getRecords().stream()
            .map(task -> toResponse(task, counts))
            .toList();
        return R.ok(PageResult.of(items, result.getTotal(), query));
    }

    /** 额度概览：业务日（Asia/Shanghai）、上限、预占、已消费、剩余、时长与阈值口径 */
    @GetMapping("/quota")
    public R<QuotaService.QuotaSnapshot> quota() {
        return R.ok(quotaService.snapshot(LoginUserHolder.getUserId()));
    }

    /** 轮询兜底：同步一次听悟状态 */
    @GetMapping("/{id}/status")
    public R<TaskItemResponse> status(@PathVariable Long id) {
        Long userId = LoginUserHolder.getUserId();
        RecognitionTask task = taskService.refreshStatus(id, userId);
        Map<Long, RecognitionTaskService.SentenceCounts> counts =
            taskService.countSentences(List.of(task.getId()));
        return R.ok(toResponse(task, counts));
    }

    private static TaskItemResponse toResponse(RecognitionTask task,
                                               Map<Long, RecognitionTaskService.SentenceCounts> counts) {
        RecognitionTaskService.SentenceCounts hit = counts.get(task.getId());
        return hit == null
            ? TaskItemResponse.of(task, 0, 0)
            : TaskItemResponse.of(task, hit.flagged(), hit.total(), hit.violations(), hit.reportPublished());
    }

    /**
     * 单条删除：先写墓碑并立即不可读，再登记清理作业（幂等）。
     * 外部对象与导出产物的实际清理属 S13，这里返回清理作业号供前端展示状态。
     */
    @DeleteMapping("/{id}")
    public R<Map<String, Object>> delete(@PathVariable Long id) {
        Long userId = LoginUserHolder.getUserId();
        DeletionJob job = deletionService.deleteTask(userId, id);
        auditLogService.success("USER", userId, AuditLogService.ACTION_TASK_DELETE, "TASK",
            String.valueOf(id), AuditLogService.detail("deletionJobId", job.getId()));
        return R.ok(Map.of("deletionJobId", job.getId(), "status", job.getStatus()));
    }
}
