package com.demo.interview.me;

import com.demo.interview.auth.CurrentUser;
import com.demo.interview.auth.UserRepository;
import com.demo.interview.record.RecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 个人中心只读接口（userId 由鉴权拦截器写入，全部按 user_id 过滤）。
 * <pre>
 * GET  /api/me                              → {username}
 * GET  /api/me/resumes                      → {count, items}
 * GET  /api/me/papers                       → {count, items}
 * GET  /api/me/submissions                  → {count, items}
 * GET  /api/me/submissions/{id}             → {id, status, report}
 * POST /api/me/resumes/{id}/learning-plan   触发（异步）生成该简历的学习路线 → 202 {status}
 * GET  /api/me/resumes/{id}/learning-plan   → {status, plan, errorMsg}
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/me")
public class MeController {

    private final MeQueryRepository meQueryRepository;
    private final LearningPlanRepository learningPlanRepository;
    private final LearningPlanAsyncService learningPlanAsyncService;
    private final RecordRepository recordRepository;
    private final UserRepository userRepository;
    private final ObjectMapper json;

    public MeController(MeQueryRepository meQueryRepository, LearningPlanRepository learningPlanRepository,
                        LearningPlanAsyncService learningPlanAsyncService, RecordRepository recordRepository,
                        UserRepository userRepository, ObjectMapper json) {
        this.meQueryRepository = meQueryRepository;
        this.learningPlanRepository = learningPlanRepository;
        this.learningPlanAsyncService = learningPlanAsyncService;
        this.recordRepository = recordRepository;
        this.userRepository = userRepository;
        this.json = json;
    }

    @GetMapping
    public ResponseEntity<?> me(HttpServletRequest request) {
        Long userId = CurrentUser.id(request);
        if (userId == null) {
            return error(HttpStatus.UNAUTHORIZED, "未登录");
        }
        String username = meQueryRepository.findUsernameById(userId).orElse(null);
        if (username == null) {
            return error(HttpStatus.NOT_FOUND, "用户不存在");
        }
        String nickname = meQueryRepository.findNicknameById(userId).orElse(null);
        return ResponseEntity.ok(new UsernameResponse(username, nickname));
    }

    /** 设置昵称（微信昵称等展示名，1~20 字）；空串表示清除昵称 */
    @PostMapping("/nickname")
    public ResponseEntity<?> setNickname(@RequestBody NicknameRequest body, HttpServletRequest request) {
        Long userId = CurrentUser.id(request);
        if (userId == null) {
            return error(HttpStatus.UNAUTHORIZED, "未登录");
        }
        String nickname = body == null ? null : body.nickname();
        nickname = nickname == null ? "" : nickname.trim();
        if (nickname.length() > 20) {
            return error(HttpStatus.BAD_REQUEST, "昵称最多 20 个字符");
        }
        userRepository.updateNickname(userId, nickname.isEmpty() ? null : nickname);
        return ResponseEntity.ok(Map.of("nickname", nickname));
    }

    @GetMapping("/resumes")
    public ResponseEntity<?> resumes(HttpServletRequest request) {
        Long userId = CurrentUser.id(request);
        if (userId == null) {
            return error(HttpStatus.UNAUTHORIZED, "未登录");
        }
        List<MeQueryRepository.ResumeItem> items = meQueryRepository.findResumesByUserId(userId);
        return ResponseEntity.ok(new ResumeListResponse(items.size(), items));
    }

    /** 删除一条简历解析记录（含它派生的学习路线）；仅能删自己名下的记录，防越权 */
    @DeleteMapping("/resumes/{id}")
    public ResponseEntity<?> deleteResume(@PathVariable long id, HttpServletRequest request) {
        Long userId = CurrentUser.id(request);
        if (userId == null) {
            return error(HttpStatus.UNAUTHORIZED, "未登录");
        }
        boolean deleted = recordRepository.deleteResumeByUser(userId, id);
        if (!deleted) {
            return error(HttpStatus.NOT_FOUND, "解析记录不存在或已删除");
        }
        log.info("用户 {} 删除简历解析记录 resumeId={}", userId, id);
        return ResponseEntity.ok(new DeleteResumeResponse(id, "解析记录已删除"));
    }

