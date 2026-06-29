package com.non.chain.mysql;

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
 * {@link MysqlTraceStore} 单元测试（H2 内存库 MySQL 模式模拟）。
 *
 * <p>覆盖：record/getTrace 往返、attributes（含 Message 载荷）往返、
 * 幂等 upsert（重试不重复）、多 runtime 隔离、JSON 序列化稳定性、未知 id 返回空。</p>
 *
 * <p>构造已定稿的 span 用 {@link Span#restored}（持久化场景，public API），
 * 而非 trace 包内的构建期 {@code new Span + end()} 流程。</p>
 */
public class MysqlTraceStoreTest {

    private DataSource dataSource;
    private TraceStore store;

    @Before
    public void setUp() throws Exception {
        dataSource = createTestDataSource();
        createTable(dataSource);
        store = new MysqlTraceStore(dataSource);
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
        String rid = "rt-mysql-1";
        Span root = Span.restored(rid, null, rid, SpanAttributes.SpanType.AGENT_RUN, "agent",
                1000L, 2000L, "ok", null, attrs(SpanAttributes.SYSTEM_PROMPT, "你是助手"));
        Span llm = Span.restored("s-llm", rid, rid, SpanAttributes.SpanType.LLM, "llm",
                1100L, 1180L, "ok", null, attrs(SpanAttributes.PROMPT_TOKENS, 42L));
        Span tool = Span.restored("s-tool", "s-llm", rid, SpanAttributes.SpanType.TOOL, "search",
                1200L, 1250L, "ok", null, attrs(SpanAttributes.RESULT, "结果", SpanAttributes.IS_ERROR, false));

        store.record(tool);
        store.record(root);
        store.record(llm); // 乱序 record

        Optional<Trace> opt = store.getTrace(rid);
        assertTrue(opt.isPresent());
        Trace trace = opt.get();
        assertEquals(rid, trace.runtimeId());
        assertEquals(3, trace.spans().size());
        // 按 start_time 排序
        assertEquals("agent_run", trace.spans().get(0).type());
        assertEquals("llm", trace.spans().get(1).type());
        assertEquals("tool", trace.spans().get(2).type());
        // tool parent 应是 llm
        assertEquals("s-llm", trace.spans().get(2).parentSpanId());
    }

    @Test
    public void attributesWithMessagesRoundTrip() {
        String rid = "rt-msg";
        Span llm = Span.restored("s1", null, rid, SpanAttributes.SpanType.LLM, "llm", 1L, 2L, "ok", null,
                attrs(SpanAttributes.MESSAGES, Arrays.asList(Message.system("sys"), Message.user("你好")),
                        SpanAttributes.RESULT_CONTENT, "回复",
                        SpanAttributes.TOTAL_TOKENS, 99L));
        store.record(llm);

        Span restored = store.getTrace(rid).get().spans().get(0);
        assertEquals("回复", restored.attributes().get(SpanAttributes.RESULT_CONTENT));
        assertEquals(99, ((Number) restored.attributes().get(SpanAttributes.TOTAL_TOKENS)).intValue());
        Object msgs = restored.attributes().get(SpanAttributes.MESSAGES);
        assertTrue("messages 应还原为 List", msgs instanceof java.util.List);
        assertEquals(2, ((java.util.List<?>) msgs).size());
    }

    @Test
    public void errorSpanRoundTrip() {
        String rid = "rt-err";
        Span span = Span.restored("s1", null, rid, SpanAttributes.SpanType.TOOL, "bad", 1L, 2L,
                "error", "boom", attrs());
        store.record(span);

        Span restored = store.getTrace(rid).get().spans().get(0);
        assertEquals("error", restored.status());
        assertEquals("boom", restored.error());
    }

    @Test
    public void recordIsIdempotent() {
        // ON DUPLICATE KEY UPDATE：重复 record 同一 span_id 不应产生重复行
        String rid = "rt-idem";
        Span span = Span.restored("s1", null, rid, SpanAttributes.SpanType.AGENT_RUN, "agent", 1L, 2L,
                "ok", null, attrs());
        store.record(span);
        store.record(span);
        store.record(span);

        Trace trace = store.getTrace(rid).get();
        assertEquals("幂等 upsert 应只有一行", 1, trace.spans().size());
    }

    @Test
    public void multipleRuntimeIdsIsolated() {
        Span s1 = Span.restored("rt-1", null, "rt-1", SpanAttributes.SpanType.AGENT_RUN, "a", 1L, 2L,
                "ok", null, attrs());
        Span s2 = Span.restored("rt-2", null, "rt-2", SpanAttributes.SpanType.GRAPH_RUN, "g", 1L, 2L,
                "ok", null, attrs());
        store.record(s1);
        store.record(s2);

        assertTrue(store.getTrace("rt-1").isPresent());
        assertTrue(store.getTrace("rt-2").isPresent());
        assertEquals(1, store.getTrace("rt-1").get().spans().size());
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
        Span root = Span.restored(rid, null, rid, SpanAttributes.SpanType.AGENT_RUN, "agent", 1L, 2L,
                "ok", null, attrs(SpanAttributes.SYSTEM_PROMPT, "sys"));
        store.record(root);

        Trace trace = store.getTrace(rid).get();
        String json = trace.toJson();
        Trace restored = Trace.fromJson(json);
        assertEquals(trace.runtimeId(), restored.runtimeId());
        assertEquals(1, restored.spans().size());
        assertEquals("sys", restored.spans().get(0).attributes().get(SpanAttributes.SYSTEM_PROMPT));
    }

    @Test
    public void recordNullIsNoop() {
        store.record(null); // 不应抛
    }

    @Test
    public void conversationIdExtractedFromRootSpan() {
        String rid = "rt-cid";
        Span root = Span.restored(rid, null, rid, SpanAttributes.SpanType.AGENT_RUN, "agent", 1L, 2L,
                "ok", null, attrs(SpanAttributes.CONVERSATION_ID, "conv-9"));
        store.record(root);

        assertEquals("conv-9", store.getTrace(rid).get().conversationId());
    }

    // ---- Helper: H2 内存库 MySQL 模式 ----

    private static DataSource createTestDataSource() {
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        // SQL 已写成可移植形式（DELETE+INSERT 幂等），H2 默认模式即可验证逻辑
        ds.setURL("jdbc:h2:mem:tracetest;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }

    private static void createTable(DataSource dataSource) throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS trace_span ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                    + "runtime_id VARCHAR(64) NOT NULL, "
                    + "span_id VARCHAR(64) NOT NULL, "
                    + "parent_span_id VARCHAR(64), "
                    + "type VARCHAR(32) NOT NULL, "
                    + "name VARCHAR(255) NOT NULL, "
                    + "start_time_ms BIGINT NOT NULL, "
                    + "end_time_ms BIGINT NOT NULL, "
                    + "status VARCHAR(16) NOT NULL, "
                    + "error TEXT, "
                    + "attributes_json TEXT, "
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                    + "CONSTRAINT uk_trace_span_id UNIQUE (span_id)"
                    + ")");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_trace_runtime ON trace_span (runtime_id)");
        }
    }
}
