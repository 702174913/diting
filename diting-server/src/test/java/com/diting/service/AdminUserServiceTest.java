package com.diting.service;

import com.diting.common.AdminRole;
import com.diting.common.BizException;
import com.diting.common.LoginDomain;
import com.diting.common.ResultCode;
import com.diting.config.DitingProperties;
import com.diting.dto.AdminAccountItem;
import com.diting.dto.AdminLoginResult;
import com.diting.entity.AdminUser;
import com.diting.mapper.AdminUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AUTH-01/03：后台登录与账号管理。
 * 不连数据库：mapper 用 Mockito 桩，其余用真实实现（BCrypt、JWT、失败计数）。
 */
class AdminUserServiceTest {

    private static final String STRONG = "Diting#2026pass";

    private AdminUserMapper mapper;
    private TokenService tokenService;
    private LoginAttemptGuard guard;
    private AdminUserService service;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @BeforeEach
    void setUp() {
        mapper = mock(AdminUserMapper.class);
        when(mapper.lockMembership()).thenReturn("ADMIN_MEMBERSHIP");
        DitingProperties props = new DitingProperties();
        props.getJwt().setSecret("test-secret-key-for-unit-tests-only-0123456789");
        props.getJwt().setExpireHours(72);
        tokenService = new TokenService(props);
        guard = new LoginAttemptGuard();
        service = new AdminUserService(mapper, tokenService, guard);
    }

    private AdminUser account(String role, boolean enabled, String rawPassword) {
        AdminUser user = new AdminUser();
        user.setId(1L);
        user.setUsername("ops_admin");
        user.setNickname("运维");
        user.setRole(role);
        user.setEnabled(enabled);
        user.setPasswordHash(encoder.encode(rawPassword));
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        return user;
    }

    @Test
    void membershipLockPrecedesReadAndLastAdminCheck() {
        when(mapper.selectById(1L)).thenReturn(account("ADMIN", true, STRONG));
        when(mapper.selectCount(any())).thenReturn(1L);
        assertThatThrownBy(() -> service.update(1L, null, "OPERATOR", true, null)).isInstanceOf(BizException.class);
        var order = org.mockito.Mockito.inOrder(mapper);
        order.verify(mapper).lockMembership();
        order.verify(mapper).selectById(1L);
        order.verify(mapper).selectCount(any());
        verify(mapper, never()).updateById(any(AdminUser.class));
    }

    @Test
    void missingMembershipGuardFailsClosed() {
        when(mapper.lockMembership()).thenReturn(null);
        assertThatThrownBy(() -> service.softDelete(1L)).isInstanceOf(BizException.class).hasMessageContaining("V7");
        verify(mapper, never()).selectById(any());
    }

    @Test
    @DisplayName("AUTH-01-1 密码正确：签发后台域 token 并返回角色")
    void loginSucceeds() {
        when(mapper.selectOne(any())).thenReturn(account("OPERATOR", true, STRONG));

        AdminLoginResult result = service.login("ops_admin", STRONG, "10.0.0.1");

        assertThat(result.role()).isEqualTo("OPERATOR");
        assertThat(result.nickname()).isEqualTo("运维");
        assertThat(tokenService.parse(result.token(), LoginDomain.ADMIN)).isEqualTo(1L);
        assertThat(tokenService.parse(result.token(), LoginDomain.USER)).isNull();
    }

