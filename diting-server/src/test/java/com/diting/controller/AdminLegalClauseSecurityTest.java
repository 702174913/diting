package com.diting.controller;

import com.diting.common.AdminRole;
import com.diting.common.LoginDomain;
import com.diting.common.PageResult;
import com.diting.config.DitingProperties;
import com.diting.dto.LegalClauseResponse;
import com.diting.entity.AdminUser;
import com.diting.mapper.AdminUserMapper;
import com.diting.mapper.UserMapper;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V13 法律条款与规则绑定的权限矩阵：读 ADMIN/OPERATOR、写仅 ADMIN，
 * 判定在服务端拦截器完成（与规则库同一口径）。
 */
@WebMvcTest(controllers = {AdminLegalClauseController.class, AdminRuleController.class})
class AdminLegalClauseSecurityTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TokenService tokenService;

    @MockBean
    private AdminUserMapper adminUserMapper;

    @MockBean
    private UserMapper userMapper;

    @MockBean
    private com.diting.service.admin.AuditLogService auditLogService;

    @MockBean
    private com.diting.service.rules.LegalClauseService legalClauseService;

    @MockBean
    private com.diting.service.rules.RuleService ruleService;

    @MockBean
    private com.diting.service.rules.RuleImportService ruleImportService;

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

    private void stubAccount(long id, AdminRole role) {
        AdminUser account = new AdminUser();
        account.setId(id);
        account.setUsername("acc" + id);
        account.setNickname("账号" + id);
        account.setRole(role.name());
        account.setEnabled(true);
        account.setCreatedAt(LocalDateTime.now());
        lenient().when(adminUserMapper.selectById(id)).thenReturn(account);
    }

    private String token(long id, LoginDomain domain) {
        return tokenService.issue(id, domain);
    }

    private static final LegalClauseResponse CLAUSE = new LegalClauseResponse(7L, "个人信息保护法", "第十三条",
        "条款原文", null, true, LocalDateTime.now(), LocalDateTime.now());

    @Test
    @DisplayName("法律条款接口不带 token：401")
    void requiresToken() throws Exception {
        mvc.perform(get("/api/admin/legal-clauses"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.symbol").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("C 端用户 token 调法律条款接口：403")
    void rejectsUserToken() throws Exception {
        mvc.perform(get("/api/admin/legal-clauses")
                .header("Authorization", "Bearer " + token(1L, LoginDomain.USER)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.symbol").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("OPERATOR 可读条款列表，但新建/绑定 403")
    void operatorReadOnly() throws Exception {
        when(legalClauseService.list(any(), any(), any()))
            .thenReturn(new PageResult<>(List.of(CLAUSE), 1, 1, 20));
        stubAccount(21L, AdminRole.OPERATOR);

        mvc.perform(get("/api/admin/legal-clauses")
                .header("Authorization", "Bearer " + token(21L, LoginDomain.ADMIN)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].lawName").value("个人信息保护法"));

        mvc.perform(post("/api/admin/legal-clauses")
                .header("Authorization", "Bearer " + token(21L, LoginDomain.ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"lawName\":\"个人信息保护法\",\"articleNo\":\"第十三条\",\"content\":\"原文\"}"))
            .andExpect(status().isForbidden());

        mvc.perform(put("/api/admin/rules/5/legal-clauses")
                .header("Authorization", "Bearer " + token(21L, LoginDomain.ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"clauseIds\":[7]}"))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ADMIN 可新建条款并绑定规则")
    void adminCanWriteAndBind() throws Exception {
        when(legalClauseService.create(any())).thenReturn(CLAUSE);
        when(ruleService.bindLegalClauses(any(), any())).thenReturn(List.of(CLAUSE));
        stubAccount(31L, AdminRole.ADMIN);

        mvc.perform(post("/api/admin/legal-clauses")
                .header("Authorization", "Bearer " + token(31L, LoginDomain.ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"lawName\":\"个人信息保护法\",\"articleNo\":\"第十三条\",\"content\":\"原文\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(7));

        mvc.perform(put("/api/admin/rules/5/legal-clauses")
                .header("Authorization", "Bearer " + token(31L, LoginDomain.ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"clauseIds\":[7]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].lawName").value("个人信息保护法"));
    }
}
