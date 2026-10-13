package com.demo.interview.record;

import com.demo.interview.util.JdbcInsertSupport;
import com.demo.interview.util.SchemaSupport;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 行为/组卷/作答记录仓储（SQLite 持久化）。
 * resume_record：简历解析记录；paper：组卷记录；paper_question：试卷题目明细；submission：作答评分记录。
 * paper / submission 带 status（PROCESSING/DONE/FAILED），配合线程池异步执行与前端轮询。
 */
@Slf4j
@Repository
public class RecordRepository {

    private static final String CREATE_RESUME_RECORD = """
            CREATE TABLE IF NOT EXISTS resume_record (
              id           INTEGER PRIMARY KEY AUTOINCREMENT,
              user_id      INTEGER NOT NULL,
              source_type  TEXT,
              text_length  INTEGER,
              profile_json TEXT,
              created_at   TEXT DEFAULT (datetime('now','localtime'))
            )
            """;

    private static final String CREATE_PAPER = """
            CREATE TABLE IF NOT EXISTS paper (
              id           INTEGER PRIMARY KEY AUTOINCREMENT,
              user_id      INTEGER NOT NULL,
              resume_id    INTEGER,
              profile_json TEXT,
              created_at   TEXT DEFAULT (datetime('now','localtime'))
            )
            """;

    private static final String CREATE_PAPER_QUESTION = """
            CREATE TABLE IF NOT EXISTS paper_question (
              id          INTEGER PRIMARY KEY AUTOINCREMENT,
              paper_id    INTEGER NOT NULL,
              question_id INTEGER NOT NULL,
              queue_no    INTEGER,
              store       TEXT DEFAULT 'bank'
            )
            """;

