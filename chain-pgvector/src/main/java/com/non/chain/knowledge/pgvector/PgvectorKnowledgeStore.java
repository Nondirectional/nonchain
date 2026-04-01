package com.non.chain.knowledge.pgvector;

import com.non.chain.knowledge.DocumentChunk;
import com.non.chain.knowledge.KnowledgeStore;
import com.non.chain.knowledge.MetadataFilter;
import com.non.chain.knowledge.SearchRequest;
import com.non.chain.knowledge.SearchResult;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * KnowledgeStore backed by PostgreSQL + pgvector.
 * Auto-creates table and indexes on first use. Uses cosine similarity.
 *
 * Required PostgreSQL setup: CREATE EXTENSION IF NOT EXISTS vector;
 */
public class PgvectorKnowledgeStore implements KnowledgeStore {

    private static final String DEFAULT_TABLE = "document_chunks";

    private final HikariDataSource dataSource;
    private final String table;
    private final int dimension;

    private PgvectorKnowledgeStore(Builder builder) {
        HikariConfig config = new HikariConfig();
        String jdbcUrl = builder.jdbcUrl;

        // 检查数据库是否存在，不存在则尝试连接 postgres 数据库并创建
        ensureDatabaseExists(jdbcUrl, builder.username, builder.password);

        config.setJdbcUrl(jdbcUrl);
        config.setUsername(builder.username);
        config.setPassword(builder.password);
        config.setMaximumPoolSize(builder.poolSize);
        config.setDriverClassName("org.postgresql.Driver");
        this.dataSource = new HikariDataSource(config);
        this.table = builder.table;
        this.dimension = builder.dimension;
        initSchema();
    }

    private void ensureDatabaseExists(String jdbcUrl, String user, String pass) {
        // 解析 jdbc:postgresql://host:port/dbname
        int lastSlash = jdbcUrl.lastIndexOf('/');
        if (lastSlash < 0) return;

        String baseUrl = jdbcUrl.substring(0, lastSlash + 1);
        String dbName = jdbcUrl.substring(lastSlash + 1).split("\\?")[0];

        // 尝试加载驱动
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException ignored) {}

