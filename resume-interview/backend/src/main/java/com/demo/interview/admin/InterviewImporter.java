package com.demo.interview.admin;

import com.demo.interview.question.QuestionRepository;
import com.demo.interview.question.SkillGraph;
import com.demo.interview.question.model.Question;
import com.demo.interview.util.LlmQuestionCleaner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 面试题文本的批量清洗入库器（常规题库）。
 * 清洗逻辑复用 {@link LlmQuestionCleaner}；去重下沉到 DB（stem_key 唯一索引 + INSERT OR IGNORE）。
 */
@Slf4j
@Service
public class InterviewImporter {

    private final LlmQuestionCleaner cleaner;
    private final QuestionRepository questionRepository;

    public InterviewImporter(LlmQuestionCleaner cleaner, QuestionRepository questionRepository) {
        this.cleaner = cleaner;
        this.questionRepository = questionRepository;
    }

    public ImportResult run(String filePath, String source) throws Exception {
        String raw = Files.readString(Path.of(filePath), StandardCharsets.UTF_8);
        return run(raw, source, Integer.MAX_VALUE);
    }

    public ImportResult runFile(String filePath, String source, int maxQuestions) throws Exception {
        String raw = Files.readString(Path.of(filePath), StandardCharsets.UTF_8);
        return run(raw, source, maxQuestions);
    }

    public ImportResult run(String raw, String source, int maxQuestions) {
        String skillsCand = String.join("、", SkillGraph.allSkills());
        List<LlmQuestionCleaner.Spec> parsed = cleaner.clean(raw, chunk -> buildPrompt(chunk, skillsCand));
        log.info("清洗共产出 {} 道题（待去重）", parsed.size());

        Set<String> uncovered = new LinkedHashSet<>();
        int added = 0, dup = 0;
        for (LlmQuestionCleaner.Spec spec : parsed) {
            if (added + dup >= maxQuestions) {
                break;
            }
            String stem = spec.stem() == null ? "" : spec.stem().trim();
            if (stem.isEmpty()) {
                continue;
            }
            // 技能标签对齐到图谱节点；图谱没命中则保留模型给的技能名
            String skill = SkillGraph.match(spec.skill());
            if (skill == null) {
                skill = spec.skill() == null ? "未分类" : spec.skill();
                uncovered.add(skill);
            }
            Question q = new Question(null, skill, Question.TYPE_SHORT_ANSWER,
                    LlmQuestionCleaner.normalizeDifficulty(spec.difficulty()), stem, spec.answer(),
                    cleaner.pointsJson(spec.points()), spec.explanation(), source);
            Long id = questionRepository.insertWithId(q);
            if (id == null) {
                dup++;
            } else {
                added++;
            }
        }
        ImportResult result = new ImportResult(parsed.size(), added, dup, source, new ArrayList<>(uncovered));
        log.info("导入完成：解析 {}，新增 {}，重复 {}，未对齐图谱 {}", parsed.size(), added, dup, uncovered);
        return result;
    }

    private String buildPrompt(String chunk, String skillsCand) {
        return """
                你是一名资深技术题库编辑。下面是一段技术面试题资料（题目：指向答案标题，后跟长文答案，可能偶夹无关段落，无编号）。
                请抽取其中所有【题目】，逐题规范化为题库结构。无关段落忽略。

                对每题输出：
                - stem：题干（保留原文，去掉多余编号/换行，40 字内）
                - answer：把长答案压缩成 80~200 字的要点式参考答案（分条、保留关键技术名词与结论）
                - points：3~5 个评分要点，每个 {"point": "要点内容", "score": 分值}，分值加起来必须等于 100
                - explanation：一句话解析本题核心考点（30 字内）
                - skill：归属技能标签（从候选里选最贴切的一个；都不合适可写最接近的技术名）
                - difficulty：只能 BASIC / INTERMEDIATE / ADVANCED 之一

                候选技能标签：%s

                只输出一个 JSON 数组，不要输出任何其他文字：
                [{"stem":"...","answer":"...","points":[{"point":"...","score":20}],"explanation":"...","skill":"...","difficulty":"INTERMEDIATE"}]

                资料如下：
                -----------------
                %s
                """.formatted(skillsCand, chunk);
    }

    public record ImportResult(int totalParsed, int added, int duplicated, String source, List<String> uncoveredSkills) {
    }
}