package com.demo.interview.submission;

import com.demo.interview.record.RecordRepository;
import com.demo.interview.submission.model.GradeRequest;
import com.demo.interview.submission.model.GradeResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 判分异步执行：接口先落 PROCESSING，返回 submissionId；本服务在线程池里跑 LLM 判分，
 * 完成回填报告并置 DONE，异常置 FAILED。前端轮询 /api/me/submissions/{id} 取报告。
 */
@Slf4j
@Service
public class GradeAsyncService {

    private final GradeService gradeService;
    private final RecordRepository recordRepository;
    private final ObjectMapper json;

    public GradeAsyncService(GradeService gradeService, RecordRepository recordRepository, ObjectMapper json) {
        this.gradeService = gradeService;
        this.recordRepository = recordRepository;
        this.json = json;
    }

    @Async("llmExecutor")
    public void process(Long submissionId, GradeRequest request) {
        try {
            GradeResult result = gradeService.grade(request);
            recordRepository.markSubmissionDone(submissionId, json.writeValueAsString(result),
                    result.totalScore(), result.totalFullScore());
            log.info("异步判分完成 submissionId={} 总分 {}/{}", submissionId, result.totalScore(), result.totalFullScore());
        } catch (Exception e) {
            log.error("异步判分失败 submissionId={}", submissionId, e);
            recordRepository.markSubmissionFailed(submissionId, "判分失败: " + e.getMessage());
        }
    }
}