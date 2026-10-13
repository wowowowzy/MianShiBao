package com.demo.interview.me;

import java.util.List;

/**
 * 学习路线：一次 LLM 调用生成的分阶段学习方案。
 */
public record LearningPlan(
        String summary,       // 一句话评估与主攻方向
        String duration,      // 建议周期，如 "4 周"
        List<Phase> phases    // 3~5 个阶段
) {
    public record Phase(
            String title,
            String days,
            List<String> goals,     // 阶段目标
            List<String> tasks,     // 具体学习任务
            List<String> practice   // 练习/实战建议
    ) {
    }
}