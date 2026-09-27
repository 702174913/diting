package com.diting.controller;

import com.diting.common.R;
import com.diting.config.LoginUserHolder;
import com.diting.dto.ReportDetailResponse;
import com.diting.entity.RecognitionTask;
import com.diting.service.RecognitionTaskService;
import com.diting.service.report.ReportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 质检记录读取（开发文档 §7.2）。
 * 归属校验复用任务服务：不是本人的记录一律 404，不泄露存在性。
 */
@RestController
@RequestMapping("/api/report")
public class ReportController {

    private final RecognitionTaskService taskService;
    private final ReportService reportService;
    public ReportController(RecognitionTaskService taskService, ReportService reportService) {
        this.taskService = taskService;
        this.reportService = reportService;
    }

    /** 读取报告：不传 version 时取最新已发布版本；重算失败时返回最近成功版本并带状态说明 */
    @GetMapping("/{id}")
    public R<ReportDetailResponse> detail(@PathVariable Long id,
                                          @RequestParam(required = false) Long version) {
        Long userId = LoginUserHolder.getUserId();
        RecognitionTask task = taskService.requireOwned(id, userId);
        return R.ok(reportService.detail(task, version));
    }

}
