package com.demo.interview.bigtech;

import com.demo.interview.rag.SqliteVecIndex;
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
 * 大厂题库向量库：bigtech_question_vec（vec0，存每题 stem 的 embedding），与常规题库向量表隔离。
 * 懒触发、后台全量索引（幂等）；连接与批处理复用 {@link SqliteVecIndex}。
 */
@Slf4j
@Service
public class BigtechVecService {

    private static final String TABLE = "bigtech_question_vec";

    private final JdbcTemplate jdbc;
    private final SqliteVecIndex index;

    private volatile boolean indexStarted = false;
    private volatile boolean ready = false;
    private Connection conn;

    public BigtechVecService(JdbcTemplate jdbc, SqliteVecIndex index) {
        this.jdbc = jdbc;
        this.index = index;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmupOnStartup() {
        ensureIndexed();
    }

    public synchronized void ensureIndexed() {
        if (indexStarted) {
            return;
        }
        indexStarted = true;
        Thread t = new Thread(this::indexAll, "bigtech-vec-index");
        t.setDaemon(true);
        t.start();
    }

    /** 简历 query 向量 → 大厂库 kNN，返回按相似度降序的题目 id */
    public List<Long> knn(double[] queryVector, int topK) {
        ensureIndexed();
        if (!ready || queryVector == null) {
            return List.of();
        }
        return index.knn(conn, TABLE, queryVector, topK, null);
    }

    private void indexAll() {
        try {
            conn = index.open(TABLE, false);
            Set<Long> indexed = index.indexedRowIds(conn, TABLE);
            List<SqliteVecIndex.Pending> pending = new ArrayList<>();
            jdbc.query("SELECT id, skill, stem FROM bigtech_question ORDER BY id", rs -> {
                long id = rs.getLong("id");
                if (!indexed.contains(id)) {
                    String skill = rs.getString("skill") == null ? "" : rs.getString("skill");
                    String stem = rs.getString("stem");
                    String text = skill + " " + (stem == null ? "" : SqliteVecIndex.truncate(stem, SqliteVecIndex.STEM_TRUNC));
                    pending.add(new SqliteVecIndex.Pending(id, text, null));
                }
            });
            int added = index.insertAll(conn, TABLE, false, pending);
            ready = true;
            log.info("大厂向量库索引完成：本轮新增 {} 题", added);
        } catch (Exception e) {
            log.warn("大厂向量库索引失败（降级随机兜底）: {}", e.getMessage());
        }
    }
}