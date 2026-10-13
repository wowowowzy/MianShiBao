package com.demo.interview.submission;

import com.demo.interview.bigtech.BigtechQuestion;
import com.demo.interview.bigtech.BigtechQuestionRepository;
import com.demo.interview.question.ProjectQuestionRepository;
import com.demo.interview.question.QuestionRepository;
import com.demo.interview.question.model.Question;
import com.demo.interview.submission.model.AnswerInput;
import com.demo.interview.submission.model.GradeRequest;
import com.demo.interview.submission.model.GradeResult;
import com.demo.interview.util.JsonLoose;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 评估核心：要点判分法。
 * 主观题打包成【一次 LLM 调用】逐题判定命中程度（hitFraction 0/0.5/1 三档），
 * 要点得分 = round(分值 × hitFraction)（部分命中给一半分），
 * 并摘录作答证据、给出错点讲解与标准/更优回答。
 * 每题要点按比例归一到该题满分（bank/project 默认 20，大厂题 25，由作答带上来）。
 */
@Slf4j
@Service
public class GradeService {

    private static final int DEFAULT_FULL_SCORE = 20;

    private final QuestionRepository questionRepository;
    private final ProjectQuestionRepository projectQuestionRepository;
    private final BigtechQuestionRepository bigtechQuestionRepository;
    private final ChatModel chatModel;
    private final ObjectMapper json;

    public GradeService(QuestionRepository questionRepository, ProjectQuestionRepository projectQuestionRepository,
                        BigtechQuestionRepository bigtechQuestionRepository, ChatModel chatModel, ObjectMapper json) {
        this.questionRepository = questionRepository;
        this.projectQuestionRepository = projectQuestionRepository;
        this.bigtechQuestionRepository = bigtechQuestionRepository;
        this.chatModel = chatModel;
        this.json = json;
    }

    public GradeResult grade(GradeRequest request) {
        List<GradeResult.ItemResult> items = new ArrayList<>();
        List<SubTask> subs = new ArrayList<>();
        for (AnswerInput answer : request.answers()) {
            Question q = loadQuestion(answer);
            if (q == null) {
                continue;
            }
            int fullScore = answer.fullScore() != null && answer.fullScore() > 0
                    ? answer.fullScore() : DEFAULT_FULL_SCORE;
            subs.add(new SubTask(answer, q, readPoints(q, fullScore)));
        }
        items.addAll(gradeSubjectiveBatch(subs));

        int total = items.stream().mapToInt(GradeResult.ItemResult::score).sum();
        int full = items.stream().mapToInt(GradeResult.ItemResult::fullScore).sum();
        log.info("评卷完成 {} 题（主观 {} 道打包判分），总分 {}/{}", items.size(), subs.size(), total, full);
        return new GradeResult(total, full, items, null);
    }

    /** 按 store 从对应表取题（bank / project / bigtech）；缺失返回 null（调用方跳过） */
    private Question loadQuestion(AnswerInput answer) {
        try {
            if (BigtechQuestion.STORE.equals(answer.store())) {
                BigtechQuestion b = bigtechQuestionRepository.findById(answer.questionId());
                return new Question(b.id(), b.skill(), b.type(), b.difficulty(), b.stem(),
                        b.answer(), b.points(), b.explanation(), BigtechQuestion.STORE);
            }
            if (ProjectQuestionRepository.STORE.equals(answer.store())) {
                return projectQuestionRepository.findById(answer.questionId());
            }
            return questionRepository.findById(answer.questionId());
        } catch (org.springframework.dao.DataAccessException e) {
            log.warn("题目 {} 未找到（store={}），降级为不可评分项: {}",
                    answer.questionId(), answer.store(), e.getMessage());
            return null;
        }
    }

    /* ---------------------------------- 主观题：打包一次判分 ---------------------------------- */

