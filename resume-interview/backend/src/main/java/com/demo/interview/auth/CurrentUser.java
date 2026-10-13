package com.demo.interview.auth;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 当前登录用户工具：读取鉴权拦截器写入的 request attribute。
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static Long id(HttpServletRequest request) {
        Object uid = request.getAttribute("userId");
        return uid instanceof Number n ? n.longValue() : null;
    }

    public static boolean isAdmin(HttpServletRequest request) {
        return Boolean.TRUE.equals(request.getAttribute("admin"));
    }
}