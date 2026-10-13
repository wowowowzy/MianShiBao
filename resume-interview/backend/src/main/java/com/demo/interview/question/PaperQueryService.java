package com.demo.interview.question;

import com.demo.interview.bigtech.BigtechQuestion;
import com.demo.interview.bigtech.BigtechQuestionRepository;
import com.demo.interview.question.model.Question;
import com.demo.interview.record.RecordRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 试卷查询：按 paperId 返回状态与题目视图（答题用，不含答案）。
 * 从 paper_question 逐题按 store 回源到 question / project_question / bigtech_question。
 */
@Slf4j
@Service
public class PaperQueryService {

    private final RecordRepository recordRepository;
    private final QuestionRepository questionRepository;
    private final ProjectQuestionRepository projectQuestionRepository;
    private final BigtechQuestionRepository bigtechQuestionRepository;

    public PaperQueryService(RecordRepository recordRepository, QuestionRepository questionRepository,
                             ProjectQuestionRepository projectQuestionRepository,
                             BigtechQuestionRepository bigtechQuestionRepository) {
        this.recordRepository = recordRepository;
        this.questionRepository = questionRepository;
        this.projectQuestionRepository = projectQuestionRepository;
        this.bigtechQuestionRepository = bigtechQuestionRepository;
    }

    /** 试卷不存在返回 null */
    public PaperView get(Long paperId) {
        String status = recordRepository.findPaperStatus(paperId);
        if (status == null) {
            return null;
        }
        List<QuestionService.QuestionView> views = new ArrayList<>();
        if ("DONE".equals(status)) {
            for (RecordRepository.PaperQuestionRow row : recordRepository.listPaperQuestions(paperId)) {
                QuestionService.QuestionView v = loadView(row.store(), row.questionId());
                if (v != null) {
                    views.add(v);
                }
            }
        }
        return new PaperView(status, views, recordRepository.findPaperError(paperId));
    }

    private QuestionService.QuestionView loadView(String store, Long id) {
        try {
            if (BigtechQuestion.STORE.equals(store)) {
                BigtechQuestion b = bigtechQuestionRepository.findById(id);
                return new QuestionService.QuestionView(b.id(), b.type(), b.difficulty(), b.skill(), b.stem(),
                        BigtechQuestion.STORE, b.company(), BigtechQuestion.FULL_SCORE);
            }
            if (ProjectQuestionRepository.STORE.equals(store)) {
                Question q = projectQuestionRepository.findById(id);
                return new QuestionService.QuestionView(q.id(), q.type(), q.difficulty(), q.skill(), q.stem(),
                        ProjectQuestionRepository.STORE, null, 20);
            }
            return QuestionService.QuestionView.of(questionRepository.findById(id));
        } catch (Exception e) {
            log.warn("试卷回源题目失败 store={} id={}: {}", store, id, e.getMessage());
            return null;
        }
    }

    public record PaperView(String status, List<QuestionService.QuestionView> questions, String errorMsg) {
    }
}