package com.demo.interview.question;

import com.demo.interview.question.model.Question;
import com.demo.interview.resume.ResumeProfile;
import com.demo.interview.util.JsonLoose;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 题库未覆盖技能的 LLM 补题器。
 * 对每个未覆盖技能动态生成 1 道原创简答题（SHORT_ANSWER / INTERMEDIATE）并落库为题库题
 * （source=llm-dynamic，id 为正），保证试卷覆盖全部简历技能点，且生成的题可走正常判分、
 * 可在历史试卷中复用。单个技能失败则跳过，不阻断整体组卷。
 */
@Slf4j
@Component
public class LlmQuestionFiller {

    private final ChatModel chatModel;
    private final QuestionRepository questionRepository;
    private final ObjectMapper json;

    public LlmQuestionFiller(ChatModel chatModel, QuestionRepository questionRepository, ObjectMapper json) {
        this.chatModel = chatModel;
        this.questionRepository = questionRepository;
        this.json = json;
    }

        /**
     * 其他专业基础题生成：一次 LLM 调用出 count 道「基础难度」简答题，
     * 结合简历技能与经历（题库不覆盖非计算机专业，全部原创并落库为 llm-dynamic 题）。
     */
    public List<QuestionService.QuestionView> generateBasics(ResumeProfile profile, int count) {
        List<QuestionService.QuestionView> views = new ArrayList<>();
        if (profile == null || count <= 0) {
            return views;
        }
        String skills = profile.skills() == null || profile.skills().isEmpty()
                ? "（简历未提取出明确技能，请按专业通用基础能力出题）"
                : profile.skills().stream().map(ResumeProfile.SkillItem::name).limit(8)
                        .reduce((a, b) -> a + "、" + b).orElse("");
        String prompt = """
                你是一名招聘面试官。候选人是【非计算机/互联网】专业背景的求职者，正在接受基础能力面。
                请结合候选人的专业经历与技能「%s」，设计 %d 道【基础难度】简答题，覆盖其专业内最常见、最值得考的知识点。
                每道题都要独立成题、题干 25~40 字，一题一个点，不要堆砌多个问题，不要出"是/否"式问题。
                只输出一个 JSON 数组，不要输出任何解释文字、不要用 Markdown 代码块，长度必须为 %d：
                [{"skill":"技能标签","stem":"题干","perfectAnswer":"要点式参考答案（分条列点）","points":[{"point":"关键点","score":3}],"explanation":"一句话拆解本题在考什么"}]
                """.formatted(skills, count, count);
        log.info("其他专业基础题 LLM 调用：count={}", count);
        String reply;
        try {
            reply = chatModel.call(new Prompt(prompt)).getResult().getOutput().getText();
        } catch (Exception e) {
            log.error("其他专业基础题 LLM 调用失败：{}", e.getMessage(), e);
            return views;
        }
        List<QuestionSpec> specs;
        try {
            specs = JsonLoose.parse(json, reply, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.warn("其他专业基础题 JSON 解析失败，跳过。reply 前 300 字: {}",
                    reply == null ? "null" : reply.substring(0, Math.min(300, reply.length())), e);
            return views;
        }
        if (specs == null) {
            return views;
        }
        for (QuestionSpec spec : specs) {
            if (views.size() >= count) {
                break;
            }
            QuestionService.QuestionView view = persistBasic(spec);
            if (view != null) {
                views.add(view);
            }
        }
        log.info("其他专业基础题生成完成：请求 {} 道，产出 {} 道", count, views.size());
        return views;
    }

    /** 基础题落库（difficulty=BASIC，source=llm-dynamic，store=bank，可走正常判分） */
    private QuestionService.QuestionView persistBasic(QuestionSpec spec) {
        if (spec.stem() == null || spec.stem().isBlank()) {
            return null;
        }
        String pointsJson;
        try {
            pointsJson = spec.points() == null ? "[]" : json.writeValueAsString(spec.points());
        } catch (Exception e) {
            log.warn("其他专业基础题评分要点序列化失败，跳过", e);
            return null;
        }
        String skill = spec.skill() == null || spec.skill().isBlank() ? "通用基础" : spec.skill();
        Question q = new Question(null, skill, Question.TYPE_SHORT_ANSWER, Question.DIFF_BASIC,
                spec.stem(), spec.perfectAnswer(), pointsJson, spec.explanation(), "llm-dynamic");
        Long id = questionRepository.insertWithId(q);
        if (id == null) {
            return null;
        }
        return new QuestionService.QuestionView(id, Question.TYPE_SHORT_ANSWER, Question.DIFF_BASIC,
                skill, spec.stem(), "bank", null, 20);
    }

    /** 为每个未覆盖技能生成 1 道答题视图；单技能失败则跳过，返回可追加的视图列表 */
    public List<QuestionService.QuestionView> fillUncovered(List<String> uncoveredSkills) {
        List<QuestionService.QuestionView> views = new ArrayList<>();
        if (uncoveredSkills == null || uncoveredSkills.isEmpty()) {
            return views;
        }
        for (String skill : uncoveredSkills) {
            QuestionService.QuestionView view = generateOne(skill);
            if (view != null) {
                views.add(view);
            }
        }
        log.info("LLM 补题完成：请求 {} 项，产出 {} 道", uncoveredSkills.size(), views.size());
        return views;
    }

    /** 单技能补题：调用 LLM 出题并宽松解析；任何失败返回 null 由上层跳过 */
    private QuestionService.QuestionView generateOne(String skill) {
        String prompt = """
                你是一名资深技术面试官。请针对技能「%s」出一道中级难度简答题（题干 25~40 字），
                考察该技能最常见、最值得挖深的知识点。
                只输出一个 JSON 对象，不要输出任何其他文字：
                {"stem":"题干（25-40 字）","perfectAnswer":"要点式标准答案（分条列点）","points":[{"point":"关键点一","score":3},{"point":"关键点二","score":2}],"explanation":"一句话拆解本题在考什么"}
                """.formatted(skill);
        log.info("LLM 补题调用：技能={}", skill);
        String reply;
        try {
            reply = chatModel.call(new Prompt(prompt)).getResult().getOutput().getText();
        } catch (Exception e) {
            log.error("LLM 补题调用失败，技能={}，跳过该技能", skill, e);
            return null;
        }
        QuestionSpec spec;
        try {
            spec = JsonLoose.parse(json, reply, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.error("LLM 补题 JSON 解析失败，技能={}，跳过该技能。reply={}", skill, reply, e);
            return null;
        }
        if (spec.stem() == null || spec.stem().isBlank()) {
            log.warn("LLM 补题缺少题干，技能={}，跳过该技能", skill);
            return null;
        }
        // 落库为题库题（source=llm-dynamic），拿到正 id 后走正常判分/试卷复用链路
        String pointsJson;
        try {
            pointsJson = spec.points() == null ? "[]" : json.writeValueAsString(spec.points());
        } catch (Exception e) {
            log.error("LLM 补题评分要点序列化失败，技能={}，跳过该技能", skill, e);
            return null;
        }
        Question q = new Question(null, skill, Question.TYPE_SHORT_ANSWER, Question.DIFF_INTERMEDIATE,
                spec.stem(), spec.perfectAnswer(), pointsJson, spec.explanation(), "llm-dynamic");
        Long id = questionRepository.insertWithId(q);
        if (id == null) {
            log.warn("LLM 补题落库失败，技能={}，跳过该技能", skill);
            return null;
        }
        return new QuestionService.QuestionView(
                id,
                Question.TYPE_SHORT_ANSWER,
                Question.DIFF_INTERMEDIATE,
                skill,
                spec.stem(),
                "bank",
                null,
                20);
    }

    /** LLM 出题返回的 JSON 结构（skill 供其他专业基础题标记技能；perfectAnswer/points/explanation 为后续判分预留） */
    private record QuestionSpec(String skill, String stem, String perfectAnswer, List<PointSpec> points, String explanation) {
    }

    private record PointSpec(String point, int score) {
    }
}