    @Test
    @DisplayName("AUTH-01-2 密码错误：LOGIN_FAILED，不泄露账号是否存在")
    void wrongPasswordRejected() {
        when(mapper.selectOne(any())).thenReturn(account("ADMIN", true, STRONG));

        assertThatThrownBy(() -> service.login("ops_admin", "wrong-password", "10.0.0.1"))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getResultCode())
            .isEqualTo(ResultCode.LOGIN_FAILED);
    }

    @Test
    @DisplayName("AUTH-01-3 账号不存在：与密码错误同一文案")
    void unknownAccountRejected() {
        when(mapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.login("nobody", STRONG, "10.0.0.1"))
            .isInstanceOf(BizException.class)
            .hasMessage(ResultCode.LOGIN_FAILED.getMessage());
    }

    @Test
    @DisplayName("AUTH-03-1 账号禁用：即使密码正确也拒绝（403）")
    void disabledAccountRejected() {
        when(mapper.selectOne(any())).thenReturn(account("ADMIN", false, STRONG));

        assertThatThrownBy(() -> service.login("ops_admin", STRONG, "10.0.0.1"))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getResultCode())
            .isEqualTo(ResultCode.ACCOUNT_DISABLED);
    }

    @Test
    @DisplayName("AUTH-03-2 账号禁用且密码错误：仍返回 LOGIN_FAILED，不暴露禁用状态")
    void disabledWithWrongPasswordLooksLikeFailure() {
        when(mapper.selectOne(any())).thenReturn(account("ADMIN", false, STRONG));

        assertThatThrownBy(() -> service.login("ops_admin", "wrong-password", "10.0.0.1"))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getResultCode())
            .isEqualTo(ResultCode.LOGIN_FAILED);
    }

    @Test
    @DisplayName("登录失败保护：连续失败达到上限后返回 429")
    void loginAttemptsThrottled() {
        when(mapper.selectOne(any())).thenReturn(account("ADMIN", true, STRONG));
        for (int i = 0; i < LoginAttemptGuard.MAX_FAILURES; i++) {
            try {
                service.login("ops_admin", "wrong-password", "10.0.0.9");
            }
            catch (BizException ignored) {
                // 预期失败
            }
        }

        assertThatThrownBy(() -> service.login("ops_admin", "wrong-password", "10.0.0.9"))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getResultCode())
            .isEqualTo(ResultCode.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("建账号：弱密码与含用户名密码被拒，合规密码以 BCrypt 存储")
    void createAccountPasswordPolicy() {
        assertThatThrownBy(() -> service.create("ops2", "12345678", "昵称", "OPERATOR"))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getResultCode())
            .isEqualTo(ResultCode.UNPROCESSABLE);

        assertThatThrownBy(() -> service.create("ops2", "ops2password1", "昵称", "OPERATOR"))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("不能包含用户名");

        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.insert(any(AdminUser.class))).thenReturn(1);
        AdminAccountItem created = service.create("ops2", "Str0ngPass2026", "新运营", "OPERATOR");

        ArgumentCaptor<AdminUser> captor = ArgumentCaptor.forClass(AdminUser.class);
        verify(mapper).insert(captor.capture());
        String storedHash = captor.getValue().getPasswordHash();
        assertThat(storedHash).isNotEqualTo("Str0ngPass2026").startsWith("$2");
        assertThat(encoder.matches("Str0ngPass2026", storedHash)).isTrue();
        assertThat(created.role()).isEqualTo("OPERATOR");
    }

    @Test
    @DisplayName("账号管理：不得取消最后一个启用 ADMIN（其余角色变更允许）")
    void mustKeepOneEnabledAdmin() {
        when(mapper.selectById(1L)).thenReturn(account("ADMIN", true, STRONG));
        when(mapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.update(1L, null, "OPERATOR", null, null))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getResultCode())
            .isEqualTo(ResultCode.CONFLICT);
        assertThatThrownBy(() -> service.update(1L, null, null, false, null))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getResultCode())
            .isEqualTo(ResultCode.CONFLICT);
        verify(mapper, never()).updateById(any(AdminUser.class));

        // 还有第二个启用 ADMIN 时即可降级
        when(mapper.selectCount(any())).thenReturn(2L);
        when(mapper.updateById(any(AdminUser.class))).thenReturn(1);
        AdminAccountItem updated = service.update(1L, null, "OPERATOR", null, null);
        assertThat(updated.role()).isEqualTo("OPERATOR");
    }

    @Test
    @DisplayName("初始化 ADMIN：已存在 ADMIN 时拒绝重复创建")
    void initialAdminOnlyOnce() {
        when(mapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.createInitialAdmin("root", "Str0ngPass2026", "超级管理员"))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getResultCode())
            .isEqualTo(ResultCode.CONFLICT);
        assertThat(AdminRole.of("ADMIN")).isEqualTo(AdminRole.ADMIN);
    }
}
