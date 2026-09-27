package com.diting.controller;

import com.diting.common.BizException;
import com.diting.common.LoginDomain;
import com.diting.common.ResultCode;
import com.diting.config.DitingProperties;
import com.diting.dto.LoginResult;
import com.diting.dto.UserInfo;
import com.diting.entity.User;
import com.diting.mapper.AdminUserMapper;
import com.diting.mapper.UserMapper;
import com.diting.service.AdminUserService;
import com.diting.service.AuthService;
import com.diting.service.TokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AUTH-07（白名单收紧）与 AUTH-05（后台 token 不能冒充用户）、AUTH-08（userinfo 无密码哈希）。
 * 走真实的拦截器与 WebConfig 路径规则：白名单是否真的只剩登录接口，由这里把关。
 */
@WebMvcTest(controllers = AuthController.class)
class AuthEndpointSecurityTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TokenService tokenService;

    @MockBean
    private AuthService authService;

    @MockBean
    private com.diting.service.AgreementService agreementService;

    @MockBean
    private AdminUserService adminUserService;

    @MockBean
    private AdminUserMapper adminUserMapper;

    @MockBean
    private UserMapper userMapper;

    @MockBean
    private com.diting.service.admin.AuditLogService auditLogService;

    @TestConfiguration
    static class Tokens {
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

    private static User user(long id) {
        User user = new User();
        user.setId(id);
        user.setUsername("alice");
        user.setNickname("Alice");
        user.setPasswordHash("$2a$10$stubhashstubhashstubhashstubhashstubhashstubhashstubhashstubha");
        user.setWechatOpenid("openid_x");
        return user;
    }

    @Test
    @DisplayName("白名单：登录/注册/微信登录不需要 token")
    void publicEndpointsOpen() throws Exception {
        when(authService.loginByPassword(anyString(), anyString(), anyString()))
            .thenReturn(new AuthService.LoginOutcome(user(1L), false));
        when(authService.issueToken(any())).thenReturn("stub-user-token");

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"alice\",\"password\":\"Passw0rd2026\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.token").value("stub-user-token"))
            .andExpect(jsonPath("$.data.userInfo.username").value("alice"))
            .andExpect(jsonPath("$.data.isNewUser").value(false));
    }

    @Test
    void wechatConsentFieldsReachServiceWithoutRequiringExistingToken() throws Exception {
        java.util.Map<String, Integer> versions = java.util.Map.of(
            "service_terms", 1, "privacy_guide", 2,
            "personal_info_auth", 1, "recording_auth", 3);
        when(authService.loginByWechat(eq("wx-code"), eq(true), eq(versions), eq(agreementService), anyString()))
            .thenReturn(new AuthService.LoginOutcome(user(1L), true));
        when(authService.issueToken(any())).thenReturn("stub-user-token");
        mvc.perform(post("/api/auth/wechat").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"wx-code\",\"agreed\":true,\"seenVersions\":"
                    + "{\"service_terms\":1,\"privacy_guide\":2,\"personal_info_auth\":1,\"recording_auth\":3}}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.isNewUser").value(true));
        verify(authService).loginByWechat(eq("wx-code"), eq(true), eq(versions), eq(agreementService), anyString());
    }

    @Test
    @DisplayName("AUTH-07-1 userinfo 不带 token：401（不再返回空对象冒充成功）")
    void userinfoRequiresLogin() throws Exception {
        mvc.perform(get("/api/auth/userinfo"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401))
            .andExpect(jsonPath("$.symbol").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("AUTH-07-2 bind-wechat 不带 token：401")
    void bindWechatRequiresLogin() throws Exception {
        mvc.perform(post("/api/auth/bind-wechat").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"wx-code\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.symbol").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("AUTH-08 带用户 token 取 userinfo：200，且响应不含密码哈希")
    void userinfoWithoutSecrets() throws Exception {
        when(authService.userInfo(1L)).thenReturn(UserInfo.of(user(1L)));
        when(userMapper.selectById(1L)).thenReturn(user(1L));
        String token = tokenService.issue(1L, LoginDomain.USER);

        String body = mvc.perform(get("/api/auth/userinfo")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(1))
            .andExpect(jsonPath("$.data.hasPassword").value(true))
            .andExpect(jsonPath("$.data.hasWechat").value(true))
            .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(body)
            .doesNotContain("passwordHash")
            .doesNotContain("password_hash")
            .doesNotContain("$2a$")
            .doesNotContain("openid_x");
    }

    @Test
    @DisplayName("AUTH-05-2 后台 token 调 C 端接口：403，不被当成同数字用户")
    void adminTokenCannotImpersonateUser() throws Exception {
        String adminToken = tokenService.issue(1L, LoginDomain.ADMIN);

        mvc.perform(get("/api/auth/userinfo").header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.symbol").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("伪造 token 调 C 端接口：401")
    void forgedTokenRejected() throws Exception {
        mvc.perform(get("/api/auth/userinfo").header("Authorization", "Bearer forged.token.value"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.symbol").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("登录失败：401 + symbol=LOGIN_FAILED")
    void loginFailureEnvelope() throws Exception {
        when(authService.loginByPassword(anyString(), anyString(), anyString()))
            .thenThrow(new BizException(ResultCode.LOGIN_FAILED));

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"alice\",\"password\":\"bad\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401))
            .andExpect(jsonPath("$.symbol").value("LOGIN_FAILED"));
    }
}
