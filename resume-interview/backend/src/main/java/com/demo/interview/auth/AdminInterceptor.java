package com.demo.interview.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * 管理员鉴权拦截器：作用于 /api/admin/**，要求 AuthInterceptor 已写入的 admin=true。
 */
@Component
public class AdminInterceptor implements HandlerInterceptor {

    private static final String JSON_403 = "{\"message\":\"需要管理员权限\"}";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (Boolean.TRUE.equals(request.getAttribute("admin"))) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(JSON_403);
        return false;
    }
}