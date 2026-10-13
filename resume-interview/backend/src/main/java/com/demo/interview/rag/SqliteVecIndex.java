package com.demo.interview.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.StringJoiner;

/**
 * sqlite-vec 向量表通用支撑：负责连接（加载 vec0 扩展）、建表、批量向量化写入、kNN 查询。
 * 各业务向量服务只提供「实体表 → 待索引行」的差异部分，避免重复实现连接/序列化/批处理。
 *
 * <p>每个向量表持有独立连接（load_extension 是连接级状态），调用方自行持有并在单线程内使用。</p>
 */
@Slf4j
@Component
public class SqliteVecIndex {

    private static final String DB_URL = "jdbc:sqlite:file:../data/resume-interview.db?busy_timeout=5000";
    private static final String DLL_REL = "lib/sqlite-vec/vec0.dll";
    public static final int EMBED_BATCH = 20;
    public static final int STEM_TRUNC = 150;

    private final DashScopeEmbeddingClient embedding;

    public SqliteVecIndex(DashScopeEmbeddingClient embedding) {
        this.embedding = embedding;
    }

    /** 打开独立连接、加载扩展并确保向量表存在 */
    public Connection open(String vecTable, boolean withSource) throws Exception {
        Path dll = locateDll();
        org.sqlite.SQLiteConfig cfg = new org.sqlite.SQLiteConfig();
        cfg.enableLoadExtension(true);
        Connection c = DriverManager.getConnection(DB_URL, (Properties) cfg.toProperties());
        try (Statement st = c.createStatement()) {
            st.execute("SELECT load_extension('" + dll.toString().replace('\\', '/') + "')");
            String cols = withSource
                    ? "embedding float[1024] distance_metric=cosine, source TEXT"
                    : "embedding float[1024] distance_metric=cosine";
            st.execute("CREATE VIRTUAL TABLE IF NOT EXISTS " + vecTable + " USING vec0(" + cols + ")");
        }
        return c;
    }

    /** 已索引的 rowid 集合（幂等索引用） */
    public Set<Long> indexedRowIds(Connection c, String vecTable) throws Exception {
        Set<Long> indexed = new HashSet<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT rowid FROM " + vecTable)) {
            while (rs.next()) {
                indexed.add(rs.getLong(1));
            }
        }
        return indexed;
    }

    /** 分批量向量化并写入（withSource 时同时写 source 列） */
    public int insertAll(Connection c, String vecTable, boolean withSource, List<Pending> pending) throws Exception {
        if (pending.isEmpty()) {
            return 0;
        }
        String sql = withSource
                ? "INSERT INTO " + vecTable + "(rowid, source, embedding) VALUES(?,?,?)"
                : "INSERT INTO " + vecTable + "(rowid, embedding) VALUES(?,?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int from = 0; from < pending.size(); from += EMBED_BATCH) {
                List<Pending> batch = pending.subList(from, Math.min(from + EMBED_BATCH, pending.size()));
                List<String> texts = batch.stream().map(Pending::text).toList();
                List<double[]> vecs = embedding.embed(texts);
                for (int i = 0; i < batch.size() && i < vecs.size(); i++) {
                    ps.setLong(1, batch.get(i).id());
                    if (withSource) {
                        ps.setString(2, batch.get(i).source());
                        ps.setString(3, toJson(vecs.get(i)));
                    } else {
                        ps.setString(2, toJson(vecs.get(i)));
                    }
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
        return pending.size();
    }

    /** kNN 查询；sources 非空时按 source 过滤（仅 withSource 表可用） */
    public List<Long> knn(Connection c, String vecTable, double[] queryVector, int topK, List<String> sources) {
        if (c == null || queryVector == null) {
            return List.of();
        }
        boolean filter = sources != null && !sources.isEmpty();
        String sql = "SELECT rowid FROM " + vecTable + " WHERE embedding MATCH ?"
                + (filter ? " AND source IN (" + String.join(",", sources.stream().map(s -> "?").toList()) + ")" : "")
                + " ORDER BY distance LIMIT ?";
        List<Long> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int idx = 1;
            ps.setString(idx++, toJson(queryVector));
            if (filter) {
                for (String s : sources) {
                    ps.setString(idx++, s);
                }
            }
            ps.setInt(idx, Math.max(1, topK));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getLong(1));
                }
            }
        } catch (Exception e) {
            log.warn("{} kNN 查询失败: {}", vecTable, e.getMessage());
            return List.of();
        }
        return out;
    }

    private Path locateDll() throws Exception {
        String[] cands = {DLL_REL, "../" + DLL_REL, "../../" + DLL_REL};
        for (String c : cands) {
            Path p = Path.of(c);
            if (Files.exists(p)) {
                return p.toAbsolutePath().normalize();
            }
        }
        throw new IllegalStateException("未找到 vec0.dll（期望 backend/lib/sqlite-vec/vec0.dll）");
    }

    public static String toJson(double[] v) {
        StringJoiner sj = new StringJoiner(",", "[", "]");
        for (double x : v) {
            sj.add(String.valueOf(x));
        }
        return sj.toString();
    }

    public static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }

    /** 待索引行：rowid + 嵌入文本 + 可选 source */
    public record Pending(long id, String text, String source) {
    }
}