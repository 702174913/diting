package com.diting.service;

import com.diting.common.BizException;
import com.diting.common.ResultCode;
import com.diting.config.DitingProperties;
import com.diting.service.tingwu.JsonNodeReader;
import com.diting.service.tingwu.CloudSubmitRejectedException;
import com.diting.service.tingwu.NotConfiguredTingwuApi;
import com.diting.service.tingwu.ResultUrlPolicy;
import com.diting.service.tingwu.TingwuApi;
import com.diting.service.tingwu.TranscriptionDownloader;
import com.diting.service.tingwu.TranscriptionParseException;
import com.diting.service.tingwu.TranscriptionParser;
import org.springframework.stereotype.Service;

import java.net.URL;
import java.util.Map;

/**
 * 通义听悟客户端适配（开发文档 §5.1—§5.3）。
 *
 * 契约要点：
 * - CreateTask 请求体：转写参数位于 **parameters.Transcription**（不是顶层 Transcription）；
 * - 响应读 **Data** 层：TaskId / TaskStatus / Result.Transcription 都在 Data 下；
 * - 未完成的任务不得去解引用缺失的 Result；已完成但缺 Result 视为契约异常；
 * - 只用通用 callApi 拿原始响应体（SDK 2.0.0 强类型模型缺 Result 字段，已记录在开发文档 §2）。
 */
@Service
public class TingwuService {

    private final DitingProperties props;
    private final OssService ossService;
    private final TingwuApi api;
    private final TranscriptionDownloader downloader;
    private final TranscriptionParser parser;

