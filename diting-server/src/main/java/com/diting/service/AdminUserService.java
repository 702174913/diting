package com.diting.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.diting.common.AdminRole;
import com.diting.common.BizException;
import com.diting.common.BusinessTime;
import com.diting.common.LoginDomain;
import com.diting.common.PageQuery;
import com.diting.common.PageResult;
import com.diting.common.PasswordPolicy;
import com.diting.common.ResultCode;
import com.diting.dto.AdminAccountItem;
import com.diting.dto.AdminLoginResult;
import com.diting.entity.AdminUser;
import com.diting.mapper.AdminUserMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 后台账号体系（开发文档 §7.3、§9）。
 * 登录校验 enabled/role；账号管理仅 ADMIN：不得用默认弱密码，且必须至少保留一个启用的 ADMIN，
 * 避免把自己锁在门外。
 */
@Service
public class AdminUserService {

    public static final String LOGIN_SCOPE = "ADMIN_LOGIN";

    private final AdminUserMapper adminUserMapper;
    private final TokenService tokenService;
    private final LoginAttemptGuard attemptGuard;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AdminUserService(AdminUserMapper adminUserMapper, TokenService tokenService,
                            LoginAttemptGuard attemptGuard) {
        this.adminUserMapper = adminUserMapper;
        this.tokenService = tokenService;
        this.attemptGuard = attemptGuard;
    }

    /** 后台登录：账号不存在、密码错误、密码为空都返回同一文案，不泄露账号是否存在 */
    public AdminLoginResult login(String username, String password, String clientIp) {
        String attemptKey = username + "@" + clientIp;
        return attemptGuard.withAttempt(LOGIN_SCOPE, attemptKey, () -> {
            AdminUser account = findByUsername(username);
            if (account == null || account.getPasswordHash() == null
                || !encoder.matches(password, account.getPasswordHash())) {
                attemptGuard.recordFailure(LOGIN_SCOPE, attemptKey);
                throw new BizException(ResultCode.LOGIN_FAILED);
            }
            if (!Boolean.TRUE.equals(account.getEnabled())) {
                throw new BizException(ResultCode.ACCOUNT_DISABLED);
            }
            AdminRole role = AdminRole.of(account.getRole());
            if (role == null) {
                throw new BizException(ResultCode.FORBIDDEN, "账号角色无效，请联系管理员");
            }
            attemptGuard.clear(LOGIN_SCOPE, attemptKey);
            String token = tokenService.issue(account.getId(), LoginDomain.ADMIN);
            return new AdminLoginResult(token, role.name(), account.getNickname());
        });
    }

    /**
     * 账号列表。
     * 默认不返回已软删除的账号：它们与审计强绑定、需要留行，但不应该混在日常视图里。
     */
    public PageResult<AdminAccountItem> list(PageQuery query, String role, Boolean enabled,
                                            boolean includeDeleted) {
        LambdaQueryWrapper<AdminUser> wrapper = new LambdaQueryWrapper<AdminUser>()
            .isNull(!includeDeleted, AdminUser::getDeletedAt)
            .eq(role != null && !role.isBlank(), AdminUser::getRole, role)
            .eq(enabled != null, AdminUser::getEnabled, enabled)
            .orderByAsc(AdminUser::getId);
        Page<AdminUser> page = adminUserMapper.selectPage(
            Page.of(query.page(), query.pageSize()), wrapper);
        List<AdminAccountItem> items = page.getRecords().stream().map(AdminAccountItem::of).toList();
        return PageResult.of(items, page.getTotal(), query);
    }

    @Transactional
    public AdminAccountItem create(String username, String rawPassword, String nickname, String role) {
        lockMembership();
        if (username == null || username.isBlank() || username.length() > 64) {
            throw new BizException(ResultCode.UNPROCESSABLE, "用户名需为 1-64 位");
        }
        AdminRole parsed = AdminRole.of(role);
        if (parsed == null) {
            throw new BizException(ResultCode.UNPROCESSABLE, "角色只能是 ADMIN/OPERATOR/INVESTOR");
        }
        PasswordPolicy.assertStrong(rawPassword, username);
        if (findByUsername(username) != null) {
            throw new BizException(ResultCode.CONFLICT, "用户名已存在");
        }
        LocalDateTime now = BusinessTime.now();
        AdminUser account = new AdminUser();
        account.setUsername(username);
        account.setPasswordHash(encoder.encode(rawPassword));
        account.setNickname(nickname == null || nickname.isBlank() ? username : nickname);
        account.setRole(parsed.name());
        account.setEnabled(true);
        account.setCreatedAt(now);
        account.setUpdatedAt(now);
        try {
            adminUserMapper.insert(account);
        }
        catch (DuplicateKeyException e) {
            throw new BizException(ResultCode.CONFLICT, "用户名已存在");
        }
        return AdminAccountItem.of(account);
    }

