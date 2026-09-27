package com.diting.controller;

import com.diting.config.DitingProperties;
import com.diting.entity.RecognitionTask;
import com.diting.mapper.AdminUserMapper;
import com.diting.mapper.RecognitionTaskMapper;
import com.diting.mapper.UserMapper;
import com.diting.service.RecognitionTaskService;
import com.diting.service.TokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 通义听悟回调契约测试（按官方 HTTP 回调文档的真实报文结构）。
 *
 * 历史缺陷（本次修复）：控制器读的是**顶层** TaskId，而官方回调体里 TaskId 在 `Data` 下，
 * 于是每条真实回调都会静默 200 返回、从不唤醒任务——回调等于没写。
 */
@WebMvcTest(controllers = CallbackController.class)
@Import(CallbackControllerTest.Beans.class)
class CallbackControllerTest {

    /** 官方 COMPLETED 回调体（去掉 Result 链接，只保留本控制器会用到的字段） */
    private static final String COMPLETED_BODY = """
        {"Code":"0","Data":{"TaskId":"remote-1","TaskStatus":"COMPLETED","TaskKey":"DT-2026-000001"},
         "Message":"success","RequestId":"req-1"}""";

    private static final String ONGOING_BODY = """
        {"Code":"0","Data":{"TaskId":"remote-1","TaskStatus":"ONGOING"},"Message":"success"}""";

    private static final String FAILED_BODY = """
        {"Code":"0","Data":{"TaskId":"remote-1","TaskStatus":"FAILED"},"Message":"success"}""";

    /** 管控台保存回调地址时发的连通性检查（没有 TaskId） */
    private static final String CONNECTIVITY_BODY = """
        {"Code":"0","Data":{"Test":"CheckSyncConf1701680500000"},"Message":"success","RequestId":"req-0"}""";

    @Autowired MockMvc mvc;
    @MockBean RecognitionTaskMapper tasks;
    @MockBean RecognitionTaskService taskService;
    @MockBean UserMapper users;
    @MockBean AdminUserMapper admins;
    @MockBean com.diting.service.admin.AuditLogService auditLogService;
    @MockBean com.diting.service.AdminUserService adminUserService;

    /**
     * 切片里 WebConfig 会装上 AdminAuthInterceptor，它需要 TokenService；
     * 回调入口本身在 WebConfig 里是放行的（/api/callback/**），所以这里只要把依赖补齐。
     */
    @TestConfiguration
    static class Beans {
        @Bean
        DitingProperties ditingProperties() {
            DitingProperties props = new DitingProperties();
            props.getJwt().setSecret("test-secret-key-for-unit-tests-only-0123456789");
            props.getJwt().setExpireHours(72);
            return props;
        }

        @Bean
        TokenService tokenService(DitingProperties props) {
            return new TokenService(props);
        }
    }

    private RecognitionTask liveTask(String remoteTaskId) {
        RecognitionTask task = new RecognitionTask();
        task.setId(42L);
        task.setTaskId(remoteTaskId);
        task.setStatus("TRANSCRIBING");
        return task;
    }

    private void stubTask(String remoteTaskId, RecognitionTask task) {
        when(tasks.selectOne(any())).thenReturn(task);
    }

