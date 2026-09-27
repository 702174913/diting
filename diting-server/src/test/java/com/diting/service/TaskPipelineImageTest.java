package com.diting.service;

import com.diting.common.BizException;
import com.diting.common.MediaKind;
import com.diting.common.ResultCode;
import com.diting.common.TaskStatus;
import com.diting.config.DitingProperties;
import com.diting.entity.RecognitionTask;
import com.diting.entity.Sentence;
import com.diting.entity.TaskAsset;
import com.diting.mapper.RecognitionTaskMapper;
import com.diting.mapper.SentenceMapper;
import com.diting.mapper.TaskAssetMapper;
import com.diting.service.ocr.OcrApiException;
import com.diting.service.ocr.OcrParser;
import com.diting.service.ocr.StubOcrApi;
import com.diting.service.report.ReportService;
import com.diting.service.storage.ObjectStorage;
import com.diting.service.tingwu.TranscriptionParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S16 图片分支：图片任务的管线走向与失败处理。
 *
 * 覆盖：纯图片跳过云端提交直接 OCR → PARSING → 发布报告；OCR 失败按次数上限重试；
 * 未配置 OCR 时不动状态；图片里没有文字判 INVALID；混合记录追加图片句子后自动发布。
 */
class TaskPipelineImageTest {

    private RecognitionTaskMapper taskMapper;
    private SentenceMapper sentenceMapper;
    private TingwuService tingwuService;
    private QuotaService quotaService;
    private ConfigService configService;
    private OcrService ocrService;
    private TaskAssetMapper taskAssetMapper;
    private ObjectStorage objectStorage;
    private ReportService reportService;
    private TaskPipelineService pipeline;

    @BeforeEach
    void setUp() {
        taskMapper = mock(RecognitionTaskMapper.class);
        sentenceMapper = mock(SentenceMapper.class);
        tingwuService = mock(TingwuService.class);
        quotaService = mock(QuotaService.class);
        configService = mock(ConfigService.class);
        ocrService = mock(OcrService.class);
        taskAssetMapper = mock(TaskAssetMapper.class);
        objectStorage = mock(ObjectStorage.class);
        reportService = mock(ReportService.class);

        when(configService.load()).thenReturn(new ConfigService.PolicyConfig(1L, 3, 60, 10));
        when(tingwuService.isConfigured()).thenReturn(true);
        when(ocrService.isConfigured()).thenReturn(true);
        when(taskMapper.claim(anyLong(), anyLong(), anyString(), any())).thenReturn(1);
        when(taskMapper.advance(anyLong(), anyLong(), anyString(), any(), any(), any(), any())).thenReturn(1);
        when(taskAssetMapper.claimOcr(anyLong(), anyLong(), anyString(), any(), anyInt())).thenReturn(1);
        when(taskAssetMapper.finishOcr(anyLong(), anyLong(), anyString(), anyString(), any(), any())).thenAnswer(inv -> {
            Long assetId = inv.getArgument(0);
            String state = inv.getArgument(3);
            for (TaskAsset row : taskAssetMapper.selectImagesToOcr(inv.getArgument(1))) {
                if (row.getId().equals(assetId)) {
                    row.setOcrStatus(state);
                    row.setOcrText(inv.getArgument(4));
                }
            }
            return 1;
        });

        DitingProperties props = new DitingProperties();
        props.getPipeline().setBatchSize(5);
        props.getPipeline().setMaxAttempts(3);
        pipeline = new TaskPipelineService(taskMapper, sentenceMapper, tingwuService,
            configService, props, ocrService, taskAssetMapper, objectStorage, reportService,
            new TaskTextWriter(taskMapper, sentenceMapper, taskAssetMapper),
            new TaskOcrWriter(taskMapper, taskAssetMapper, quotaService), mock(TaskQuotaSettlement.class));
        when(taskMapper.lockById(anyLong())).thenAnswer(inv -> taskMapper.selectDue(5).stream()
            .filter(task -> task.getId().equals(inv.getArgument(0))).findFirst().orElse(null));
    }

