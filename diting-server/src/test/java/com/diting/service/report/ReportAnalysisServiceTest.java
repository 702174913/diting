package com.diting.service.report;

import com.diting.common.BizException;
import com.diting.common.ResultCode;
import com.diting.common.TaskStatus;
import com.diting.entity.RecognitionTask;
import com.diting.entity.ReportVersion;
import com.diting.mapper.RecognitionTaskMapper;
import com.diting.mapper.ReportVersionMapper;
import com.diting.mapper.ViolationMapper;
import com.diting.service.ConfigService;
import com.diting.service.TaskPipelineService;
import com.diting.service.rules.RuleMatchService;
import com.diting.service.rules.RuleModels;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 发布必须通过墓碑、版本、状态与非空文本门禁；任一 CAS 失败不得假成功。 */
class ReportAnalysisServiceTest {
    private ReportVersionMapper versions;
    private ViolationMapper violations;
    private RecognitionTaskMapper tasks;
    private TaskPipelineService pipeline;
    private ReportAnalysisService service;
    private RecognitionTask live;
    private static final List<RuleModels.SentenceView> TEXT =
        List.of(new RuleModels.SentenceView(1L, 1, 0, "真实转写文本"));

    @BeforeEach
    void setUp() {
        versions = mock(ReportVersionMapper.class);
        violations = mock(ViolationMapper.class);
        tasks = mock(RecognitionTaskMapper.class);
        pipeline = mock(TaskPipelineService.class);
        ConfigService config = mock(ConfigService.class);
        when(config.load()).thenReturn(new ConfigService.PolicyConfig(1L, 3, 60, 10));
        service = new ReportAnalysisService(versions, violations, new RuleMatchService(), config, pipeline, tasks,
            new com.fasterxml.jackson.databind.ObjectMapper());
        live = task(TaskStatus.WAITING_ROLE);
        when(tasks.lockById(42L)).thenReturn(live);
        when(versions.insert(any(ReportVersion.class))).thenAnswer(inv -> {
            ((ReportVersion) inv.getArgument(0)).setId(7L);
            return 1;
        });
        when(pipeline.markAnalyzing(any())).thenAnswer(inv -> {
            RecognitionTask task = inv.getArgument(0);
            task.setStatus("ANALYZING");
            task.setVersion(task.getVersion() + 1);
            return true;
        });
    }

    private static RecognitionTask task(TaskStatus state) {
        RecognitionTask task = new RecognitionTask();
        task.setId(42L); task.setUserId(1L); task.setVersion(3L); task.setStatus(state.name());
        return task;
    }

    private ReportVersion publish(RecognitionTask snapshot, boolean confirmed) {
        return service.publish(snapshot, 1L, 1L, List.of(), TEXT,
            confirmed ? Map.of(0, "AGENT") : Map.of(), confirmed);
    }

    @Test
    void roleConfirmationPublishesAndCompletesUnderTaskLock() {
        ReportVersion report = publish(task(TaskStatus.WAITING_ROLE), true);
        assertThat(report.getStatus()).isEqualTo("PUBLISHED");
        var order = inOrder(tasks, pipeline, versions);
        order.verify(tasks).lockById(42L);
        order.verify(pipeline).markAnalyzing(live);
        order.verify(versions).insert(report);
        order.verify(versions).updateById(report);
        order.verify(pipeline).markCompleted(live, true);
    }

    @Test
    void rejectedCasDoesNotWriteAnyReport() {
        doReturn(false).when(pipeline).markAnalyzing(any());
        assertThatThrownBy(() -> publish(task(TaskStatus.WAITING_ROLE), true))
            .isInstanceOf(BizException.class);
        verifyNoInteractions(versions, violations);
        verify(pipeline, never()).markCompleted(any(), anyBoolean());
    }

