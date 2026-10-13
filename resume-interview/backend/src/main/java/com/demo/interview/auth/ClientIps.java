package com.demo.interview.auth;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Locale;

/**
 * 从请求中提取客户端 IP（优先反代头，其次 remoteAddr），归一化为纯 IP 文本。
 * 生产经 Nginx 反代时真实 IP 在 X-Forwarded-For / X-Real-IP。
 */
public final class ClientIps {

    private ClientIps() {
    }

    public static String of(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String first = xff.split(",")[0].trim();
            if (!first.isEmpty()) {
                return normalize(first);
            }
        }
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) {
            return normalize(real.trim());
        }
        return normalize(request.getRemoteAddr());
    }

    /** 去掉方括号/端口/zone id，转小写 */
    private static String normalize(String ip) {
        if (ip == null) {
            return null;
        }
        String s = ip.trim();
        if (s.startsWith("[")) {
            int end = s.indexOf(']');
            if (end > 0) {
                s = s.substring(1, end);
            }
        } else if (s.chars().filter(ch -> ch == ':').count() == 1) {
            // IPv4:port 形式，去掉端口；多于一个冒号视为 IPv6 保留
            s = s.substring(0, s.indexOf(':'));
        }
        int zone = s.indexOf('%');
        if (zone > 0) {
            s = s.substring(0, zone);
        }
        return s.toLowerCase(Locale.ROOT);
    }
}