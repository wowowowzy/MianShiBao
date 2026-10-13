package com.demo.interview.auth;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册 JWT 鉴权拦截器：**只守 /api/**，放行前端静态资源与 SPA 页面**，
 * 白名单外放行注册 / 登录。
 * 注意：绝不能挂 /**——否则托管在前端的 index.html、/assets/** 会被判 401，
 * 单进程 fat jar 部署时前端直接打不开。
 * /api/admin/** 额外经过管理员拦截器。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final AdminInterceptor adminInterceptor;

    public WebMvcConfig(AuthInterceptor authInterceptor, AdminInterceptor adminInterceptor) {
        this.authInterceptor = authInterceptor;
        this.adminInterceptor = adminInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/register", "/api/auth/login", "/api/auth/captcha", "/api/auth/wechat-login");
        registry.addInterceptor(adminInterceptor)
                .addPathPatterns("/api/admin/**");
    }
}