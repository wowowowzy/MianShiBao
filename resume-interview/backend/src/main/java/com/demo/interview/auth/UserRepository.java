package com.demo.interview.auth;

import com.demo.interview.util.JdbcInsertSupport;
import com.demo.interview.util.SchemaSupport;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 用户仓储。schema 首启自动建表；username 唯一索引保证不重复注册。
 * 含权限与配额字段：admin（管理员）、banned（封禁）、quota（今日剩余使用次数）。
 */
@Slf4j
@Repository
public class UserRepository {

    /** 普通用户每日默认配额 */
    public static final int DEFAULT_QUOTA = 5;

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS user (
              id            INTEGER PRIMARY KEY AUTOINCREMENT,
              username      TEXT UNIQUE NOT NULL,
              password_hash TEXT NOT NULL,
              created_at    TEXT NOT NULL,
              admin         INTEGER NOT NULL DEFAULT 0,
              banned        INTEGER NOT NULL DEFAULT 0,
              quota         INTEGER NOT NULL DEFAULT 5,
              openid        TEXT,
              nickname      TEXT
            )
            """;

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void initSchema() {
        jdbc.execute(CREATE_TABLE);
        boolean migrated = SchemaSupport.addColumnIfMissing(jdbc, "user", "admin", "INTEGER NOT NULL DEFAULT 0");
        migrated |= SchemaSupport.addColumnIfMissing(jdbc, "user", "banned", "INTEGER NOT NULL DEFAULT 0");
        migrated |= SchemaSupport.addColumnIfMissing(jdbc, "user", "quota", "INTEGER NOT NULL DEFAULT 5");
        migrated |= SchemaSupport.addColumnIfMissing(jdbc, "user", "openid", "TEXT");
        migrated |= SchemaSupport.addColumnIfMissing(jdbc, "user", "nickname", "TEXT");
        // openid 唯一索引：同一微信用户重复登录只会命中同一条账号
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_user_openid ON user(openid)");
        log.info("用户表初始化完成{}", migrated ? "（含 openid 字段迁移）" : "");
    }

    /** 新增用户并返回带自增 id 的完整实体 */
    public User insert(String username, String passwordHash, String createdAt, boolean admin) {
        Long id = JdbcInsertSupport.insert(jdbc,
                "INSERT INTO user(username, password_hash, created_at, admin, quota) VALUES(?,?,?,?,?)",
                ps -> {
                    ps.setString(1, username);
                    ps.setString(2, passwordHash);
                    ps.setString(3, createdAt);
                    ps.setInt(4, admin ? 1 : 0);
                    ps.setInt(5, DEFAULT_QUOTA);
                });
        return findById(id);
    }

    /** 按用户名查用户，不存在返回 null */
    public User findByUsername(String username) {
        return jdbc.query("SELECT * FROM user WHERE username=?", rowMapper, username)
                .stream().findFirst().orElse(null);
    }

    /** 按微信 openid 查用户，不存在返回 null */
    public User findByOpenid(String openid) {
        if (openid == null || openid.isBlank()) {
            return null;
        }
        return jdbc.query("SELECT * FROM user WHERE openid=?", rowMapper, openid)
                .stream().findFirst().orElse(null);
    }

    /** 按微信 openid 建号：微信用户无密码，password_hash 落空串占位（不参与密码登录） */
    public User insertWechat(String username, String openid, String createdAt) {
        Long id = JdbcInsertSupport.insert(jdbc,
                "INSERT INTO user(username, password_hash, created_at, admin, quota, openid) VALUES(?,?,?,0,?,?)",
                ps -> {
                    ps.setString(1, username);
                    ps.setString(2, "");
                    ps.setString(3, createdAt);
                    ps.setInt(4, DEFAULT_QUOTA);
                    ps.setString(5, openid);
                });
        return findById(id);
    }

    /** 按主键查用户，不存在返回 null */
    public User findById(Long id) {
        if (id == null) {
            return null;
        }
        return jdbc.query("SELECT * FROM user WHERE id=?", rowMapper, id)
                .stream().findFirst().orElse(null);
    }

    /** 全量用户（管理后台用） */
    public List<User> findAll() {
        return jdbc.query("SELECT * FROM user ORDER BY id", rowMapper);
    }

    /** 扣 1 次配额；返回受影响行数（0 = 已用完或非正配额） */
    public int consumeQuota(Long userId) {
        return jdbc.update("UPDATE user SET quota = quota - 1 WHERE id=? AND quota > 0", userId);
    }

    /** 设置用户昵称（微信昵称等展示名），返回受影响行数 */
    public int updateNickname(Long userId, String nickname) {
        return jdbc.update("UPDATE user SET nickname=? WHERE id=?", nickname, userId);
    }

    public void setQuota(Long userId, int quota) {
        jdbc.update("UPDATE user SET quota=? WHERE id=?", Math.max(0, quota), userId);
    }

    public void setBanned(Long userId, boolean banned) {
        jdbc.update("UPDATE user SET banned=? WHERE id=?", banned ? 1 : 0, userId);
    }

    public void setAdmin(Long userId, boolean admin) {
        jdbc.update("UPDATE user SET admin=? WHERE id=?", admin ? 1 : 0, userId);
    }

    /** 每日重置：普通用户配额恢复默认（管理员配额不参与消耗，保持不动） */
    public int resetNormalQuota() {
        return jdbc.update("UPDATE user SET quota=? WHERE admin=0", DEFAULT_QUOTA);
    }

    private static final RowMapper<User> rowMapper = new org.springframework.jdbc.core.DataClassRowMapper<>(User.class);
}