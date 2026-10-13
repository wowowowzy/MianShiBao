package com.demo.interview.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 常规题库向量库：question_vec（vec0，存每题 stem 的 embedding，带 source 域）。
 * 懒触发、后台全量索引（幂等）；连接与批处理复用 {@link SqliteVecIndex}。
 */
@Slf4j
@Service
public class QuestionVecService {

    private static final String TABLE = "question_vec";

    private final JdbcTemplate jdbc;
    private final SqliteVecIndex index;

    private volatile boolean indexStarted = false;
    private volatile boolean ready = false;
    private Connection conn;

    public QuestionVecService(JdbcTemplate jdbc, SqliteVecIndex index) {
        this.jdbc = jdbc;
        this.index = index;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmupOnStartup() {
        ensureIndexed();
    }

    /** 首次调用触发后台全量索引（立即返回；索引完成前 knn 返回空、调用方走标签通道） */
    public synchronized void ensureIndexed() {
        if (indexStarted) {
            return;
        }
        indexStarted = true;
        Thread t = new Thread(this::indexAll, "question-vec-index");
        t.setDaemon(true);
        t.start();
    }

    /** 简历 query 向量 → 岗位域内题目 kNN（域过滤下沉进 SQL） */
    public List<Scored> knn(double[] queryVector, int topK, List<String> sources) {
        ensureIndexed();
        if (!ready || queryVector == null || sources == null || sources.isEmpty()) {
            return List.of();
        }
        return index.knn(conn, TABLE, queryVector, topK, sources).stream().map(Scored::new).toList();
    }

    private void indexAll() {
        try {
            conn = index.open(TABLE, true);
            Set<Long> indexed = index.indexedRowIds(conn, TABLE);
            List<SqliteVecIndex.Pending> pending = new ArrayList<>();
            jdbc.query("SELECT id, skill, stem, source FROM question WHERE status='ACTIVE' ORDER BY id", rs -> {
                long id = rs.getLong("id");
                if (!indexed.contains(id)) {
                    String skill = rs.getString("skill") == null ? "" : rs.getString("skill");
                    String stem = rs.getString("stem");
                    String text = skill + " " + (stem == null ? "" : SqliteVecIndex.truncate(stem, SqliteVecIndex.STEM_TRUNC));
                    pending.add(new SqliteVecIndex.Pending(id, text, rs.getString("source")));
                }
            });
            int added = index.insertAll(conn, TABLE, true, pending);
            ready = true;
            log.info("题目向量库索引完成：本轮新增 {} 题", added);
        } catch (Exception e) {
            log.warn("题目向量库索引失败（降级标签通道）: {}", e.getMessage());
        }
    }

    /** kNN 候选：questionId */
    public record Scored(Long questionId) {
    }
}