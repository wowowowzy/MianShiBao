package com.demo.interview.util;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 通用 JSON 小工具（业务对象反序列化等）。
 */
public final class Jsons {

    private Jsons() {
    }

    /** 把 JSON 文本反序列化为通用对象（Map/List）；空/失败返回 null */
    public static Object parseObject(ObjectMapper json, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return json.readValue(raw, Object.class);
        } catch (Exception e) {
            return null;
        }
    }
}