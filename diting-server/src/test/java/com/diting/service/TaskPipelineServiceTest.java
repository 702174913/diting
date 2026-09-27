package com.diting.service;

import com.diting.common.BizException;
import com.diting.common.BusinessTime;
import com.diting.common.TaskStatus;
import com.diting.config.DitingProperties;
import com.diting.entity.RecognitionTask;
import com.diting.entity.Sentence;
import com.diting.mapper.RecognitionTaskMapper;
import com.diting.mapper.SentenceMapper;
import com.diting.service.tingwu.TranscriptionParser;
import com.diting.service.tingwu.CloudSubmitRejectedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S06 作业管线：领取、提交、对账、解析、额度与墓碑。
 * mapper 用桩，云端用 Mockito 桩的 TingwuService，验证的是管线的判定逻辑。
 */
class TaskPipelineServiceTest {

    private RecognitionTaskMapper taskMapper;
    private SentenceMapper sentenceMapper;
    private TingwuService tingwuService;
    private QuotaService quotaService;
    private TaskQuotaSettlement settlement;
    private ConfigService configService;
    private com.diting.service.report.ReportService reportService;
    private TaskPipelineService pipeline;

    @BeforeEach
    void setUp() {
        taskMapper = mock(RecognitionTaskMapper.class);
        sentenceMapper = mock(SentenceMapper.class);
        tingwuService = mock(TingwuService.class);
        quotaService = mock(QuotaService.class);
        settlement = mock(TaskQuotaSettlement.class);
        when(settlement.confirmAccepted(any(), anyString(), anyInt())).thenReturn(true);
        when(settlement.rejectAndRelease(any(), anyString(), anyString())).thenReturn(true);
        configService = mock(ConfigService.class);
        DitingProperties props = new DitingProperties();
        props.getPipeline().setBatchSize(5);
        props.getPipeline().setMaxAttempts(3);
        // 真实时长回填会用到策略上限（maxRecordingMinutes）
        when(configService.load()).thenReturn(new ConfigService.PolicyConfig(1L, 3, 60, 10));
        // S16：图片 OCR 分支需要 OcrService / 资产表 / 对象存储 / 报告服务
        com.diting.service.OcrService ocrService = mock(com.diting.service.OcrService.class);
        com.diting.mapper.TaskAssetMapper taskAssetMapper = mock(com.diting.mapper.TaskAssetMapper.class);
        com.diting.service.storage.ObjectStorage objectStorage = mock(com.diting.service.storage.ObjectStorage.class);
        reportService = mock(com.diting.service.report.ReportService.class);
        // 用例里不做图片 OCR：资产表为空即可（音频路径不读资产）
        when(taskAssetMapper.selectByTask(anyLong())).thenReturn(java.util.List.of());
        when(taskAssetMapper.selectImagesToOcr(anyLong())).thenReturn(java.util.List.of());
        pipeline = new TaskPipelineService(taskMapper, sentenceMapper, tingwuService,
            configService, props, ocrService, taskAssetMapper, objectStorage, reportService,
            new TaskTextWriter(taskMapper, sentenceMapper, taskAssetMapper),
            new TaskOcrWriter(taskMapper, taskAssetMapper, quotaService), settlement);
        when(taskMapper.lockById(anyLong())).thenAnswer(inv -> taskMapper.selectDue(5).stream()
            .filter(task -> task.getId().equals(inv.getArgument(0))).findFirst().orElse(null));
        when(tingwuService.isConfigured()).thenReturn(true);
    }

    private static RecognitionTask task(long id, TaskStatus status) {
        RecognitionTask task = new RecognitionTask();
        task.setId(id);
        task.setUserId(1L);
        task.setStatus(status.name());
        // 本类用例覆盖的是音频路径；图片任务另有专门用例
        task.setMediaKind(com.diting.common.MediaKind.AUDIO.name());
        task.setOssObject("recordings/1/a.m4a");
        task.setVersion(0L);
        task.setAttemptCount(0);
        task.setCreatedAt(BusinessTime.now());
        return task;
    }

    private void due(RecognitionTask... tasks) {
        when(taskMapper.selectDue(anyInt())).thenReturn(List.of(tasks));
        when(taskMapper.claim(anyLong(), anyLong(), anyString(), any())).thenReturn(1);
    }

    @Test
    @DisplayName("JOB-01 QUEUED 任务被领取并提交云端：登记远端任务号 + 额度转已消费")
    void submitsQueuedTask() {
        due(task(100L, TaskStatus.QUEUED));
        when(tingwuService.submitTranscription(anyString(), any())).thenReturn("tw-100");

        int progressed = pipeline.tick();

        assertThat(progressed).isEqualTo(1);
        verify(taskMapper).claim(eq(100L), eq(0L), eq(TaskStatus.SUBMITTING.name()), any());
        verify(settlement).confirmAccepted(any(), eq("tw-100"), anyInt());
    }

