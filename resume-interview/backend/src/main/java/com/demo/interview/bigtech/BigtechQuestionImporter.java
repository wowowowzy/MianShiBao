package com.demo.interview.bigtech;

import com.demo.interview.util.LlmQuestionCleaner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 大厂面经文本 → 清洗成标准题目 → 落库 bigtech_question（带公司标签）。
 * 清洗逻辑复用 {@link LlmQuestionCleaner}；去重下沉到 DB。
 */
@Slf4j
@Service
public class BigtechQuestionImporter {

    private final LlmQuestionCleaner cleaner;
    private final BigtechQuestionRepository repository;

    public BigtechQuestionImporter(LlmQuestionCleaner cleaner, BigtechQuestionRepository repository) {
        this.cleaner = cleaner;
        this.repository = repository;
    }

    /** 导入某公司面经文件 */
    public ImportResult runFile(String filePath, String company, int maxQuestions) throws Exception {
        String raw = Files.readString(Path.of(filePath), StandardCharsets.UTF_8);
        List<LlmQuestionCleaner.Spec> parsed = cleaner.clean(raw, chunk -> buildPrompt(chunk, company));

        int added = 0, dup = 0;
        for (LlmQuestionCleaner.Spec spec : parsed) {
            if (maxQuestions > 0 && added + dup >= maxQuestions) {
                break;
            }
            String stem = spec.stem() == null ? "" : spec.stem().trim();
            if (stem.isEmpty()) {
                continue;
            }
            BigtechQuestion q = new BigtechQuestion(null, company, spec.skill(), "SHORT_ANSWER",
                    LlmQuestionCleaner.normalizeDifficulty(spec.difficulty()), stem, spec.answer(),
                    cleaner.pointsJson(spec.points()), spec.explanation());
            Long id = repository.insertWithId(q);
            if (id == null) {
                dup++;
            } else {
                added++;
            }
        }
        log.info("大厂导入完成：公司={}，解析 {}，新增 {}，重复 {}", company, parsed.size(), added, dup);
        return new ImportResult(company, parsed.size(), added, dup);
    }

    private String buildPrompt(String chunk, String company) {
        return """
                你是一名资深技术题库编辑。下面是【%s】AI Agent / AI 应用开发岗位的面试题与面经资料。
                请抽取其中所有【面试题】，逐题规范化为题库结构；与面试题无关的内容（流程介绍、薪资、闲聊）忽略；若某段只是“面经描述”没有明确题目，可概括成一道有价值的面试题。

                对每题输出：
                - stem：题干（保留原意，去掉多余编号/换行/公司作业，40 字内）
                - answer：把答案压缩成 80~200 字的要点式参考答案（分条、保留关键技术名词与结论）
                - points：3~5 个评分要点，每个 {"point":"要点内容","score":分值}，分值加起来必须等于 100
                - explanation：一句话解析本题核心考点（30 字内）
                - skill：知识点标签（如 Agent、RAG、LangChain、Prompt、Function Calling、多Agent、向量数据库、Transformer、Python 等，选最贴切的一个）
                - difficulty：只能 BASIC / INTERMEDIATE / ADVANCED 之一

                只输出一个 JSON 数组，不要输出任何其他文字：
                [{"stem":"...","answer":"...","points":[{"point":"...","score":20}],"explanation":"...","skill":"...","difficulty":"INTERMEDIATE"}]

                资料如下：
                -----------------
                %s
                """.formatted(company, chunk);
    }

    public record ImportResult(String company, int totalParsed, int added, int duplicated) {
    }
}