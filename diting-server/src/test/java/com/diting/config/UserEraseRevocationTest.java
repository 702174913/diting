package com.diting.config;

import com.diting.common.BizException;
import com.diting.common.LoginDomain;
import com.diting.entity.User;
import com.diting.mapper.UserMapper;
import com.diting.service.TokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserEraseRevocationTest {
    private final TokenService tokens = mock(TokenService.class);
    private final UserMapper users = mock(UserMapper.class);
    private final UserAuthInterceptor interceptor = new UserAuthInterceptor(tokens, users);
    private final HttpServletResponse response = mock(HttpServletResponse.class);

    @AfterEach void clear() { LoginUserHolder.clear(); }

    private HttpServletRequest request(String method, String path) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getRequestURI()).thenReturn(path);
        when(request.getHeader("Authorization")).thenReturn("Bearer valid");
        when(tokens.parse("valid", LoginDomain.USER)).thenReturn(7L);
        return request;
    }

    @Test void revokedTokenCannotWriteOrReadUserDataButCanCheckOwnDeletionJob() {
        User erased = new User(); erased.setId(7L); erased.setErasedAt(LocalDateTime.now());
        when(users.selectById(7L)).thenReturn(erased);
        assertThatThrownBy(() -> interceptor.preHandle(request("POST", "/api/auth/bind-wechat"), response, null))
            .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> interceptor.preHandle(request("POST", "/api/tasks"), response, null))
            .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> interceptor.preHandle(request("GET", "/api/tasks"), response, null))
            .isInstanceOf(BizException.class);
        assertThat(interceptor.preHandle(request("GET", "/api/deletions/1"), response, null)).isTrue();
        assertThatThrownBy(() -> interceptor.preHandle(request("POST", "/api/deletions/1"), response, null))
            .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> interceptor.preHandle(request("GET", "/api/deletions/1/other"), response, null))
            .isInstanceOf(BizException.class);
    }

    @Test void missingUserInvalidatesPreviouslySignedToken() {
        assertThatThrownBy(() -> interceptor.preHandle(request("GET", "/api/tasks"), response, null))
            .isInstanceOf(BizException.class);
    }
}
