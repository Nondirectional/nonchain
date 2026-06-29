package com.non.chain.mysql;

import com.non.chain.trace.Span;
import com.non.chain.trace.SpanAttributes;
import com.non.chain.trace.Trace;
import com.non.chain.trace.TraceSerializer;
import com.non.chain.trace.TraceStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link TraceStore} 的 MySQL 持久化实现。
 *
 * <p>把执行链路的每个 span 落到 {@code trace_span} 表（schema 见 {@code trace_span.sql}），
 * 支持按 runtimeId 拉回完整 span 树。与 {@link MysqlChatMemoryStore} 同风格：
 * JDBC + DataSource，标准 SQL，异常包装为 {@link RuntimeException} + 中文消息。</p>
 *
 * <p><b>幂等写入</b>：{@code record} 在同一事务内「按 span_id 先 DELETE 再 INSERT」实现幂等
 * （重试不产生重复行），用可移植 SQL（H2/MySQL/PostgreSQL 通用，与 {@link com.non.chain.postgres.PostgresTraceStore} 一致）。
 * {@code attributes} 整体序列化为 JSON 存 {@code attributes_json} 列。</p>
 *
 * <pre>{@code
 * DataSource dataSource = ...; // HikariCP, Druid 等
 * TraceStore store = new MysqlTraceStore(dataSource);
 *
 * Agent agent = Agent.builder(llm, registry).trace(store).build();
 * ChatResult r = agent.run("你好");
 * Trace trace = store.getTrace(r.runtimeId()).orElseThrow();
 * }</pre>
 */
public class MysqlTraceStore implements TraceStore {

    private static final String TABLE_NAME = "trace_span";

    private static final String DELETE_SPAN =
            "DELETE FROM " + TABLE_NAME + " WHERE span_id = ?";

    private static final String INSERT_SPAN =
            "INSERT INTO " + TABLE_NAME +
                    " (runtime_id, span_id, parent_span_id, type, name, start_time_ms, end_time_ms," +
                    " status, error, attributes_json)" +
                    " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String GET_TRACE =
            "SELECT span_id, parent_span_id, runtime_id, type, name," +
                    " start_time_ms, end_time_ms, status, error, attributes_json" +
                    " FROM " + TABLE_NAME + " WHERE runtime_id = ? ORDER BY start_time_ms ASC";

    private final DataSource dataSource;

    public MysqlTraceStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void record(Span span) {
        if (span == null) {
            return;
        }
        // 幂等：同事务内先按 span_id 删除再插入（可移植 SQL，H2/MySQL/PostgreSQL 通用）
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(DELETE_SPAN)) {
                    ps.setString(1, span.spanId());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(INSERT_SPAN)) {
                    bindSpan(ps, span);
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException("记录 trace span 失败: " + span.spanId(), e);
        }
    }

    private static void bindSpan(PreparedStatement ps, Span span) throws SQLException {
        ps.setString(1, span.runtimeId());
        ps.setString(2, span.spanId());
        ps.setString(3, span.parentSpanId());
        ps.setString(4, span.type());
        ps.setString(5, span.name() != null ? span.name() : "");
        ps.setLong(6, span.startTimeMs());
        ps.setLong(7, span.endTimeMs());
        ps.setString(8, span.status() != null ? span.status() : "ok");
        ps.setString(9, span.error());
        ps.setString(10, TraceSerializer.serializeAttributes(span.attributes()));
    }

    @Override
    public Optional<Trace> getTrace(String runtimeId) {
        if (runtimeId == null) {
            return Optional.empty();
        }
        List<Span> spans = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(GET_TRACE)) {
            ps.setString(1, runtimeId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    spans.add(readSpan(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("获取 trace 失败: " + runtimeId, e);
        }
        if (spans.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Trace(runtimeId, extractConversationId(spans), spans));
    }

    private static Span readSpan(ResultSet rs) throws SQLException {
        String spanId = rs.getString("span_id");
        String parent = rs.getString("parent_span_id");
        String runtimeId = rs.getString("runtime_id");
        String type = rs.getString("type");
        String name = rs.getString("name");
        long start = rs.getLong("start_time_ms");
        long end = rs.getLong("end_time_ms");
        String status = rs.getString("status");
        String error = rs.getString("error");
        String attrsJson = rs.getString("attributes_json");
        return Span.restored(spanId, parent, runtimeId, type, name,
                start, end, status, error,
                TraceSerializer.deserializeAttributes(attrsJson));
    }

    /** 从 root span 的 conversation_id 载荷提取 conversationId（可空，与 InMemoryTraceStore 一致）。 */
    private static String extractConversationId(List<Span> spans) {
        for (Span s : spans) {
            if (s.parentSpanId() == null) {
                Object cid = s.attributes().get(SpanAttributes.CONVERSATION_ID);
                if (cid != null) {
                    return cid.toString();
                }
                break;
            }
        }
        return null;
    }
}
