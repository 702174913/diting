package com.diting.config;

import com.diting.common.AdminRole;
import com.diting.common.BizException;
import com.diting.common.LoginDomain;
import com.diting.common.ResultCode;
import com.diting.entity.AdminUser;
import com.diting.mapper.AdminUserMapper;
import com.diting.service.TokenService;
import com.diting.service.admin.AuditLogService;
import lombok.extern.slf4j.Slf4j;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;

/** 后台域鉴权：/api/admin/** 除登录外都必须是有效的后台 token，且每次请求复核启用状态与角色 */
@Slf4j
@Component
public class AdminAuthInterceptor extends BaseAuthInterceptor {

    private final AdminUserMapper adminUserMapper;
    private final AuditLogService auditLogService;

    public AdminAuthInterceptor(TokenService tokenService, AdminUserMapper adminUserMapper,
                                AuditLogService auditLogService) {
        super(tokenService);
        this.adminUserMapper = adminUserMapper;
        this.auditLogService = auditLogService;
    }

    @Override
    protected LoginDomain domain() {
        return LoginDomain.ADMIN;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (isPreflight(request)) {
            return true;
        }
        Long adminId = null;
        try {
            adminId = requireSubject(request);
            // 每次请求回库复核：禁用、软删除和改角色立即生效。
            AdminUser account = adminUserMapper.selectById(adminId);
            if (account == null || account.getDeletedAt() != null) {
                throw new BizException(ResultCode.UNAUTHORIZED);
            }
            if (!Boolean.TRUE.equals(account.getEnabled())) {
                throw new BizException(ResultCode.ACCOUNT_DISABLED);
            }
            AdminRole role = AdminRole.of(account.getRole());
            if (role == null) {
                throw new BizException(ResultCode.FORBIDDEN, "账号角色无效，请联系管理员");
            }
            requireAnnotationRole(handler, role);
            LoginUserHolder.set(LoginUser.admin(adminId, role));
            return true;
        }
        catch (BizException denied) {
            // 不记录路径、query、Authorization 或用户输入；审计故障也不能改变原来的拒绝结果。
            try {
                auditLogService.record("ADMIN", adminId, "ACCESS_DENIED", "ADMIN_API", null,
                    AuditLogService.RESULT_DENIED,
                    AuditLogService.detail("reason", denied.getResultCode().getSymbol()));
            }
            catch (Exception auditFailure) {
                log.warn("后台权限拒绝审计写入失败（reason={}）", denied.getResultCode().getSymbol());
            }
            throw denied;
        }
    }

    /** @RequireRole 声明的角色集合校验；未标注即所有后台角色可访问 */
    private void requireAnnotationRole(Object handler, AdminRole role) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return;
        }
        RequireRole required = handlerMethod.getMethodAnnotation(RequireRole.class);
        if (required == null) {
            required = handlerMethod.getBeanType().getAnnotation(RequireRole.class);
        }
        if (required == null) {
            return;
        }
        for (AdminRole allowed : required.value()) {
            if (allowed == role) {
                return;
            }
        }
        throw new BizException(ResultCode.FORBIDDEN);
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                               Object handler, Exception ex) {
        LoginUserHolder.clear();
    }
}
