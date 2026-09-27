package com.diting.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.diting.common.AdminRole;
import com.diting.common.R;
import com.diting.config.RequireRole;
import com.diting.entity.QuotaLedger;
import com.diting.entity.RecognitionTask;
import com.diting.mapper.QuotaLedgerMapper;
import com.diting.mapper.RecognitionTaskMapper;
import com.diting.service.TaskPipelineService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 后台运维只读接口。
 *
 * 原「实时队列明细」(`GET /api/admin/ops/queue`，S17) 已随「质检运营」统计页一起下线；
 * 那时它是唯一带个体任务号的聚合旁路，现在队列总量只从总览的「当前队列」聚合指标看。
 * 剩下的核销对账口子是 ADMIN 专用、不返回原文/用户号/对象 key 的只读查询。
 */
@RestController
@RequestMapping("/api/admin/ops")
public class AdminOpsController {

    /** 一次最多返回的条目数：这两类查询只用于「堵在哪/要不要人工核销」，不是导出明细 */
    private static final int MAX_QUEUE_ROWS = 50;

    private final RecognitionTaskMapper taskMapper;
    private final QuotaLedgerMapper quotaLedgerMapper;

    public AdminOpsController(RecognitionTaskMapper taskMapper, QuotaLedgerMapper quotaLedgerMapper) {
        this.taskMapper = taskMapper;
        this.quotaLedgerMapper = quotaLedgerMapper;
    }

    /**
     * 只读账务核销候选：无 TaskId 的不确定提交不能自动退额或重提。
     * ADMIN 逐项取得云侧受理/账单证据后再处置；不返回原文、用户号、对象 key 或云侧响应。
     * 包含已删除任务，以免账号抹除遮蔽仍预占的额度；使用 ID 游标避免旧任务挤占整页。
     */
    public record ReconciliationItem(Long id, String status, String errorCode, String quotaState,
                                     boolean hasRemoteTaskId, boolean deleted, String createdAt) {
    }

    public record ReconciliationPage(List<ReconciliationItem> items, Long nextAfterId) {
    }

    @GetMapping("/reconciliation")
    @RequireRole(AdminRole.ADMIN)
    public R<ReconciliationPage> reconciliation(@RequestParam(required = false) Integer limit,
                                                 @RequestParam(required = false) Long afterId) {
        int size = queueLimit(limit);
        LambdaQueryWrapper<RecognitionTask> wrapper = new LambdaQueryWrapper<RecognitionTask>()
            .in(RecognitionTask::getErrorCode, List.of(
                TaskPipelineService.ERR_SUBMIT_UNCERTAIN, TaskPipelineService.ERR_SUBMIT_UNRECONCILED,
                TaskPipelineService.ERR_QUOTA_UNSETTLED, TaskPipelineService.ERR_SUBMIT_REJECTED,
                TaskPipelineService.ERR_REMOTE_FAILED, TaskPipelineService.ERR_OCR_FAILED))
            .orderByAsc(RecognitionTask::getId)
            .last("LIMIT " + size);
        if (afterId != null && afterId > 0) {
            wrapper.gt(RecognitionTask::getId, afterId);
        }
        List<RecognitionTask> tasks = taskMapper.selectList(wrapper);
        if (tasks.isEmpty()) {
            return R.ok(new ReconciliationPage(List.of(), null));
        }
        List<Long> ids = tasks.stream().map(RecognitionTask::getId).toList();
        List<QuotaLedger> ledgers = quotaLedgerMapper.selectList(new LambdaQueryWrapper<QuotaLedger>()
            .in(QuotaLedger::getTaskId, ids));
        Map<Long, String> quotaStates = ledgers.stream()
            .collect(Collectors.toMap(QuotaLedger::getTaskId, QuotaLedger::getState));
        List<ReconciliationItem> items = tasks.stream()
            .map(task -> new ReconciliationItem(task.getId(), task.getStatus(), task.getErrorCode(),
                quotaStates.get(task.getId()), task.getTaskId() != null && !task.getTaskId().isBlank(),
                task.getDeletedAt() != null,
                task.getCreatedAt() == null ? null : task.getCreatedAt().toString()))
            .toList();
        return R.ok(new ReconciliationPage(items, tasks.get(tasks.size() - 1).getId()));
    }

    /** 上限收敛：客户端只能把上限调小，不能拉出全量明细 */
    static int queueLimit(Integer limit) {
        return limit == null || limit <= 0 ? MAX_QUEUE_ROWS : Math.min(limit, MAX_QUEUE_ROWS);
    }
}
