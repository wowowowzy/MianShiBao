package com.demo.interview.me;

import com.demo.interview.resume.ResumeProfile;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 学习路线接口。
 * POST /api/me/learning-plan  传入能力画像（可附近期答题弱项，服务端自动拉取）→ 分阶段学习方案
 */
@RestController
@RequestMapping("/api/me")
public class LearningPlanController {

    private final LearningPlanService learningPlanService;

    public LearningPlanController(LearningPlanService learningPlanService) {
        this.learningPlanService = learningPlanService;
    }

    @PostMapping("/learning-plan")
    public ResponseEntity<?> learningPlan(@RequestBody(required = false) PlanRequestBody body,
                                          HttpServletRequest request) {
        Object uid = request.getAttribute("userId");
        Long userId = uid instanceof Number n ? n.longValue() : null;
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("message", "未登录"));
        }
        if (body == null || body.profile() == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "profile 不能为空"));
        }
        try {
            return ResponseEntity.ok(learningPlanService.generate(userId, body.profile()));
        } catch (Exception e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("message", "学习路线生成失败: " + e.getMessage()));
        }
    }

    record PlanRequestBody(ResumeProfile profile) {
    }
}