    private static RecognitionTask imageTask(long id, TaskStatus status) {
        RecognitionTask task = new RecognitionTask();
        task.setId(id);
        task.setUserId(1L);
        task.setStatus(status.name());
        task.setMediaKind(MediaKind.IMAGE.name());
        task.setVersion(0L);
        task.setAttemptCount(0);
        return task;
    }

    private static TaskAsset imageAsset(long id, String key) {
        TaskAsset asset = new TaskAsset();
        asset.setId(id);
        asset.setTaskId(200L);
        asset.setKind(TaskAsset.KIND_IMAGE);
        asset.setSequence(1);
        asset.setObjectKey(key);
        asset.setOcrStatus(TaskAsset.OCR_PENDING);
        return asset;
    }

    private static byte[] jpeg() {
        return new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0};
    }

    @Test
    @DisplayName("纯图片任务：跳过云端提交，QUEUED → SUBMITTING 登记，再进 TRANSCRIBING")
    void imageOnlyTaskSkipsCloudSubmit() {
        RecognitionTask task = imageTask(200L, TaskStatus.QUEUED);
        when(taskMapper.selectDue(anyInt())).thenReturn(List.of(task));

        pipeline.tick();

        // 状态机只允许 QUEUED → SUBMITTING；图片任务在 SUBMITTING 里不提交云端，直接转入识别态
        verify(tingwuService, never()).submitTranscription(anyString(), any());
        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        verify(taskMapper).advance(eq(200L), anyLong(), status.capture(), any(), any(), any(), any());
        assertThat(status.getValue()).isEqualTo(TaskStatus.TRANSCRIBING.name());
    }

    @Test
    @DisplayName("TRANSCRIBING：执行 OCR 落库后进入 PARSING")
    void imageOnlyTaskRunsOcrThenParsing() {
        RecognitionTask task = imageTask(206L, TaskStatus.TRANSCRIBING);
        when(taskMapper.selectDue(anyInt())).thenReturn(List.of(task));
        TaskAsset asset = imageAsset(9L, "images/1/a.jpg");
        when(taskAssetMapper.selectImagesToOcr(206L)).thenReturn(List.of(asset));

        when(objectStorage.read("images/1/a.jpg")).thenReturn(jpeg());
        // OcrService 返回的是「按阅读顺序排好的行」；这里用桩数据走真实解析
        when(ocrService.recognize(any())).thenReturn(OcrParser.parse(StubOcrApi.STUB_DATA));
        when(taskAssetMapper.selectByTask(206L)).thenReturn(List.of());
        when(sentenceMapper.selectMaxSequence(206L)).thenReturn(0);

        pipeline.tick();

        verify(taskAssetMapper).finishOcr(eq(9L), eq(206L), anyString(), eq("DONE"), anyString(), any());
        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        verify(taskMapper).advance(eq(206L), anyLong(), status.capture(), any(), any(), any(), any());
        assertThat(status.getValue()).isEqualTo(TaskStatus.PARSING.name());
    }

    @Test
    @DisplayName("纯图片任务进入 PARSING：直接发布报告（没有说话人可确认，跳过 WAITING_ROLE）")
    void imageOnlyTaskPublishesReport() {
        RecognitionTask task = imageTask(201L, TaskStatus.PARSING);
        when(taskMapper.selectDue(anyInt())).thenReturn(List.of(task));
        when(sentenceMapper.selectMaxSequence(201L)).thenReturn(3);
        when(taskAssetMapper.selectByTask(201L)).thenReturn(List.of());

        pipeline.tick();

        // 调度器不在发布事务外提前写 ANALYZING；该状态由报告事务统一推进。
        verify(taskMapper, never()).advance(anyLong(), anyLong(), anyString(), any(), any(), any(), any());
        verify(reportService).analyzeWithoutRoles(task);
    }

    @Test
    @DisplayName("图片里没有文字：判 INVALID，不发布空报告")
    void imageWithoutTextIsInvalid() {
        RecognitionTask task = imageTask(202L, TaskStatus.PARSING);
        when(taskMapper.selectDue(anyInt())).thenReturn(List.of(task));
        when(sentenceMapper.selectMaxSequence(202L)).thenReturn(0);
        when(taskAssetMapper.selectByTask(202L)).thenReturn(List.of());

        pipeline.tick();

        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(taskMapper).advance(eq(202L), anyLong(), status.capture(), any(),
            error.capture(), any(), any());
        assertThat(status.getValue()).isEqualTo(TaskStatus.INVALID.name());
        assertThat(error.getValue()).isEqualTo(TaskPipelineService.ERR_NO_TEXT);
        verify(reportService, never()).analyzeWithoutRoles(any());
    }

    @Test
    @DisplayName("OCR 失败：留在 TRANSCRIBING 重试并记 OCR_FAILED，不把任务判死")
    void ocrFailureRetries() {
        RecognitionTask task = imageTask(203L, TaskStatus.TRANSCRIBING);
        when(taskMapper.selectDue(anyInt())).thenReturn(List.of(task));
        TaskAsset asset = imageAsset(11L, "images/1/b.jpg");
        when(taskAssetMapper.selectImagesToOcr(203L)).thenReturn(List.of(asset));

        when(objectStorage.read("images/1/b.jpg")).thenReturn(jpeg());
        when(ocrService.recognize(any())).thenThrow(new IllegalStateException("OCR 调用失败：timeout"));

        pipeline.tick();

        verify(taskAssetMapper).finishOcr(eq(11L), eq(203L), anyString(), eq("FAILED"), any(), eq("OCR_UNCERTAIN"));
        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(taskMapper).advance(eq(203L), anyLong(), status.capture(), any(), error.capture(), any(), any());
        assertThat(status.getValue()).isEqualTo(TaskStatus.TRANSCRIBING.name());
        assertThat(error.getValue()).isEqualTo(TaskPipelineService.ERR_OCR_FAILED);
    }

    @Test
    void crashedOcrLeaseCanBeReclaimed() {
        RecognitionTask task = imageTask(214L, TaskStatus.TRANSCRIBING);
        when(taskMapper.selectDue(anyInt())).thenReturn(List.of(task));
        TaskAsset asset = imageAsset(14L, "images/1/recover.jpg");
        asset.setOcrStatus(TaskAsset.OCR_RUNNING);
        asset.setOcrAttempts(1);
        when(taskAssetMapper.selectImagesToOcr(214L)).thenReturn(List.of(asset));
        when(objectStorage.read(asset.getObjectKey())).thenReturn(jpeg());
        when(ocrService.recognize(any())).thenReturn(OcrParser.parse(StubOcrApi.STUB_DATA));
        pipeline.tick();
        verify(taskAssetMapper).claimOcr(eq(14L), eq(214L), anyString(), any(), eq(3));
        verify(taskAssetMapper).finishOcr(eq(14L), eq(214L), anyString(), eq("DONE"), anyString(), any());
    }

    @Test
    void exhaustedOcrNeverPublishesPartialNoProblemReport() {
        RecognitionTask task = imageTask(215L, TaskStatus.TRANSCRIBING);
        when(taskMapper.selectDue(anyInt())).thenReturn(List.of(task));
        TaskAsset asset = imageAsset(15L, "images/1/rejected.jpg");
        asset.setOcrStatus(TaskAsset.OCR_FAILED);
        asset.setOcrAttempts(3);
        when(taskAssetMapper.selectImagesToOcr(215L)).thenReturn(List.of(asset));
        when(taskAssetMapper.exhaustOcr(15L, 215L, 3)).thenReturn(1);
        pipeline.tick();
        verify(taskAssetMapper).exhaustOcr(15L, 215L, 3);
        verify(taskMapper).advance(eq(215L), anyLong(), eq("FAILED"), any(),
            eq(TaskPipelineService.ERR_OCR_FAILED), any(), any());
        verify(ocrService, never()).recognize(any());
        verify(reportService, never()).analyzeWithoutRoles(any());
    }

    @Test
    @DisplayName("云端明确拒绝（未开通/无权限）：当次即置终态，不再拿同一张图重复付费重试")
    void ocrCloudRejectionFailsFast() {
        RecognitionTask task = imageTask(216L, TaskStatus.TRANSCRIBING);
        when(taskMapper.selectDue(anyInt())).thenReturn(List.of(task));
        TaskAsset asset = imageAsset(16L, "images/1/not-open.jpg");
        when(taskAssetMapper.selectImagesToOcr(216L)).thenReturn(List.of(asset));
        when(objectStorage.read(asset.getObjectKey())).thenReturn(jpeg());
        when(ocrService.recognize(any())).thenThrow(
            OcrApiException.ofCode("ocrServiceNotOpen", "You have not activated the OCR service."));

        pipeline.tick();

        // 官方错误码留在资产行上（以前只落常量 INTERNAL_ERROR，分不清没开通还是图坏了）
        verify(taskAssetMapper).abandonOcr(eq(16L), eq(216L), anyString(), eq(3), eq("ocrServiceNotOpen"));
        verify(taskAssetMapper, never()).finishOcr(eq(16L), eq(216L), anyString(), eq("FAILED"), any(), any());
        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(taskMapper).advance(eq(216L), anyLong(), status.capture(), any(), error.capture(), any(), any());
        assertThat(status.getValue()).isEqualTo(TaskStatus.FAILED.name());
        assertThat(error.getValue()).isEqualTo(TaskPipelineService.ERR_OCR_REJECTED);
        verify(reportService, never()).analyzeWithoutRoles(any());
    }

    @Test
    @DisplayName("限流等可重试失败：错误码照样落库，任务继续重试")
    void throttledOcrKeepsCodeAndRetries() {
        RecognitionTask task = imageTask(217L, TaskStatus.TRANSCRIBING);
        when(taskMapper.selectDue(anyInt())).thenReturn(List.of(task));
        TaskAsset asset = imageAsset(17L, "images/1/throttled.jpg");
        when(taskAssetMapper.selectImagesToOcr(217L)).thenReturn(List.of(asset));
        when(objectStorage.read(asset.getObjectKey())).thenReturn(jpeg());
        when(ocrService.recognize(any())).thenThrow(
            OcrApiException.ofCode("Throttling.User", "超过限流阈值"));

        pipeline.tick();

        verify(taskAssetMapper).finishOcr(eq(17L), eq(217L), anyString(), eq("FAILED"), any(), eq("Throttling.User"));
        verify(taskAssetMapper, never()).abandonOcr(anyLong(), anyLong(), anyString(), anyInt(), any());
        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(taskMapper).advance(eq(217L), anyLong(), status.capture(), any(), error.capture(), any(), any());
        assertThat(status.getValue()).isEqualTo(TaskStatus.TRANSCRIBING.name());
        assertThat(error.getValue()).isEqualTo(TaskPipelineService.ERR_OCR_FAILED);
    }

    @Test
    @DisplayName("本地预检拒绝（尺寸/格式）：同样当次判死，不等次数用尽")
    void ocrPrecheckRejectionFailsFast() {
        RecognitionTask task = imageTask(218L, TaskStatus.TRANSCRIBING);
        when(taskMapper.selectDue(anyInt())).thenReturn(List.of(task));
        TaskAsset asset = imageAsset(18L, "images/1/too-big.jpg");
        when(taskAssetMapper.selectImagesToOcr(218L)).thenReturn(List.of(asset));
        when(objectStorage.read(asset.getObjectKey())).thenReturn(jpeg());
        when(ocrService.recognize(any())).thenThrow(
            new BizException(ResultCode.UNPROCESSABLE, "图片尺寸超出识别范围：长宽需大于 15 像素且小于 8192 像素"));

        pipeline.tick();

        verify(taskAssetMapper).abandonOcr(eq(18L), eq(218L), anyString(), eq(3), eq("UNPROCESSABLE"));
        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(taskMapper).advance(eq(218L), anyLong(), status.capture(), any(), error.capture(), any(), any());
        assertThat(status.getValue()).isEqualTo(TaskStatus.FAILED.name());
        assertThat(error.getValue()).isEqualTo(TaskPipelineService.ERR_OCR_REJECTED);
    }

    @Test
    @DisplayName("落库口径闭环：管线写的行文本能被 TaskTextWriter 重建出同样的句子（旧口径会抛解析异常）")
    void storedOcrTextRebuildsTheSameSentences() {
        RecognitionTask task = imageTask(219L, TaskStatus.TRANSCRIBING);
        when(taskMapper.selectDue(anyInt())).thenReturn(List.of(task));
        TaskAsset asset = imageAsset(19L, "images/1/roundtrip.jpg");
        when(taskAssetMapper.selectImagesToOcr(219L)).thenReturn(List.of(asset));
        when(objectStorage.read(asset.getObjectKey())).thenReturn(jpeg());
        when(ocrService.recognize(any())).thenReturn(OcrParser.parse(StubOcrApi.STUB_DATA));
        // 识别完的同轮就会重建图片句子（harness 的 finishOcr 桩会把文本写回同一行）
        when(taskAssetMapper.selectByTask(219L)).thenReturn(List.of(asset));
        when(sentenceMapper.selectMaxSequence(219L)).thenReturn(0);

        pipeline.tick();

        assertThat(asset.getOcrText()).isEqualTo("我保证能减免利息。\n请你今天把款还上。");
        ArgumentCaptor<Sentence> rows = ArgumentCaptor.forClass(Sentence.class);
        verify(sentenceMapper, times(2)).insert(rows.capture());
        assertThat(rows.getAllValues()).extracting(Sentence::getText)
            .containsExactly("我保证能减免利息。", "请你今天把款还上。");
        assertThat(rows.getAllValues()).extracting(Sentence::getSourceAssetId).containsOnly(19L);
        verify(taskMapper).advance(eq(219L), anyLong(), eq("PARSING"), any(), any(), any(), any());
    }

    @Test
    @DisplayName("OCR 未配置：整任务停在原地等配置，不判失败、不扣额度")
    void ocrNotConfiguredStopsQuietly() {
        RecognitionTask task = imageTask(204L, TaskStatus.TRANSCRIBING);
        when(taskMapper.selectDue(anyInt())).thenReturn(List.of(task));
        when(ocrService.isConfigured()).thenReturn(false);

        pipeline.tick();

        verify(taskMapper, never()).advance(anyLong(), anyLong(), anyString(), any(), any(), any(), any());
        verify(ocrService, never()).recognize(any());
        verify(quotaService, never()).consume(anyLong());
    }

    @Test
    void mixedEmptyAudioStillRecognizesImagesAndPublishes() {
        RecognitionTask task = mixedEmptyAudio(210L, TaskStatus.PARSING);
        TaskAsset asset = imageAsset(21L, "images/1/mixed.jpg");
        when(taskAssetMapper.selectImagesToOcr(210L)).thenReturn(List.of(asset));

        when(objectStorage.read(asset.getObjectKey())).thenReturn(jpeg());
        when(ocrService.recognize(any())).thenReturn(OcrParser.parse(StubOcrApi.STUB_DATA));
        when(sentenceMapper.selectMaxSequence(210L)).thenReturn(3);

        pipeline.tick();

        verify(ocrService).recognize(any());
        verify(taskAssetMapper).finishOcr(eq(21L), eq(210L), anyString(), eq("DONE"), anyString(), any());
        verify(reportService).analyzeWithoutRoles(task);
        verify(taskMapper, never()).advance(anyLong(), anyLong(), eq("INVALID"), any(), any(), any(), any());
    }

    @Test
    void mixedEmptyAudioTransitionsToParsingBeforeAutomaticPublication() {
        mixedEmptyAudio(211L, TaskStatus.TRANSCRIBING);
        pipeline.tick();
        verify(taskMapper).advance(eq(211L), anyLong(), eq("PARSING"), any(), any(), any(), any());
        verify(reportService, never()).analyzeWithoutRoles(any());
    }

    @Test
    void mixedAllEmptyIsInvalidOnlyAfterImagesChecked() {
        mixedEmptyAudio(212L, TaskStatus.PARSING);
        when(sentenceMapper.selectMaxSequence(212L)).thenReturn(0);
        pipeline.tick();
        verify(taskAssetMapper).selectImagesToOcr(212L);
        verify(taskMapper).advance(eq(212L), anyLong(), eq("INVALID"), any(), eq(TaskPipelineService.ERR_NO_TEXT), any(), any());
        verify(reportService, never()).analyzeWithoutRoles(any());
    }

    @Test
    void imagePublicationFailureRemainsRecoverableParsing() {
        RecognitionTask task = imageTask(213L, TaskStatus.PARSING);
        when(taskMapper.selectDue(anyInt())).thenReturn(List.of(task));
        when(sentenceMapper.selectMaxSequence(213L)).thenReturn(3);
        when(reportService.analyzeWithoutRoles(task)).thenThrow(new IllegalStateException("temporary DB error"));
        pipeline.tick();
        verify(taskMapper).advance(eq(213L), anyLong(), eq("PARSING"), any(),
            eq(TaskPipelineService.ERR_NO_REPORT), any(), any());
    }

    private RecognitionTask mixedEmptyAudio(long id, TaskStatus status) {
        RecognitionTask task = imageTask(id, status);
        task.setMediaKind(MediaKind.MIXED.name());
        task.setTaskId("tw-empty");
        when(taskMapper.selectDue(anyInt())).thenReturn(List.of(task));
        when(tingwuService.getTaskInfo("tw-empty")).thenReturn(new TingwuService.TaskInfo("tw-empty",
            TingwuService.RemoteStatus.COMPLETED, "https://example.com/fixture", null));
        when(tingwuService.fetchTranscription(any())).thenReturn(
            new TranscriptionParser.ParsedTranscription(List.of(), 1000, 0));
        return task;
    }

    @Test
    @DisplayName("混合记录：音频解析落库后追加图片句子并自动发布")
    void mixedTaskAppendsImagesThenPublishes() {
        RecognitionTask task = imageTask(205L, TaskStatus.PARSING);
        task.setMediaKind(MediaKind.MIXED.name());
        task.setTaskId("tw-205");
        when(taskMapper.selectDue(anyInt())).thenReturn(List.of(task));
        when(tingwuService.getTaskInfo("tw-205")).thenReturn(new TingwuService.TaskInfo("tw-205",
            TingwuService.RemoteStatus.COMPLETED, "https://tingwu-result.example.com/a.json", null));
        when(tingwuService.fetchTranscription(any())).thenReturn(new TranscriptionParser.ParsedTranscription(
            List.of(new TranscriptionParser.ParsedSentence(1, 1, 0, 900, "你好，我", List.of())),
            900, 1));
        when(taskAssetMapper.selectByTask(205L)).thenReturn(List.of());
        when(sentenceMapper.selectMaxSequence(205L)).thenReturn(1);

        pipeline.tick();

        verify(reportService).analyzeWithoutRoles(task);
        verify(taskMapper, never()).advance(eq(205L), anyLong(), eq(TaskStatus.WAITING_ROLE.name()),
            any(), any(), any(), any());
        // 音频句子写入 1 条（图片本轮无待识别项，不追加）
        ArgumentCaptor<Sentence> rows = ArgumentCaptor.forClass(Sentence.class);
        verify(sentenceMapper).insert(rows.capture());
        assertThat(rows.getValue().getText()).isEqualTo("你好，我");
        assertThat(rows.getValue().getSourceAssetId()).isNull();
    }
}
