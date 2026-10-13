package com.demo.interview.bigtech;

/**
 * 大厂面试题（独立于常规题库，带公司标签）。
 */
public record BigtechQuestion(
        Long id,
        String company,      // 公司名，如 字节跳动 / 腾讯
        String skill,        // 知识点标签
        String type,         // SHORT_ANSWER
        String difficulty,   // BASIC / INTERMEDIATE / ADVANCED
        String stem,
        String answer,
        String points,       // 评分要点 JSON
        String explanation
) {
    public static final String STORE = "bigtech";
    public static final int FULL_SCORE = 25;
}