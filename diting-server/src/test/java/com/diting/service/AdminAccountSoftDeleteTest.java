package com.diting.service;

import com.diting.common.AdminRole;
import com.diting.common.BizException;
import com.diting.common.ResultCode;
import com.diting.config.DitingProperties;
import com.diting.dto.AdminAccountItem;
import com.diting.entity.AdminUser;
import com.diting.mapper.AdminUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S18 后台账号软删除。
 *
 * 账号与审计日志强绑定（audit_log.actor_id → admin_users.id），所以「删除」必须是可追溯的软删除：
 * 标记 + 停用 + 脱敏 + 密码随机化。这里验的是这几件事真的发生了，以及
 * 「不能删掉最后一个启用的 ADMIN」这条约束对删除同样生效。
 */
class AdminAccountSoftDeleteTest {

    private static final String STRONG = "Diting#2026pass";

    private final Map<Long, AdminUser> rows = new HashMap<>();
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private AdminUserService service;

    @BeforeEach
    void setUp() {
        rows.clear();
        AdminUserMapper mapper = mock(AdminUserMapper.class);
        when(mapper.lockMembership()).thenReturn("ADMIN_MEMBERSHIP");
        DitingProperties props = new DitingProperties();
        props.getJwt().setSecret("test-secret-key-for-unit-tests-only-0123456789");
        props.getJwt().setExpireHours(72);
        service = new AdminUserService(mapper, new TokenService(props), new LoginAttemptGuard());

        when(mapper.selectById(any())).thenAnswer(inv -> rows.get(((Number) inv.getArgument(0)).longValue()));
        when(mapper.updateById(any(AdminUser.class))).thenAnswer(inv -> {
            AdminUser row = inv.getArgument(0);
            rows.put(row.getId(), row);
            return 1;
        });
        // selectCount 用于「至少保留一个启用的 ADMIN」：按 deleted_at / enabled / role 过滤
        when(mapper.selectCount(any())).thenAnswer(inv -> rows.values().stream()
            .filter(row -> row.getDeletedAt() == null)
            .filter(row -> Boolean.TRUE.equals(row.getEnabled()))
            .filter(row -> AdminRole.ADMIN.name().equals(row.getRole()))
            .count());
    }

    private AdminUser seed(long id, String username, String role, boolean enabled) {
        AdminUser row = new AdminUser();
        row.setId(id);
        row.setUsername(username);
        row.setNickname("运维 " + id);
        row.setRole(role);
        row.setEnabled(enabled);
        row.setPasswordHash(encoder.encode(STRONG));
        row.setCreatedAt(LocalDateTime.now());
        row.setUpdatedAt(LocalDateTime.now());
        rows.put(id, row);
        return row;
    }

    @Test
    @DisplayName("注销：标记 deleted_at + 停用 + 用户名脱敏 + 昵称抹掉 + 密码随机化")
    void softDeleteMarksDisablesAndAnonymizes() {
        seed(1L, "root_admin", AdminRole.ADMIN.name(), true);
        seed(2L, "ops_two", AdminRole.OPERATOR.name(), true);

        AdminAccountItem deleted = service.softDelete(2L);

        assertThat(deleted.deleted()).isTrue();
        assertThat(deleted.deletedAt()).isNotNull();
        assertThat(deleted.enabled()).isFalse();
        assertThat(deleted.username()).isEqualTo("deleted#2");
        assertThat(deleted.nickname()).isEqualTo("已注销账号");

        AdminUser stored = rows.get(2L);
        assertThat(stored.getDeletedAt()).isNotNull();
        assertThat(stored.getUsername()).isEqualTo("deleted#2");
        // 原密码必须失效：即使有人手工把 deleted_at 改回 NULL，也不能拿旧密码登录
        assertThat(encoder.matches(STRONG, stored.getPasswordHash())).isFalse();
        assertThat(stored.getPasswordHash()).startsWith("$2");
    }

    @Test
    @DisplayName("注销最后一个启用的 ADMIN 被拒（与禁用同一条约束）")
    void softDeleteRejectsLastEnabledAdmin() {
        seed(1L, "root_admin", AdminRole.ADMIN.name(), true);

        assertThatThrownBy(() -> service.softDelete(1L))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("必须至少保留一个启用的 ADMIN")
            .extracting(e -> ((BizException) e).getResultCode())
            .isEqualTo(ResultCode.CONFLICT);

        assertThat(rows.get(1L).getDeletedAt()).isNull();
        assertThat(rows.get(1L).getUsername()).isEqualTo("root_admin");
    }

    @Test
    @DisplayName("还有另一个启用的 ADMIN 时，可以注销其中一个")
    void softDeleteAllowedWhenAnotherAdminRemains() {
        seed(1L, "root_admin", AdminRole.ADMIN.name(), true);
        seed(2L, "second_admin", AdminRole.ADMIN.name(), true);

        assertThat(service.softDelete(2L).deleted()).isTrue();
        assertThat(rows.get(2L).getDeletedAt()).isNotNull();
        assertThat(rows.get(1L).getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("重复注销：报「已注销」而不是「不存在」")
    void softDeleteRejectsAlreadyDeletedAccount() {
        AdminUser row = seed(2L, "ops_two", AdminRole.OPERATOR.name(), false);
        row.setDeletedAt(LocalDateTime.now());

        assertThatThrownBy(() -> service.softDelete(2L))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("账号已注销")
            .extracting(e -> ((BizException) e).getResultCode())
            .isEqualTo(ResultCode.CONFLICT);
    }

    @Test
    @DisplayName("已注销账号不能再被修改")
    void updateRejectsDeletedAccount() {
        AdminUser row = seed(2L, "deleted#2", AdminRole.OPERATOR.name(), false);
        row.setDeletedAt(LocalDateTime.now());

        assertThatThrownBy(() -> service.update(2L, "新昵称", null, null, null))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("账号已注销")
            .extracting(e -> ((BizException) e).getResultCode())
            .isEqualTo(ResultCode.CONFLICT);
    }

    @Test
    @DisplayName("注销不存在的账号：404")
    void softDeleteUnknownAccount() {
        assertThatThrownBy(() -> service.softDelete(999L))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getResultCode())
            .isEqualTo(ResultCode.NOT_FOUND);
    }
}
