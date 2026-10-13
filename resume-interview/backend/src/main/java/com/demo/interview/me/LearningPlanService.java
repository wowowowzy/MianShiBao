package com.demo.interview.me;

import com.demo.interview.record.RecordRepository;
import com.demo.interview.resume.ResumeProfile;
import com.demo.interview.util.JsonLoose;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 学习路线生成：一次 LLM 调用，
 * 输入 = 候选人能力画像 + 近 5 次模拟面试的答题弱项（得分 < 及格线的题），
 * 输出 = 分阶段学习方案（阶段/时长/目标/任务/练习）。
 */
@Slf4j
@Service
public class LearningPlanService {

    private static final int MAX_WEAK_SUBMISSIONS = 5;
    private static final double WEAK_RATIO = 0.6;

    private final RecordRepository recordRepository;
    private final ChatModel chatModel;
    private final ObjectMapper json;

    public LearningPlanService(RecordRepository recordRepository, ChatModel chatModel, ObjectMapper json) {
        this.recordRepository = recordRepository;
        this.chatModel = chatModel;
        this.json = json;
    }

    public LearningPlan generate(Long userId, ResumeProfile profile) {
        String weakSummary = collectWeakPoints(userId);
        String prompt = buildPrompt(profile, weakSummary);
        log.info("生成学习路线：user={} 弱项摘要长度={}", userId, weakSummary.length());
        String reply = chatModel.call(new Prompt(prompt)).getResult().getOutput().getText();
        try {
            LearningPlan plan = JsonLoose.parse(json, reply, new TypeReference<>() {
            });
            if (plan == null || plan.phases() == null || plan.phases().isEmpty()) {
                throw new IllegalArgumentException("LLM 未返回有效阶段");
            }
            return plan;
        } catch (Exception e) {
            log.error("学习路线解析失败，返回兜底结构。reply 前 300 字: {}",
                    reply == null ? "null" : reply.substring(0, Math.min(300, reply.length())), e);
            return fallback(profile);
        }
    }

    /** 从近几次作答的评分报告中提取低分题（技能 + 得分 + 点评），作为学习方案的事实依据 */
    private String collectWeakPoints(Long userId) {
        List<RecordRepository.SubmissionRow> subs = recordRepository.listSubmissionsByUser(userId);
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (RecordRepository.SubmissionRow s : subs) {
            if (shown >= MAX_WEAK_SUBMISSIONS) {
                break;
            }
            try {
                JsonNode root = json.readTree(s.reportJson());
                JsonNode items = root.path("items");
                for (JsonNode it : items) {
                    int score = it.path("score").asInt(0);
                    int full = it.path("fullScore").asInt(0);
                    if (full > 0 && score * 1.0 / full < WEAK_RATIO) {
                        sb.append("- 技能[").append(it.path("skill").asText("?"))
                                .append("] 得分 ").append(score).append("/").append(full)
                                .append("，点评：").append(it.path("briefFeedback").asText("—"))
                                .append("\n");
                        shown++;
                        if (shown >= MAX_WEAK_SUBMISSIONS) {
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {
                // 单条解析失败跳过
            }
        }
        return sb.length() == 0 ? "（用户暂无作答记录，或暂无明显弱项）" : sb.toString().stripTrailing();
    }

    private String buildPrompt(ResumeProfile p, String weakSummary) {
        StringBuilder skills = new StringBuilder();
        if (p.skills() != null) {
            for (ResumeProfile.SkillItem s : p.skills()) {
                skills.append(s.name()).append('(').append(s.level()).append(")、");
            }
        }
        StringBuilder projects = new StringBuilder();
        if (p.projects() != null) {
            for (ResumeProfile.ProjectItem pr : p.projects()) {
                projects.append(pr.name()).append('[').append(pr.techStack()).append("]、");
            }
        }
        return """
                你是一名资深的 Java 后端职业成长导师。请根据候选人的能力画像与近期模拟面试的答题表现，
                制定一份可执行、不空洞的学习路线。

                候选人画像：
                - 目标岗位：%s
                - 经验：%d 年
                - 技能点：%s
                - 项目经历：%s

                近期模拟面试的答题弱项（来自评分报告，得分低于及格线）：
                %s

                要求：
                1. summary：一句话评估现状与主攻方向；
                2. duration：整个路线的建议周期（中文，如"4 周"）；
                3. phases：3 到 5 个阶段，每阶段给出 title（阶段名）、days（该阶段建议时长）、goals（本阶段目标列表）、tasks（具体可执行的学习任务列表，要结合其岗位与弱项，避免"学好基础"这种空话）、practice（练习/实战建议列表）；
                4. 弱项要从答题表现里点名并给出针对性补法。
                只输出一个 JSON 对象，不要输出任何其他文字：
                {"summary":"...","duration":"...","phases":[{"title":"...","days":"...","goals":["..."],"tasks":["..."],"practice":["..."]}]}
                """.formatted(
                p.targetRole() == null ? "Java 后端工程师" : p.targetRole(),
                p.yearsExperience(),
                skills.length() == 0 ? "（未提取）" : skills.substring(0, skills.length() - 1),
                projects.length() == 0 ? "（未提取）" : projects.substring(0, projects.length() - 1),
                weakSummary);
    }

    /** 解析失败时的兜底结构，保证接口不空返回 */
    private LearningPlan fallback(ResumeProfile p) {
        return new LearningPlan(
                "学习方案生成失败，请稍后重试。",
                "—",
                List.of(new LearningPlan.Phase(
                        "暂不可用", "—",
                        List.of("重新生成学习路线"), List.of("请再次点击生成"), List.of("期间可先做一套面试题"))));
    }
}