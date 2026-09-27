package com.diting.controller;

import com.diting.common.AdminRole;
import com.diting.common.BizException;
import com.diting.common.LoginDomain;
import com.diting.common.ResultCode;
import com.diting.config.AdminAuthInterceptor;
import com.diting.config.RequireRole;
import com.diting.entity.AdminUser;
import com.diting.entity.QuotaLedger;
import com.diting.entity.RecognitionTask;
import com.diting.mapper.AdminUserMapper;
import com.diting.mapper.QuotaLedgerMapper;
import com.diting.mapper.RecognitionTaskMapper;
import com.diting.service.TokenService;
import com.diting.service.admin.AuditLogService;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.method.HandlerMethod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 运营接口的两个不变量：只读字段白名单与权限边界。
 *
 * 原「运营队列明细」用例已随该接口下线（页面与接口同批删除）；保留的
 * /api/admin/ops/reconciliation 同样不许把 user_id / 对象 key / 云侧响应带出去。
 */
class AdminOpsControllerTest {

    private static RecognitionTask runningTask() {
        RecognitionTask task = new RecognitionTask();
        task.setId(77L);
        task.setUserId(4242L);
        task.setTaskId("DT-2026-09-4a7b3c9d");
        task.setStatus("ANALYZING");
        task.setDurationSec(455);
        task.setCreatedAt(LocalDateTime.now().minusMinutes(3));
        return task;
    }

    @Test
    @DisplayName("核销队列：包含墓碑与无远端号任务，仅 ADMIN 可见且不泄露用户和对象 key")
    void reconciliationIncludesUncertainAndDeletedTasksWithoutSensitivePayload() throws Exception {
        RecognitionTaskMapper tasks = mock(RecognitionTaskMapper.class);
        QuotaLedgerMapper ledgers = mock(QuotaLedgerMapper.class);
        RecognitionTask unknown = runningTask();
        unknown.setId(77L);
        unknown.setTaskId(null);
        unknown.setStatus("FAILED");
        unknown.setErrorCode("SUBMIT_UNRECONCILED");
        unknown.setDeletedAt(LocalDateTime.now());
        RecognitionTask accepted = runningTask();
        accepted.setId(78L);
        accepted.setErrorCode("QUOTA_UNSETTLED");
        when(tasks.selectList(any())).thenReturn(List.of(unknown, accepted));
        QuotaLedger reserved = new QuotaLedger(); reserved.setTaskId(77L); reserved.setState("RESERVED");
        QuotaLedger consumed = new QuotaLedger(); consumed.setTaskId(78L); consumed.setState("CONSUMED");
        when(ledgers.selectList(any())).thenReturn(List.of(reserved, consumed));

        var page = new AdminOpsController(tasks, ledgers).reconciliation(5, 76L).getData();
        assertThat(page.nextAfterId()).isEqualTo(78L);
        assertThat(page.items()).hasSize(2);
        assertThat(page.items().get(0).deleted()).isTrue();
        assertThat(page.items().get(0).hasRemoteTaskId()).isFalse();
        assertThat(page.items().get(0).quotaState()).isEqualTo("RESERVED");
        assertThat(page.items().get(1).hasRemoteTaskId()).isTrue();
        assertThat(page.items().get(1).quotaState()).isEqualTo("CONSUMED");
        assertThat(Arrays.stream(AdminOpsController.ReconciliationItem.class.getRecordComponents())
            .map(RecordComponent::getName).toList())
            .doesNotContain("userId", "taskId", "ossObject", "errorMsg", "objectKey");
        RequireRole role = AdminOpsController.class.getMethod("reconciliation", Integer.class, Long.class)
            .getAnnotation(RequireRole.class);
        assertThat(role.value()).containsExactly(AdminRole.ADMIN);
    }

    @Test
    void reconciliationRoleIsEnforcedByActualAdminInterceptor() throws Exception {
        TokenService tokens = mock(TokenService.class);
        AdminUserMapper users = mock(AdminUserMapper.class);
        when(tokens.parse("valid", LoginDomain.ADMIN)).thenReturn(1L);
        AdminUser admin = new AdminUser();
        admin.setId(1L);
        admin.setEnabled(true);
        admin.setRole("OPERATOR");
        when(users.selectById(1L)).thenReturn(admin);
        AdminAuthInterceptor interceptor = new AdminAuthInterceptor(tokens, users, mock(AuditLogService.class));
        var request = new MockHttpServletRequest("GET", "/api/admin/ops/reconciliation");
        request.addHeader("Authorization", "Bearer valid");
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        var method = AdminOpsController.class.getMethod("reconciliation", Integer.class, Long.class);
        var handler = new HandlerMethod(new AdminOpsController(mock(RecognitionTaskMapper.class),
            mock(QuotaLedgerMapper.class)), method);
        assertThatThrownBy(() -> interceptor.preHandle(request, response, handler))
            .isInstanceOfSatisfying(BizException.class,
                error -> assertThat(error.getResultCode()).isEqualTo(ResultCode.FORBIDDEN));
        admin.setRole("ADMIN");
        assertThat(interceptor.preHandle(request, response, handler)).isTrue();
        interceptor.afterCompletion(request, response, handler, null);
    }

    @Test
    @DisplayName("上限收敛：客户端只能把上限调小，拉不出全量明细")
    void queueLimitIsClamped() {
        assertThat(AdminOpsController.queueLimit(null)).isEqualTo(50);
        assertThat(AdminOpsController.queueLimit(0)).isEqualTo(50);
        assertThat(AdminOpsController.queueLimit(-3)).isEqualTo(50);
        assertThat(AdminOpsController.queueLimit(5)).isEqualTo(5);
        assertThat(AdminOpsController.queueLimit(999)).isEqualTo(50);
    }

}
