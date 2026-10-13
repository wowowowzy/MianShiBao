package com.demo.interview.question;

import com.demo.interview.admin.UsageService;
import com.demo.interview.auth.CurrentUser;
import com.demo.interview.question.model.Question;
import com.demo.interview.record.RecordRepository;
import com.demo.interview.resume.ResumeProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 出题接口（异步）。
 * POST /api/questions/generate   按画像组卷 → 202 {paperId, status}
 * POST /api/questions/reuse      复用画像重新组卷 → 202 {paperId, status}
 * POST /api/questions/bigtech    大厂挑战组卷（4×25）→ 202 {paperId, status}
 * GET  /api/questions/paper/{id} 轮询试卷状态与题目（DONE 后含 questions）
 * GET  /api/questions            全量题库（调试）
 */
@Slf4j
@RestController
@RequestMapping("/api/questions")
public class QuestionController {

    private final QuestionService questionService;
    private final PaperAsyncService paperAsyncService;
    private final PaperQueryService paperQueryService;
    private final RecordRepository recordRepository;
    private final UsageService usageService;
    private final ObjectMapper json;

    public QuestionController(QuestionService questionService, PaperAsyncService paperAsyncService,
                              PaperQueryService paperQueryService, RecordRepository recordRepository,
                              UsageService usageService, ObjectMapper json) {
        this.questionService = questionService;
        this.paperAsyncService = paperAsyncService;
        this.paperQueryService = paperQueryService;
        this.recordRepository = recordRepository;
        this.usageService = usageService;
        this.json = json;
    }

    @PostMapping("/generate")
    public ResponseEntity<?> generate(@RequestBody ResumeProfile profile,
                                      @RequestParam(defaultValue = "5") int maxQuestions,
                                      @RequestParam(required = false) String role,
                                      @RequestParam(required = false) Long resumeId,
                                      @RequestParam(required = false) String majorDomain,
                                      HttpServletRequest request) {
        Long userId = CurrentUser.id(request);
        if (userId == null) {
            return ResponseEntity.status(401).build();
        }
        ResponseEntity<?> blocked = consumeOrBlock(userId, request);
        if (blocked != null) {
            return blocked;
        }
        Long paperId = recordRepository.insertProcessingPaper(userId, writeProfile(profile), resumeId);
        ResponseEntity<?> rejected = enqueue(paperId, profile, maxQuestions, role, false, majorDomain);
        if (rejected != null) {
            return rejected;
        }
        return ResponseEntity.accepted().body(Map.of("paperId", paperId, "status", "PROCESSING"));
    }

    @PostMapping("/bigtech")
    public ResponseEntity<?> bigtech(@RequestBody ResumeProfile profile,
                                     @RequestParam(required = false) Long resumeId,
                                     HttpServletRequest request) {
        Long userId = CurrentUser.id(request);
        if (userId == null) {
            return ResponseEntity.status(401).build();
        }
        ResponseEntity<?> blocked = consumeOrBlock(userId, request);
        if (blocked != null) {
            return blocked;
        }
        Long paperId = recordRepository.insertProcessingPaper(userId, writeProfile(profile), resumeId);
        ResponseEntity<?> rejected = enqueue(paperId, profile, 4, "AI 应用开发", true, null);
        if (rejected != null) {
            return rejected;
        }
        return ResponseEntity.accepted().body(Map.of("paperId", paperId, "status", "PROCESSING"));
    }

    @PostMapping("/reuse")
    public ResponseEntity<?> reuse(@RequestBody(required = false) ReuseRequest body, HttpServletRequest request) {
        Long userId = CurrentUser.id(request);
        if (userId == null) {
            return ResponseEntity.status(401).build();
        }
        if (body == null || body.paperId() == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "paperId 不能为空"));
        }
        ResponseEntity<?> blocked = consumeOrBlock(userId, request);
        if (blocked != null) {
            return blocked;
        }
        String profileJson = recordRepository.findPaperProfileJson(body.paperId());
        if (profileJson == null) {
            return ResponseEntity.status(404).body(Map.of("message", "试卷不存在: " + body.paperId()));
        }
        ResumeProfile profile;
        try {
            profile = json.readValue(profileJson, ResumeProfile.class);
        } catch (Exception e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("message", "试卷画像解析失败: " + e.getMessage()));
        }
        // 继承来源试卷的简历关联，使复核删除时依旧能精确级联
        Long srcResumeId = recordRepository.findPaperResumeId(body.paperId());
        Long paperId = recordRepository.insertProcessingPaper(userId, profileJson, srcResumeId);
        ResponseEntity<?> rejected = enqueue(paperId, profile, 5, null, false, null);
        if (rejected != null) {
            return rejected;
        }
        return ResponseEntity.accepted().body(Map.of("paperId", paperId, "status", "PROCESSING"));
    }

    /** 轮询试卷：DONE 后返回答题视图（不含答案） */
    @GetMapping("/paper/{id}")
    public ResponseEntity<?> paper(@PathVariable long id) {
        PaperQueryService.PaperView pv = paperQueryService.get(id);
        if (pv == null) {
            return ResponseEntity.status(404).body(Map.of("message", "试卷不存在"));
        }
        return ResponseEntity.ok(pv);
    }

    @GetMapping
    public List<Question> listAll() {
        return questionService.listAll();
    }

    /** 入队组卷；线程池拒绝（队列满）时把试卷置 FAILED 并返回 503 */
    private ResponseEntity<?> enqueue(Long paperId, ResumeProfile profile, int maxQuestions, String role, boolean bigtech, String majorDomain) {
        try {
            paperAsyncService.process(paperId, profile, maxQuestions, role, bigtech, majorDomain);
            return null;
        } catch (TaskRejectedException e) {
            log.warn("组卷任务被拒绝（线程池繁忙） paperId={}", paperId);
            recordRepository.markPaperFailed(paperId, "系统繁忙，请稍后重试");
            return ResponseEntity.status(503).body(Map.of("message", "系统繁忙，请稍后重试"));
        }
    }

    private String writeProfile(ResumeProfile profile) {
        try {
            return json.writeValueAsString(profile);
        } catch (Exception e) {
            return "{}";
        }
    }

    private ResponseEntity<?> consumeOrBlock(Long userId, HttpServletRequest request) {
        UsageService.Result usage = usageService.consume(userId, CurrentUser.isAdmin(request));
        if (usage == UsageService.Result.OK) {
            return null;
        }
        if (usage == UsageService.Result.BANNED) {
            return ResponseEntity.status(403).body(Map.of("message", "账号已被封禁，请联系管理员"));
        }
        return ResponseEntity.status(429).body(Map.of("message", "今日使用次数已用完（普通用户每天 5 次，次日 0 点重置）"));
    }

    record ReuseRequest(Long paperId) {
    }
}