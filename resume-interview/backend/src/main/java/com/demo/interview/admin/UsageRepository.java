package com.demo.interview.admin;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 每日使用量仓储：按 (user_id, day) 累计使用次数（统计查询见 AdminQueryRepository）。
 */
@Slf4j
@Repository
public class UsageRepository {

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS usage_log (
              user_id INTEGER NOT NULL,
              day     TEXT NOT NULL,
              used    INTEGER NOT NULL DEFAULT 0,
              PRIMARY KEY (user_id, day)
            )
            """;

    private final JdbcTemplate jdbc;

    public UsageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void initSchema() {
        jdbc.execute(CREATE_TABLE);
        log.info("使用量统计表初始化完成");
    }

    /** 当日使用次数 +1（无则插入） */
    public void increment(Long userId, String day) {
        jdbc.update("""
                INSERT INTO usage_log(user_id, day, used) VALUES(?,?,1)
                ON CONFLICT(user_id, day) DO UPDATE SET used = used + 1
                """, userId, day);
    }
}