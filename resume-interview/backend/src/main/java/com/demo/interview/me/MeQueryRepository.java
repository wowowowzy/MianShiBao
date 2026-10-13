package com.demo.interview.me;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 个人中心只读查询仓储（JdbcTemplate 单方向 SELECT，不建表、不落库）。
 */
@Slf4j
@Repository
public class MeQueryRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public MeQueryRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Optional<String> findUsernameById(long userId) {
        return guarded("user", () -> jdbc.query("SELECT username FROM user WHERE id=?",
                        (rs, rowNum) -> rs.getString("username"), userId)
                .stream().findFirst());
    }

    public Optional<String> findNicknameById(long userId) {
        return guarded("user", () -> jdbc.query("SELECT nickname FROM user WHERE id=?",
                        (rs, rowNum) -> rs.getString("nickname"), userId)
                .stream().findFirst());
    }

    /** 简历记录是否属于该用户 */
    public boolean existsResume(long userId, long resumeId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM resume_record WHERE id=? AND user_id=?",
                Integer.class, resumeId, userId);
        return n != null && n > 0;
    }

    /** 读取简历画像 JSON（同时校验归属） */
    public Optional<String> findResumeProfileJson(long userId, long resumeId) {
        return jdbc.query("SELECT profile_json FROM resume_record WHERE id=? AND user_id=?",
                        (rs, rowNum) -> rs.getString(1), resumeId, userId)
                .stream().findFirst();
    }

    /** 简历解析记录列表（按创建倒序），profile 为 profile_json 反序列化后的通用对象 */
    public List<ResumeItem> findResumesByUserId(long userId) {
        return guarded("resume_record", () -> jdbc.query("""
                        SELECT id, source_type, text_length, profile_json,
                               COALESCE(status, 'DONE') AS status, error_msg, created_at
                        FROM resume_record WHERE user_id=? ORDER BY id DESC
                        """, resumeRowMapper, userId));
    }

    /** 组卷记录列表（按创建倒序），子查询按 paper_question 统计题目数量 */
    public List<PaperItem> findPapersByUserId(long userId) {
        return guarded("paper", () -> jdbc.query("""
                        SELECT p.id, p.created_at,
                               COALESCE(p.status,'DONE') AS status,
                               (SELECT COUNT(*) FROM paper_question pq WHERE pq.paper_id=p.id) AS question_count
                        FROM paper p WHERE p.user_id=? ORDER BY p.id DESC
                        """, paperRowMapper, userId));
    }

    /** 作答评分记录列表（按创建倒序），pct = round(totalScore / fullScore * 100) */
    public List<SubmissionItem> findSubmissionsByUserId(long userId) {
        return guarded("submission", () -> jdbc.query("""
                        SELECT id, paper_id, total_score, full_score,
                               COALESCE(status,'DONE') AS status, created_at
                        FROM submission WHERE user_id=? ORDER BY id DESC
                        """, submissionRowMapper, userId));
    }

    /** 单条作答完整报告（必须同时命中 user_id，防止越权读他人数据） */
    public Optional<SubmissionDetail> findSubmissionById(long userId, long submissionId) {
        return guarded("submission", () -> jdbc.query("""
                        SELECT id, report_json, COALESCE(status,'DONE') AS status, error_msg, created_at
                        FROM submission WHERE id=? AND user_id=?
                        """, submissionDetailRowMapper, submissionId, userId)
                .stream().findFirst());
    }

    // ---------- 工具 ----------

    private <T> T guarded(String biz, Supplier<T> action) {
        try {
            return action.get();
        } catch (DataAccessException e) {
            log.error("个人中心查询失败 biz={}", biz, e);
            // 如实报告数据访问故障并保留原始异常链；不再一律归因为"表未就绪"（会掩盖磁盘满、锁等待、连接断开等真实原因）
            throw new IllegalStateException("个人中心数据查询失败（" + biz + "）：" + e.getMessage(), e);
        }
    }

    private Object parseJson(String raw) {
        return com.demo.interview.util.Jsons.parseObject(json, raw);
    }

    private static Integer intOrNull(ResultSet rs, String column) throws SQLException {
        Number n = (Number) rs.getObject(column);
        return n == null ? null : n.intValue();
    }

    private static Long longOrNull(ResultSet rs, String column) throws SQLException {
        Number n = (Number) rs.getObject(column);
        return n == null ? null : n.longValue();
    }

    private static int pct(Integer total, Integer full) {
        if (total == null || full == null || full <= 0) {
            return 0;
        }
        return (int) Math.round(total * 100.0 / full);
    }

    // ---------- 行映射 & 响应结构 ----------

    public record ResumeItem(Long id, String sourceType, Integer textLength, Object profile,
                             String status, String errorMsg, String createdAt) {
    }

    public record PaperItem(Long id, int questionCount, String status, String createdAt) {
    }

    public record SubmissionItem(Long id, Long paperId, Integer totalScore, Integer fullScore,
                                 int pct, String status, String createdAt) {
    }

    public record SubmissionDetail(Long id, String status, String errorMsg, String createdAt, Object report) {
    }

    private final RowMapper<ResumeItem> resumeRowMapper = (rs, rowNum) -> new ResumeItem(
            rs.getLong("id"),
            rs.getString("source_type"),
            intOrNull(rs, "text_length"),
            parseJson(rs.getString("profile_json")),
            rs.getString("status"),
            rs.getString("error_msg"),
            rs.getString("created_at"));

    private final RowMapper<PaperItem> paperRowMapper = (rs, rowNum) -> new PaperItem(
            rs.getLong("id"),
            rs.getInt("question_count"),
            rs.getString("status"),
            rs.getString("created_at"));

    private final RowMapper<SubmissionItem> submissionRowMapper = (rs, rowNum) -> {
        Integer total = intOrNull(rs, "total_score");
        Integer full = intOrNull(rs, "full_score");
        return new SubmissionItem(
                rs.getLong("id"),
                longOrNull(rs, "paper_id"),
                total, full,
                pct(total, full),
                rs.getString("status"),
                rs.getString("created_at"));
    };

    private final RowMapper<SubmissionDetail> submissionDetailRowMapper = (rs, rowNum) -> new SubmissionDetail(
            rs.getLong("id"),
            rs.getString("status"),
            rs.getString("error_msg"),
            rs.getString("created_at"),
            parseJson(rs.getString("report_json")));
}