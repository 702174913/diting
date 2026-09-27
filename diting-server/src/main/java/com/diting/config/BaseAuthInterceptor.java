package com.diting.config;

import com.diting.common.BizException;
import com.diting.common.LoginDomain;
import com.diting.common.ResultCode;
import com.diting.service.TokenService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/** 鉴权拦截器公共逻辑：CORS 预检放行、Bearer 解析、身份域判定 */
public abstract class BaseAuthInterceptor implements HandlerInterceptor {

    protected static final String BEARER_PREFIX = "Bearer ";

    protected final TokenService tokenService;

    protected BaseAuthInterceptor(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    protected abstract LoginDomain domain();

    /**
     * CORS 预检不带 Authorization，必须在鉴权前放行：
     * 带 Authorization 的跨域请求一定先发 OPTIONS，拦下它会直接 500，
     * 浏览器便连真实请求都不会发。
     */
    protected boolean isPreflight(HttpServletRequest request) {
        return HttpMethod.OPTIONS.matches(request.getMethod());
    }

    /**
     * 解析本域 token；拿到另一身份域的有效 token 时返回 403 而不是 401，
     * 便于区分“没登录”和“登错域”，也避免两端互相冒充时被误判为未登录。
     */
    protected Long requireSubject(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        String raw = header.substring(BEARER_PREFIX.length());
        Long subject = tokenService.parse(raw, domain());
        if (subject != null) {
            return subject;
        }
        for (LoginDomain other : LoginDomain.values()) {
            if (other != domain() && tokenService.parse(raw, other) != null) {
                throw new BizException(ResultCode.FORBIDDEN);
            }
        }
        throw new BizException(ResultCode.UNAUTHORIZED);
    }
}
