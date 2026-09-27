package com.diting.config;

import com.diting.common.LoginDomain;
import com.diting.service.TokenService;
import com.diting.mapper.UserMapper;
import com.diting.common.BizException;
import com.diting.common.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

/** C 端用户域鉴权：/api/** 除白名单外都必须是有效的用户 token */
@Component
public class UserAuthInterceptor extends BaseAuthInterceptor {

    private final UserMapper userMapper;

    public UserAuthInterceptor(TokenService tokenService, UserMapper userMapper) {
        super(tokenService);
        this.userMapper = userMapper;
    }

    @Override
    protected LoginDomain domain() {
        return LoginDomain.USER;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (isPreflight(request)) {
            return true;
        }
        Long userId = requireSubject(request);
        var user = userMapper.selectById(userId);
        if (user == null || (user.getErasedAt() != null
            && !("GET".equals(request.getMethod())
                && request.getRequestURI().matches("/api/deletions/[0-9]+")))) {
            throw new BizException(ResultCode.UNAUTHORIZED, "账号已注销或登录已失效");
        }
        // 已撤销令牌只可用来查询自己的清理作业最小状态；所有写入和其他读取均拒绝。
        LoginUserHolder.set(LoginUser.user(userId));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                               Object handler, Exception ex) {
        LoginUserHolder.clear();
    }
}
