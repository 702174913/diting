package com.diting.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.diting.common.BusinessTime;
import com.diting.common.BizException;
import com.diting.common.LoginDomain;
import com.diting.common.ResultCode;
import com.diting.dto.UserInfo;
import com.diting.entity.User;
import com.diting.mapper.UserMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** 账号体系：微信授权 + 账号密码双通道，openid 与账号互通绑定 */
@Service
public class AuthService {

    public static final String LOGIN_SCOPE = "USER_LOGIN";

    private final UserMapper userMapper;
    private final WechatAuthService wechatAuthService;
    private final TokenService tokenService;
    private final LoginAttemptGuard attemptGuard;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthService(UserMapper userMapper, WechatAuthService wechatAuthService,
                       TokenService tokenService, LoginAttemptGuard attemptGuard) {
        this.userMapper = userMapper;
        this.wechatAuthService = wechatAuthService;
        this.tokenService = tokenService;
        this.attemptGuard = attemptGuard;
    }

    /** 登录结果：用户 + 是否本次新建（微信首次登录需要引导补昵称等） */
    public record LoginOutcome(User user, boolean isNewUser) {
    }

    /** 微信一键登录：已有账号直接登录；首次建号必须在同一事务记录已见四份协议版本。 */
    @org.springframework.transaction.annotation.Transactional
    public LoginOutcome loginByWechat(String code, boolean agreed,
                                     java.util.Map<String, Integer> seenVersions,
                                     AgreementService agreements, String clientIp) {
        String openid = wechatAuthService.codeToOpenid(code);
        User user = findByOpenid(openid);
        if (user != null) {
            requireActive(user);
            return new LoginOutcome(user, false);
        }
        agreements.assertConsented(agreed, seenVersions);
        User created = new User();
        created.setWechatOpenid(openid);
        created.setNickname("微信用户");
        created.setCreatedAt(BusinessTime.now());
        userMapper.insert(created);
        agreements.recordConsents(created.getId(), clientIp, seenVersions);
        return new LoginOutcome(created, true);
    }

    public LoginOutcome register(String username, String password) {
        // C 端同样走强度校验（弱密码表与后台共用），不只靠 DTO 的长度注解
        com.diting.common.PasswordPolicy.assertStrongForUser(password, username);
        if (findByUsername(username) != null) {
            throw new BizException(ResultCode.CONFLICT, "用户名已存在");
        }
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(encoder.encode(password));
        user.setNickname(username);
        user.setCreatedAt(BusinessTime.now());
        userMapper.insert(user);
        return new LoginOutcome(user, true);
    }

    /**
     * 账号注册（S19）：先校验协议同意（必须勾选且版本与当前一致），
     * 建号成功后把 4 份当前版本快照进 agreement_consents 供审计。
     */
    @org.springframework.transaction.annotation.Transactional
    public LoginOutcome registerWithConsent(String username, String password,
                                            boolean agreed, java.util.Map<String, Integer> seenVersions,
                                            AgreementService agreementService, String clientIp) {
        agreementService.assertConsented(agreed, seenVersions);
        LoginOutcome outcome = register(username, password);
        agreementService.recordConsents(outcome.user().getId(), clientIp, seenVersions);
        return outcome;
    }

    public LoginOutcome loginByPassword(String username, String password, String clientIp) {
        String attemptKey = username + "@" + clientIp;
        return attemptGuard.withAttempt(LOGIN_SCOPE, attemptKey, () -> {
            User user = findByUsername(username);
            if (user == null || user.getErasedAt() != null || user.getPasswordHash() == null
                || !encoder.matches(password, user.getPasswordHash())) {
                attemptGuard.recordFailure(LOGIN_SCOPE, attemptKey);
                throw new BizException(ResultCode.LOGIN_FAILED);
            }
            attemptGuard.clear(LOGIN_SCOPE, attemptKey);
            return new LoginOutcome(user, false);
        });
    }

    /** 当前登录账号绑定微信：一个 openid 只绑定一个账号 */
    public User bindWechat(Long userId, String code) {
        String openid = wechatAuthService.codeToOpenid(code);
        User existing = findByOpenid(openid);
        if (existing != null && !existing.getId().equals(userId)) {
            throw new BizException(ResultCode.CONFLICT, "该微信已绑定其他账号");
        }
        User user = userMapper.selectById(userId);
        requireActive(user);
        if (userMapper.bindActiveWechat(userId, openid) != 1) {
            throw new BizException(ResultCode.UNAUTHORIZED, "账号已注销");
        }
        user.setWechatOpenid(openid);
        return user;
    }

    /** 当前登录用户信息，绝不返回密码哈希 */
    public UserInfo userInfo(Long userId) {
        User user = userMapper.selectById(userId);
        requireActive(user);
        return UserInfo.of(user);
    }

    public String issueToken(User user) {
        requireActive(user);
        return tokenService.issue(user.getId(), LoginDomain.USER);
    }

    private static void requireActive(User user) {
        if (user == null || user.getErasedAt() != null) {
            throw new BizException(ResultCode.UNAUTHORIZED, "账号已注销或登录已失效");
        }
    }

    private User findByOpenid(String openid) {
        return userMapper.selectOne(
            new LambdaQueryWrapper<User>().eq(User::getWechatOpenid, openid));
    }

    private User findByUsername(String username) {
        return userMapper.selectOne(
            new LambdaQueryWrapper<User>().eq(User::getUsername, username));
    }
}
