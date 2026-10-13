package com.demo.interview.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 微信小程序登录配置（appid 即用户在公众平台开通的 Mini Program AppID，
 * secret 为小程序密钥，仅服务端持有，配合 wx.login 的 code 换取 openid）。
 * 前缀 wechat.miniapp，经 {@code @ConfigurationPropertiesScan} 自动注册。
 */
@ConfigurationProperties(prefix = "wechat.miniapp")
public class WechatProperties {

    /** 小程序 AppID */
    private String appid;

    /** 小程序 AppSecret（仅服务端使用，禁止下发到前端） */
    private String secret;

    public String getAppid() {
        return appid;
    }

    public void setAppid(String appid) {
        this.appid = appid;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }
}