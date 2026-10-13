package com.demo.interview.util;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 插入并回填自增主键的公共逻辑（SQLite 下用 RETURN_GENERATED_KEYS）。
 */
public final class JdbcInsertSupport {

    private JdbcInsertSupport() {
    }

    @FunctionalInterface
    public interface PsBinder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    public static Long insert(JdbcTemplate jdbc, String sql, PsBinder binder) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            binder.bind(ps);
            return ps;
        }, holder);
        Number key = holder.getKey();
        return key == null ? null : key.longValue();
    }
}