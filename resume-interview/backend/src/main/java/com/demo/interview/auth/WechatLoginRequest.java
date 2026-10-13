package com.demo.interview.auth;

/**
 * 微信小程序静默登录请求体（wx.login 返回的临时 code，5 分钟有效、一次性）。
 */
public record WechatLoginRequest(String code) {
}