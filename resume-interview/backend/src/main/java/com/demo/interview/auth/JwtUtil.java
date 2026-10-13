package com.demo.interview.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * JWT 工具：HS256 签发与校验，密钥来自配置 auth.jwt-secret（需 ≥32 字节），有效期 7 天。
 * 令牌携带 uid 与 admin，供鉴权拦截器写入 request attribute。
 */
@Component
public class JwtUtil {

    private final SecretKey key;
    private final long ttlMillis;

    public JwtUtil(@Value("${auth.jwt-secret}") String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("auth.jwt-secret 至少需要 32 字节用于 HS256（当前 " + bytes.length + " 字节）");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.ttlMillis = TimeUnit.DAYS.toMillis(7);
    }

    /** 签发 token，subject 为用户名，携带 uid 与 admin 声明 */
    public String generate(String username, Long uid, boolean admin) {
        Date now = new Date();
        return Jwts.builder()
                .subject(username)
                .claim("uid", uid)
                .claim("admin", admin)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttlMillis))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** 校验 token，非法/过期抛 JwtException */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** 从已校验的 claims 中解析出用户 id（claim 中的小型数字可能被反序列化为 Integer，统一按 Number 取） */
    public Long userId(Claims claims) {
        return claims.get("uid", Number.class).longValue();
    }

    /** 从 claims 中解析管理员标记 */
    public boolean admin(Claims claims) {
        return Boolean.TRUE.equals(claims.get("admin", Boolean.class));
    }
}