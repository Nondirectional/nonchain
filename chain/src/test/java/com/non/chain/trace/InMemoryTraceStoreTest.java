package com.non.chain.trace;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * InMemoryTraceStore 单元测试：record/getTrace 往返、LRU 淘汰、并发安全。
 */
public class InMemoryTraceStoreTest {

    private Span newRootSpan(String runtimeId, String type, String name) {
        return new Span(runtimeId, null, runtimeId, type, name, System.currentTimeMillis());
    }

    @Test
    public void recordAndGetTraceRoundTrip() {
        InMemoryTraceStore store = new InMemoryTraceStore();
        String rid = UUID.randomUUID().toString();

        Span root = new Span(rid, null, rid, "agent_run", "agent", 1000L);
        Span child = new Span(UUID.randomUUID().toString(), root.spanId(), rid, "llm", "llm", 1100L);
        root.putAttribute(SpanAttributes.SYSTEM_PROMPT, "sys");
        child.putAttribute(SpanAttributes.RESULT_CONTENT, "hello");

        store.record(child); // 故意乱序 record
        store.record(root);

        Optional<Trace> opt = store.getTrace(rid);
        assertTrue(opt.isPresent());
        Trace trace = opt.get();
        assertEquals(rid, trace.runtimeId());
        assertEquals(2, trace.spans().size());
        // 按 startTime 排序
        assertEquals("agent_run", trace.spans().get(0).type());
        assertEquals("llm", trace.spans().get(1).type());
        // 系统提示载荷保留
        assertEquals("sys", trace.spans().get(0).attributes().get(SpanAttributes.SYSTEM_PROMPT));
    }

    @Test
    public void getTraceReturnsEmptyForUnknown() {
        InMemoryTraceStore store = new InMemoryTraceStore();
        assertFalse(store.getTrace("nope").isPresent());
        assertFalse(store.getTrace(null).isPresent());
    }

    @Test
    public void conversationIdExtractedFromRootSpan() {
        InMemoryTraceStore store = new InMemoryTraceStore();
        String rid = UUID.randomUUID().toString();
        Span root = newRootSpan(rid, "agent_run", "agent");
        root.putAttribute(SpanAttributes.CONVERSATION_ID, "conv-1");
        store.record(root);

        Trace trace = store.getTrace(rid).get();
        assertEquals("conv-1", trace.conversationId());
    }

    @Test
    public void lruEvictionDropsOldestTree() {
        InMemoryTraceStore store = new InMemoryTraceStore(2);
        String r1 = "rt-1", r2 = "rt-2", r3 = "rt-3";

        store.record(newRootSpan(r1, "agent_run", "a"));
        store.record(newRootSpan(r2, "agent_run", "b"));
        // 访问 r1，使其成为最近使用 → r2 成最久未访问
        store.getTrace(r1);
        store.record(newRootSpan(r3, "agent_run", "c"));

        assertEquals(2, store.size());
        // r2 被淘汰
        assertFalse(store.getTrace(r2).isPresent());
        assertTrue(store.getTrace(r1).isPresent());
        assertTrue(store.getTrace(r3).isPresent());
    }

    @Test
    public void concurrentRecordSameRuntimeIdIsThreadSafe() throws Exception {
        InMemoryTraceStore store = new InMemoryTraceStore();
        String rid = "shared-rt";
        store.record(newRootSpan(rid, "agent_run", "agent"));

        int threads = 8;
        int perThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    for (int j = 0; j < perThread; j++) {
                        Span s = new Span(UUID.randomUUID().toString(), rid, rid, "tool", "t" + j,
                                System.currentTimeMillis());
                        store.record(s);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        pool.shutdown();
        assertTrue(latch.await(30, TimeUnit.SECONDS));

        Trace trace = store.getTrace(rid).get();
        // root + threads*perThread 个 tool span，无丢失无并发异常
        assertEquals(1 + threads * perThread, trace.spans().size());
    }

    @Test
    public void capacityMustBePositive() {
        try {
            new InMemoryTraceStore(0);
            fail("应抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void recordNullIsNoop() {
        InMemoryTraceStore store = new InMemoryTraceStore();
        store.record(null); // 不应抛
        assertEquals(0, store.size());
    }

    @Test
    public void multipleDistinctTrees() {
        InMemoryTraceStore store = new InMemoryTraceStore();
        List<String> rids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String rid = "multi-" + i;
            rids.add(rid);
            store.record(newRootSpan(rid, "agent_run", "agent"));
        }
        assertEquals(5, store.size());
        for (String rid : rids) {
            assertTrue(store.getTrace(rid).isPresent());
        }
    }
}