    @Test
    void definiteCloudRejectionStopsAndReleasesReservedQuota() {
        due(task(106L, TaskStatus.QUEUED));
        when(tingwuService.submitTranscription(anyString(), any()))
            .thenThrow(new CloudSubmitRejectedException("InvalidAppKey"));
        when(taskMapper.advance(eq(106L), eq(1L), eq(TaskStatus.SUBMITTING.name()),
            any(), eq(TaskPipelineService.ERR_SUBMIT_REJECTED), any(), isNull())).thenReturn(1);
        assertThat(pipeline.tick()).isEqualTo(1);
        verify(settlement).rejectAndRelease(any(), eq(TaskPipelineService.ERR_SUBMIT_REJECTED), anyString());
        verify(quotaService, never()).consume(106L);
    }

    @Test
    void rejectedRefundFailureKeepsMarkerAndNeverResubmitsCloud() {
        RecognitionTask queued = task(116L, TaskStatus.QUEUED);
        RecognitionTask retry = task(116L, TaskStatus.SUBMITTING);
        retry.setErrorCode(TaskPipelineService.ERR_SUBMIT_REJECTED);
        retry.setVersion(3L);
        when(taskMapper.selectDue(anyInt())).thenReturn(List.of(queued), List.of(retry));
        when(taskMapper.claim(anyLong(), anyLong(), anyString(), any())).thenReturn(1);
        when(taskMapper.advance(eq(116L), anyLong(), eq("SUBMITTING"), any(),
            eq(TaskPipelineService.ERR_SUBMIT_REJECTED), any(), isNull())).thenReturn(1);
        when(tingwuService.submitTranscription(anyString(), any()))
            .thenThrow(new CloudSubmitRejectedException("InvalidAppKey"));
        when(settlement.rejectAndRelease(any(), anyString(), anyString()))
            .thenThrow(new BizException(com.diting.common.ResultCode.CONFLICT)).thenReturn(true);
        assertThat(pipeline.tick()).isEqualTo(1);
        assertThat(pipeline.tick()).isEqualTo(1);
        verify(tingwuService, times(1)).submitTranscription(anyString(), any());
        verify(settlement, times(2)).rejectAndRelease(any(), anyString(), anyString());
    }

    @Test
    void acceptedRemoteIdIsPreservedAfterQuotaTransactionFailsAndPollingWaitsForSettlement() {
        RecognitionTask queued = task(117L, TaskStatus.QUEUED);
        RecognitionTask accepted = task(117L, TaskStatus.TRANSCRIBING);
        accepted.setTaskId("tw-117");
        when(taskMapper.selectDue(anyInt())).thenReturn(List.of(queued), List.of(accepted));
        when(taskMapper.claim(anyLong(), anyLong(), anyString(), any())).thenReturn(1);
        when(tingwuService.submitTranscription(anyString(), any())).thenReturn("tw-117");
        when(settlement.confirmAccepted(any(), anyString(), anyInt()))
            .thenThrow(new BizException(com.diting.common.ResultCode.CONFLICT));
        when(taskMapper.markSubmitted(eq(117L), eq(1L), eq("tw-117"), any())).thenReturn(1);
        doThrow(new BizException(com.diting.common.ResultCode.CONFLICT))
            .when(settlement).ensureAcceptedConsumed(any());
        when(taskMapper.advance(eq(117L), anyLong(), eq("TRANSCRIBING"), any(),
            eq(TaskPipelineService.ERR_QUOTA_UNSETTLED), any(), any())).thenReturn(1);
        assertThat(pipeline.tick()).isEqualTo(1);
        assertThat(pipeline.tick()).isEqualTo(1);
        verify(taskMapper).markSubmitted(eq(117L), eq(1L), eq("tw-117"), any());
        verify(tingwuService, never()).getTaskInfo(anyString());
    }

    @Test
    @DisplayName("JOB-05 提交结果不确定：退回 SUBMITTING 等对账，不转已消费、不盲重提")
    void uncertainSubmit() {
        RecognitionTask queued = task(101L, TaskStatus.QUEUED);
        due(queued);
        when(tingwuService.submitTranscription(anyString(), any()))
            .thenThrow(new IllegalStateException("read timeout"));

        pipeline.tick();

        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(taskMapper).advance(eq(101L), eq(1L), status.capture(), any(), code.capture(), any(), any());
        assertThat(status.getValue()).isEqualTo(TaskStatus.SUBMITTING.name());
        assertThat(code.getValue()).isEqualTo(TaskPipelineService.ERR_SUBMIT_UNCERTAIN);
        verify(quotaService, never()).consume(anyLong());
        verify(quotaService, never()).release(anyLong());
    }