    private org.springframework.test.web.servlet.ResultActions call(String body, String ip) throws Exception {
        return mvc.perform(post("/api/callback/tingwu")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body)
            .with(request -> {
                request.setRemoteAddr(ip);
                return request;
            }));
    }

    @Test
    void completedCallbackFromOfficialPayloadWakesTask() throws Exception {
        stubTask("remote-1", liveTask("remote-1"));
        when(taskService.wakeUp(42L)).thenReturn(true);

        call(COMPLETED_BODY, "10.0.0.1")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));

        verify(taskService, times(1)).wakeUp(42L);
    }

    @Test
    void ongoingAndFailedCallbacksAlsoWakeTask() throws Exception {
        stubTask("remote-1", liveTask("remote-1"));
        when(taskService.wakeUp(42L)).thenReturn(true);

        call(ONGOING_BODY, "10.0.0.2")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));
        call(FAILED_BODY, "10.0.0.2")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));

        // ONGOING 是子任务完成的通知，也必须唤醒：它决定「下一轮什么时候查」
        verify(taskService, times(2)).wakeUp(42L);
    }

    @Test
    void connectivityCheckDoesNotTouchDatabase() throws Exception {
        call(CONNECTIVITY_BODY, "10.0.0.3")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));

        verifyNoInteractions(tasks, taskService);
    }

    @Test
    void unknownRemoteTaskIsAcknowledgedWithoutWaking() throws Exception {
        stubTask("remote-x", null);

        call(COMPLETED_BODY, "10.0.0.4")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));

        verify(taskService, never()).wakeUp(any());
    }

    @Test
    void deletedTaskCallbackIsIgnoredToKeepTombstone() throws Exception {
        RecognitionTask deleted = liveTask("remote-1");
        deleted.setDeletedAt(java.time.LocalDateTime.now());
        stubTask("remote-1", deleted);

        call(COMPLETED_BODY, "10.0.0.5")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));

        verify(taskService, never()).wakeUp(any());
    }

    @Test
    void emptyBodyOrMissingTaskIdIsAcknowledged() throws Exception {
        call("{}", "10.0.0.6").andExpect(status().isOk());
        call("{\"Code\":\"0\",\"Data\":{\"TaskStatus\":\"COMPLETED\"}}", "10.0.0.6")
            .andExpect(status().isOk());

        verifyNoInteractions(tasks, taskService);
    }

    /** 配了路径令牌后：裸地址与错令牌一律 401，正确令牌才唤醒 */
    @Test
    void pathTokenIsRequiredWhenConfigured() throws Exception {
        DitingProperties props = new DitingProperties();
        props.getAliyun().getTingwu().setCallbackToken("s3cret");
        CallbackController controller = new CallbackController(tasks, taskService, props);
        stubTask("remote-1", liveTask("remote-1"));
        when(taskService.wakeUp(42L)).thenReturn(true);

        var ok = new org.springframework.mock.web.MockHttpServletRequest();
        ok.setRemoteAddr("10.0.0.7");
        org.junit.jupiter.api.Assertions.assertTrue(controller.tingwu(
            java.util.Map.of("Code", "0", "Data", java.util.Map.of("TaskId", "remote-1")), "s3cret", ok)
            .getCode() == 0);

        org.junit.jupiter.api.Assertions.assertThrows(com.diting.common.BizException.class,
            () -> controller.tingwu(java.util.Map.of("Code", "0"), null, ok));
        org.junit.jupiter.api.Assertions.assertThrows(com.diting.common.BizException.class,
            () -> controller.tingwu(java.util.Map.of("Code", "0"), "wrong", ok));

        verify(taskService, times(1)).wakeUp(42L);
    }

    /** 未配令牌：同 IP 超过每分钟上限返回 429（防回调轰炸空转管线） */
    @Test
    void missingTokenFallsBackToPerIpRateLimit() throws Exception {
        for (int i = 0; i < 60; i++) {
            call(CONNECTIVITY_BODY, "10.0.0.8").andExpect(status().isOk());
        }
        call(CONNECTIVITY_BODY, "10.0.0.8")
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value(429));
    }

    /** 启动日志必须告诉我们管控台该填什么地址（历史 callbackPath 就是这么变成死配置的） */
    @Test
    void startupLogsTheCallbackAddressForTheConsole() {
        DitingProperties props = new DitingProperties();
        props.getAliyun().getTingwu().setPublicBaseUrl("https://api.example.com/");
        props.getAliyun().getTingwu().setCallbackToken("tok");
        CallbackController controller = new CallbackController(tasks, taskService, props);

        // 不抛异常即可；真实文案由日志断言外部不可见，这里只锁「不依赖请求上下文」
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(controller::logCallbackAddress);
    }
}