    private List<GradeResult.ItemResult> gradeSubjectiveBatch(List<SubTask> subs) {
        if (subs.isEmpty()) {
            return List.of();
        }
        String prompt = buildBatchPrompt(subs);
        log.info("LLM 批量判分：{} 道主观题（一次调用）", subs.size());
        String reply = chatModel.call(new Prompt(prompt)).getResult().getOutput().getText();

        BatchVerdict batch;
        try {
            batch = JsonLoose.parse(json, reply, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.error("批量判分 JSON 解析失败，回退逐题判分。reply 前 500 字: {}", sample(reply), e);
            return fallbackToSingle(subs);
        }
        if (batch == null || batch.results() == null || batch.results().isEmpty()) {
            log.warn("批量判分无结果，回退逐题判分。reply 前 500 字: {}", sample(reply));
            return fallbackToSingle(subs);
        }

        Map<Integer, QResult> byIndex = new HashMap<>();
        for (QResult r : batch.results()) {
            byIndex.put(r.qIndex(), r);
        }
        List<GradeResult.ItemResult> out = new ArrayList<>();
        for (int i = 0; i < subs.size(); i++) {
            SubTask t = subs.get(i);
            QResult r = byIndex.get(i + 1);
            String brief = r == null ? "" : r.briefFeedback();
            out.add(buildItemResult(t, r == null ? List.of() : r.items(), brief));
        }
        return out;
    }

    private List<GradeResult.ItemResult> fallbackToSingle(List<SubTask> subs) {
        List<GradeResult.ItemResult> out = new ArrayList<>();
        for (SubTask t : subs) {
            try {
                out.add(gradeShortAnswer(t));
            } catch (Exception e) {
                log.warn("单题回退判分失败 id={}", t.q().id(), e);
            }
        }
        return out;
    }

    private String buildBatchPrompt(List<SubTask> subs) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一名严格、公正的技术面试评分官。下面是 ")
              .append(subs.size())
              .append(" 道技术面试题（含参考答案与评分要点）及候选人对每题的作答，请逐题按要点评分。\n\n");
        for (int i = 0; i < subs.size(); i++) {
            SubTask t = subs.get(i);
            sb.append("=== 题目 ").append(i + 1).append(" ===\n");
            sb.append("题干：").append(t.q().stem()).append("\n");
            sb.append("参考答案：").append(t.q().answer()).append("\n");
            sb.append("评分要点与分值：").append(renderPoints(t.points())).append("\n");
            sb.append("候选人作答：")
                    .append(t.answer().answerText() == null || t.answer().answerText().isBlank()
                            ? "(未作答)" : t.answer().answerText())
                    .append("\n\n");
        }
        sb.append("请对每题的每个评分要点评定命中程度 hitFraction（三档）：\n")
                .append("- 1：基本答出该要点核心；用自己的话讲出核心机制同样算命中（语义等价即命中，不要只按关键字对判分）\n")
                .append("- 0.5：方向正确但只覆盖部分语义 / 缺失关键细节\n")
                .append("- 0：完全未答出或答错\n")
                .append("并从作答原文摘录支撑判定的证据（evidence，未答出则留空）。每题给一句 briefFeedback（40 字以内，指出主要丢分点）。\n")
                .append("只输出一个 JSON 对象，不要输出任何其他文字：\n")
                .append("{\"results\":[{\"qIndex\":1,\"items\":[{\"point\":\"要点原文\",\"score\":分值,\"hitFraction\":1,\"evidence\":\"从作答中摘录的原文\"}],\"briefFeedback\":\"点评\"}]}");
        return sb.toString();
    }

