package com.diting.controller;

import com.diting.common.BizException;
import com.diting.common.R;
import com.diting.service.TaskPipelineService;
import com.diting.service.tingwu.StubTingwuApi;
import com.diting.service.tingwu.TingwuApi;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * **仅测试 profile** 的作业驱动接口：
 * - 手动触发一轮调度（E2E 里确定性推进，不依赖定时器）；
 * - 编排听悟桩客户端的响应，模拟排队/运行/完成/失败与结果地址失效。
 *
 * 生产不会加载（@Profile("test")），且非桩客户端会直接拒绝。
 */
@Profile("test")
@RestController
@RequestMapping("/api/test/pipeline")
public class TestPipelineController {

    private final TaskPipelineService pipeline;
    private final TingwuApi tingwuApi;
    private final com.diting.service.export.ExportService exportService;
    private final com.diting.service.lifecycle.RetentionService retentionService;

    public TestPipelineController(TaskPipelineService pipeline, TingwuApi tingwuApi,
                                  com.diting.service.export.ExportService exportService,
                                  com.diting.service.lifecycle.RetentionService retentionService) {
        this.pipeline = pipeline;
        this.tingwuApi = tingwuApi;
        this.exportService = exportService;
        this.retentionService = retentionService;
    }

    @PostMapping("/tick")
    public R<Map<String, Object>> tick() {
        int progressed = pipeline.tick();
        return R.ok(Map.of("progressed", progressed));
    }

    /** 手动推进一轮导出作业（E2E 用它做确定性验证） */
    @PostMapping("/export-tick")
    public R<Map<String, Object>> exportTick() {
        return R.ok(Map.of("generated", exportService.tick()));
    }

    /** 手动推进一轮清理（E2E 用它做确定性验证） */
    @PostMapping("/retention-tick")
    public R<Map<String, Object>> retentionTick() {
        return R.ok(Map.of("processed", retentionService.tick()));
    }

    public record ScriptRequest(String createTaskId, String status, String transcriptionUrl) {
    }

    @PostMapping("/tingwu-script")
    public R<Map<String, Object>> script(@Validated @RequestBody ScriptRequest req) {
        if (!(tingwuApi instanceof StubTingwuApi stub)) {
            throw new BizException(com.diting.common.ResultCode.NOT_CONFIGURED,
                "该接口只在使用桩听悟客户端（diting.aliyun.tingwu.type=stub）的测试环境可用");
        }
        String taskId = req.createTaskId() == null ? "tw-stub-task" : req.createTaskId();
        Map<String, Object> create = new LinkedHashMap<>();
        create.put("Code", "0");
        create.put("Data", Map.of("TaskId", taskId));
        stub.willReturnCreate(create);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("TaskId", taskId);
        data.put("TaskStatus", req.status() == null ? "ONGOING" : req.status());
        if (req.transcriptionUrl() != null) {
            data.put("Result", Map.of("Transcription", req.transcriptionUrl()));
        }
        Map<String, Object> get = new LinkedHashMap<>();
        get.put("Code", "0");
        get.put("Data", data);
        stub.willReturnGet(get);
        return R.ok(Map.of("taskId", taskId, "status", data.get("TaskStatus")));
    }
}
