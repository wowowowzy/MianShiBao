package com.demo.interview.question;

import com.demo.interview.question.model.Question;
import com.demo.interview.rag.QuestionVecService;
import com.demo.interview.rag.SemanticRanker;
import com.demo.interview.resume.ResumeProfile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 出题编排：简历能力画像 → 按【岗位域】限域 → 技能匹配 → 组卷（难度阶梯）→ 网络补充题 → 项目场景题。
 * 岗位决定启用哪些题库来源（source 域）：
 * - Java 后端：mysql-redis + java-basic + mq-distributed + dachang
 * - 软件测试：testdev
 * - 前端开发：frontend + network-os-ds
 * - AI 应用开发：ai-app
 * 另设网络补充源（network-os-ds）：任何岗位下，简历技能命中计网/OS/算法即追加少量补充题。
 * 题库覆盖不到的简历技能点由 {@link LlmQuestionFiller} 动态补原创题。
 */
@Slf4j
@Service
public class QuestionService {

    private static final int MAX_PER_SKILL = 2;
    private static final int SUPPLEMENT_MAX = 3;
    private static final int RETRIEVE_TOPK = 30;          // 题目级粗召回候选数（召回率优先，交 rerank 精排）
    private static final int RERANK_PICK = 5;             // rerank 精排最终取题数
    private static final int PROJECT_QUESTION_COUNT = 2;  // 整卷内项目场景题数量

    private static final String SRC_NETWORK = "imported:network-os-ds";
    private static final String SRC_TEST = "imported:testdev";
    private static final String SRC_FRONTEND = "imported:frontend";
    private static final String SRC_AI_APP = "imported:ai-app";
    private static final List<String> JAVA_ROLE_SOURCES = List.of(
            "imported:mysql-redis", "imported:java-basic", "imported:mq-distributed", "imported:dachang");

    private static final List<String> NETWORK_TRIGGERS = List.of(
            "tcp", "udp", "http", "https", "dns", "socket", "网络", "操作系统",
            "数据结构", "算法", "协议", "三次握手", "tls", "tcpip", "链路", "路由",
            "链表", "二叉树", "排序", "栈", "队列", "图论", "哈希", "数组");

    private final QuestionRepository repository;
    private final Random random = new Random();
    private final LlmQuestionFiller llmQuestionFiller;
    private final ProjectQuestionGenerator projectQuestionGenerator;
    private final QuestionVecService questionVecService;
    private final SemanticRanker semanticRanker;

    public QuestionService(QuestionRepository repository, LlmQuestionFiller llmQuestionFiller,
                           ProjectQuestionGenerator projectQuestionGenerator, QuestionVecService questionVecService,
                           SemanticRanker semanticRanker) {
        this.repository = repository;
        this.llmQuestionFiller = llmQuestionFiller;
        this.projectQuestionGenerator = projectQuestionGenerator;
        this.questionVecService = questionVecService;
        this.semanticRanker = semanticRanker;
    }

    /** 兼容 reuse 入口：默认按 Java 后端岗位出题 */
    public GenerateResult generate(ResumeProfile profile, int maxQuestions) {
        return generate(profile, maxQuestions, "Java 后端", null);
    }

    /** 按岗位出题（未传大类时按计算机出 RAG 题） */
    public GenerateResult generate(ResumeProfile profile, int maxQuestions, String role) {
        return generate(profile, maxQuestions, role, null);
    }

