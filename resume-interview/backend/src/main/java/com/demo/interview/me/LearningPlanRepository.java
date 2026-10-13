package com.demo.interview.me;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 学习路线仓储：一份解析简历对应一条学习路线（resume_id 主键）。
 * 带 status（PROCESSING/DONE/FAILED）配合异步生成与前端轮询。
 */
@Slf4j
@Repository
public class LearningPlanRepository {

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS learning_plan (
              resume_id  INTEGER PRIMARY KEY,
              user_id    INTEGER NOT NULL,
              status     TEXT,
              plan_json  TEXT,
              error_msg  TEXT,
              created_at TEXT DEFAULT (datetime('now','localtime'))
            )
            """;

    private final JdbcTemplate jdbc;

    public LearningPlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void initSchema() {
        jdbc.execute(CREATE_TABLE);
        log.info("学习路线表初始化完成");
    }

    /** 不存在返回 null */
    public Row find(long resumeId) {
        List<Row> rows = jdbc.query("SELECT resume_id, user_id, status, plan_json, error_msg FROM learning_plan WHERE resume_id=?",
                (rs, rowNum) -> new Row(rs.getLong("resume_id"), rs.getLong("user_id"),
                        rs.getString("status"), rs.getString("plan_json"), rs.getString("error_msg")),
                resumeId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 标记为生成中（存在则重置） */
    public void upsertProcessing(long resumeId, long userId) {
        jdbc.update("""
                INSERT INTO learning_plan(resume_id, user_id, status, plan_json, error_msg)
                VALUES(?,?, 'PROCESSING', NULL, NULL)
                ON CONFLICT(resume_id) DO UPDATE SET status='PROCESSING', plan_json=NULL, error_msg=NULL
                """, resumeId, userId);
    }

    public void markDone(long resumeId, String planJson) {
        jdbc.update("UPDATE learning_plan SET status='DONE', plan_json=?, error_msg=NULL WHERE resume_id=?", planJson, resumeId);
    }

    public void markFailed(long resumeId, String errorMsg) {
        jdbc.update("UPDATE learning_plan SET status='FAILED', error_msg=? WHERE resume_id=?", errorMsg, resumeId);
    }

    public record Row(long resumeId, long userId, String status, String planJson, String errorMsg) {
    }
}