    @Test
    void deletedTaskRejectsLatePublication() {
        live.setDeletedAt(LocalDateTime.now());
        assertThatThrownBy(() -> publish(task(TaskStatus.WAITING_ROLE), true))
            .isInstanceOf(BizException.class);
        verifyNoInteractions(versions, violations, pipeline);
    }

    @Test
    void staleWorkerVersionCannotPublish() {
        live.setVersion(4L);
        assertThatThrownBy(() -> publish(task(TaskStatus.WAITING_ROLE), true))
            .isInstanceOf(BizException.class).hasMessageContaining("已被更新");
        verifyNoInteractions(versions, violations, pipeline);
    }

    @Test
    void queuedTaskCannotPublish() {
        live.setStatus("QUEUED");
        assertThatThrownBy(() -> publish(task(TaskStatus.QUEUED), true)).isInstanceOf(BizException.class);
        verifyNoInteractions(versions, violations, pipeline);
    }

    @Test
    void legacyWaitingRolePublishesAutomaticallyWithoutBusinessRoles() {
        ReportVersion report = publish(task(TaskStatus.WAITING_ROLE), false);
        assertThat(report.getStatus()).isEqualTo("PUBLISHED");
        verify(pipeline).markAnalyzing(live);
        verify(pipeline).markCompleted(live, true);
    }

    @Test
    void emptyTextCannotProduceSuccessfulReport() {
        assertThatThrownBy(() -> service.publish(task(TaskStatus.WAITING_ROLE), 1L, 1L,
            List.of(), List.of(), Map.of(999, "AGENT"), true))
            .isInstanceOf(BizException.class).hasMessageContaining("空报告");
        verifyNoInteractions(versions, violations, pipeline);
    }

    @Test
    void imageAnalysisStartsInsidePublicationTransaction() {
        live.setStatus("PARSING");
        ReportVersion report = publish(task(TaskStatus.PARSING), false);
        assertThat(report.getStatus()).isEqualTo("PUBLISHED");
        verify(pipeline).markAnalyzing(live);
        verify(pipeline).markCompleted(live, true);
    }

    @Test
    void completedTaskRecalculationStillRequiresCas() {
        live.setStatus("COMPLETED");
        doReturn(false).when(pipeline).markAnalyzing(any());
        assertThatThrownBy(() -> publish(task(TaskStatus.COMPLETED), true)).isInstanceOf(BizException.class);
        verifyNoInteractions(versions, violations);
    }

    /** 后台「重新判读」：角色未确认时已完成的记录也要能按当前规则集再发一版 */
    @Test
    void completedTaskCanBeReanalyzedWithoutRoles() {
        live.setStatus("COMPLETED");
        ReportVersion report = publish(task(TaskStatus.COMPLETED), false);
        assertThat(report.getStatus()).isEqualTo("PUBLISHED");
        verify(pipeline).markAnalyzing(live);
        verify(pipeline).markCompleted(live, true);
    }

    @Test
    void queuedTaskCannotBeReanalyzedEvenWithoutRoles() {
        live.setStatus("QUEUED");
        assertThatThrownBy(() -> publish(task(TaskStatus.QUEUED), false)).isInstanceOf(BizException.class);
        verifyNoInteractions(versions, violations, pipeline);
    }

    @Test
    void completionFailureRollsBackPublicationTransaction() {
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(tx.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
        ProxyFactory factory = new ProxyFactory(service);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(tx, new AnnotationTransactionAttributeSource()));
        ReportAnalysisService proxy = (ReportAnalysisService) factory.getProxy();
        doThrow(new BizException(ResultCode.CONFLICT, "完成 CAS 失败"))
            .when(pipeline).markCompleted(any(), eq(true));

        assertThatThrownBy(() -> proxy.publish(task(TaskStatus.WAITING_ROLE), 1L, 1L,
            List.of(), TEXT, Map.of(0, "AGENT"), true)).isInstanceOf(BizException.class);
        verify(tx).rollback(status);
        verify(tx, never()).commit(any());
    }
}
