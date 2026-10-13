package com.demo.interview.util;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * SQLite schema 迁移小工具（各仓储共用）。
 */
public final class SchemaSupport {

    private SchemaSupport() {
    }

    /**
     * 列存在才执行 ALTER TABLE ... DROP COLUMN（SQLite 无 DROP COLUMN IF EXISTS）。
     *
     * @return 是否实际删除了列
     */
    public static boolean dropColumnIfExists(JdbcTemplate jdbc, String table, String column) {
        List<String> cols = jdbc.query("PRAGMA table_info(" + table + ")",
                (rs, rowNum) -> rs.getString("name"));
        if (cols.contains(column)) {
            jdbc.execute("ALTER TABLE " + table + " DROP COLUMN " + column);
            return true;
        }
        return false;
    }

    /**
     * 列不存在才执行 ALTER TABLE ... ADD COLUMN（SQLite 无 ADD COLUMN IF NOT EXISTS）。
     *
     * @return 是否实际新增了列
     */
    public static boolean addColumnIfMissing(JdbcTemplate jdbc, String table, String column, String ddl) {
        List<String> cols = jdbc.query("PRAGMA table_info(" + table + ")",
                (rs, rowNum) -> rs.getString("name"));
        if (!cols.contains(column)) {
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + ddl);
            return true;
        }
        return false;
    }
}