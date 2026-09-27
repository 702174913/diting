package com.diting.config;

import com.diting.service.AdminUserService;
import com.diting.service.ConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 后台账号初始化。
 * 只有在「库中还没有可用的 ADMIN」且「外部通过环境变量提供了初始密码」时才会建号；
 * 没有提供就保持无 ADMIN 并打印提示——绝不使用任何默认弱密码。
 *
 * 环境变量：DITING_ADMIN_INIT_USERNAME / DITING_ADMIN_INIT_PASSWORD（见 application.yml）
 */
@Slf4j
@Component
public class AdminAccountInitializer implements ApplicationRunner {

    private final AdminUserService adminUserService;
    private final ConfigService configService;
    private final DitingProperties props;

    public AdminAccountInitializer(AdminUserService adminUserService, ConfigService configService,
                                   DitingProperties props) {
        this.adminUserService = adminUserService;
        this.configService = configService;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        // P2 修复：热路径的 load() 不再写库，缺省行在这里启动时补齐一次
        try {
            configService.ensureDefaults();
        }
        catch (Exception e) {
            log.warn("策略缺省值补齐跳过：{}", e.getMessage());
        }
        long existing;
        try {
            existing = adminUserService.countEnabledAdmins();
        }
        catch (Exception e) {
            log.warn("后台账号初始化跳过：读取 admin_users 失败（{}）", e.getMessage());
            return;
        }
        if (existing > 0) {
            return;
        }
        String username = props.getAdmin().getInitUsername();
        String password = props.getAdmin().getInitPassword();
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            log.warn("尚无 ADMIN 账号。请用环境变量提供初始密码后重启："
                + "DITING_ADMIN_INIT_USERNAME / DITING_ADMIN_INIT_PASSWORD（长度≥10 且含字母与数字）。"
                + "系统不会创建任何默认密码账号。");
            return;
        }
        try {
            adminUserService.createInitialAdmin(username, password, "超级管理员");
            log.info("已创建初始 ADMIN 账号：{}", username);
        }
        catch (Exception e) {
            log.error("初始 ADMIN 账号创建失败：{}", e.getMessage());
        }
    }
}
