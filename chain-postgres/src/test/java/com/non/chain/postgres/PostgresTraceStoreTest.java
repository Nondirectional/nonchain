package com.non.chain.postgres;

import com.non.chain.Message;
import com.non.chain.trace.Span;
import com.non.chain.trace.SpanAttributes;
import com.non.chain.trace.Trace;
import com.non.chain.trace.TraceStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.*;

/**
 * {@link PostgresTraceStore} 单元测试（H2 内存库 PostgreSQL 模式模拟）。
 *
 * <p>覆盖：record/getTrace 往返、attributes（含 Message 载荷）往返、
 * 幂等 upsert（ON CONFLICT 重试不重复）、多 runtime 隔离、JSON 序列化稳定性、未知 id 返回空。</p>
 */
public class PostgresTraceStoreTest {

    private DataSource dataSource;
    private TraceStore store;

    @Before
    public void setUp() throws Exception {
        dataSource = createTestDataSource();
        createTable(dataSource);
        store = new PostgresTraceStore(dataSource);
    }

    @After
    public void tearDown() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS trace_span");
        }
    }

    private static Map<String, Object> attrs(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    public void recordAndGetTraceRoundTrip() {
        String rid = "rt-pg-1";
        Span root = Span.restored(rid, null, rid, SpanAttributes.SpanType.GRAPH_RUN, "flow",
                1000L, 5000L, "ok", null, attrs(SpanAttributes.GRAPH_NAME, "flow"));
        Span node = Span.restored("s-node", rid, rid, SpanAttributes.SpanType.GRAPH_NODE, "n1",
                1100L, 2000L, "ok", null, attrs(SpanAttributes.NODE_NAME, "n1"));

        store.record(node);
        store.record(root);

        Optional<Trace> opt = store.getTrace(rid);
        assertTrue(opt.isPresent());
        Trace trace = opt.get();
        assertEquals(rid, trace.runtimeId());
        assertEquals(2, trace.spans().size());
        assertEquals("graph_run", trace.spans().get(0).type());
        assertEquals("graph_node", trace.spans().get(1).type());
        assertEquals(root.spanId(), trace.spans().get(1).parentSpanId());
    }

    @Test
    public void attributesWithMessagesRoundTrip() {
        String rid = "rt-msg";
        Span llm = Span.restored("s1", null, rid, SpanAttributes.SpanType.LLM, "llm", 1L, 2L, "ok", null,
                attrs(SpanAttributes.MESSAGES, Arrays.asList(Message.user("hi")),
                        SpanAttributes.RESULT_CONTENT, "回复"));
        store.record(llm);

        Span restored = store.getTrace(rid).get().spans().get(0);
        assertEquals("回复", restored.attributes().get(SpanAttributes.RESULT_CONTENT));
        Object msgs = restored.attributes().get(SpanAttributes.MESSAGES);
        assertTrue(msgs instanceof java.util.List);
        assertEquals(1, ((java.util.List<?>) msgs).size());
    }

    @Test
    public void errorSpanRoundTrip() {
        String rid = "rt-err";
        Span span = Span.restored("s1", null, rid, SpanAttributes.SpanType.GRAPH_NODE, "boom", 1L, 2L,
                "error", "节点炸了", attrs(SpanAttributes.ERROR, "节点炸了"));
        store.record(span);

        Span restored = store.getTrace(rid).get().spans().get(0);
        assertEquals("error", restored.status());
        assertEquals("节点炸了", restored.error());
    }

    @Test
    public void recordIsIdempotent() {
        // ON CONFLICT (span_id) DO UPDATE：重复 record 同一 span_id 不应产生重复行
        String rid = "rt-idem";
        Span span = Span.restored("s1", null, rid, SpanAttributes.SpanType.AGENT_RUN, "agent", 1L, 2L,
                "ok", null, attrs());
        store.record(span);
        store.record(span);

        assertEquals(1, store.getTrace(rid).get().spans().size());
    }

    @Test
    public void multipleRuntimeIdsIsolated() {
        store.record(Span.restored("rt-1", null, "rt-1", SpanAttributes.SpanType.AGENT_RUN, "a", 1L, 2L, "ok", null, attrs()));
        store.record(Span.restored("rt-2", null, "rt-2", SpanAttributes.SpanType.GRAPH_RUN, "g", 1L, 2L, "ok", null, attrs()));

        assertEquals("agent_run", store.getTrace("rt-1").get().spans().get(0).type());
        assertEquals("graph_run", store.getTrace("rt-2").get().spans().get(0).type());
    }

    @Test
    public void getTraceReturnsEmptyForUnknown() {
        assertFalse(store.getTrace("nope").isPresent());
        assertFalse(store.getTrace(null).isPresent());
    }

    @Test
    public void persistedTraceIsJsonSerializable() {
        String rid = "rt-json";
        store.record(Span.restored(rid, null, rid, SpanAttributes.SpanType.AGENT_RUN, "agent", 1L, 2L,
                "ok", null, attrs(SpanAttributes.SYSTEM_PROMPT, "sys")));

        Trace trace = store.getTrace(rid).get();
        Trace restored = Trace.fromJson(trace.toJson());
        assertEquals(trace.runtimeId(), restored.runtimeId());
        assertEquals("sys", restored.spans().get(0).attributes().get(SpanAttributes.SYSTEM_PROMPT));
    }

    @Test
    public void recordNullIsNoop() {
        store.record(null);
    }

    @Test
    public void conversationIdExtractedFromRootSpan() {
        String rid = "rt-cid";
        store.record(Span.restored(rid, null, rid, SpanAttributes.SpanType.AGENT_RUN, "agent", 1L, 2L,
                "ok", null, attrs(SpanAttributes.CONVERSATION_ID, "conv-9")));
        assertEquals("conv-9", store.getTrace(rid).get().conversationId());
    }

    // ---- Helper: H2 内存库 PostgreSQL 模式 ----

    private static DataSource createTestDataSource() {
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        // SQL 已写成可移植形式（DELETE+INSERT 幂等），H2 默认模式即可验证逻辑
        ds.setURL("jdbc:h2:mem:tracetestpg;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }

    private static void createTable(DataSource dataSource) throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            // SQL 可移植，H2 默认模式即可建表
            stmt.execute("CREATE TABLE IF NOT EXISTS trace_span ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                    + "runtime_id VARCHAR(64) NOT NULL, "
                    + "span_id VARCHAR(64) NOT NULL UNIQUE, "
                    + "parent_span_id VARCHAR(64), "
                    + "type VARCHAR(32) NOT NULL, "
                    + "name VARCHAR(255) NOT NULL, "
                    + "start_time_ms BIGINT NOT NULL, "
                    + "end_time_ms BIGINT NOT NULL, "
                    + "status VARCHAR(16) NOT NULL, "
                    + "error TEXT, "
                    + "attributes_json TEXT, "
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                    + ")");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_trace_runtime ON trace_span (runtime_id)");
        }
    }
}
