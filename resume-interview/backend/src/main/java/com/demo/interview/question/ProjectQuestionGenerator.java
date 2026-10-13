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
import java.util.Random;

/**
 * 项目场景题生成器：从简历项目经历里随机抽一个项目，让 LLM 出 count 道
 * 「亮点如何实现 / 难点与取舍」类简答题，落库到独立表 project_question
 * （store=project，拿到正 id 可走正常判分），不进入 RAG 题库。
 * 任何失败都返回空列表，由上层用常规出题补足名额。
 */
@Slf4j
@Component
public class ProjectQuestionGenerator {

    private static final String SKILL = "项目实战";

    private final ChatModel chatModel;
    private final ProjectQuestionRepository projectQuestionRepository;
    private final ObjectMapper json;
    private final Random random = new Random();

    public ProjectQuestionGenerator(ChatModel chatModel, ProjectQuestionRepository projectQuestionRepository,
                                    ObjectMapper json) {
        this.chatModel = chatModel;
        this.projectQuestionRepository = projectQuestionRepository;
        this.json = json;
    }

    /** 随机抽一个项目，生成至多 count 道场景题；无项目/失败返回空 */
    public List<QuestionService.QuestionView> generate(ResumeProfile profile, int count) {
        List<QuestionService.QuestionView> views = new ArrayList<>();
        if (profile == null || profile.projects() == null || profile.projects().isEmpty() || count <= 0) {
            return views;
        }
        ResumeProfile.ProjectItem project = profile.projects().get(random.nextInt(profile.projects().size()));
        String reply;
        try {
            reply = chatModel.call(new Prompt(buildPrompt(project, count))).getResult().getOutput().getText();
        } catch (Exception e) {
            log.warn("项目场景题 LLM 调用失败，跳过：{}", e.getMessage());
            return views;
        }
        List<Spec> specs;
        try {
            specs = JsonLoose.parse(json, reply, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.warn("项目场景题 JSON 解析失败，跳过。reply 前 300 字: {}",
                    reply == null ? "null" : reply.substring(0, Math.min(300, reply.length())), e);
            return views;
        }
        if (specs == null) {
            return views;
        }
        for (Spec spec : specs) {
            if (views.size() >= count) {
                break;
            }
            QuestionService.QuestionView v = persist(project.name(), spec);
            if (v != null) {
                views.add(v);
            }
        }
        log.info("项目场景题生成完成：项目={}，产出 {} 道", project.name(), views.size());
        return views;
    }

    private QuestionService.QuestionView persist(String projectName, Spec spec) {
        if (spec.stem() == null || spec.stem().isBlank()) {
            return null;
        }
        String pointsJson;
        try {
            pointsJson = spec.points() == null ? "[]" : json.writeValueAsString(spec.points());
        } catch (Exception e) {
            log.warn("项目场景题评分要点序列化失败，跳过", e);
            return null;
        }
        Question q = new Question(null, SKILL, Question.TYPE_SHORT_ANSWER, Question.DIFF_INTERMEDIATE,
                spec.stem(), spec.perfectAnswer(), pointsJson, spec.explanation(), ProjectQuestionRepository.STORE);
        Long id = projectQuestionRepository.insertWithId(projectName, q);
        if (id == null) {
            return null;
        }
        return new QuestionService.QuestionView(id, Question.TYPE_SHORT_ANSWER, Question.DIFF_INTERMEDIATE,
                SKILL, spec.stem(), ProjectQuestionRepository.STORE, null, 20);
    }

    private String buildPrompt(ResumeProfile.ProjectItem p, int count) {
        String role = p.role() == null ? "（未填写）" : p.role();
        String tech = (p.techStack() == null || p.techStack().isEmpty()) ? "（未填写）" : String.join("、", p.techStack());
        String desc = p.description() == null ? "（未填写）" : p.description();
        return """
                你是一名一线互联网公司的高级技术面试官，正在对候选人进行“项目深挖”环节。下面是从其简历中提取的【一个】项目，请只围绕这一个项目设计 %d 道简答题，用来判断候选人是否真正主导/参与过它、能否讲清实现细节与工程取舍。

                【项目信息】
                项目名：%s
                本人角色：%s
                技术栈：%s
                项目描述：%s

                【出题要求】
                1. 必须全部以该项目为背景：题干要点出项目名，并自然带入其技术栈；禁止出与该项目无关的通用八股题（例如“JVM 内存结构是什么”“TCP 三次握手过程”）。
                2. 像真实面试官现场追问：语言自然、一题只聚焦一个点，不要把多个问题堆进同一题，也不要出“是/否”式问题。
                3. 按下列角度分配题目：
                   - 第 1 题（必出）：亮点实现——从项目描述里挑一个亮点或关键能力，追问“具体是怎么设计实现的”：核心方案与原理、关键流程或数据结构、如何保证正确性与性能。
                   - 第 2 题（若题目数≥2）：难点与取舍——追问一个难点或线上问题：如何定位、根因是什么、怎么解决、有哪些权衡与踩坑。
                   - 第 3 题及以后（若题目数>2）：可扩展到容量与扩展性、方案对比与选型、“如果重做会怎么改”等。
                4. 你并不了解候选人项目内部的真实细节，因此：
                   - 题干只做“追问”，不要臆断或编造项目里确实存在的具体事实；
                   - perfectAnswer 不是唯一标准答案，而是“优秀回答应当覆盖的要点清单”（方案与原理、具体机制或数据、量化结果、边界与取舍、替代方案对比）。
                5. points 每题 3~5 条，分值合计必须为 100，分值大小体现该要点的重要性。
                6. explanation：一句话点明这道题在考察什么能力。
                7. 严格只输出一个 JSON 数组，数组长度必须为 %d，不要输出任何解释文字、不要用 Markdown 代码块，字段如下：
                [{"stem":"题干","perfectAnswer":"要点式参考答案","points":[{"point":"要点","score":30}],"explanation":"考点"}]
                """.formatted(count, p.name(), role, tech, desc, count);
    }

    /** LLM 出题返回结构 */
    private record Spec(String stem, String perfectAnswer, List<PointSpec> points, String explanation) {
    }

    private record PointSpec(String point, int score) {
    }
}