    private GradeResult.ItemResult gradeShortAnswer(SubTask t) {
        String prompt = """
                你是一名严格、公正的技术面试评分官。下面是题目、参考答案、评分要点(含分值)与候选人作答。

                题目：%s
                参考答案：%s
                评分要点与分值：%s
                候选人作答：%s

                请为每个评分要点评定命中程度 hitFraction（三档）：
                - 1：基本答出该要点核心；用自己的话讲出核心机制同样算命中（语义等价即命中，不要只按关键字对判分）
                - 0.5：方向正确但只覆盖部分语义 / 缺失关键细节
                - 0：完全未答出或答错
                并从作答原文摘录支撑判定的证据（evidence，未答出则留空）。
                最后给出 briefFeedback（40 字以内、指出主要丢分点的一句点评）。
                只输出一个 JSON 对象，不要输出任何其他文字：
                {"items":[{"point":"要点原文","score":分值,"hitFraction":1,"evidence":"从作答中摘录的原文"}],"briefFeedback":"..."}
                """.formatted(t.q().stem(), t.q().answer(), renderPoints(t.points()),
                t.answer().answerText() == null ? "(未作答)" : t.answer().answerText());

        log.info("LLM 单题判分 id={} 技能={}", t.q().id(), t.q().skill());
        String reply = chatModel.call(new Prompt(prompt)).getResult().getOutput().getText();
        Verdict verdict;
        try {
            verdict = JsonLoose.parse(json, reply, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.error("判分 JSON 解析失败，id={}", t.q().id(), e);
            verdict = new Verdict(null, "评分解析失败，请人工复核");
        }
        return buildItemResult(t, verdict.items() == null ? List.of() : verdict.items(),
                verdict.briefFeedback() == null ? "" : verdict.briefFeedback());
    }

    private GradeResult.ItemResult buildItemResult(SubTask t, List<VItem> verdictItems, String brief) {
        List<GradeResult.PointResult> basePoints = t.points();
        List<GradeResult.PointResult> judged = new ArrayList<>();
        for (int i = 0; i < basePoints.size(); i++) {
            GradeResult.PointResult base = basePoints.get(i);
            double fraction = 0;
            String evidence = "";
            if (verdictItems != null && i < verdictItems.size()) {
                VItem vi = verdictItems.get(i);
                fraction = resolveFraction(vi);
                evidence = vi.evidence() == null ? "" : vi.evidence();
            }
            int pointScore = (int) Math.round(base.score() * fraction);
            judged.add(new GradeResult.PointResult(base.point(), pointScore, fraction > 0, evidence));
        }
        int score = judged.stream()
                .filter(GradeResult.PointResult::hit)
                .mapToInt(GradeResult.PointResult::score)
                .sum();
        int full = basePoints.stream().mapToInt(GradeResult.PointResult::score).sum();
        Question q = t.q();
        return new GradeResult.ItemResult(q.id(), q.skill(), q.difficulty(), q.type(), q.stem(),
                t.answer().answerText(), score, full, judged,
                q.explanation(), q.answer(), brief == null ? "" : brief);
    }

    private static double resolveFraction(VItem vi) {
        if (vi.hitFraction() != null) {
            return Math.round(vi.hitFraction() * 2) / 2.0;
        }
        return Boolean.TRUE.equals(vi.hit()) ? 1.0 : 0.0;
    }

    private static String sample(String reply) {
        if (reply == null) {
            return "null";
        }
        return reply.substring(0, Math.min(500, reply.length()));
    }

    private List<GradeResult.PointResult> readPoints(Question q, int fullScore) {
        List<GradeResult.PointResult> raw = new ArrayList<>();
        if (q.points() != null && !q.points().isBlank()) {
            try {
                JsonNode arr = json.readTree(q.points());
                for (JsonNode n : arr) {
                    raw.add(new GradeResult.PointResult(
                            n.path("point").asText(""),
                            n.path("score").asInt(0),
                            false, ""));
                }
            } catch (Exception e) {
                log.warn("题目 {} 的评分要点 JSON 解析失败", q.id(), e);
            }
        }
        return normalizePoints(raw, fullScore);
    }

    /** 把每题要点分值按比例归一到该题满分，用最大余数法保证整数且合计精确 */
    private static List<GradeResult.PointResult> normalizePoints(List<GradeResult.PointResult> raw, int fullScore) {
        if (raw.isEmpty()) {
            return List.of(new GradeResult.PointResult("整体作答", fullScore, false, ""));
        }
        if (raw.size() == 1) {
            return List.of(new GradeResult.PointResult(raw.get(0).point(), fullScore, false, ""));
        }
        int n = raw.size();
        int total = raw.stream().mapToInt(GradeResult.PointResult::score).sum();
        int[] scaled = new int[n];
        if (total <= 0) {
            int base = fullScore / n;
            for (int i = 0; i < n; i++) {
                scaled[i] = base;
            }
            for (int i = 0; i < fullScore - base * n; i++) {
                scaled[i]++;
            }
        } else {
            double[] frac = new double[n];
            int assigned = 0;
            for (int i = 0; i < n; i++) {
                double exact = raw.get(i).score() * (double) fullScore / total;
                scaled[i] = (int) Math.floor(exact);
                frac[i] = exact - scaled[i];
                assigned += scaled[i];
            }
            Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) {
                order[i] = i;
            }
            java.util.Arrays.sort(order, (a, b) -> Double.compare(frac[b], frac[a]));
            for (int i = 0; i < fullScore - assigned; i++) {
                scaled[order[i % n]]++;
            }
        }
        List<GradeResult.PointResult> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new GradeResult.PointResult(raw.get(i).point(), scaled[i], false, ""));
        }
        return out;
    }

    private String renderPoints(List<GradeResult.PointResult> points) {
        return points.stream()
                .map(p -> "{\"point\":\"" + p.point() + "\",\"score\":" + p.score() + "}")
                .toList()
                .toString();
    }

    private record SubTask(AnswerInput answer, Question q, List<GradeResult.PointResult> points) {
    }

    private record Verdict(List<VItem> items, String briefFeedback) {
    }

    private record VItem(String point, int score, Double hitFraction, Boolean hit, String evidence) {
    }

    private record BatchVerdict(List<QResult> results) {
    }

    private record QResult(int qIndex, List<VItem> items, String briefFeedback) {
    }
}