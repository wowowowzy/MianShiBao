package com.demo.interview.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * 通用「LLM 题库清洗器」：文本分块 → 逐块让 LLM 结构化成标准题目 → 宽松解析。
 * 供常规题库导入与大厂题库导入共用，差异只在提示词（由调用方以 promptForChunk 提供）。
 */
@Slf4j
@Component
public class LlmQuestionCleaner {

    private static final int CHUNK_SIZE = 8000;
    private static final int MAX_ATTEMPTS = 3;

    private final ChatModel chatModel;
    private final ObjectMapper json;

    public LlmQuestionCleaner(ChatModel chatModel, ObjectMapper json) {
        this.chatModel = chatModel;
        this.json = json;
    }

    /** 对整段文本分块清洗，返回全部结构化题目 */
    public List<Spec> clean(String rawText, Function<String, String> promptForChunk) {
        List<Spec> all = new ArrayList<>();
        for (String chunk : chunk(cleanBlank(rawText), CHUNK_SIZE)) {
            List<Spec> got = cleanChunk(chunk, promptForChunk);
            if (got != null) {
                all.addAll(got);
            }
        }
        return all;
    }

    /** 单块清洗，空回复/解析失败自动重试；最终失败返回 null 由上层跳过 */
    private List<Spec> cleanChunk(String chunk, Function<String, String> promptForChunk) {
        String base = promptForChunk.apply(chunk);
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                String prompt = attempt == 1 ? base
                        : base + "\n\n（再次强调：只输出一个 JSON 数组，不要输出对象、不要输出任何解释文字或 Markdown。）";
                String reply = chatModel.call(new Prompt(prompt)).getResult().getOutput().getText();
                if (reply == null || reply.isBlank()) {
                    continue;
                }
                return parseSpecs(reply);
            } catch (Exception e) {
                log.warn("清洗块第 {} 次失败：{}", attempt, e.getMessage());
            }
        }
        return null;
    }

    /** 兼容「单个对象」与「完整数组」两种形态 */
    private List<Spec> parseSpecs(String reply) throws Exception {
        String trimmed = reply.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            Spec single = JsonLoose.parse(json, reply, new TypeReference<>() {
            });
            return single == null ? List.of() : List.of(single);
        }
        return JsonLoose.parse(json, reply, new TypeReference<>() {
        });
    }

    /** 评分要点序列化为 JSON（null → "[]"） */
    public String pointsJson(List<Point> points) {
        if (points == null || points.isEmpty()) {
            return "[]";
        }
        try {
            return json.writeValueAsString(points);
        } catch (Exception e) {
            return "[]";
        }
    }

    /** 归一难度：含 BASIC/ADVANCED 按之，否则 INTERMEDIATE */
    public static String normalizeDifficulty(String d) {
        if (d == null) {
            return "INTERMEDIATE";
        }
        String up = d.toUpperCase(Locale.ROOT);
        if (up.contains("BASIC")) {
            return "BASIC";
        }
        if (up.contains("ADVANCED")) {
            return "ADVANCED";
        }
        return "INTERMEDIATE";
    }

    /** 题干去重键：去首尾空白 + 小写 */
    public static String stemKey(String stem) {
        return stem == null ? "" : stem.trim().toLowerCase(Locale.ROOT);
    }

    private List<String> chunk(String text, int size) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < text.length(); i += size) {
            out.add(text.substring(i, Math.min(i + size, text.length())));
        }
        return out;
    }

    private String cleanBlank(String raw) {
        return raw.replaceAll("(?m)^\\s*$", "").trim();
    }

    /** 结构化题目 */
    public record Spec(String stem, String answer, List<Point> points, String explanation, String skill, String difficulty) {
    }

    public record Point(String point, int score) {
    }
}