package com.demo.interview.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 注册 / 登录接口（白名单）。异常统一由 GlobalExceptionHandler 处理。
 * GET  /api/auth/captcha   获取图形验证码 → {uuid, img}
 * POST /api/auth/register  注册 → 200 {id, username}（需验证码；一个来源 IP 仅可注册一个账号）
 * POST /api/auth/login     登录 → 200 {token, username, admin}（需验证码）
 * POST /api/auth/wechat-login 微信小程序静默登录 → 200 {token, username, admin, isNew}
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final CaptchaService captchaService;
    private final WechatLoginService wechatLoginService;

    public AuthController(AuthService authService, CaptchaService captchaService,
                          WechatLoginService wechatLoginService) {
        this.authService = authService;
        this.captchaService = captchaService;
        this.wechatLoginService = wechatLoginService;
    }

    @GetMapping("/captcha")
    public ResponseEntity<?> captcha() {
        return ResponseEntity.ok(captchaService.create());
    }

    @PostMapping("/wechat-login")
    public ResponseEntity<?> wechatLogin(@RequestBody WechatLoginRequest body) {
        WechatLoginService.Result r = wechatLoginService.login(body == null ? null : body.code());
        return ResponseEntity.ok(Map.of(
                "token", r.token(),
                "username", r.username(),
                "admin", r.admin(),
                "isNew", r.isNew(),
                "nickname", r.nickname() == null ? "" : r.nickname()));
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest body, HttpServletRequest request) {
        if (!captchaService.verify(body.uuid(), body.captcha())) {
            return ResponseEntity.badRequest().body(Map.of("message", "验证码错误或已过期"));
        }
        User u = authService.register(body.username(), body.password(), false, ClientIps.of(request), true);
        return ResponseEntity.ok(Map.of("id", u.id(), "username", u.username()));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest body) {
        if (!captchaService.verify(body.uuid(), body.captcha())) {
            return ResponseEntity.badRequest().body(Map.of("message", "验证码错误或已过期"));
        }
        AuthService.LoginResult result = authService.login(body.username(), body.password());
        return ResponseEntity.ok(Map.of(
                "token", result.token(),
                "username", result.username(),
                "admin", result.admin()));
    }
}