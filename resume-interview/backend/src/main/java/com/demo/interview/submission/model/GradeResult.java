package com.demo.interview.submission.model;

import java.util.List;

/**
 * 评分结果：逐题明细 + 试卷总分 + 能力画像。
 */
public record GradeResult(
        int totalScore,          // 试卷总分（各题得分之和）
        int totalFullScore,      // 试卷满分
        List<ItemResult> items,  // 逐题结果
        Long submissionId        // 作答记录主键（Controller 落库后回填）
) {
    public record ItemResult(
            Long questionId,
            String skill,
            String difficulty,
            String type,
            String stem,
            String userAnswer,
            int score,               // 本题得分
            int fullScore,           // 本题满分
            List<PointResult> points,// 要点判定明细（含 evidence）
            String explanation,      // 错点讲解
            String perfectAnswer,    // 标准/更优回答
            String briefFeedback     // 一句总评
    ) {
    }

    public record PointResult(
            String point,           // 要点描述
            int score,              // 该要点分值
            boolean hit,            // 是否命中
            String evidence         // 从作答中摘录的支撑原文
    ) {
    }
}