package com.demo.interview.auth;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 注册 IP 台账：记录每个注册账号的来源 IP（IPv6/IPv4），
 * 一个 IP 只允许注册一个账号，防止刷号消耗 LLM 调用。
 */
@Slf4j
@Repository
public class RegisterIpRepository {

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS register_ip (
              ip         TEXT PRIMARY KEY,
              user_id    INTEGER,
              created_at TEXT DEFAULT (datetime('now','localtime'))
            )
            """;

    private final JdbcTemplate jdbc;

    public RegisterIpRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void initSchema() {
        jdbc.execute(CREATE_TABLE);
        log.info("注册 IP 表初始化完成");
    }

    public boolean exists(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM register_ip WHERE ip=?", Integer.class, ip);
        return n != null && n > 0;
    }

    /** 记录 IP → 用户（ip 唯一，重复插入会抛异常） */
    public void save(String ip, Long userId) {
        jdbc.update("INSERT INTO register_ip(ip, user_id) VALUES(?,?)", ip, userId);
    }
}