    private static final String CREATE_SUBMISSION = """
            CREATE TABLE IF NOT EXISTS submission (
              id           INTEGER PRIMARY KEY AUTOINCREMENT,
              paper_id     INTEGER,
              user_id      INTEGER NOT NULL,
              answers_json TEXT,
              report_json  TEXT,
              total_score  INTEGER,
              full_score   INTEGER,
              created_at   TEXT DEFAULT (datetime('now','localtime'))
            )
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public RecordRepository(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @PostConstruct
    void initSchema() {
        jdbc.execute(CREATE_RESUME_RECORD);
        jdbc.execute(CREATE_PAPER);
        jdbc.execute(CREATE_PAPER_QUESTION);
        jdbc.execute(CREATE_SUBMISSION);
        // 旧库清理只写不读的列
        if (SchemaSupport.dropColumnIfExists(jdbc, "resume_record", "major_domain")
                | SchemaSupport.dropColumnIfExists(jdbc, "resume_record", "role")) {
            log.info("迁移表 resume_record 删除废弃列完成");
        }
        // 解析状态列
        if (SchemaSupport.addColumnIfMissing(jdbc, "resume_record", "status", "TEXT DEFAULT 'DONE'")
                | SchemaSupport.addColumnIfMissing(jdbc, "resume_record", "error_msg", "TEXT")) {
            log.info("迁移表 resume_record 新增状态列完成");
        }
        // 试卷题源列
        if (SchemaSupport.addColumnIfMissing(jdbc, "paper_question", "store", "TEXT DEFAULT 'bank'")) {
            log.info("迁移表 paper_question 新增题源列完成");
        }
        // 组卷 / 判分 异步状态列
        if (SchemaSupport.addColumnIfMissing(jdbc, "paper", "status", "TEXT DEFAULT 'DONE'")
                | SchemaSupport.addColumnIfMissing(jdbc, "paper", "error_msg", "TEXT")) {
            log.info("迁移表 paper 新增状态列完成");
        }
        // 试卷来源简历（级联删除用；旧卷为 NULL，删除时退回画像文本匹配）
        if (SchemaSupport.addColumnIfMissing(jdbc, "paper", "resume_id", "INTEGER")) {
            log.info("迁移表 paper 新增 resume_id 列完成");
        }
        if (SchemaSupport.addColumnIfMissing(jdbc, "submission", "status", "TEXT DEFAULT 'DONE'")
                | SchemaSupport.addColumnIfMissing(jdbc, "submission", "error_msg", "TEXT")) {
            log.info("迁移表 submission 新增状态列完成");
        }
        log.info("记录表（resume_record/paper/paper_question/submission）初始化完成");
    }

    // ---------- 简历解析记录 ----------

    public Long insertProcessingResumeRecord(Long userId) {
        return JdbcInsertSupport.insert(jdbc,
                "INSERT INTO resume_record(user_id, status) VALUES(?,?)",
                ps -> {
                    ps.setLong(1, userId);
                    ps.setString(2, "PROCESSING");
                });
    }

    public void markResumeExtracted(Long id, String sourceType, int textLength) {
        if (id == null) {
            return;
        }
        jdbc.update("UPDATE resume_record SET source_type=?, text_length=? WHERE id=?", sourceType, textLength, id);
    }

    public void markResumeDone(Long id, String profileJson) {
        if (id == null) {
            return;
        }
        jdbc.update("UPDATE resume_record SET profile_json=?, status='DONE', error_msg=NULL WHERE id=?", profileJson, id);
    }

    public void markResumeFailed(Long id, String errorMsg) {
        if (id == null) {
            return;
        }
        jdbc.update("UPDATE resume_record SET status='FAILED', error_msg=? WHERE id=?", errorMsg, id);
    }

    // ---------- 组卷记录 ----------

    public Long insertProcessingPaper(Long userId, String profileJson, Long resumeId) {
        return JdbcInsertSupport.insert(jdbc,
                "INSERT INTO paper(user_id, resume_id, profile_json, status) VALUES(?,?,?,?)",
                ps -> {
                    ps.setLong(1, userId);
                    if (resumeId == null) {
                        ps.setNull(2, java.sql.Types.INTEGER);
                    } else {
                        ps.setLong(2, resumeId);
                    }
                    ps.setString(3, profileJson);
                    ps.setString(4, "PROCESSING");
                });
    }

    /** 取试卷的来源简历 id（reuse 继承用）；不存在或未记录返回 null */
    public Long findPaperResumeId(Long paperId) {
        List<Object> rows = jdbc.query("SELECT resume_id FROM paper WHERE id=?",
                (rs, rowNum) -> rs.getObject(1), paperId);
        if (rows.isEmpty() || rows.get(0) == null) {
            return null;
        }
        return ((Number) rows.get(0)).longValue();
    }

    public void markPaperDone(Long paperId) {
        jdbc.update("UPDATE paper SET status='DONE', error_msg=NULL WHERE id=?", paperId);
    }

    public void markPaperFailed(Long paperId, String errorMsg) {
        jdbc.update("UPDATE paper SET status='FAILED', error_msg=? WHERE id=?", errorMsg, paperId);
    }

    /** 返回试卷状态；不存在返回 null */
    public String findPaperStatus(Long paperId) {
        List<String> rows = jdbc.query("SELECT COALESCE(status,'DONE') FROM paper WHERE id=?",
                (rs, rowNum) -> rs.getString(1), paperId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 返回试卷失败原因（error_msg）；不存在失败信息返回 null */
    public String findPaperError(Long paperId) {
        List<String> rows = jdbc.query("SELECT error_msg FROM paper WHERE id=?",
                (rs, rowNum) -> rs.getString(1), paperId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public String findPaperProfileJson(Long paperId) {
        List<String> rows = jdbc.query("SELECT profile_json FROM paper WHERE id=?",
                (rs, rowNum) -> rs.getString(1), paperId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insertPaperQuestion(Long paperId, Long questionId, int queueNo, String store) {
        jdbc.update("INSERT INTO paper_question(paper_id, question_id, queue_no, store) VALUES(?,?,?,?)",
                paperId, questionId, queueNo, store == null ? "bank" : store);
    }

    /** 试卷题目明细（按答题顺序） */
    public List<PaperQuestionRow> listPaperQuestions(Long paperId) {
        return jdbc.query("SELECT question_id, store, queue_no FROM paper_question WHERE paper_id=? ORDER BY queue_no",
                (rs, rowNum) -> new PaperQuestionRow(rs.getLong("question_id"), rs.getString("store"), rs.getObject("queue_no") == null ? null : rs.getInt("queue_no")),
                paperId);
    }

    // ---------- 作答评分记录 ----------

    public Long insertProcessingSubmission(Long userId, String answersJson) {
        return JdbcInsertSupport.insert(jdbc,
                "INSERT INTO submission(user_id, answers_json, status) VALUES(?,?,?)",
                ps -> {
                    ps.setLong(1, userId);
                    ps.setString(2, answersJson);
                    ps.setString(3, "PROCESSING");
                });
    }

    public void markSubmissionDone(Long id, String reportJson, int totalScore, int fullScore) {
        jdbc.update("UPDATE submission SET report_json=?, total_score=?, full_score=?, status='DONE', error_msg=NULL WHERE id=?",
                reportJson, totalScore, fullScore, id);
    }

    public void markSubmissionFailed(Long id, String errorMsg) {
        jdbc.update("UPDATE submission SET status='FAILED', error_msg=? WHERE id=?", errorMsg, id);
    }

    public List<SubmissionRow> listSubmissionsByUser(Long userId) {
        return jdbc.query("""
                        SELECT id, paper_id, user_id, answers_json, report_json, total_score, full_score, created_at
                        FROM submission WHERE user_id=? ORDER BY id DESC
                        """,
                submissionRowMapper, userId);
    }

    /**
     * 删除某用户的一条简历解析记录，并级联清除其画像衍生数据（事务内原子完成）：
     * - resume_record 自身
     * - 该简历的学习路线（learning_plan，直接源于简历画像）
     * - 该简历派生的试卷（paper，按 resume_id 精确关联）、试卷明细（paper_question）及其作答（submission）
     *   对未记录 resume_id 的历史旧卷，退回 profile_json 文本匹配兜底。
     * 返回是否删除了简历记录。
     */
    public boolean deleteResumeByUser(Long userId, Long resumeId) {
        List<String> profileRows = jdbc.query("SELECT profile_json FROM resume_record WHERE id=? AND user_id=?",
                (rs, rowNum) -> rs.getString(1), resumeId, userId);
        if (profileRows.isEmpty()) {
            return false;
        }
        String profileJson = profileRows.get(0);
        // 精确按 resume_id 关联；旧卷（resume_id 为 NULL）退回画像文本匹配
        List<Long> paperIds = new ArrayList<>(jdbc.query(
                "SELECT id FROM paper WHERE user_id=? AND (resume_id=? OR (resume_id IS NULL AND profile_json=?))",
                (rs, rowNum) -> rs.getLong(1), userId, resumeId, profileJson == null ? "" : profileJson));
        return Boolean.TRUE.equals(tx.execute(status -> {
            jdbc.update("DELETE FROM learning_plan WHERE resume_id=?", resumeId);
            for (Long pid : paperIds) {
                jdbc.update("DELETE FROM submission WHERE paper_id=?", pid);
                jdbc.update("DELETE FROM paper_question WHERE paper_id=?", pid);
                jdbc.update("DELETE FROM paper WHERE id=?", pid);
            }
            jdbc.update("DELETE FROM resume_record WHERE id=? AND user_id=?", resumeId, userId);
            return true;
        }));
    }

    // ---------- 行映射 ----------

    public record PaperQuestionRow(Long questionId, String store, Integer queueNo) {
    }

    public record SubmissionRow(Long id, Long paperId, Long userId, String answersJson, String reportJson,
                                Integer totalScore, Integer fullScore, String createdAt) {
    }

    private static final RowMapper<SubmissionRow> submissionRowMapper = new org.springframework.jdbc.core.DataClassRowMapper<>(SubmissionRow.class);
}