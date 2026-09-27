package com.diting.controller;

import com.diting.common.AdminRole;
import com.diting.common.LoginDomain;
import com.diting.common.PageResult;
import com.diting.config.DitingProperties;
import com.diting.dto.AdminLoginResult;
import com.diting.entity.AdminUser;
import com.diting.mapper.AdminUserMapper;
import com.diting.mapper.UserMapper;
import com.diting.service.AdminUserService;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AUTH-04（用户 token 访问后台 403）与后台权限矩阵。
 * 角色判定在拦截器里按 admin_users 实时复核：改 URL、改前端视图都绕不过。
 */
@WebMvcTest(controllers = AdminController.class)
class AdminEndpointSecurityTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TokenService tokenService;

    @MockBean
    private AdminUserService adminUserService;

    @MockBean
    private AdminUserMapper adminUserMapper;

    @MockBean
    private UserMapper userMapper;

    /** 审计服务在切片里没有 mapper，直接打桩（审计行为由 S10 的 E2E 覆盖） */
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

    private void stubAccount(long id, AdminRole role, boolean enabled) {
        AdminUser account = new AdminUser();
        account.setId(id);
        account.setUsername("acc" + id);
        account.setNickname("账号" + id);
        account.setRole(role.name());
        account.setEnabled(enabled);
        account.setCreatedAt(LocalDateTime.now());
        lenient().when(adminUserMapper.selectById(id)).thenReturn(account);
    }

    private String token(long id, LoginDomain domain) {
        return tokenService.issue(id, domain);
    }

    @Test
    @DisplayName("后台登录不需要 token（白名单保留），返回 token/role/nickname")
    void adminLoginIsPublic() throws Exception {
        when(adminUserService.login(anyString(), anyString(), anyString()))
            .thenReturn(new AdminLoginResult("stub-token", "ADMIN", "超级管理员"));

        mvc.perform(post("/api/admin/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"ops\",\"password\":\"Str0ngPass2026\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.token").value("stub-token"))
            .andExpect(jsonPath("$.data.role").value("ADMIN"))
            .andExpect(jsonPath("$.data.nickname").value("超级管理员"));
    }

    @Test
    @DisplayName("后台接口不带 token：401")
    void adminEndpointRequiresToken() throws Exception {
        mvc.perform(get("/api/admin/dashboard").param("period", "7d"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.symbol").value("UNAUTHORIZED"));
        org.mockito.Mockito.verify(auditLogService).record(
            org.mockito.ArgumentMatchers.eq("ADMIN"), org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.eq("ACCESS_DENIED"), org.mockito.ArgumentMatchers.eq("ADMIN_API"),
            org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq("DENIED"),
            org.mockito.ArgumentMatchers.argThat(detail -> "UNAUTHORIZED".equals(detail.get("reason"))
                && detail.size() == 1));
    }

    @Test
    @DisplayName("AUTH-04 用户 token 调后台接口：403")
    void userTokenRejectedOnAdminApi() throws Exception {
        mvc.perform(get("/api/admin/dashboard").param("period", "7d")
                .header("Authorization", "Bearer " + token(1L, LoginDomain.USER)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.symbol").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("后台 token 调后台接口：三角色都可读聚合看板")
    void allAdminRolesCanReadAggregates() throws Exception {
        for (AdminRole role : AdminRole.values()) {
            stubAccount(11L, role, true);
            mvc.perform(get("/api/admin/dashboard").param("period", "7d")
                    .header("Authorization", "Bearer " + token(11L, LoginDomain.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        }
    }

    @Test
    @DisplayName("权限矩阵：账号管理仅 ADMIN，OPERATOR/INVESTOR 403")
    void accountManagementIsAdminOnly() throws Exception {
        when(adminUserService.list(any(), any(), any(), anyBoolean()))
            .thenReturn(new PageResult<>(List.of(), 0, 1, 20));

        stubAccount(21L, AdminRole.OPERATOR, true);
        mvc.perform(get("/api/admin/accounts")
                .header("Authorization", "Bearer " + token(21L, LoginDomain.ADMIN)))
            .andExpect(status().isForbidden());

        org.mockito.Mockito.verify(auditLogService).record(
            org.mockito.ArgumentMatchers.eq("ADMIN"), org.mockito.ArgumentMatchers.eq(21L),
            org.mockito.ArgumentMatchers.eq("ACCESS_DENIED"), org.mockito.ArgumentMatchers.eq("ADMIN_API"),
            org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq("DENIED"),
            org.mockito.ArgumentMatchers.argThat(detail -> "FORBIDDEN".equals(detail.get("reason"))));

        stubAccount(22L, AdminRole.INVESTOR, true);
        mvc.perform(get("/api/admin/accounts")
                .header("Authorization", "Bearer " + token(22L, LoginDomain.ADMIN)))
            .andExpect(status().isForbidden());

        stubAccount(23L, AdminRole.ADMIN, true);
        mvc.perform(get("/api/admin/accounts")
                .header("Authorization", "Bearer " + token(23L, LoginDomain.ADMIN)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.page").value(1))
            .andExpect(jsonPath("$.data.pageSize").value(20))
            .andExpect(jsonPath("$.data.items").isArray());
    }

    @Test
    @DisplayName("AUTH-03-3 已禁用账号的 token 立即失效（403 ACCOUNT_DISABLED）")
    void disabledAdminTokenRejected() throws Exception {
        stubAccount(31L, AdminRole.ADMIN, false);

        mvc.perform(get("/api/admin/dashboard").param("period", "7d")
                .header("Authorization", "Bearer " + token(31L, LoginDomain.ADMIN)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.symbol").value("ACCOUNT_DISABLED"));
    }

    @Test
    @DisplayName("角色被改后立即生效：库中已不是 ADMIN 时按新角色判权")
    void roleChangeTakesEffectImmediately() throws Exception {
        stubAccount(41L, AdminRole.OPERATOR, true);

        mvc.perform(get("/api/admin/accounts")
                .header("Authorization", "Bearer " + token(41L, LoginDomain.ADMIN)))
            .andExpect(status().isForbidden());
    }
}
