package com.demo.interview.resume;

import java.util.List;

/**
 * LLM 从简历中结构化抽取出的能力画像。
 * 字段名与 JSON 输出对齐，由 Spring AI 结构化输出 + 宽松解析兜底得到。
 */
public record ResumeProfile(
        String name,
        String targetRole,
        int yearsExperience,
        String school,
        String degree,
        String major,
        List<SkillItem> skills,
        List<ProjectItem> projects
) {
    public record SkillItem(String name, String level, List<String> keywords) {
    }

    public record ProjectItem(String name, String role, List<String> techStack, String description) {
    }
}