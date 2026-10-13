package com.demo.interview.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * JWT 鉴权拦截器。除白名单外的所有请求必须携带 {@code Authorization: Bearer <token>}。
 * 校验通过后按 uid **查库**取当前用户，实时写入 userId / admin，并拦截已封禁账号
 * （权限与封禁状态即时生效，不受 token 中旧声明影响）。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;

    public AuthInterceptor(JwtUtil jwtUtil, UserRepository userRepository) {
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length());
            try {
                Claims claims = jwtUtil.parse(token);
                User user = userRepository.findById(jwtUtil.userId(claims));
                if (user == null) {
                    writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, "未登录或登录过期");
                    return false;
                }
                if (user.banned()) {
                    writeJson(response, HttpServletResponse.SC_FORBIDDEN, "账号已被封禁，请联系管理员");
                    return false;
                }
                request.setAttribute("userId", user.id());
                request.setAttribute("admin", user.admin());
                return true;
            } catch (JwtException | IllegalArgumentException e) {
                // 过期或非法 token，落 401
            }
        }
        writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, "未登录或登录过期");
        return false;
    }

    private void writeJson(HttpServletResponse response, int status, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"message\":\"" + message + "\"}");
    }
}