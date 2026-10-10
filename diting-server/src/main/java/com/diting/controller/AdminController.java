package com.diting.controller;

import com.diting.common.AdminRole;
import com.diting.common.BizException;
import com.diting.common.PageQuery;
import com.diting.common.PageResult;
import com.diting.common.R;
import com.diting.common.ResultCode;
import com.diting.config.RequireRole;
import com.diting.dto.AdminAccountCreateRequest;
import com.diting.dto.AdminAccountItem;
import com.diting.dto.AdminAccountUpdateRequest;
import com.diting.dto.AdminLoginResult;
import com.diting.service.AdminUserService;
import com.diting.service.admin.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理后台接口。
 * 登录走 admin_users + BCrypt（不再有占位登录）；聚合看板三类角色都可读，
 * 账号管理仅 ADMIN —— 权限由拦截器按 @RequireRole 强制，前端菜单只影响可见性。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminUserService adminUserService;
    private final AuditLogService auditLogService;

    public AdminController(AdminUserService adminUserService, AuditLogService auditLogService) {
        this.adminUserService = adminUserService;
        this.auditLogService = auditLogService;
    }

    public record AdminLoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    @PostMapping("/login")
    public R<AdminLoginResult> login(@Validated @RequestBody AdminLoginRequest req,
                                     HttpServletRequest request) {
        return R.ok(adminUserService.login(req.username(), req.password(),
            com.diting.config.ClientAddress.of(request)));
    }

    /** 账号变更留痕：绝不记录密码与哈希 */
    private void audit(String action, AdminAccountItem account) {
        com.diting.config.LoginUser actor = com.diting.config.LoginUserHolder.get();
        auditLogService.success(actor.domain().name(), actor.id(), action, "ADMIN_USER",
            String.valueOf(account.id()), AuditLogService.detail(
                "username", account.username(),
                "role", account.role(),
                "enabled", account.enabled()));
    }

    @GetMapping("/dashboard")
    @RequireRole({AdminRole.ADMIN, AdminRole.OPERATOR, AdminRole.INVESTOR})
    public R<Map<String, Object>> dashboard(@RequestParam String period) {
        // P2 修复：占位实现，已废弃。真实统计走 /api/admin/stats/overview（AdminStatsController）；
        // 保留路由避免旧前端 404，同时充当权限切片的探针端点（三角色可读聚合一类的断言），
        // 但禁止新增调用（前端对应函数已标 @deprecated）。
        return R.ok(Map.of("period", period, "deprecated", true));
    }

    /** 账号列表：分页 page 从 1 起，仅 ADMIN 可读 */
    @GetMapping("/accounts")
    @RequireRole(AdminRole.ADMIN)
    public R<PageResult<AdminAccountItem>> accounts(@RequestParam(required = false) Integer page,
                                                    @RequestParam(required = false) Integer pageSize,
                                                    @RequestParam(required = false) String role,
                                                    @RequestParam(required = false) Boolean enabled,
                                                    @RequestParam(required = false, defaultValue = "false")
                                                    boolean includeDeleted) {
        return R.ok(adminUserService.list(PageQuery.of(page, pageSize), role, enabled, includeDeleted));
    }

    @PostMapping("/accounts")
    @RequireRole(AdminRole.ADMIN)
    public R<AdminAccountItem> createAccount(@Valid @RequestBody AdminAccountCreateRequest req) {
        AdminAccountItem created = adminUserService.create(req.username(), req.password(),
            req.nickname(), req.role());
        audit("ACCOUNT_CREATE", created);
        return R.ok(created);
    }

    @PutMapping("/accounts/{id}")
    @RequireRole(AdminRole.ADMIN)
    public R<AdminAccountItem> updateAccount(@PathVariable Long id,
                                             @Valid @RequestBody AdminAccountUpdateRequest req) {
        if (req.isEmpty()) {
            throw new BizException(ResultCode.UNPROCESSABLE, "请至少提交一个要修改的字段");
        }
        AdminAccountItem updated = adminUserService.update(id, req.nickname(), req.role(),
            req.enabled(), req.password());
        audit("ACCOUNT_UPDATE", updated);
        return R.ok(updated);
    }

    /**
     * 注销账号（软删除）。
     * 不做物理删除：审计日志的 actor_id 指向这张表，删行会让审计失去引用。
     * 实现为「标记 deleted_at + 停用 + 用户名/密码脱敏」，行保留。
     */
    @DeleteMapping("/accounts/{id}")
    @RequireRole(AdminRole.ADMIN)
    public R<AdminAccountItem> deleteAccount(@PathVariable Long id) {
        AdminAccountItem deleted = adminUserService.softDelete(id);
        audit("ACCOUNT_DELETE", deleted);
        return R.ok(deleted);
    }
}
