package com.demo.interview.question.model;

/**
 * 一道面试题。入库时即内建"可评分性"：points 为评分要点列表（要点判分法），
 * 评估层按命中要点累加得分；explanation 为错点讲解（评估层直接展示）。
 */
public record Question(
        Long id,
        String skill,        // 技能标签（SkillGraph 节点名）
        String type,         // SHORT_ANSWER
        String difficulty,   // BASIC / INTERMEDIATE / ADVANCED
        String stem,         // 题干
        String answer,       // 参考/标准答案
        String points,       // 评分要点 JSON（[{point, score}]）
        String explanation,  // 错点讲解
        String source        // 素材来源
) {
    public static final String TYPE_SHORT_ANSWER = "SHORT_ANSWER";

    public static final String DIFF_BASIC = "BASIC";
    public static final String DIFF_INTERMEDIATE = "INTERMEDIATE";
    public static final String DIFF_ADVANCED = "ADVANCED";
}