        // 尝试连接默认的 postgres 数据库
        try (Connection conn = java.sql.DriverManager.getConnection(baseUrl + "postgres", user, pass);
             Statement stmt = conn.createStatement()) {

            // 检查数据库是否存在
            ResultSet rs = stmt.executeQuery(String.format(
                "SELECT 1 FROM pg_database WHERE datname = '%s'", dbName));

            if (!rs.next()) {
                // 不存在则创建
                stmt.execute(String.format("CREATE DATABASE %s", dbName));
            }
        } catch (SQLException e) {
            // 如果连 postgres 库都失败或创建失败，可能是权限问题或环境问题，这里记录但不强行中断
            // 因为有些环境可能已经存在库但禁止列出数据库
        }
    }

    private void initSchema() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE EXTENSION IF NOT EXISTS vector");
            stmt.execute(String.format(
                "CREATE TABLE IF NOT EXISTS %s (" +
                "  chunk_id          TEXT PRIMARY KEY," +
                "  document_id       TEXT NOT NULL," +
                "  knowledge_base_id TEXT NOT NULL," +
                "  content           TEXT NOT NULL," +
                "  metadata          JSONB NOT NULL DEFAULT '{}'," +
                "  embedding         vector(%d) NOT NULL," +
                "  chunk_index       INTEGER" +
                ")", table, dimension));
            stmt.execute(String.format(
                "CREATE INDEX IF NOT EXISTS idx_%s_kb  ON %s (knowledge_base_id)", table, table));
            stmt.execute(String.format(
                "CREATE INDEX IF NOT EXISTS idx_%s_doc ON %s (document_id)", table, table));
            try {
                stmt.execute(String.format(
                    "CREATE INDEX IF NOT EXISTS idx_%s_vec ON %s USING ivfflat (embedding vector_cosine_ops)",
                    table, table));
            } catch (SQLException ignored) {
                // ivfflat requires rows; safe to skip on empty table
            }
        } catch (SQLException e) {
            throw new RuntimeException("pgvector 表初始化失败", e);
        }
    }

    @Override
    public String add(DocumentChunk chunk) {
        return addAll(List.of(chunk)).get(0);
    }

    @Override
    public List<String> addAll(List<DocumentChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            throw new IllegalArgumentException("chunks 不能为空");
        }
        String sql = String.format(
            "INSERT INTO %s (chunk_id, document_id, knowledge_base_id, content, metadata, embedding, chunk_index) " +
            "VALUES (?, ?, ?, ?, ?::jsonb, ?::vector, ?) " +
            "ON CONFLICT (chunk_id) DO UPDATE SET " +
            "  document_id = EXCLUDED.document_id, " +
            "  knowledge_base_id = EXCLUDED.knowledge_base_id, " +
            "  content = EXCLUDED.content, " +
            "  metadata = EXCLUDED.metadata, " +
            "  embedding = EXCLUDED.embedding, " +
            "  chunk_index = EXCLUDED.chunk_index", table);
        List<String> ids = new ArrayList<>(chunks.size());
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (DocumentChunk chunk : chunks) {
                if (chunk.embedding() == null) {
                    throw new IllegalArgumentException("chunk 缺少 embedding: " + chunk.chunkId());
                }
                String id = chunk.chunkId() != null ? chunk.chunkId() : UUID.randomUUID().toString();
                ps.setString(1, id);
                ps.setString(2, chunk.documentId());
                ps.setString(3, chunk.knowledgeBaseId());
                ps.setString(4, chunk.content());
                ps.setString(5, metadataToJson(chunk.metadata()));
                ps.setString(6, toVectorLiteral(chunk.embedding()));
                if (chunk.chunkIndex() != null) {
                    ps.setInt(7, chunk.chunkIndex());
                } else {
                    ps.setNull(7, java.sql.Types.INTEGER);
                }
                ps.addBatch();
                ids.add(id);
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("写入 chunk 失败", e);
        }
        return ids;
    }

    @Override
    public List<SearchResult> search(SearchRequest request) {
        if (request.queryEmbedding() == null) {
            throw new IllegalArgumentException("queryEmbedding 不能为空");
        }
        StringBuilder inner = new StringBuilder(String.format(
            "SELECT chunk_id, document_id, knowledge_base_id, content, metadata, chunk_index, " +
            "  1 - (embedding <=> ?::vector) AS score " +
            "FROM %s WHERE 1=1", table));

        List<Object> params = new ArrayList<>();
        params.add(toVectorLiteral(request.queryEmbedding()));

        if (!request.knowledgeBaseIds().isEmpty()) {
            inner.append(" AND knowledge_base_id = ANY(?)");
            params.add(request.knowledgeBaseIds().toArray(new String[0]));
        }
        if (!request.documentIds().isEmpty()) {
            inner.append(" AND document_id = ANY(?)");
            params.add(request.documentIds().toArray(new String[0]));
        }
        if (!request.chunkIds().isEmpty()) {
            inner.append(" AND chunk_id = ANY(?)");
            params.add(request.chunkIds().toArray(new String[0]));
        }
        if (request.metadataFilter() != null) {
            inner.append(" AND ");
            inner.append(buildMetadataFilter(request.metadataFilter(), params));
        }

        StringBuilder sql = new StringBuilder("SELECT * FROM (").append(inner).append(") _q");
        if (request.minScore() != null) {
            sql.append(" WHERE score >= ?");
            params.add(request.minScore());
        }
        sql.append(" ORDER BY score DESC LIMIT ?");
        params.add(request.topK());

        List<SearchResult> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                Object p = params.get(i);
                if (p instanceof String[]) {
                    ps.setArray(i + 1, conn.createArrayOf("text", (String[]) p));
                } else if (p instanceof String) {
                    ps.setString(i + 1, (String) p);
                } else if (p instanceof Double) {
                    ps.setDouble(i + 1, (Double) p);
                } else if (p instanceof Integer) {
                    ps.setInt(i + 1, (Integer) p);
                } else {
                    ps.setObject(i + 1, p);
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(SearchResult.builder(
                            rs.getString("knowledge_base_id"),
                            rs.getString("document_id"),
                            rs.getString("chunk_id"),
                            rs.getString("content"),
                            rs.getDouble("score"))
                        .metadata(parseJsonToMap(rs.getString("metadata")))
                        .chunkIndex(rs.getObject("chunk_index") != null ? rs.getInt("chunk_index") : null)
                        .build());
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("向量检索失败", e);
        }
        return results;
    }

    @Override
    public void delete(String chunkId) {
        deleteAll(List.of(chunkId));
    }

    @Override
    public void deleteAll(List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) return;
        String sql = String.format("DELETE FROM %s WHERE chunk_id = ANY(?)", table);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setArray(1, conn.createArrayOf("text", chunkIds.toArray(new String[0])));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("删除 chunk 失败", e);
        }
    }

    @Override
    public void deleteByDocumentId(String documentId) {
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("documentId 不能为空");
        }
        String sql = String.format("DELETE FROM %s WHERE document_id = ?", table);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, documentId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("按 document 删除 chunk 失败", e);
        }
    }

    public void close() {
        dataSource.close();
    }

    // --- helpers ---

    private String toVectorLiteral(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(embedding[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    private String metadataToJson(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : metadata.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(e.getKey().replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            sb.append(':');
            Object v = e.getValue();
            if (v instanceof String) {
                sb.append('"').append(((String) v).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            } else if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else {
                sb.append('"').append(String.valueOf(v).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            }
        }
        sb.append('}');
        return sb.toString();
    }

    private Map<String, Object> parseJsonToMap(String json) {
        // Minimal JSON object parser for flat string/number/boolean values
        Map<String, Object> map = new LinkedHashMap<>();
        if (json == null || json.isBlank() || json.trim().equals("{}")) return map;
        String trimmed = json.trim();
        if (trimmed.startsWith("{")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("}")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        // Simple key:value splitting — sufficient for flat metadata maps
        int i = 0;
        while (i < trimmed.length()) {
            // skip whitespace
            while (i < trimmed.length() && Character.isWhitespace(trimmed.charAt(i))) i++;
            if (i >= trimmed.length()) break;
            // parse key
            if (trimmed.charAt(i) != '"') break;
            i++;
            int keyStart = i;
            while (i < trimmed.length() && trimmed.charAt(i) != '"') i++;
            String key = trimmed.substring(keyStart, i);
            i++; // closing quote
            while (i < trimmed.length() && (trimmed.charAt(i) == ':' || Character.isWhitespace(trimmed.charAt(i)))) i++;
            // parse value
            if (i >= trimmed.length()) break;
            if (trimmed.charAt(i) == '"') {
                i++;
                int valStart = i;
                while (i < trimmed.length() && trimmed.charAt(i) != '"') i++;
                map.put(key, trimmed.substring(valStart, i));
                i++;
            } else {
                int valStart = i;
                while (i < trimmed.length() && trimmed.charAt(i) != ',' && trimmed.charAt(i) != '}') i++;
                String raw = trimmed.substring(valStart, i).trim();
                if (raw.equals("true")) map.put(key, true);
                else if (raw.equals("false")) map.put(key, false);
                else if (raw.contains(".")) {
                    try { map.put(key, Double.parseDouble(raw)); } catch (NumberFormatException e) { map.put(key, raw); }
                } else {
                    try { map.put(key, Long.parseLong(raw)); } catch (NumberFormatException e) { map.put(key, raw); }
                }
            }
            while (i < trimmed.length() && (trimmed.charAt(i) == ',' || Character.isWhitespace(trimmed.charAt(i)))) i++;
        }
        return map;
    }

    private String buildMetadataFilter(MetadataFilter filter, List<Object> params) {
        switch (filter.type()) {
            case CONDITION:
                return buildCondition(filter, params);
            case AND: {
                List<String> parts = new ArrayList<>();
                for (MetadataFilter child : filter.children()) {
                    parts.add("(" + buildMetadataFilter(child, params) + ")");
                }
                return String.join(" AND ", parts);
            }
            case OR: {
                List<String> parts = new ArrayList<>();
                for (MetadataFilter child : filter.children()) {
                    parts.add("(" + buildMetadataFilter(child, params) + ")");
                }
                return String.join(" OR ", parts);
            }
            case NOT:
                return "NOT (" + buildMetadataFilter(filter.children().get(0), params) + ")";
            default:
                throw new IllegalArgumentException("未知过滤类型: " + filter.type());
        }
    }

    private String buildCondition(MetadataFilter filter, List<Object> params) {
        String path = "metadata->>?";
        switch (filter.operator()) {
            case EQ:
                params.add(filter.key());
                params.add(String.valueOf(filter.value()));
                return path + " = ?";
            case NE:
                params.add(filter.key());
                params.add(String.valueOf(filter.value()));
                return path + " != ?";
            case GT:
                params.add(filter.key());
                params.add(String.valueOf(filter.value()));
                return "(" + path + ")::numeric > ?::numeric";
            case GTE:
                params.add(filter.key());
                params.add(String.valueOf(filter.value()));
                return "(" + path + ")::numeric >= ?::numeric";
            case LT:
                params.add(filter.key());
                params.add(String.valueOf(filter.value()));
                return "(" + path + ")::numeric < ?::numeric";
            case LTE:
                params.add(filter.key());
                params.add(String.valueOf(filter.value()));
                return "(" + path + ")::numeric <= ?::numeric";
            case EXISTS:
                params.add(filter.key());
                return "metadata ?? ?";
            case IN: {
                params.add(filter.key());
                List<?> values = (List<?>) filter.value();
                List<String> placeholders = new ArrayList<>();
                for (Object v : values) {
                    params.add(String.valueOf(v));
                    placeholders.add("?");
                }
                return path + " IN (" + String.join(",", placeholders) + ")";
            }
            default:
                throw new IllegalArgumentException("未知过滤操作符: " + filter.operator());
        }
    }

    // --- builder ---

    public static Builder builder(String jdbcUrl, int dimension) {
        return new Builder(jdbcUrl, dimension);
    }

    public static class Builder {
        private final String jdbcUrl;
        private final int dimension;
        private String username = "";
        private String password = "";
        private int poolSize = 5;
        private String table = DEFAULT_TABLE;

        private Builder(String jdbcUrl, int dimension) {
            if (jdbcUrl == null || jdbcUrl.isBlank()) throw new IllegalArgumentException("jdbcUrl 不能为空");
            if (dimension <= 0) throw new IllegalArgumentException("dimension 必须大于 0");
            this.jdbcUrl = jdbcUrl;
            this.dimension = dimension;
        }

        public Builder username(String username) { this.username = username; return this; }
        public Builder password(String password) { this.password = password; return this; }
        public Builder poolSize(int poolSize) { this.poolSize = poolSize; return this; }
        public Builder table(String table) { this.table = table; return this; }

        public PgvectorKnowledgeStore build() {
            return new PgvectorKnowledgeStore(this);
        }
    }
}
