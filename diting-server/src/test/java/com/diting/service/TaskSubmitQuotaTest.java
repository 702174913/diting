package com.diting.service;

import com.diting.common.BizException;
import com.diting.common.ResultCode;
import com.diting.entity.RecognitionTask;
import com.diting.entity.UploadSession;
import com.diting.mapper.RecognitionTaskMapper;
import com.diting.mapper.SentenceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S03 提交链路：幂等键、时长上限预检、登记与预占同事务。
 * 并发争份额与回滚的数据库真实效果见 run_s03_quota_e2e.sh。
 */
class TaskSubmitQuotaTest {

    private RecognitionTaskMapper taskMapper;
    private QuotaService quotaService;
    private UploadSessionService uploadSessionService;
    private TingwuService tingwu;
    private OcrService ocr;
    private RecognitionTaskService service;

    @BeforeEach
    void setUp() {
        taskMapper = mock(RecognitionTaskMapper.class);
        quotaService = mock(QuotaService.class);
        uploadSessionService = mock(UploadSessionService.class);
        ConfigService configService = mock(ConfigService.class);
        when(configService.load()).thenReturn(new ConfigService.PolicyConfig(1L, 3, 60, 10));
        UploadSession session = new UploadSession();
        session.setId(500L);
        session.setUserId(1L);
        session.setObjectKey("recordings/1/abc.m4a");
        session.setStatus(UploadSessionService.STATUS_VERIFIED);
        session.setMediaKind(com.diting.common.MediaKind.AUDIO.name());
        when(uploadSessionService.consumeForSubmit(eq(1L), eq(500L))).thenReturn(session);
        // S16：提交前先按会话类型分型（requireOwned 只读校验），随后统一消费
        when(uploadSessionService.requireOwned(eq(1L), eq(500L))).thenReturn(session);
        com.diting.mapper.UserMapper userMapper = mock(com.diting.mapper.UserMapper.class);
        when(userMapper.lockById(1L)).thenReturn(new com.diting.entity.User());
        tingwu = mock(TingwuService.class);
        ocr = mock(OcrService.class);
        when(tingwu.isConfigured()).thenReturn(true);
        when(ocr.isConfigured()).thenReturn(true);
        service = new RecognitionTaskService(taskMapper, mock(SentenceMapper.class),
            tingwu, configService, quotaService, uploadSessionService,
            mock(com.diting.service.lifecycle.RetentionService.class),
            mock(com.diting.mapper.TaskAssetMapper.class), new com.diting.config.DitingProperties(), userMapper, ocr);
    }

    private static RecognitionTask task(Long id, String key) {
        RecognitionTask task = new RecognitionTask();
        task.setId(id);
        task.setUserId(1L);
        task.setIdempotencyKey(key);
        task.setStatus("PENDING");
        return task;
    }

    @Test
    @DisplayName("录音与图片不可合并提交，且幂等键命中也不能绕过")
    void rejectsMixedMediaBeforeIdempotentReturnOrConsumption() {
        UploadSession image = new UploadSession();
        image.setId(501L);
        image.setUserId(1L);
        image.setMediaKind(com.diting.common.MediaKind.IMAGE.name());
        image.setStatus(UploadSessionService.STATUS_VERIFIED);
        when(uploadSessionService.requireOwned(1L, 501L)).thenReturn(image);
        when(taskMapper.selectOne(any())).thenReturn(task(100L, "reused-key"));

        assertThatThrownBy(() -> service.submit(1L, java.util.List.of(500L, 501L), 30, "reused-key"))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("录音和图片不能在同一任务中提交")
            .extracting(e -> ((BizException) e).getResultCode())
            .isEqualTo(ResultCode.UNPROCESSABLE);
        verify(taskMapper, never()).selectOne(any());
        verify(uploadSessionService, never()).consumeForSubmit(any(), any());
        verify(quotaService, never()).reserve(any(), any(), anyInt());
        verify(taskMapper, never()).insert(any(RecognitionTask.class));
    }

