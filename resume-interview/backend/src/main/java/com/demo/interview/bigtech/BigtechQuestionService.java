package com.demo.interview.bigtech;

import com.demo.interview.question.QuestionService;
import com.demo.interview.rag.SemanticRanker;
import com.demo.interview.resume.ResumeProfile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 大厂挑战出题：只用大厂题库，向量 kNN 粗召回 10 → rerank 精排取 4，每题固定 25 分。
 * 语义/精排失败时随机兜底，保证始终能出题。
 */
@Slf4j
@Service
public class BigtechQuestionService {

    private static final int RETRIEVE_TOPK = 10;
    private static final int PICK = 4;

    private final BigtechQuestionRepository repository;
    private final BigtechVecService vecService;
    private final SemanticRanker semanticRanker;

    public BigtechQuestionService(BigtechQuestionRepository repository, BigtechVecService vecService,
                                  SemanticRanker semanticRanker) {
        this.repository = repository;
        this.vecService = vecService;
        this.semanticRanker = semanticRanker;
    }

    public List<QuestionService.QuestionView> generate(ResumeProfile profile) {
        List<String> skills = profile.skills() == null ? List.of()
                : profile.skills().stream().map(ResumeProfile.SkillItem::name).toList();
        String query = String.join("、", skills);

        List<BigtechQuestion> picked = new ArrayList<>();
        if (!skills.isEmpty()) {
            picked.addAll(semanticPick(query));
        }
        // 兜底：不足 4 题时随机补齐
        if (picked.size() < PICK) {
            Set<Long> have = new HashSet<>();
            picked.forEach(q -> have.add(q.id()));
            for (BigtechQuestion q : repository.findRandom(PICK * 3)) {
                if (picked.size() >= PICK) {
                    break;
                }
                if (have.add(q.id())) {
                    picked.add(q);
                }
            }
        }

        List<QuestionService.QuestionView> views = new ArrayList<>();
        for (BigtechQuestion q : picked) {
            views.add(new QuestionService.QuestionView(q.id(), q.type(), q.difficulty(), q.skill(), q.stem(),
                    BigtechQuestion.STORE, q.company(), BigtechQuestion.FULL_SCORE));
        }
        log.info("大厂挑战出题：{} 题（每题 {} 分）", views.size(), BigtechQuestion.FULL_SCORE);
        return views;
    }

    /** 语义粗召回 + rerank 精排，返回按相关度排序的题目（失败返回空） */
    private List<BigtechQuestion> semanticPick(String query) {
        try {
            double[] qvec = semanticRanker.embed(query);
            List<Long> ids = vecService.knn(qvec, RETRIEVE_TOPK);
            if (ids.isEmpty()) {
                return List.of();
            }
            List<BigtechQuestion> qs = repository.findByIds(ids);
            Map<Long, BigtechQuestion> byId = new HashMap<>();
            qs.forEach(q -> byId.put(q.id(), q));
            List<BigtechQuestion> ordered = new ArrayList<>();
            for (Long id : ids) {
                BigtechQuestion q = byId.get(id);
                if (q != null) {
                    ordered.add(q);
                }
            }
            if (ordered.isEmpty()) {
                return List.of();
            }
            List<String> stems = ordered.stream()
                    .map(q -> (q.skill() == null ? "" : q.skill() + "-") + q.stem())
                    .toList();
            List<BigtechQuestion> out = new ArrayList<>();
            for (int idx : semanticRanker.rerank(query, stems, PICK)) {
                if (idx >= 0 && idx < ordered.size()) {
                    out.add(ordered.get(idx));
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("大厂题目 语义召回/精排失败，走随机兜底：{}", e.getMessage());
            return List.of();
        }
    }
}