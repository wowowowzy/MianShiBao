package com.demo.interview.question;

import com.demo.interview.question.model.Question;
import com.demo.interview.util.JdbcInsertSupport;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 项目场景题仓储：LLM 按简历项目即时生成的场景题单独存这张表，
 * 不进入 RAG 题库（question），保证 question 表只存放「可向量化」的常规题。
 * 历史记录通过 paper_question.store='project' 连表查询。
 */
@Slf4j
@Repository
public class ProjectQuestionRepository {

    /** 题目来源标记：项目场景题 */
    public static final String STORE = "project";

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS project_question (
              id           INTEGER PRIMARY KEY AUTOINCREMENT,
              project_name TEXT,
              skill        TEXT,
              type         TEXT,
              difficulty   TEXT,
              stem         TEXT NOT NULL,
              answer       TEXT,
              points       TEXT,
              explanation  TEXT,
              created_at   TEXT DEFAULT (datetime('now','localtime'))
            )
            """;

    private final JdbcTemplate jdbc;

    public ProjectQuestionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void initSchema() {
        jdbc.execute(CREATE_TABLE);
        log.info("项目场景题表初始化完成");
    }

    /** 插入并返回自增主键（供判分按 id 取题） */
    public Long insertWithId(String projectName, Question q) {
        return JdbcInsertSupport.insert(jdbc, """
                INSERT INTO project_question(project_name, skill, type, difficulty, stem, answer, points, explanation)
                VALUES(?,?,?,?,?,?,?,?)
                """, ps -> {
            ps.setString(1, projectName);
            ps.setString(2, q.skill());
            ps.setString(3, q.type());
            ps.setString(4, q.difficulty());
            ps.setString(5, q.stem());
            ps.setString(6, q.answer());
            ps.setString(7, q.points());
            ps.setString(8, q.explanation());
        });
    }

    public Question findById(Long id) {
        return jdbc.queryForObject("SELECT * FROM project_question WHERE id=?", rowMapper, id);
    }

    private static final RowMapper<Question> rowMapper = (rs, rowNum) -> new Question(
            rs.getLong("id"),
            rs.getString("skill"),
            rs.getString("type"),
            rs.getString("difficulty"),
            rs.getString("stem"),
            rs.getString("answer"),
            rs.getString("points"),
            rs.getString("explanation"),
            STORE);
}