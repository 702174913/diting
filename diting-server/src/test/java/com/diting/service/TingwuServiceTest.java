package com.diting.service;

import com.diting.common.BizException;
import com.diting.common.ResultCode;
import com.diting.config.DitingProperties;
import com.diting.service.tingwu.StubTingwuApi;
import com.diting.service.tingwu.CloudSubmitRejectedException;
import com.diting.service.tingwu.TranscriptionParseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ASR-01/02/03：听悟客户端适配（用桩客户端喂契约样本，不连云）。
 */
class TingwuServiceTest {

    private StubTingwuApi api;
    private TingwuService service;

    @BeforeEach
    void setUp() throws Exception {
        api = new StubTingwuApi();
        OssService ossService = mock(OssService.class);
        // 桩：返回一个合法的预签名下载地址（不联网）
        when(ossService.presignDownloadForTingwu(anyString()))
            .thenAnswer(inv -> java.net.URI.create("https://oss.example.com/recordings/1/a.m4a?sig=stub").toURL());
        DitingProperties props = new DitingProperties();
        props.getAliyun().getTingwu().setAppKey("app-key-stub");
        props.getAliyun().getTingwu().setDiarizationSpeakerCount(0);
        service = new TingwuService(props, ossService, api, new com.fasterxml.jackson.databind.ObjectMapper());
    }