    @Test
    @DisplayName("对账次数超限：标记 SUBMIT_UNRECONCILED 并保留额度预占，等待人工")
    void reconcileExhausted() {
        RecognitionTask submitting = task(102L, TaskStatus.SUBMITTING);
        submitting.setAttemptCount(3);
        due(submitting);

        pipeline.tick();

        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(taskMapper).advance(eq(102L), eq(1L), status.capture(), any(), code.capture(), any(), any());
        assertThat(status.getValue()).isEqualTo(TaskStatus.FAILED.name());
        assertThat(code.getValue()).isEqualTo(TaskPipelineService.ERR_SUBMIT_UNRECONCILED);
        verify(quotaService, never()).release(anyLong());
        verify(tingwuService, never()).submitTranscription(anyString(), any());
    }

    @Test
    @DisplayName("JOB-09 TRANSCRIBING 且远端完成：只重跑解析落库，不再提交付费转写")
    void reparseWithoutResubmit() {
        RecognitionTask task = task(103L, TaskStatus.TRANSCRIBING);
        task.setTaskId("tw-103");
        due(task);
        when(tingwuService.getTaskInfo("tw-103")).thenReturn(new TingwuService.TaskInfo("tw-103",
            TingwuService.RemoteStatus.COMPLETED, "https://tingwu-result.example.com/a.json", null));
        when(tingwuService.fetchTranscription(any())).thenReturn(new TranscriptionParser.ParsedTranscription(
            List.of(new TranscriptionParser.ParsedSentence(1, 1, 0, 900, "你好，我", List.of()),
                new TranscriptionParser.ParsedSentence(2, 2, 900, 1800, "喂，你好。", List.of())),
            1800, 2));

        pipeline.tick();

        verify(tingwuService, never()).submitTranscription(anyString(), any());
        verify(quotaService, never()).consume(anyLong());
        // 两次删除：一次清音频句子（source_asset_id 为空），一次清图片句子（本用例无图片，空转）
        verify(sentenceMapper, times(2)).delete(any());
        ArgumentCaptor<Sentence> rows = ArgumentCaptor.forClass(Sentence.class);
        verify(sentenceMapper, times(2)).insert(rows.capture());
        assertThat(rows.getAllValues()).extracting(Sentence::getSequence).containsExactly(1, 2);
        assertThat(rows.getAllValues()).extracting(Sentence::getText)
            .containsExactly("你好，我", "喂，你好。");
        verify(reportService).analyzeWithoutRoles(task);
        verify(taskMapper, never()).advance(eq(103L), anyLong(), eq(TaskStatus.WAITING_ROLE.name()),
            any(), any(), any(), any());
    }

    @Test
    @DisplayName("空音频：判 INVALID，不落句子")
    void emptyAudio() {
        RecognitionTask task = task(104L, TaskStatus.TRANSCRIBING);
        task.setTaskId("tw-104");
        due(task);
        when(tingwuService.getTaskInfo("tw-104")).thenReturn(new TingwuService.TaskInfo("tw-104",
            TingwuService.RemoteStatus.COMPLETED, "https://tingwu-result.example.com/a.json", null));
        when(tingwuService.fetchTranscription(any()))
            .thenReturn(new TranscriptionParser.ParsedTranscription(List.of(), 0, 0));

        pipeline.tick();

        verify(sentenceMapper, never()).insert(any(Sentence.class));
        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        verify(taskMapper).advance(eq(104L), eq(1L), status.capture(), any(), any(), any(), any());
        assertThat(status.getValue()).isEqualTo(TaskStatus.INVALID.name());
    }

    @Test
    @DisplayName("远端失败：任务失败但不退额度（不是“未受理”）")
    void remoteFailedKeepsQuota() {
        RecognitionTask task = task(105L, TaskStatus.TRANSCRIBING);
        task.setTaskId("tw-105");
        due(task);
        when(tingwuService.getTaskInfo("tw-105")).thenReturn(new TingwuService.TaskInfo("tw-105",
            TingwuService.RemoteStatus.FAILED, null, "audio decode error"));

        pipeline.tick();

        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(taskMapper).advance(eq(105L), eq(1L), eq(TaskStatus.FAILED.name()), any(), code.capture(), any(), any());
        assertThat(code.getValue()).isEqualTo(TaskPipelineService.ERR_REMOTE_FAILED);
        verify(quotaService, never()).release(anyLong());
    }

