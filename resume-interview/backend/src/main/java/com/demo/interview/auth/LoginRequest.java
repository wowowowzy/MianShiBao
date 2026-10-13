package com.demo.interview.auth;

/**
 * 登录请求体（含图形验证码 uuid 与用户输入）。
 */
public record LoginRequest(String username, String password, String uuid, String captcha) {
}