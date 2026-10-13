package com.demo.interview.auth;

/**
 * 注册请求体（含图形验证码 uuid 与用户输入）。
 */
public record RegisterRequest(String username, String password, String uuid, String captcha) {
}