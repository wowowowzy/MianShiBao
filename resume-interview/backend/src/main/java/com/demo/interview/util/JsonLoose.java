package com.demo.interview.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 回复的宽松 JSON 解析：整体解析 → ```json 代码块 → 首个大括号片段。
 * LLM 即使多输出解释性文字也能兜底取出 JSON。复用调用方注入的 ObjectMapper。
 */
public final class JsonLoose {

    private static final Pattern JSON_BLOCK = Pattern.compile("```(?:json)?\\s*(\\{.*?\\})\\s*```", Pattern.DOTALL);
    private static final Pattern JSON_BRACE = Pattern.compile("\\{.*\\}", Pattern.DOTALL);

    private JsonLoose() {
    }

    public static <T> T parse(ObjectMapper json, String reply, TypeReference<T> ref) throws Exception {
        if (reply == null || reply.isBlank()) {
            throw new IllegalArgumentException("LLM 回复为空");
        }
        try {
            return json.readValue(reply, ref);
        } catch (Exception ignored) {
        }
        Matcher block = JSON_BLOCK.matcher(reply);
        if (block.find()) {
            return json.readValue(block.group(1), ref);
        }
        Matcher brace = JSON_BRACE.matcher(reply);
        if (brace.find()) {
            return json.readValue(brace.group(), ref);
        }
        throw new IllegalArgumentException("未在回复中找到 JSON");
    }
}