    @Test
    @DisplayName("JOB-06 结果地址不可用：停在 PARSING 并记录解析失败，可重试")
    void resultUrlBroken() {
        RecognitionTask task = task(106L, TaskStatus.TRANSCRIBING);
        task.setTaskId("tw-106");
        due(task);
        when(tingwuService.getTaskInfo("tw-106")).thenReturn(new TingwuService.TaskInfo("tw-106",
            TingwuService.RemoteStatus.COMPLETED, "https://tingwu-result.example.com/a.json", null));
        when(tingwuService.fetchTranscription(any()))
            .thenThrow(new BizException(com.diting.common.ResultCode.UNPROCESSABLE, "下载转写结果失败：签名已过期"));

        pipeline.tick();

        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(taskMapper).advance(eq(106L), eq(1L), status.capture(), any(), code.capture(), any(), any());
        assertThat(status.getValue()).isEqualTo(TaskStatus.PARSING.name());
        assertThat(code.getValue()).isEqualTo(TaskPipelineService.ERR_PARSE_FAILED);
    }

    @Test
    @DisplayName("JOB-08 墓碑优先：已删除任务不被领取")
    void tombstoneBlocksProcessing() {
        RecognitionTask deleted = task(107L, TaskStatus.QUEUED);
        deleted.setDeletedAt(BusinessTime.now());
        due(deleted);

        assertThat(pipeline.tick()).isZero();
        verify(taskMapper, never()).claim(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("JOB-03/04 租约竞争：CAS 领取失败即放弃本轮，不重复处理")
    void claimConflictSkips() {
        due(task(108L, TaskStatus.QUEUED));
        when(taskMapper.claim(anyLong(), anyLong(), anyString(), any())).thenReturn(0);

        assertThat(pipeline.tick()).isZero();
        verify(tingwuService, never()).submitTranscription(anyString(), any());
    }

    @Test
    @DisplayName("未配置听悟：查队列后按媒体类型跳过音频，不产生假进展")
    void notConfiguredDoesNothing() {
        when(tingwuService.isConfigured()).thenReturn(false);
        due(task(112L, TaskStatus.QUEUED));

        assertThat(pipeline.tick()).isZero();
        verify(taskMapper).selectDue(anyInt());
        verify(taskMapper, never()).claim(anyLong(), anyLong(), anyString(), any());
        verify(tingwuService, never()).submitTranscription(anyString(), any());
    }

    @Test
    void completionCasFailureMustAbortPublication() {
        RecognitionTask task = task(113L, TaskStatus.ANALYZING);
        when(taskMapper.advance(eq(113L), anyLong(), eq("COMPLETED"), any(), any(), any(), any())).thenReturn(0);
        assertThatThrownBy(() -> pipeline.markCompleted(task, true))
            .isInstanceOf(BizException.class).hasMessageContaining("发布已取消");
    }

    @Test
    @DisplayName("COMPLETED 守卫：没有已发布报告时拒绝标记完成")
    void completedRequiresPublishedReport() {
        RecognitionTask task = task(109L, TaskStatus.ANALYZING);

        assertThatThrownBy(() -> pipeline.markCompleted(task, false))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("报告尚未发布");

        when(taskMapper.advance(eq(109L), eq(0L), eq(TaskStatus.COMPLETED.name()), any(), any(), any(), any()))
            .thenReturn(1);
        pipeline.markCompleted(task, true);
        verify(taskMapper).advance(eq(109L), eq(0L), eq(TaskStatus.COMPLETED.name()), any(), any(), any(), any());
    }

    @Test
    @DisplayName("历史 WAITING_ROLE 自动发布报告，不需听悟配置或用户确认")
    void waitingRolePickedForAutomaticPublication() {
        RecognitionTask task = task(110L, TaskStatus.WAITING_ROLE);
        due(task);
        when(tingwuService.isConfigured()).thenReturn(false);

        assertThat(pipeline.tick()).isEqualTo(1);
        verify(taskMapper).claim(eq(110L), eq(0L), eq(TaskStatus.WAITING_ROLE.name()), any());
        verify(reportService).analyzeWithoutRoles(task);
        verify(tingwuService, never()).getTaskInfo(anyString());
    }

    @Test
    @DisplayName("租约时间与退避时间按配置计算")
    void leaseAndBackoff() {
        RecognitionTask task = task(111L, TaskStatus.TRANSCRIBING);
        task.setTaskId("tw-111");
        due(task);
        when(tingwuService.getTaskInfo("tw-111")).thenReturn(new TingwuService.TaskInfo("tw-111",
            TingwuService.RemoteStatus.RUNNING, null, null));

        pipeline.tick();

        ArgumentCaptor<LocalDateTime> lease = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(taskMapper).claim(eq(111L), eq(0L), eq(TaskStatus.TRANSCRIBING.name()), lease.capture());
        assertThat(lease.getValue()).isAfter(BusinessTime.now().plusSeconds(30));

        ArgumentCaptor<LocalDateTime> nextAttempt = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(taskMapper).advance(eq(111L), eq(1L), eq(TaskStatus.TRANSCRIBING.name()),
            nextAttempt.capture(), any(), any(), any());
        assertThat(nextAttempt.getValue()).isAfter(BusinessTime.now());
    }
}