    public TingwuService(DitingProperties props, OssService ossService, TingwuApi api) {
        this.props = props;
        this.ossService = ossService;
        this.api = api;
        this.downloader = new TranscriptionDownloader(new ResultUrlPolicy.Strict(
            props.getAliyun().getTingwu().isAllowHttpResult()
                ? java.util.Arrays.stream(props.getAliyun().getTingwu().getHttpResultHostSuffixes().split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList()
                : java.util.List.of()));
        this.parser = new TranscriptionParser();
    }

    /** 远端状态 → 内部可判定的四态 + 未知 */
    public enum RemoteStatus {
        QUEUED, RUNNING, COMPLETED, FAILED, UNKNOWN
    }

    /** 远端任务信息：只暴露我们真正要用到的字段，其他一律不解析 */
    public record TaskInfo(String taskId, RemoteStatus status, String transcriptionUrl, String message) {

        public boolean isCompleted() {
            return status == RemoteStatus.COMPLETED;
        }
    }

    /** 提交离线转写，返回听悟任务号 */
    public String submitTranscription(String objectKey) {
        return submitTranscription(objectKey, null);
    }

    /**
     * 提交离线转写。
     * businessKey 会写进 Input.TaskKey（官方文档说明为「用户自行设置的自定义标识」），
     * 用于提交结果不确定时按业务键对账与排查。
     */
    public String submitTranscription(String objectKey, String businessKey) {
        URL downloadUrl = ossService.presignDownloadForTingwu(objectKey);
        String body = buildCreateTaskBody(downloadUrl.toString(),
            props.getAliyun().getTingwu().getAppKey(), businessKey);
        Map<String, ?> response = api.createTask(body);
        JsonNodeReader reader = JsonNodeReader.of(response);
        String code = reader.text("Code");
        if (code != null && !"0".equals(code) && !"200".equals(code)) {
            throw new CloudSubmitRejectedException(code);
        }
        String taskId = reader.text("Data.TaskId");
        if (taskId == null || taskId.isBlank()) {
            throw new TranscriptionParseException("创建转写任务失败：响应缺少 Data.TaskId");
        }
        return taskId;
    }

    /** 查询任务（回调与轮询共用） */
    public TaskInfo getTaskInfo(String taskId) {
        return parseTaskInfo(api.getTask(taskId), taskId);
    }

    /** 回调体与查询响应同构，统一走同一套解析 */
    public TaskInfo taskInfoFromCallback(Map<String, ?> payload) {
        return parseTaskInfo(payload, null);
    }

    /** 下载并解析转写结果：只有 COMPLETED 才允许调用 */
    public TranscriptionParser.ParsedTranscription fetchTranscription(TaskInfo info) {
        if (info == null || !info.isCompleted()) {
            throw new BizException(ResultCode.PROCESSING, "任务尚未完成，结果不可读取");
        }
        if (info.transcriptionUrl() == null || info.transcriptionUrl().isBlank()) {
            throw new TranscriptionParseException("任务已完成但缺少 Result.Transcription");
        }
        return parser.parse(downloader.download(info.transcriptionUrl()));
    }

    /** CreateTask 请求体：转写参数放在 parameters 下 */
    String buildCreateTaskBody(String fileUrl, String appKey, String businessKey) {
        String diarization = speakerCount() > 0
            ? """
              "Transcription": { "DiarizationEnabled": true, "Diarization": { "SpeakerCount": %d } }"""
            .formatted(speakerCount())
            : """
              "Transcription": { "DiarizationEnabled": true }""";
        String taskKey = businessKey == null || businessKey.isBlank()
            ? ""
            : "\n                \"TaskKey\": \"%s\",".formatted(businessKey.replace("\"", ""));
        // SourceLanguage 官方取值是 cn（不是 zh）；TaskKey 用于业务对账
        return """
            {
              "AppKey": "%s",
              "Input": {
                "SourceLanguage": "%s",%s
                "FileUrl": "%s",
                "ProgressiveCallbacksEnabled": true
              },
              "Parameters": {
                %s
              }
            }
            """.formatted(appKey, sourceLanguage(), taskKey, fileUrl, diarization.indent(0).trim());
    }

    private String sourceLanguage() {
        String configured = props.getAliyun().getTingwu().getSourceLanguage();
        return configured == null || configured.isBlank() ? "cn" : configured.trim();
    }

    private int speakerCount() {
        // 0 表示不指定人数，交由云端声学聚类自行判断（多人场景不强行按两人切）
        Integer configured = props.getAliyun().getTingwu().getDiarizationSpeakerCount();
        return configured == null ? 0 : Math.max(0, configured);
    }

    /**
     * 解析任务信息：缺失字段一律明确报错，不做兜底。
     * 已完成但缺 Result 属于契约异常，必须暴露出来而不是静默当成“空转写”。
     */
    private TaskInfo parseTaskInfo(Map<String, ?> response, String fallbackTaskId) {
        if (response == null || response.isEmpty()) {
            throw new TranscriptionParseException("听悟响应为空");
        }
        JsonNodeReader reader = JsonNodeReader.of(response);
        String code = reader.text("Code");
        if (code != null && !"0".equals(code) && !"200".equals(code)) {
            throw new BizException(ResultCode.FAILED,
                "听悟返回错误（Code " + code + "）：" + reader.text("Message"));
        }
        String taskId = reader.text("Data.TaskId");
        if (taskId == null) {
            taskId = fallbackTaskId;
        }
        String rawStatus = reader.text("Data.TaskStatus");
        if (rawStatus == null || rawStatus.isBlank()) {
            throw new TranscriptionParseException("听悟响应缺少 Data.TaskStatus");
        }
        RemoteStatus status = mapStatus(rawStatus);
        String transcriptionUrl = null;
        if (status == RemoteStatus.COMPLETED) {
            transcriptionUrl = reader.text("Data.Result.Transcription");
            if (transcriptionUrl == null || transcriptionUrl.isBlank()) {
                throw new TranscriptionParseException("任务已完成但响应缺少 Data.Result.Transcription");
            }
        }
        return new TaskInfo(taskId, status, transcriptionUrl, reader.text("Data.Message"));
    }

    /** 远端状态映射：QUEUEING/ONGOING → 运行中；不接受未知状态静默当成成功 */
    static RemoteStatus mapStatus(String rawStatus) {
        String value = rawStatus.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (value) {
            case "QUEUEING", "QUEUED", "PENDING" -> RemoteStatus.QUEUED;
            case "ONGOING", "RUNNING", "TRANSCRIBING" -> RemoteStatus.RUNNING;
            case "COMPLETED", "SUCCESS", "SUCCEEDED" -> RemoteStatus.COMPLETED;
            case "FAILED", "ERROR" -> RemoteStatus.FAILED;
            default -> RemoteStatus.UNKNOWN;
        };
    }

    /** 未配置凭据时立刻失败，避免带着占位串去调云 */
    public boolean isConfigured() {
        return !(api instanceof NotConfiguredTingwuApi);
    }
}
