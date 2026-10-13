package com.demo.interview.question;

import com.demo.interview.question.model.Question;
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
 * SQLite 题库仓储。schema 首启自动建表；题目带 skill 标签与评分要点。
 * stem_key（题干归一化）唯一索引用于 DB 级去重，插入走 INSERT OR IGNORE。
 */
@Slf4j
@Repository
public class QuestionRepository {

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS question (
              id             INTEGER PRIMARY KEY AUTOINCREMENT,
              skill          TEXT NOT NULL,
              type           TEXT NOT NULL,
              difficulty     TEXT NOT NULL,
              stem           TEXT NOT NULL,
              stem_key       TEXT,
              answer         TEXT,
              points         TEXT,
              explanation    TEXT,
              source         TEXT,
              status         TEXT DEFAULT 'ACTIVE'
            )
            """;

    private final JdbcTemplate jdbc;

    public QuestionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void initSchema() {
        jdbc.execute(CREATE_TABLE);
        // 旧库清理已废弃的客观题列（SQLite 无 DROP COLUMN IF EXISTS，用 PRAGMA 判断幂等）
        if (SchemaSupport.dropColumnIfExists(jdbc, "question", "options")
                | SchemaSupport.dropColumnIfExists(jdbc, "question", "correct_option")) {
            log.info("迁移表 question 删除废弃列完成");
        }
        // 题干去重键 + 清理历史重复 + 唯一索引
        SchemaSupport.addColumnIfMissing(jdbc, "question", "stem_key", "TEXT");
        jdbc.update("UPDATE question SET stem_key = lower(trim(stem)) WHERE stem_key IS NULL OR stem_key = ''");
        int dup = jdbc.update("""
                DELETE FROM question WHERE id NOT IN (
                  SELECT MIN(id) FROM question WHERE stem_key IS NOT NULL GROUP BY stem_key)
                """);
        int refs = jdbc.update("""
                DELETE FROM paper_question WHERE question_id NOT IN (SELECT id FROM question)
                """);
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_question_stem_key ON question(stem_key)");
        if (dup > 0 || refs > 0) {
            log.info("题库去重迁移：删除重复题 {} 条、悬空试卷引用 {} 条", dup, refs);
        }
        log.info("题库表初始化完成");
    }

    /** 插入并返回自增主键；题干重复（唯一索引冲突）时返回 null */
    public Long insertWithId(Question q) {
        return JdbcInsertSupport.insert(jdbc, """
                INSERT OR IGNORE INTO question(skill, type, difficulty, stem, stem_key, answer, points, explanation, source)
                VALUES(?,?,?,?,?,?,?,?,?)
                """, ps -> {
            ps.setString(1, q.skill());
            ps.setString(2, q.type());
            ps.setString(3, q.difficulty());
            ps.setString(4, q.stem());
            ps.setString(5, LlmQuestionCleaner.stemKey(q.stem()));
            ps.setString(6, q.answer());
            ps.setString(7, q.points());
            ps.setString(8, q.explanation());
            ps.setString(9, q.source());
        });
    }

    /** 按来源（域）召回题目：用于按岗位限域出题 */
    public List<Question> findBySources(List<String> sources) {
        if (sources == null || sources.isEmpty()) {
            return List.of();
        }
        String in = String.join(",", sources.stream().map(s -> "?").toList());
        return jdbc.query("SELECT * FROM question WHERE status='ACTIVE' AND source IN (" + in + ")",
                rowMapper, sources.toArray());
    }

    public List<Question> findAll() {
        return jdbc.query("SELECT * FROM question WHERE status='ACTIVE' ORDER BY skill, difficulty", rowMapper);
    }

    /** 按主键批量查询（题目级召回后取题） */
    public List<Question> findByIds(java.util.List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        String in = String.join(",", ids.stream().map(s -> "?").toList());
        return jdbc.query("SELECT * FROM question WHERE id IN (" + in + ")", rowMapper, ids.toArray());
    }

    public Question findById(Long id) {
        return jdbc.queryForObject("SELECT * FROM question WHERE id=?", rowMapper, id);
    }

    private static final RowMapper<Question> rowMapper = new org.springframework.jdbc.core.DataClassRowMapper<>(Question.class);
}