package com.demo.interview.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理后台跨用户只读查询（JdbcTemplate SELECT，不落库）。
 */
@Slf4j
@Repository
public class AdminQueryRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public AdminQueryRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** 总体计数 */
    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("users", count("user"));
        m.put("banned", jdbc.queryForObject("SELECT COUNT(*) FROM user WHERE banned=1", Integer.class));
        m.put("resumes", count("resume_record"));
        m.put("papers", count("paper"));
        m.put("submissions", count("submission"));
        return m;
    }

    private int count(String table) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return n == null ? 0 : n;
    }

    /** 用户列表（含今日使用次数） */
    public List<UserRow> listUsers() {
        return jdbc.query("""
                SELECT u.id, u.username, u.admin, u.banned, u.quota, u.created_at,
                       COALESCE(l.used, 0) AS today_used
                FROM user u LEFT JOIN usage_log l ON l.user_id = u.id AND l.day = ?
                ORDER BY u.id
                """, userRowMapper, LocalDate.now().toString());
    }

    /** 简历解析记录（含画像），跨用户 */
    public List<ResumeRow> listResumes(int limit) {
        return jdbc.query("""
                SELECT r.id, r.user_id, u.username, r.source_type, r.text_length,
                       r.status, r.error_msg, r.profile_json, r.created_at
                FROM resume_record r LEFT JOIN user u ON u.id = r.user_id
                ORDER BY r.id DESC LIMIT ?
                """, resumeRowMapper, limit);
    }

    /** 近 N 天每日总使用量（升序） */
    public List<DayCount> usageTrend(int days) {
        String from = LocalDate.now().minusDays(Math.max(0, days - 1)).toString();
        return jdbc.query("""
                SELECT day, SUM(used) AS used FROM usage_log
                WHERE day >= ? GROUP BY day ORDER BY day
                """, (rs, rowNum) -> new DayCount(rs.getString("day"), rs.getInt("used")), from);
    }

    // ---------- 行映射 ----------

    public record DayCount(String day, int used) {
    }

    public record UserRow(Long id, String username, boolean admin, boolean banned, int quota,
                          int todayUsed, String createdAt) {
    }

    public record ResumeRow(Long id, Long userId, String username, String sourceType, Integer textLength,
                            String status, String errorMsg, Object profile, String createdAt) {
    }

    private final RowMapper<UserRow> userRowMapper = (rs, rowNum) -> new UserRow(
            rs.getLong("id"),
            rs.getString("username"),
            rs.getInt("admin") == 1,
            rs.getInt("banned") == 1,
            rs.getInt("quota"),
            rs.getInt("today_used"),
            rs.getString("created_at"));

    private final RowMapper<ResumeRow> resumeRowMapper = (rs, rowNum) -> new ResumeRow(
            rs.getLong("id"),
            rs.getLong("user_id"),
            rs.getString("username"),
            rs.getString("source_type"),
            (Integer) rs.getObject("text_length"),
            rs.getString("status"),
            rs.getString("error_msg"),
            parseJson(rs.getString("profile_json")),
            rs.getString("created_at"));

    private Object parseJson(String raw) {
        return com.demo.interview.util.Jsons.parseObject(json, raw);
    }
}