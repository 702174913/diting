package com.diting.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 拦截器与 CORS 配置。
 * 白名单最小化：只有登录相关接口与听悟回调（回调按官方协议自行校验）不需要登录态；
 * bind-wechat、userinfo 等必须登录，不能再出现在白名单里。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final String[] PUBLIC_AUTH_PATHS = {
        "/api/auth/wechat",
        "/api/auth/register",
        "/api/auth/login"
    };

    private final UserAuthInterceptor userAuthInterceptor;
    private final AdminAuthInterceptor adminAuthInterceptor;
    /** CORS 允许来源，来自 diting.cors.allowed-origin-patterns（逗号分隔，可用 [*] 通配端口） */
    private final String[] allowedOriginPatterns;

    public WebConfig(UserAuthInterceptor userAuthInterceptor,
                     AdminAuthInterceptor adminAuthInterceptor,
                     @Value("${diting.cors.allowed-origin-patterns:http://localhost:[*],http://127.0.0.1:[*]}")
                     String[] allowedOriginPatterns) {
        this.userAuthInterceptor = userAuthInterceptor;
        this.adminAuthInterceptor = adminAuthInterceptor;
        this.allowedOriginPatterns = allowedOriginPatterns;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // C 端用户域：两个身份域各自独立，后台路径交给下面的拦截器
        registry.addInterceptor(userAuthInterceptor)
            .addPathPatterns("/api/**")
            .excludePathPatterns(PUBLIC_AUTH_PATHS)
            .excludePathPatterns("/api/agreements/**")
            .excludePathPatterns("/api/callback/**")
            .excludePathPatterns("/api/admin/**");

        // 后台域：除登录外都必须带有效后台 token
        registry.addInterceptor(adminAuthInterceptor)
            .addPathPatterns("/api/admin/**")
            .excludePathPatterns("/api/admin/login");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 允许来源改为可配置（diting.cors.allowed-origin-patterns / 环境变量 DITING_CORS_ORIGINS），
        // 默认仍是本机回环。两个原因：
        //   1) 本机 5173/5174 常被其它项目占用、端口会顺延，所以用 [*] 通配端口而不是写死；
        //   2) 反向代理若没透传原始 Host，Spring 会拿 Origin 与 Host 比对，
        //      把同源请求误判成跨域从而进入本校验 —— 部署域名不在白名单就 403
        //      Invalid CORS request。生产把部署域名（或 *）配进环境变量即可解决，
        //      不必改代码，也不必依赖 nginx 传对 Host。
        // 认证走 Authorization: Bearer（不是 Cookie），跨站请求无法自动携带凭据，
        // 因此即便放开来源也不会被冒用身份；仍建议按部署域名收敛，别长期用 *。
        registry.addMapping("/api/**")
            .allowedOriginPatterns(allowedOriginPatterns)
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
            .allowedHeaders("*");
    }
}