    @GetMapping("/papers")
    public ResponseEntity<?> papers(HttpServletRequest request) {
        Long userId = CurrentUser.id(request);
        if (userId == null) {
            return error(HttpStatus.UNAUTHORIZED, "未登录");
        }
        List<MeQueryRepository.PaperItem> items = meQueryRepository.findPapersByUserId(userId);
        return ResponseEntity.ok(new PaperListResponse(items.size(), items));
    }

    @GetMapping("/submissions")
    public ResponseEntity<?> submissions(HttpServletRequest request) {
        Long userId = CurrentUser.id(request);
        if (userId == null) {
            return error(HttpStatus.UNAUTHORIZED, "未登录");
        }
        List<MeQueryRepository.SubmissionItem> items = meQueryRepository.findSubmissionsByUserId(userId);
        return ResponseEntity.ok(new SubmissionListResponse(items.size(), items));
    }

    @GetMapping("/submissions/{id}")
    public ResponseEntity<?> submission(@PathVariable long id, HttpServletRequest request) {
        Long userId = CurrentUser.id(request);
        if (userId == null) {
            return error(HttpStatus.UNAUTHORIZED, "未登录");
        }
        Optional<MeQueryRepository.SubmissionDetail> detail = meQueryRepository.findSubmissionById(userId, id);
        if (detail.isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "作答记录不存在");
        }
        return ResponseEntity.ok(detail.get());
    }

    /** 触发（异步）生成该简历的学习路线；已完成则直接返回 */
    @PostMapping("/resumes/{id}/learning-plan")
    public ResponseEntity<?> generatePlan(@PathVariable long id, HttpServletRequest request) {
        Long userId = CurrentUser.id(request);
        if (userId == null) {
            return error(HttpStatus.UNAUTHORIZED, "未登录");
        }
        if (!meQueryRepository.existsResume(userId, id)) {
            return error(HttpStatus.NOT_FOUND, "简历记录不存在");
        }
        LearningPlanRepository.Row row = learningPlanRepository.find(id);
        if (row != null && "DONE".equals(row.status()) && row.planJson() != null) {
            return ResponseEntity.ok(planResponse(row));
        }
        learningPlanRepository.upsertProcessing(id, userId);
        try {
            learningPlanAsyncService.process(userId, id);
        } catch (org.springframework.core.task.TaskRejectedException e) {
            learningPlanRepository.markFailed(id, "系统繁忙，请稍后重试");
            return ResponseEntity.status(503).body(Map.of("message", "系统繁忙，请稍后重试"));
        }
        return ResponseEntity.accepted().body(Map.of("status", "PROCESSING"));
    }

    /** 查询学习路线状态与内容（前端轮询） */
    @GetMapping("/resumes/{id}/learning-plan")
    public ResponseEntity<?> getPlan(@PathVariable long id, HttpServletRequest request) {
        Long userId = CurrentUser.id(request);
        if (userId == null) {
            return error(HttpStatus.UNAUTHORIZED, "未登录");
        }
        if (!meQueryRepository.existsResume(userId, id)) {
            return error(HttpStatus.NOT_FOUND, "简历记录不存在");
        }
        LearningPlanRepository.Row row = learningPlanRepository.find(id);
        if (row == null) {
            return ResponseEntity.ok(Map.of("status", "NONE"));
        }
        return ResponseEntity.ok(planResponse(row));
    }

    private Map<String, Object> planResponse(LearningPlanRepository.Row row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", row.status());
        m.put("plan", row.planJson() == null ? null : com.demo.interview.util.Jsons.parseObject(json, row.planJson()));
        m.put("errorMsg", row.errorMsg());
        return m;
    }

    private static ResponseEntity<ErrorMsg> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new ErrorMsg(message));
    }

    // ---------- 响应结构 ----------

    record UsernameResponse(String username, String nickname) {
    }

    /** 昵称请求体 */
    record NicknameRequest(String nickname) {
    }

    record DeleteResumeResponse(Long id, String message) {
    }

    record ResumeListResponse(int count, List<MeQueryRepository.ResumeItem> items) {
    }

    record PaperListResponse(int count, List<MeQueryRepository.PaperItem> items) {
    }

    record SubmissionListResponse(int count, List<MeQueryRepository.SubmissionItem> items) {
    }

    record ErrorMsg(String message) {
    }
}