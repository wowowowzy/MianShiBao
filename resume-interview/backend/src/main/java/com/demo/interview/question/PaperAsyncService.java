package com.demo.interview.question;

import com.demo.interview.bigtech.BigtechQuestionService;
import com.demo.interview.record.RecordRepository;
import com.demo.interview.resume.ResumeProfile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 组卷异步执行：接口先落 PROCESSING 试卷并返回 paperId；本服务在线程池里跑「语义召回 + rerank + LLM 补题/项目题」，
 * 完成后写入 paper_question 并置 DONE，异常置 FAILED。前端轮询 /api/questions/paper/{id} 取题。
 */
@Slf4j
@Service
public class PaperAsyncService {

    private final QuestionService questionService;
    private final BigtechQuestionService bigtechQuestionService;
    private final RecordRepository recordRepository;

    public PaperAsyncService(QuestionService questionService, BigtechQuestionService bigtechQuestionService,
                             RecordRepository recordRepository) {
        this.questionService = questionService;
        this.bigtechQuestionService = bigtechQuestionService;
        this.recordRepository = recordRepository;
    }

    @Async("llmExecutor")
    public void process(Long paperId, ResumeProfile profile, int maxQuestions, String role, boolean bigtech,
                        String majorDomain) {
        try {
            List<QuestionService.QuestionView> views = bigtech
                    ? bigtechQuestionService.generate(profile)
                    : questionService.generate(profile, maxQuestions, role, majorDomain).questions();
            int queueNo = 1;
            for (QuestionService.QuestionView v : views) {
                recordRepository.insertPaperQuestion(paperId, v.id(), queueNo++, v.store());
            }
            recordRepository.markPaperDone(paperId);
            log.info("异步组卷完成 paperId={} 题数={} bigtech={}", paperId, views.size(), bigtech);
        } catch (Exception e) {
            log.error("异步组卷失败 paperId={}", paperId, e);
            recordRepository.markPaperFailed(paperId, "组卷失败: " + e.getMessage());
        }
    }
}