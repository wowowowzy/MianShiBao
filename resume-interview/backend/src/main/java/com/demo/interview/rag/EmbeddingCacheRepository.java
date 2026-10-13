package com.demo.interview.rag;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 文本向量缓存：技能名/短语 -> 向量。命中避免重复调 DashScope embedding（省 token）。
 * 查询路径先查此缓存，miss 才计算并回写。
 */
@Slf4j
@Repository
public class EmbeddingCacheRepository {

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS embedding_cache (
              text        TEXT PRIMARY KEY,
              vector_text TEXT NOT NULL,
              model       TEXT NOT NULL,
              created_at  TEXT DEFAULT (datetime('now','localtime'))
            )
            """;

    private final JdbcTemplate jdbc;

    public EmbeddingCacheRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void initSchema() {
        jdbc.execute(CREATE_TABLE);
    }

    /** 命中返回向量 JSON 文本，未命中返回 null */
    public String getVector(String text) {
        List<String> rows = jdbc.query("SELECT vector_text FROM embedding_cache WHERE text=?",
                (rs, rowNum) -> rs.getString(1), text);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void put(String text, String model, String vectorText) {
        jdbc.update("""
                INSERT INTO embedding_cache(text, vector_text, model) VALUES(?,?,?)
                ON CONFLICT(text) DO UPDATE SET vector_text=excluded.vector_text, model=excluded.model
                """, text, vectorText, model);
    }
}