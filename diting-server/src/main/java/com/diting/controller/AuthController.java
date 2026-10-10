package com.diting.controller;

import com.diting.common.R;
import com.diting.config.LoginUserHolder;
import com.diting.dto.LoginResult;
import com.diting.dto.PasswordLoginRequest;
import com.diting.dto.RegisterRequest;
import com.diting.dto.UserInfo;
import com.diting.dto.WechatLoginRequest;
import com.diting.entity.User;
import com.diting.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端身份接口。
 * 白名单只有 wechat/register/login（见 WebConfig）：bind-wechat 与 userinfo 必须登录，
 * 未登录一律 401，不再返回空对象冒充成功。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final com.diting.service.AgreementService agreementService;

    public AuthController(AuthService authService, com.diting.service.AgreementService agreementService) {
        this.authService = authService;
        this.agreementService = agreementService;
    }

    @PostMapping("/wechat")
    public R<LoginResult> wechat(@Valid @RequestBody WechatLoginRequest req,
                                 HttpServletRequest request) {
        return R.ok(toLoginResult(authService.loginByWechat(req.code(),
            Boolean.TRUE.equals(req.agreed()), req.seenVersions(), agreementService, clientIp(request))));
    }

    @PostMapping("/register")
    public R<LoginResult> register(@Valid @RequestBody RegisterRequest req,
                                   HttpServletRequest request) {
        req.assertPasswordStrong();
        // S19：注册必须勾选同意 4 份用户协议（版本一致才放行），同意快照记审计
        boolean agreed = Boolean.TRUE.equals(req.agreed());
        return R.ok(toLoginResult(authService.registerWithConsent(
            req.username(), req.password(), agreed, req.seenVersions(),
            agreementService, clientIp(request))));
    }

    @PostMapping("/login")
    public R<LoginResult> login(@Valid @RequestBody PasswordLoginRequest req,
                                HttpServletRequest request) {
        return R.ok(toLoginResult(
            authService.loginByPassword(req.username(), req.password(), clientIp(request))));
    }

    @PostMapping("/bind-wechat")
    public R<UserInfo> bindWechat(@Valid @RequestBody WechatLoginRequest req) {
        Long userId = LoginUserHolder.getUserId();
        return R.ok(UserInfo.of(authService.bindWechat(userId, req.code())));
    }

    @GetMapping("/userinfo")
    public R<UserInfo> userinfo() {
        return R.ok(authService.userInfo(LoginUserHolder.getUserId()));
    }

    private LoginResult toLoginResult(AuthService.LoginOutcome outcome) {
        User user = outcome.user();
        return LoginResult.of(authService.issueToken(user), user, outcome.isNewUser());
    }

    /** 登录失败保护只认直连来源；代理部署须在可信代理层统一限流。 */
    private static String clientIp(HttpServletRequest request) {
        return com.diting.config.ClientAddress.of(request);
    }
}
