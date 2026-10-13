package com.demo.interview.auth;

import cn.hutool.captcha.CaptchaUtil;
import cn.hutool.captcha.LineCaptcha;
import cn.hutool.cache.CacheUtil;
import cn.hutool.cache.impl.TimedCache;
import cn.hutool.core.util.IdUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 图形验证码（Hutool）：生成 uuid + 图片 base64，验证码按 uuid 缓存 5 分钟、一次性使用。
 * 可用 auth.captcha.enabled=false 关闭校验（默认开启）。
 */
@Service
public class CaptchaService {

    private static final long TTL_MILLIS = 5 * 60 * 1000;

    private final TimedCache<String, String> cache = CacheUtil.newTimedCache(TTL_MILLIS);
    private final boolean enabled;

    public CaptchaService(@Value("${auth.captcha.enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    public CaptchaDto create() {
        LineCaptcha captcha = CaptchaUtil.createLineCaptcha(130, 44, 4, 30);
        String uuid = IdUtil.fastSimpleUUID();
        cache.put(uuid, captcha.getCode());
        return new CaptchaDto(uuid, captcha.getImageBase64Data());
    }

    /** 校验并消费验证码（一次性） */
    public boolean verify(String uuid, String code) {
        if (!enabled) {
            return true;
        }
        if (uuid == null || uuid.isBlank() || code == null || code.isBlank()) {
            return false;
        }
        String expect = cache.get(uuid);
        if (expect == null) {
            return false;
        }
        cache.remove(uuid);
        return expect.equalsIgnoreCase(code.trim());
    }

    /** uuid + 图片（data:image/png;base64,...） */
    public record CaptchaDto(String uuid, String img) {
    }
}