    /**
     * 按岗位 + 专业大类出题：
     * - 计算机大类（或未指定）：走常规 RAG 召回 + 精排 + 项目场景题（默认路径）
     * - 其他专业：题库无题，纯 LLM 出 3 道基础题 + 2 道项目场景题，完全不进 RAG
     */
    public GenerateResult generate(ResumeProfile profile, int maxQuestions, String role, String majorDomain) {
        if (isOtherMajor(majorDomain)) {
            return generateForOtherMajor(profile, maxQuestions);
        }
        List<String> skillNames = profile.skills() == null ? List.of()
                : profile.skills().stream().map(ResumeProfile.SkillItem::name).toList();
        List<String> sources = sourcesForRole(role);

        // 主通道：题目级语义召回 + ReRank 精排（贴合简历，高相关题优先）
        List<Question> ranked = semanticRetrieve(skillNames, sources, RERANK_PICK);

        // 次通道：标签/文本兜底（补足岗位必备与召回不足）；文本没匹配上的技能直接交给 LLM 补题
        Set<Long> haveIds = new HashSet<>();
        ranked.forEach(q -> haveIds.add(q.id()));
        List<Question> labelPool = repository.findBySources(sources);
        Map<String, List<Question>> bySkill = matchByText(skillNames, labelPool);
        List<String> uncovered = new ArrayList<>();
        for (String name : skillNames) {
            if (!bySkill.containsKey(name)) {
                uncovered.add(name);
            }
        }
        List<Question> labelPicked = pickFrom(bySkill, maxQuestions);
        List<Question> merged = new ArrayList<>(ranked);
        for (Question q : labelPicked) {
            if (haveIds.add(q.id())) {
                merged.add(q);
            }
        }

        // 网络补充题（独立域）：简历命中计网/OS/算法则追加
        List<QuestionView> netExtra = networkSupplement(skillNames);

        // 项目场景题：随机抽一个项目，LLM 出 PROJECT_QUESTION_COUNT 道，占整卷名额
        List<QuestionView> projectViews = projectQuestionGenerator.generate(profile, PROJECT_QUESTION_COUNT);
        int baseTarget = Math.max(0, maxQuestions - projectViews.size());

        // 难度阶梯排序 + 裁剪到 baseTarget
        merged.sort(Comparator.comparingInt(QuestionService::difficultyRank).thenComparing(u -> random.nextInt()));
        List<QuestionView> views = new ArrayList<>(merged.stream().limit(baseTarget).map(QuestionView::of).toList());
        views.addAll(netExtra);
        if (views.size() > baseTarget) {
            views = new ArrayList<>(views.subList(0, baseTarget));
        }

        // 未覆盖技能交给 LLM 动态补题
        int remaining = Math.max(0, baseTarget - views.size());
        views = combineWithLlmFill(views, uncovered, remaining);

        // 并入项目场景题，整卷封顶 maxQuestions
        views.addAll(projectViews);
        if (views.size() > maxQuestions) {
            views = new ArrayList<>(views.subList(0, maxQuestions));
        }

        int matched = skillNames.size() - uncovered.size();
        log.info("出题(role={})：Rerank精排 {} 题，标签保底 {} 题，项目场景 {} 题，最终 {} 题，未覆盖 {}，计网补充 {}",
                role == null ? "默认" : role, ranked.size(), labelPicked.size(), projectViews.size(),
                views.size(), uncovered, netExtra.size());
        return new GenerateResult(views, matched, skillNames.size(), uncovered, null);
    }

    /** 是否属于「其他专业」：题库无题，走纯 LLM 出题 */
    private static boolean isOtherMajor(String majorDomain) {
        if (majorDomain == null) {
            return false;
        }
        String m = majorDomain.trim();
        return m.contains("其他") || m.contains("其它") || m.contains("非计算")
                || m.contains("非互联网") || m.contains("非IT") || m.contains(" non");
    }

