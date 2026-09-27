package com.diting.service;

import com.diting.common.BizException;
import com.diting.common.LoginDomain;
import com.diting.common.ResultCode;
import com.diting.config.DitingProperties;
import com.diting.dto.UserInfo;
import com.diting.entity.User;
import com.diting.mapper.UserMapper;
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

/** C 端账号：注册/登录/绑定微信与 userinfo 的字段边界 */
class AuthServiceTest {

    private static final String PASSWORD = "Passw0rd2026";

    private UserMapper userMapper;
    private WechatAuthService wechatAuthService;
    private AuthService authService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        wechatAuthService = mock(WechatAuthService.class);
        DitingProperties props = new DitingProperties();
        props.getJwt().setSecret("test-secret-key-for-unit-tests-only-0123456789");
        props.getJwt().setExpireHours(72);
        authService = new AuthService(userMapper, wechatAuthService,
            new TokenService(props), new LoginAttemptGuard());
    }

    private User user(Long id, String username, String rawPassword, String openid) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setNickname(username == null ? "微信用户" : username);
        user.setPasswordHash(rawPassword == null ? null : encoder.encode(rawPassword));
        user.setWechatOpenid(openid);
        user.setCreatedAt(LocalDateTime.now());
        return user;
    }

    @Test
    @DisplayName("注册：用户名重复冲突，新用户以 BCrypt 存储且签发用户域 token")
    void register() {
        when(userMapper.selectOne(any())).thenReturn(user(1L, "alice", PASSWORD, null));
        assertThatThrownBy(() -> authService.register("alice", PASSWORD))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getResultCode())
            .isEqualTo(ResultCode.CONFLICT);

        when(userMapper.selectOne(any())).thenReturn(null);
        when(userMapper.insert(any(User.class))).thenReturn(1);
        AuthService.LoginOutcome outcome = authService.register("bob", PASSWORD);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        org.mockito.Mockito.verify(userMapper).insert(captor.capture());
        assertThat(captor.getValue().getPasswordHash()).startsWith("$2").isNotEqualTo(PASSWORD);
        assertThat(outcome.isNewUser()).isTrue();
        assertThat(outcome.user().getUsername()).isEqualTo("bob");
    }

    @Test
    void firstWechatLoginRequiresConsentAndStoresSnapshotInAccountTransaction() throws Exception {
        AgreementService agreements = mock(AgreementService.class);
        when(wechatAuthService.codeToOpenid("code")).thenReturn("openid-new");
        org.mockito.Mockito.doThrow(new BizException(ResultCode.UNPROCESSABLE))
            .when(agreements).assertConsented(false, null);
        assertThatThrownBy(() -> authService.loginByWechat("code", false, null, agreements, "127.0.0.1"))
            .isInstanceOf(BizException.class);
        verify(userMapper, never()).insert(any(User.class));
        var seen = java.util.Map.of("service_terms", 1, "privacy_guide", 1,
            "personal_info_auth", 1, "recording_auth", 1);
        when(userMapper.insert(any(User.class))).thenAnswer(inv -> {
            ((User) inv.getArgument(0)).setId(7L);
            return 1;
        });
        assertThat(authService.loginByWechat("code", true, seen, agreements, "127.0.0.1").user().getId())
            .isEqualTo(7L);
        verify(agreements).recordConsents(7L, "127.0.0.1", seen);
        assertThat(AuthService.class.getMethod("loginByWechat", String.class, boolean.class,
            java.util.Map.class, AgreementService.class, String.class)
            .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isTrue();
    }

    @Test
    void consentInsertFailureRollsBackWechatAccountTransaction() {
        AgreementService agreements = mock(AgreementService.class);
        when(wechatAuthService.codeToOpenid("code")).thenReturn("openid-new");
        when(userMapper.insert(any(User.class))).thenAnswer(inv -> {
            ((User) inv.getArgument(0)).setId(7L);
            return 1;
        });
        var seen = java.util.Map.of("service_terms", 1, "privacy_guide", 1,
            "personal_info_auth", 1, "recording_auth", 1);
        org.mockito.Mockito.doThrow(new BizException(ResultCode.CONFLICT))
            .when(agreements).recordConsents(7L, "127.0.0.1", seen);
        var txManager = mock(org.springframework.transaction.PlatformTransactionManager.class);
        var txStatus = mock(org.springframework.transaction.TransactionStatus.class);
        when(txManager.getTransaction(any())).thenReturn(txStatus);
        var proxyFactory = new org.springframework.aop.framework.ProxyFactory(authService);
        proxyFactory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(
            txManager, new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        AuthService proxy = (AuthService) proxyFactory.getProxy();
        assertThatThrownBy(() -> proxy.loginByWechat("code", true, seen, agreements, "127.0.0.1"))
            .isInstanceOf(BizException.class);
        verify(txManager).rollback(txStatus);
        verify(txManager, never()).commit(any());
    }

    @Test
    @DisplayName("登录：密码正确返回用户域 token，错误返回 LOGIN_FAILED")
    void loginByPassword() {
        when(userMapper.selectOne(any())).thenReturn(user(5L, "alice", PASSWORD, null));

        AuthService.LoginOutcome outcome = authService.loginByPassword("alice", PASSWORD, "127.0.0.1");
        String token = authService.issueToken(outcome.user());
        assertThat(new TokenService(props()).parse(token, LoginDomain.USER)).isEqualTo(5L);

        assertThatThrownBy(() -> authService.loginByPassword("alice", "bad", "127.0.0.1"))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getResultCode())
            .isEqualTo(ResultCode.LOGIN_FAILED);
    }

    @Test
    @DisplayName("userinfo 只返回必要字段，不含密码哈希与 openid 明文标记以外内容")
    void userInfoHasNoSecrets() {
        when(userMapper.selectById(5L)).thenReturn(user(5L, "alice", PASSWORD, "openid_x"));

        UserInfo info = authService.userInfo(5L);

        assertThat(info.id()).isEqualTo(5L);
        assertThat(info.hasPassword()).isTrue();
        assertThat(info.hasWechat()).isTrue();
        assertThat(info.toString()).doesNotContain("$2");
    }

    @Test
    @DisplayName("绑定微信：openid 已属于他人时冲突，未绑定账号可正常绑定")
    void bindWechat() {
        when(wechatAuthService.codeToOpenid("code-1")).thenReturn("openid_taken");
        when(userMapper.selectOne(any())).thenReturn(user(9L, null, null, "openid_taken"));
        assertThatThrownBy(() -> authService.bindWechat(5L, "code-1"))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getResultCode())
            .isEqualTo(ResultCode.CONFLICT);

        when(wechatAuthService.codeToOpenid("code-2")).thenReturn("openid_free");
        when(userMapper.selectOne(any())).thenReturn(null);
        when(userMapper.selectById(5L)).thenReturn(user(5L, "alice", PASSWORD, null));
        when(userMapper.bindActiveWechat(5L, "openid_free")).thenReturn(1);

        User bound = authService.bindWechat(5L, "code-2");
        assertThat(bound.getWechatOpenid()).isEqualTo("openid_free");
    }

    @Test
    void erasedUserCannotLogInOrBindWechat() {
        User erased = user(5L, "alice", PASSWORD, "openid_x");
        erased.setErasedAt(java.time.LocalDateTime.now());
        when(userMapper.selectOne(any())).thenReturn(erased);
        when(userMapper.selectById(5L)).thenReturn(erased);
        when(wechatAuthService.codeToOpenid(any())).thenReturn("openid_x");
        assertThatThrownBy(() -> authService.loginByPassword("alice", PASSWORD, "127.0.0.1"))
            .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> authService.loginByWechat("code", false, null, mock(AgreementService.class), "127.0.0.1"))
            .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> authService.bindWechat(5L, "code"))
            .isInstanceOf(BizException.class);
        verify(userMapper, never()).bindActiveWechat(any(), any());
    }

    private static DitingProperties props() {
        DitingProperties props = new DitingProperties();
        props.getJwt().setSecret("test-secret-key-for-unit-tests-only-0123456789");
        props.getJwt().setExpireHours(72);
        return props;
    }
}
