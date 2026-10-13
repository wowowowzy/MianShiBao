package com.demo.interview.submission;

import com.demo.interview.admin.UsageService;
import com.demo.interview.auth.CurrentUser;
import com.demo.interview.record.RecordRepository;
import com.demo.interview.submission.model.GradeRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 交卷评分接口（异步）。
 * POST /api/submissions/grade  提交整卷作答 → 立即落 PROCESSING 记录并返回 202 {submissionId, status}，
 *                              判分在线程池执行；前端轮询 /api/me/submissions/{id} 取报告。
 */
@Slf4j
@RestController
@RequestMapping("/api/submissions")
public class GradeController {

    private final GradeAsyncService gradeAsyncService;
    private final RecordRepository recordRepository;
    private final UsageService usageService;
    private final ObjectMapper json;

    public GradeController(GradeAsyncService gradeAsyncService, RecordRepository recordRepository,
                           UsageService usageService, ObjectMapper json) {
        this.gradeAsyncService = gradeAsyncService;
        this.recordRepository = recordRepository;
        this.usageService = usageService;
        this.json = json;
    }

    @PostMapping("/grade")
    public ResponseEntity<?> grade(@RequestBody GradeRequest request, HttpServletRequest servletRequest) {
        Long userId = CurrentUser.id(servletRequest);
        if (userId == null) {
            return ResponseEntity.status(401).build();
        }
        UsageService.Result usage = usageService.consume(userId, CurrentUser.isAdmin(servletRequest));
        if (usage != UsageService.Result.OK) {
            String msg = usage == UsageService.Result.BANNED
                    ? "账号已被封禁，请联系管理员"
                    : "今日使用次数已用完（普通用户每天 5 次，次日 0 点重置）";
            return ResponseEntity.status(usage == UsageService.Result.BANNED ? 403 : 429)
                    .body(Map.of("message", msg));
        }
        Long submissionId;
        try {
            submissionId = recordRepository.insertProcessingSubmission(userId, json.writeValueAsString(request.answers()));
        } catch (Exception e) {
            log.error("作答记录落库失败 userId={}", userId, e);
            return ResponseEntity.status(500).body(Map.of("message", "提交失败，请重试"));
        }
        try {
            gradeAsyncService.process(submissionId, request);
        } catch (TaskRejectedException e) {
            log.warn("判分任务被拒绝（线程池繁忙） submissionId={}", submissionId);
            recordRepository.markSubmissionFailed(submissionId, "系统繁忙，请稍后重试");
            return ResponseEntity.status(503).body(Map.of("message", "系统繁忙，请稍后重试"));
        }
        return ResponseEntity.accepted().body(Map.of("submissionId", submissionId, "status", "PROCESSING"));
    }
}