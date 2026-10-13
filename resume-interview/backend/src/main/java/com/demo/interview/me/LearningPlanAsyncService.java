package com.demo.interview.me;

import com.demo.interview.resume.ResumeProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 学习路线异步生成：接口先把该简历的路线置为 PROCESSING，本服务在线程池里跑一次 LLM，
 * 完成置 DONE 并回填 plan_json，失败置 FAILED。前端轮询。
 */
@Slf4j
@Service
public class LearningPlanAsyncService {

    private final MeQueryRepository meQueryRepository;
    private final LearningPlanService learningPlanService;
    private final LearningPlanRepository learningPlanRepository;
    private final ObjectMapper json;

    public LearningPlanAsyncService(MeQueryRepository meQueryRepository, LearningPlanService learningPlanService,
                                    LearningPlanRepository learningPlanRepository, ObjectMapper json) {
        this.meQueryRepository = meQueryRepository;
        this.learningPlanService = learningPlanService;
        this.learningPlanRepository = learningPlanRepository;
        this.json = json;
    }

    @Async("llmExecutor")
    public void process(Long userId, Long resumeId) {
        try {
            String profileJson = meQueryRepository.findResumeProfileJson(userId, resumeId).orElse(null);
            if (profileJson == null) {
                learningPlanRepository.markFailed(resumeId, "简历记录不存在或缺少画像");
                return;
            }
            ResumeProfile profile = json.readValue(profileJson, ResumeProfile.class);
            LearningPlan plan = learningPlanService.generate(userId, profile);
            learningPlanRepository.markDone(resumeId, json.writeValueAsString(plan));
            log.info("学习路线生成完成 resumeId={} phases={}", resumeId, plan.phases() == null ? 0 : plan.phases().size());
        } catch (Exception e) {
            log.error("学习路线生成失败 resumeId={}", resumeId, e);
            learningPlanRepository.markFailed(resumeId, "生成失败: " + e.getMessage());
        }
    }
}