    /**
     * 其他专业出题：完全不进 RAG 题库（题库只覆盖计算机类岗位）。
     * 由 LLM 按简历技能/经历出 {@code min(3, 剩余)} 道基础题 + 项目场景题（有项目才出）。
     */
    private GenerateResult generateForOtherMajor(ResumeProfile profile, int maxQuestions) {
        int skillCount = profile == null || profile.skills() == null ? 0 : profile.skills().size();
        // 项目场景题（若简历没有项目则出 0 道）
        List<QuestionView> scenarioViews = projectQuestionGenerator.generate(profile, PROJECT_QUESTION_COUNT);
        // 基础题：固定 3 道，但不超过整卷剩余名额
        int baseWant = Math.min(3, Math.max(0, maxQuestions - scenarioViews.size()));
        List<QuestionView> baseViews = baseWant > 0
                ? llmQuestionFiller.generateBasics(profile, baseWant)
                : List.of();

        List<QuestionView> views = new ArrayList<>();
        views.addAll(baseViews);
        views.addAll(scenarioViews);
        if (views.size() > maxQuestions) {
            views = new ArrayList<>(views.subList(0, maxQuestions));
        }
        log.info("出题(其他专业，纯 LLM 不走 RAG)：基础 {} 道，场景 {} 道，最终 {} 题",
                baseViews.size(), scenarioViews.size(), views.size());
        // 其他专业无「未覆盖技能」口径，matched/total 按技能数平账
        return new GenerateResult(views, skillCount, skillCount, List.of(), null);
    }

    /** 岗位 → 启用的题库来源域 */
    private static List<String> sourcesForRole(String role) {
        String r = role == null ? "" : role.trim();
        if (r.contains("测试")) {
            return List.of(SRC_TEST);
        }
        if (r.contains("前端")) {
            return List.of(SRC_FRONTEND, SRC_NETWORK);
        }
        if (r.contains("AI") || r.contains("ai") || r.contains("Agent") || r.contains("应用开发")) {
            return List.of(SRC_AI_APP);
        }
        return JAVA_ROLE_SOURCES;
    }

