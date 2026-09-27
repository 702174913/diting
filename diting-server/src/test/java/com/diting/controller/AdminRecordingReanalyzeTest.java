package com.diting.controller;

import com.diting.common.AdminRole;
import com.diting.common.BizException;
import com.diting.common.LoginDomain;
import com.diting.config.LoginUser;
import com.diting.config.LoginUserHolder;
import com.diting.config.RequireRole;
import com.diting.entity.RecognitionTask;
import com.diting.entity.ReportVersion;
import com.diting.mapper.RecognitionTaskMapper;
import com.diting.service.DeletionService;
import com.diting.service.OssService;
import com.diting.service.admin.AuditLogService;
import com.diting.service.report.ReportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 后台「重新判读」（POST /api/admin/recordings/{id}/reanalyze）。
 *
 * 这个入口会改写一条记录的**已发布结论**，所以三件事必须钉死：
 * 权限只有 ADMIN、已删除记录一律拒绝、审计留痕且不记录原文。
 */
class AdminRecordingReanalyzeTest {

    private static RecognitionTask task(Long id, String status) {
        RecognitionTask task = new RecognitionTask();
        task.setId(id);
        task.setUserId(2L);
        task.setStatus(status);
        task.setVersion(1L);
        task.setDurationSec(48);
        return task;
    }

    private static ReportVersion version(long version, int risk, int compliance) {
        ReportVersion row = new ReportVersion();
        row.setId(900L);
        row.setTaskId(1046L);
        row.setVersion(version);
        row.setRuleSetVersion(32L);
        row.setRiskScore(risk);
        row.setComplianceScore(compliance);
        return row;
    }

    private static AdminRecordingController controller(RecognitionTaskMapper tasks, ReportService reports,
                                                       AuditLogService audit) {
        return new AdminRecordingController(tasks, reports, mock(DeletionService.class),
            mock(OssService.class), audit);
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    @Test
    @DisplayName("权限：重新判读只给 ADMIN，OPERATOR/INVESTOR 都拿不到")
    void onlyAdminCanReanalyze() throws NoSuchMethodException {
        RequireRole role = AdminRecordingController.class.getMethod("reanalyze", Long.class)
            .getAnnotation(RequireRole.class);

        assertThat(role).isNotNull();
        assertThat(role.value()).containsExactly(AdminRole.ADMIN);
        assertThat(role.value()).doesNotContain(AdminRole.OPERATOR, AdminRole.INVESTOR);
    }

    @Test
    @DisplayName("重判成功：返回新版本与分数，并写一条 REPORT_REANALYZE 审计")
    void reanalyzePublishesNewVersionAndAudits() {
        RecognitionTaskMapper tasks = mock(RecognitionTaskMapper.class);
        ReportService reports = mock(ReportService.class);
        AuditLogService audit = mock(AuditLogService.class);
        when(tasks.selectById(1046L)).thenReturn(task(1046L, "COMPLETED"));
        when(reports.analyzeWithoutRoles(any())).thenReturn(version(2L, 95, 5));
        LoginUserHolder.set(LoginUser.admin(7L, AdminRole.ADMIN));

        Map<String, Object> payload = controller(tasks, reports, audit).reanalyze(1046L).getData();

        assertThat(payload).containsEntry("reportVersion", 2L)
            .containsEntry("riskScore", 95)
            .containsEntry("complianceScore", 5)
            .containsEntry("ruleSetVersion", 32L);
        verify(audit).success(eq("ADMIN"), eq(7L), eq(AuditLogService.ACTION_REPORT_REANALYZE),
            eq("TASK"), eq("1046"), any());
    }

    @Test
    @DisplayName("已删除记录：404 且不触发判读")
    void deletedRecordingCannotBeReanalyzed() {
        RecognitionTaskMapper tasks = mock(RecognitionTaskMapper.class);
        ReportService reports = mock(ReportService.class);
        RecognitionTask deleted = task(1046L, "DELETED");
        deleted.setDeletedAt(LocalDateTime.now());
        when(tasks.selectById(1046L)).thenReturn(deleted);

        assertThatThrownBy(() -> controller(tasks, reports, mock(AuditLogService.class)).reanalyze(1046L))
            .isInstanceOf(BizException.class);
        verify(reports, never()).analyzeWithoutRoles(any());
    }
}