    @Test
    @DisplayName("QUOTA-04-1 同一幂等键重复提交：返回既有任务，不重复扣份数")
    void repeatedSubmitReturnsSameTask() {
        when(taskMapper.selectOne(any())).thenReturn(task(100L, "k-1"));

        RecognitionTask result = service.submit(1L, 500L, 60, "k-1");

        assertThat(result.getId()).isEqualTo(100L);
        verify(taskMapper, never()).insert(any(RecognitionTask.class));
        verify(quotaService, never()).reserve(any(), any(), anyInt());
    }

    @Test
    @DisplayName("QUOTA-04-2 不传幂等键时按 uploadId 派生，同一上传两次提交落到同一任务")
    void derivedKeyIsStablePerObject() {
        when(taskMapper.selectOne(any())).thenReturn(null);
        when(taskMapper.insert(any(RecognitionTask.class))).thenAnswer(inv -> {
            RecognitionTask row = inv.getArgument(0);
            row.setId(200L);
            return 1;
        });

        service.submit(1L, 500L, 30, null);
        service.submit(1L, 500L, 30, "   ");

        ArgumentCaptor<RecognitionTask> captor = ArgumentCaptor.forClass(RecognitionTask.class);
        verify(taskMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        assertThat(captor.getAllValues().get(0).getIdempotencyKey())
            .isEqualTo(captor.getAllValues().get(1).getIdempotencyKey())
            .startsWith("auto:")
            .hasSizeLessThanOrEqualTo(64);
        verify(quotaService, org.mockito.Mockito.times(2)).reserve(eq(1L), eq(200L), eq(3));
    }

    @Test
    void noCloudCredentialsRejectBeforeConsumingUploadOrReservingQuota() {
        when(tingwu.isConfigured()).thenReturn(false);
        assertThatThrownBy(() -> service.submit(1L, 500L, 30, "audio-no-cloud"))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getResultCode())
            .isEqualTo(ResultCode.NOT_CONFIGURED);
        UploadSession image = new UploadSession(); image.setId(501L); image.setUserId(1L);
        image.setMediaKind(com.diting.common.MediaKind.IMAGE.name());
        image.setStatus(UploadSessionService.STATUS_VERIFIED);
        when(uploadSessionService.requireOwned(1L, 501L)).thenReturn(image);
        when(ocr.isConfigured()).thenReturn(false);
        assertThatThrownBy(() -> service.submit(1L, java.util.List.of(501L), null, "image-no-cloud"))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getResultCode())
            .isEqualTo(ResultCode.NOT_CONFIGURED);
        verify(uploadSessionService, never()).consumeForSubmit(any(), any());
        verify(quotaService, never()).reserve(any(), any(), anyInt());
        verify(taskMapper, never()).insert(any(RecognitionTask.class));
    }

    @Test
    @DisplayName("QUOTA-01-2 时长超过配置上限：422，且不登记、不占额度")
    void durationOverLimitRejected() {
        when(taskMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.submit(1L, 500L, 601, "k-2"))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getResultCode())
            .isEqualTo(ResultCode.UNPROCESSABLE);
        verify(taskMapper, never()).insert(any(RecognitionTask.class));
        verify(quotaService, never()).reserve(any(), any(), anyInt());
    }

    @Test
    @DisplayName("幂等键超长：422")
    void tooLongIdempotencyKeyRejected() {
        assertThatThrownBy(() -> service.submit(1L, 500L, 10, "k".repeat(65)))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("最长 64 位");
    }

    @Test
    @DisplayName("并发重复提交：唯一键拦下第二条，返回已有任务且不重复占额度")
    void duplicateKeyRaceReturnsWinner() {
        when(taskMapper.selectOne(any())).thenReturn(null).thenReturn(task(300L, "k-3"));
        when(taskMapper.insert(any(RecognitionTask.class)))
            .thenThrow(new DuplicateKeyException("uk_user_idem"));

        RecognitionTask result = service.submit(1L, 500L, 30, "k-3");

        assertThat(result.getId()).isEqualTo(300L);
        verify(quotaService, never()).reserve(any(), any(), anyInt());
    }

    @Test
    @DisplayName("正常登记：登记与预占在同一事务方法内（@Transactional 声明）")
    void submitIsTransactional() throws Exception {
        assertThat(RecognitionTaskService.class
            .getMethod("submit", Long.class, Long.class, Integer.class, String.class)
            .isAnnotationPresent(Transactional.class)).isTrue();
    }
}