    /** 题目级语义召回：简历技术要点 → 题目向量 kNN 粗召回 topK → MaaS rerank 精排取前 pick（失败降级空列表） */
    private List<Question> semanticRetrieve(List<String> skillNames, List<String> sources, int pick) {
        if (skillNames.isEmpty()) {
            return List.of();
        }
        String query = String.join("、", skillNames);
        try {
            double[] qvec = semanticRanker.embed(query);
            List<QuestionVecService.Scored> cands = questionVecService.knn(qvec, RETRIEVE_TOPK, sources);
            if (cands.isEmpty()) {
                return List.of();
            }
            List<Long> ids = cands.stream().map(QuestionVecService.Scored::questionId).toList();
            List<Question> qs = repository.findByIds(ids);
            if (qs.isEmpty()) {
                return List.of();
            }
            List<String> stems = qs.stream()
                    .map(q -> (q.skill() == null ? "" : q.skill() + "-") + q.stem())
                    .toList();
            List<Question> out = new ArrayList<>();
            for (int idx : semanticRanker.rerank(query, stems, pick)) {
                if (idx >= 0 && idx < qs.size()) {
                    out.add(qs.get(idx));
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("题目级语义召回/精排降级（走标签保底）：{}", e.getMessage(), e);
            return List.of();
        }
    }

    /** 简历技能名与域池题目 skill 做文本匹配（相等/互相包含，忽略大小写） */
    private Map<String, List<Question>> matchByText(List<String> skillNames, List<Question> pool) {
        Map<String, List<Question>> bySkill = new LinkedHashMap<>();
        for (String name : skillNames) {
            for (Question q : pool) {
                if (skillMatches(name, q.skill())) {
                    bySkill.computeIfAbsent(name, k -> new ArrayList<>()).add(q);
                }
            }
        }
        return bySkill;
    }

    /** 技能名与题目 skill 的宽松文本匹配：忽略大小写，相等或互相包含 */
    private static boolean skillMatches(String skillName, String questionSkill) {
        if (skillName == null || questionSkill == null) {
            return false;
        }
        String a = skillName.toLowerCase();
        String b = questionSkill.toLowerCase();
        return a.equals(b) || a.contains(b) || b.contains(a);
    }

    /** 网络补充题：简历技能命中计网/OS/算法时，从网络域取与该技能语义对齐的题（不足则随机补齐） */
    private List<QuestionView> networkSupplement(List<String> skillNames) {
        List<String> triggered = new ArrayList<>();
        for (String n : skillNames) {
            String ln = n.toLowerCase();
            if (NETWORK_TRIGGERS.stream().anyMatch(ln::contains)) {
                triggered.add(n);
            }
        }
        if (triggered.isEmpty()) {
            return List.of();
        }
        List<Question> netPool = repository.findBySources(List.of(SRC_NETWORK));
        if (netPool.isEmpty()) {
            return List.of();
        }
        List<QuestionView> extra = new ArrayList<>();
        // 1) 按触发技能语义/文本对齐到网络域题目
        for (String name : triggered) {
            if (extra.size() >= SUPPLEMENT_MAX) {
                break;
            }
            Question best = bestNetQuestion(name, netPool);
            if (best != null) {
                extra.add(QuestionView.of(best));
                netPool.remove(best);
            }
        }
        // 2) 仍不足则随机补齐到上限
        while (extra.size() < SUPPLEMENT_MAX && !netPool.isEmpty()) {
            Collections.shuffle(netPool, random);
            extra.add(QuestionView.of(netPool.remove(0)));
        }
        log.info("网络补充题触发：追加 {} 道（计网/OS/算法，语义对齐 {} 道）",
                extra.size(), Math.min(extra.size(), triggered.size()));
        return extra;
    }

    /** 在网络域池中挑一道与简历技能文本匹配的题；找不到返回 null */
    private Question bestNetQuestion(String name, List<Question> pool) {
        for (Question q : pool) {
            if (skillMatches(name, q.skill())) {
                return q;
            }
        }
        return null;
    }

    private List<Question> pickFrom(Map<String, List<Question>> bySkill, int target) {
        List<Question> picked = new ArrayList<>();
        for (List<Question> qs : bySkill.values()) {
            qs.stream()
                    .sorted(Comparator.comparingInt(QuestionService::difficultyRank))
                    .limit(MAX_PER_SKILL)
                    .forEach(picked::add);
        }
        picked.sort(Comparator.comparingInt(QuestionService::difficultyRank)
                .thenComparing(unused -> random.nextInt()));
        if (picked.size() <= target) {
            return picked;
        }
        return new ArrayList<>(picked.subList(0, target));
    }

    /** 题库题（可能为空）+ LLM 未覆盖技能补题合并；补题器未注入或无未覆盖技能时保持原题列表不变 */
    private List<QuestionView> combineWithLlmFill(List<QuestionView> baseViews, List<String> uncovered, int remaining) {
        if (llmQuestionFiller == null || uncovered == null || uncovered.isEmpty() || remaining <= 0) {
            return baseViews;
        }
        List<String> pick = uncovered.size() <= remaining ? uncovered : uncovered.subList(0, remaining);
        List<QuestionView> combined = new ArrayList<>(baseViews);
        combined.addAll(llmQuestionFiller.fillUncovered(pick));
        return combined;
    }

    private static int difficultyRank(Question q) {
        return switch (q.difficulty()) {
            case Question.DIFF_BASIC -> 1;
            case Question.DIFF_INTERMEDIATE -> 2;
            default -> 3;
        };
    }

    public List<Question> listAll() {
        return repository.findAll();
    }

    /** 出题结果：答题视图（不含答案/要点/讲解，避免作答前泄题）；paperId 由 Controller 落库后回填 */
    public record GenerateResult(
            List<QuestionView> questions,
            int matchedSkillCount,
            int totalSkillCount,
            List<String> uncoveredSkills,
            Long paperId
    ) {
    }

    /** 答题者可见的题目视图；store 题源、company 公司（大厂题）、fullScore 本题满分 */
    public record QuestionView(
            Long id,
            String type,
            String difficulty,
            String skill,
            String stem,
            String store,
            String company,
            int fullScore
    ) {
        static QuestionView of(Question q) {
            return new QuestionView(q.id(), q.type(), q.difficulty(), q.skill(), q.stem(), "bank", null, 20);
        }
    }
}