    private static Map<String, Object> response(String status, String transcriptionUrl) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("TaskId", "tw-task-0001");
        data.put("TaskStatus", status);
        if (transcriptionUrl != null) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("Transcription", transcriptionUrl);
            data.put("Result", result);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("Code", "0");
        body.put("Message", "success");
        body.put("Data", data);
        return body;
    }

    @Test
    @DisplayName("ASR-01-1 CreateTask 正常：从 Data.TaskId 取任务号，且转写参数在 parameters 下")
    void createTask() {
        api.willReturnCreate(response("ONGOING", null));

        String taskId = service.submitTranscription("recordings/1/a.m4a");

        assertThat(taskId).isEqualTo("tw-task-0001");
        assertThat(service.isConfigured()).isTrue();
    }

    @Test
    void createTaskNonSuccessBusinessCodeIsDefinitiveRejectionWithoutLeakingMessage() {
        api.willReturnCreate(Map.of("Code", "InvalidAppKey", "Message", "secret details"));
        assertThatThrownBy(() -> service.submitTranscription("recordings/1/a.m4a"))
            .isInstanceOf(CloudSubmitRejectedException.class)
            .hasMessageNotContaining("secret details");
    }

    @Test
    @DisplayName("ASR-01-2 请求体结构：parameters.Transcription 而非顶层 Transcription")
    void createTaskBodyShape() {
        String body = service.buildCreateTaskBody("https://oss.example.com/a.m4a", "app-key-stub", "auto:abc");

        assertThat(body).contains("\"Parameters\"");
        assertThat(body).contains("\"Transcription\"");
        assertThat(body).contains("\"DiarizationEnabled\":true");
        // 顶层不允许再出现 Transcription（旧实现的错误位置）
        int parametersIndex = body.indexOf("\"Parameters\"");
        int transcriptionIndex = body.indexOf("\"Transcription\"");
        assertThat(parametersIndex).isLessThan(transcriptionIndex);
        assertThat(body).doesNotContain("\"SpeakerCount\"");
        // 官方取值是 cn（不是 zh），且带上了业务键 TaskKey
        assertThat(body).contains("\"SourceLanguage\":\"cn\"");
        assertThat(body).contains("\"TaskKey\":\"auto:abc\"");
    }

    @Test
    void createTaskEscapesClientKeyAndSignedUrl() throws Exception {
        String key = "key\\with\"quote\nand newline";
        String url = "https://oss.example.com/a?name=\"quoted\"&other=1";
        String body = service.buildCreateTaskBody(url, "app\nkey", key);
        var parsed = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        assertThat(parsed.path("Input").path("TaskKey").asText()).isEqualTo(key);
        assertThat(parsed.path("Input").path("FileUrl").asText()).isEqualTo(url);
        assertThat(parsed.path("AppKey").asText()).isEqualTo("app\nkey");
    }

    @Test
    @DisplayName("催收通话默认指定双人，显式设为 0 时才交给云端自动判断")
    void defaultSpeakerCountIsTwo() {
        DitingProperties props = new DitingProperties();
        TingwuService withDefault = new TingwuService(props, mock(OssService.class), api, new com.fasterxml.jackson.databind.ObjectMapper());

        assertThat(withDefault.buildCreateTaskBody("https://x/a.m4a", "k", null))
            .contains("\"DiarizationEnabled\":true", "\"SpeakerCount\":2");
    }

    @Test
    @DisplayName("ASR-01-3 指定人数时才写 SpeakerCount")
    void speakerCountOptional() {
        DitingProperties props = new DitingProperties();
        props.getAliyun().getTingwu().setAppKey("k");
        props.getAliyun().getTingwu().setDiarizationSpeakerCount(3);
        TingwuService withCount = new TingwuService(props, mock(OssService.class), api, new com.fasterxml.jackson.databind.ObjectMapper());

        assertThat(withCount.buildCreateTaskBody("https://x/a.m4a", "k", null)).contains("\"SpeakerCount\":3");
    }

    @Test
    @DisplayName("ASR-03-1 未完成：状态映射为运行中，且不给结果地址")
    void ongoingStatus() {
        api.willReturnGet(response("ONGOING", null));

        TingwuService.TaskInfo info = service.getTaskInfo("tw-task-0001");

        assertThat(info.status()).isEqualTo(TingwuService.RemoteStatus.RUNNING);
        assertThat(info.transcriptionUrl()).isNull();
        assertThatThrownBy(() -> service.fetchTranscription(info))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getResultCode())
            .isEqualTo(ResultCode.PROCESSING);
    }

    @Test
    @DisplayName("ASR-03-2 排队与失败状态映射正确；未知状态不当作成功")
    void statusMapping() {
        assertThat(TingwuService.mapStatus("QUEUEING")).isEqualTo(TingwuService.RemoteStatus.QUEUED);
        assertThat(TingwuService.mapStatus("FAILED")).isEqualTo(TingwuService.RemoteStatus.FAILED);
        assertThat(TingwuService.mapStatus("completed")).isEqualTo(TingwuService.RemoteStatus.COMPLETED);
        assertThat(TingwuService.mapStatus("SOMETHING_NEW")).isEqualTo(TingwuService.RemoteStatus.UNKNOWN);

        api.willReturnGet(response("FAILED", null));
        assertThat(service.getTaskInfo("tw-task-0001").status()).isEqualTo(TingwuService.RemoteStatus.FAILED);
    }

    @Test
    @DisplayName("ASR-02-1 缺 Data 或缺 TaskStatus：明确报错")
    void missingEnvelope() {
        Map<String, Object> noData = new LinkedHashMap<>();
        noData.put("Code", "0");
        api.willReturnGet(noData);
        assertThatThrownBy(() -> service.getTaskInfo("tw-task-0001"))
            .isInstanceOf(TranscriptionParseException.class)
            .hasMessageContaining("TaskStatus");

        Map<String, Object> noStatus = new LinkedHashMap<>();
        noStatus.put("Code", "0");
        noStatus.put("Data", Map.of("TaskId", "tw-task-0001"));
        api.willReturnGet(noStatus);
        assertThatThrownBy(() -> service.getTaskInfo("tw-task-0001"))
            .hasMessageContaining("TaskStatus");
    }

    @Test
    @DisplayName("ASR-02-2 已完成但缺 Result.Transcription：报契约异常，不当成空转写")
    void completedWithoutResult() {
        api.willReturnGet(response("COMPLETED", null));

        assertThatThrownBy(() -> service.getTaskInfo("tw-task-0001"))
            .isInstanceOf(TranscriptionParseException.class)
            .hasMessageContaining("缺少 Data.Result.Transcription");
    }

    @Test
    @DisplayName("ASR-02-3 业务错误码：带出 Code 与 Message，不静默")
    void errorCode() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("Code", "400");
        body.put("Message", "AppKey invalid");
        api.willReturnGet(body);

        assertThatThrownBy(() -> service.getTaskInfo("tw-task-0001"))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("AppKey invalid");
    }

    @Test
    @DisplayName("ASR-01-4 已完成：拿到结果地址（但不在这里下载）")
    void completedGivesUrl() {
        api.willReturnGet(response("COMPLETED", "https://tingwu-result.example.com/a.json"));

        TingwuService.TaskInfo info = service.getTaskInfo("tw-task-0001");

        assertThat(info.isCompleted()).isTrue();
        assertThat(info.transcriptionUrl()).isEqualTo("https://tingwu-result.example.com/a.json");
    }

    @Test
    @DisplayName("回调体与查询响应同构，走同一套解析")
    void callbackParsed() {
        TingwuService.TaskInfo info = service.taskInfoFromCallback(response("COMPLETED",
            "https://tingwu-result.example.com/a.json"));

        assertThat(info.status()).isEqualTo(TingwuService.RemoteStatus.COMPLETED);
        assertThat(info.taskId()).isEqualTo("tw-task-0001");
    }
}
