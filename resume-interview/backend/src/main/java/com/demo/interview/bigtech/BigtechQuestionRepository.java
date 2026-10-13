package com.demo.interview.bigtech;

import com.demo.interview.util.JdbcInsertSupport;
import com.demo.interview.util.LlmQuestionCleaner;
import com.demo.interview.util.SchemaSupport;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 大厂面试题仓储（独立表 bigtech_question，不与常规题库混用）。
 * stem_key 唯一索引做 DB 级去重，插入走 INSERT OR IGNORE。
 */
@Slf4j
@Repository
public class BigtechQuestionRepository {

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS bigtech_question (
              id          INTEGER PRIMARY KEY AUTOINCREMENT,
              company     TEXT,
              skill       TEXT,
              type        TEXT,
              difficulty  TEXT,
              stem        TEXT NOT NULL,
              stem_key    TEXT,
              answer      TEXT,
              points      TEXT,
              explanation TEXT,
              created_at  TEXT DEFAULT (datetime('now','localtime'))
            )
            """;

    private final JdbcTemplate jdbc;

    public BigtechQuestionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void initSchema() {
        jdbc.execute(CREATE_TABLE);
        SchemaSupport.addColumnIfMissing(jdbc, "bigtech_question", "stem_key", "TEXT");
        jdbc.update("UPDATE bigtech_question SET stem_key = lower(trim(stem)) WHERE stem_key IS NULL OR stem_key = ''");
        int dup = jdbc.update("""
                DELETE FROM bigtech_question WHERE id NOT IN (
                  SELECT MIN(id) FROM bigtech_question WHERE stem_key IS NOT NULL GROUP BY stem_key)
                """);
        int refs = jdbc.update("""
                DELETE FROM paper_question WHERE store='bigtech'
                  AND question_id NOT IN (SELECT id FROM bigtech_question)
                """);
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_bigtech_stem_key ON bigtech_question(stem_key)");
        if (dup > 0 || refs > 0) {
            log.info("大厂题库去重迁移：删除重复题 {} 条、悬空试卷引用 {} 条", dup, refs);
        }
        log.info("大厂题库表初始化完成");
    }

    /** 插入并返回自增主键；题干重复时返回 null */
    public Long insertWithId(BigtechQuestion q) {
        return JdbcInsertSupport.insert(jdbc, """
                INSERT OR IGNORE INTO bigtech_question(company, skill, type, difficulty, stem, stem_key, answer, points, explanation)
                VALUES(?,?,?,?,?,?,?,?,?)
                """, ps -> {
            ps.setString(1, q.company());
            ps.setString(2, q.skill());
            ps.setString(3, q.type());
            ps.setString(4, q.difficulty());
            ps.setString(5, q.stem());
            ps.setString(6, LlmQuestionCleaner.stemKey(q.stem()));
            ps.setString(7, q.answer());
            ps.setString(8, q.points());
            ps.setString(9, q.explanation());
        });
    }

    public BigtechQuestion findById(Long id) {
        return jdbc.queryForObject("SELECT * FROM bigtech_question WHERE id=?", rowMapper, id);
    }

    public List<BigtechQuestion> findByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        String in = String.join(",", ids.stream().map(s -> "?").toList());
        return jdbc.query("SELECT * FROM bigtech_question WHERE id IN (" + in + ")", rowMapper, ids.toArray());
    }

    /** 供随机兜底取样 */
    public List<BigtechQuestion> findRandom(int limit) {
        return jdbc.query("SELECT * FROM bigtech_question ORDER BY RANDOM() LIMIT ?", rowMapper, limit);
    }

    private static final RowMapper<BigtechQuestion> rowMapper = new org.springframework.jdbc.core.DataClassRowMapper<>(BigtechQuestion.class);
}