    /** 修改昵称/角色/启用状态/密码；会校验“至少保留一个启用的 ADMIN” */
    @Transactional
    public AdminAccountItem update(Long id, String nickname, String role, Boolean enabled,
                                   String rawPassword) {
        lockMembership();
        AdminUser account = requireAccount(id);
        AdminRole currentRole = AdminRole.of(account.getRole());
        AdminRole nextRole = role == null || role.isBlank() ? currentRole : AdminRole.of(role);
        if (nextRole == null) {
            throw new BizException(ResultCode.UNPROCESSABLE, "角色只能是 ADMIN/OPERATOR/INVESTOR");
        }
        boolean nextEnabled = enabled == null ? Boolean.TRUE.equals(account.getEnabled()) : enabled;
        boolean losesAdmin = currentRole == AdminRole.ADMIN
            && (nextRole != AdminRole.ADMIN || !nextEnabled);
        if (losesAdmin && countEnabledAdmins() <= 1) {
            throw new BizException(ResultCode.CONFLICT, "必须至少保留一个启用的 ADMIN");
        }
        if (rawPassword != null && !rawPassword.isBlank()) {
            PasswordPolicy.assertStrong(rawPassword, account.getUsername());
            account.setPasswordHash(encoder.encode(rawPassword));
        }
        if (nickname != null && !nickname.isBlank()) {
            account.setNickname(nickname);
        }
        account.setRole(nextRole.name());
        account.setEnabled(nextEnabled);
        account.setUpdatedAt(BusinessTime.now());
        adminUserMapper.updateById(account);
        return AdminAccountItem.of(account);
    }

    public long countEnabledAdmins() {
        return adminUserMapper.selectCount(new LambdaQueryWrapper<AdminUser>()
            .isNull(AdminUser::getDeletedAt)
            .eq(AdminUser::getRole, AdminRole.ADMIN.name())
            .eq(AdminUser::getEnabled, true));
    }

    /**
     * 软删除账号（开发文档 §9）。
     *
     * 为什么不是物理删除：audit_log.actor_id 指向 admin_users.id，删行会让「每一次调取都可被审计」
     * 这句话失去引用。所以只标记 deleted_at + 停用 + 脱敏：
     * - 用户名改为 deleted#<id>，既不占用原用户名（可重新注册同名），也不破坏唯一约束；
     * - 昵称抹掉；
     * - 密码哈希换成随机值 —— 即使有人手工把 deleted_at 改回 NULL，原密码也不能登录。
     * 与禁用同一条约束：不能删掉最后一个启用的 ADMIN，否则等于把自己锁在门外。
     */
    @Transactional
    public AdminAccountItem softDelete(Long id) {
        lockMembership();
        AdminUser account = requireAccount(id);
        AdminRole role = AdminRole.of(account.getRole());
        if (role == AdminRole.ADMIN && Boolean.TRUE.equals(account.getEnabled())
            && countEnabledAdmins() <= 1) {
            throw new BizException(ResultCode.CONFLICT, "必须至少保留一个启用的 ADMIN");
        }
        LocalDateTime now = BusinessTime.now();
        account.setDeletedAt(now);
        account.setEnabled(false);
        account.setUpdatedAt(now);
        account.setNickname("已注销账号");
        account.setUsername("deleted#" + account.getId());
        account.setPasswordHash(encoder.encode(UUID.randomUUID().toString()));
        try {
            adminUserMapper.updateById(account);
        }
        catch (DuplicateKeyException e) {
            // 极罕见：有人把用户名真的起成了 deleted#<id>。换个带随机后缀的脱敏名再试。
            account.setUsername("deleted#" + account.getId() + "#"
                + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x10000)));
            adminUserMapper.updateById(account);
        }
        return AdminAccountItem.of(account);
    }

    /** 初始化后台账号：仅在库中还没有 ADMIN 时使用，密码强度与建账号同标准 */
    @Transactional
    public AdminAccountItem createInitialAdmin(String username, String rawPassword, String nickname) {
        lockMembership();
        if (countEnabledAdmins() > 0) {
            throw new BizException(ResultCode.CONFLICT, "已存在 ADMIN 账号");
        }
        return create(username, rawPassword, nickname, AdminRole.ADMIN.name());
    }

    private void lockMembership() {
        if (adminUserMapper.lockMembership() == null) {
            throw new BizException(ResultCode.CONFLICT, "管理约束未初始化，请先执行 V7 数据库迁移");
        }
    }

    private AdminUser findByUsername(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        // 已软删除的账号不参与登录：用户名已被脱敏，这里再显式排除一次，避免手工改库后能登录
        return adminUserMapper.selectOne(new LambdaQueryWrapper<AdminUser>()
            .isNull(AdminUser::getDeletedAt)
            .eq(AdminUser::getUsername, username));
    }

    /** 取账号：不存在报 404，已软删除报 409（不是「不存在」，而是「已注销」） */
    private AdminUser requireAccount(Long id) {
        AdminUser account = id == null ? null : adminUserMapper.selectById(id);
        if (account == null) {
            throw new BizException(ResultCode.NOT_FOUND, "账号不存在");
        }
        if (account.getDeletedAt() != null) {
            throw new BizException(ResultCode.CONFLICT, "账号已注销，不能再修改");
        }
        return account;
    }
}
