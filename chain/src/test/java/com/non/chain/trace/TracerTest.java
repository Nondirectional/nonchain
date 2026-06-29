package com.non.chain.trace;

import org.junit.Test;

import java.util.Optional;

import static org.junit.Assert.*;

/**
 * Tracer 单元测试：startSpan 嵌套、push/pop、close 后 record、startChild 显式 parent、current 栈。
 */
public class TracerTest {

    @Test
    public void startSpanAtTopLevelCreatesNewRoot() {
        InMemoryTraceStore store = new InMemoryTraceStore();
        Tracer tracer = new Tracer(store);

        Tracer.ScopedSpan span = tracer.startSpan(SpanAttributes.SpanType.AGENT_RUN, "agent");
        try {
            // 顶层新根：parentSpanId=null，runtimeId=自身 spanId
            assertNull(span.span().parentSpanId());
            assertEquals(span.spanId(), span.runtimeId());
            // current 即它自己
            assertEquals(span.context(), Tracer.current());
        } finally {
            span.close();
        }

        // close 后 current 清空
        assertNull(Tracer.current());
        // 已 record 进 store
        Optional<Trace> trace = store.getTrace(span.runtimeId());
        assertTrue(trace.isPresent());
        assertEquals(1, trace.get().spans().size());
    }

    @Test
    public void nestedStartSpanInheritsParentAndRuntimeId() {
        InMemoryTraceStore store = new InMemoryTraceStore();
        Tracer tracer = new Tracer(store);

        Tracer.ScopedSpan root = tracer.startSpan("agent_run", "agent");
        String rootRuntime = root.runtimeId();
        try {
            Tracer.ScopedSpan child = tracer.startSpan("llm", "llm");
            try {
                // 子 span 继承 runtimeId，parent 指向 root
                assertEquals(rootRuntime, child.runtimeId());
                assertEquals(root.spanId(), child.span().parentSpanId());
                // current 栈顶现在是 child
                assertEquals(child.context(), Tracer.current());
            } finally {
                child.close();
            }
            // child close 后 current 回到 root
            assertEquals(root.context(), Tracer.current());
        } finally {
            root.close();
        }

        Trace trace = store.getTrace(rootRuntime).get();
        assertEquals(2, trace.spans().size());
        // 两 span 共享 runtimeId
        for (Span s : trace.spans()) {
            assertEquals(rootRuntime, s.runtimeId());
        }
    }

    @Test
    public void tryWithResourcesAutoClosesAndRecords() {
        InMemoryTraceStore store = new InMemoryTraceStore();
        Tracer tracer = new Tracer(store);

        String runtimeId;
        try (Tracer.ScopedSpan span = tracer.startSpan("agent_run", "agent")) {
            runtimeId = span.runtimeId();
            span.putAttribute(SpanAttributes.SYSTEM_PROMPT, "you are helpful");
        }

        // 自动 close → 已 record
        assertNull(Tracer.current());
        Trace trace = store.getTrace(runtimeId).get();
        Span recorded = trace.spans().get(0);
        assertEquals("ok", recorded.status());
        assertEquals("you are helpful", recorded.attributes().get(SpanAttributes.SYSTEM_PROMPT));
        assertTrue(recorded.endTimeMs() >= recorded.startTimeMs());
    }

    @Test
    public void markErrorWritesErrorStatus() {
        InMemoryTraceStore store = new InMemoryTraceStore();
        Tracer tracer = new Tracer(store);

        String runtimeId;
        try (Tracer.ScopedSpan span = tracer.startSpan("tool", "bad_tool")) {
            runtimeId = span.runtimeId();
            span.markErrorMessage("boom");
        }

        Span recorded = store.getTrace(runtimeId).get().spans().get(0);
        assertEquals("error", recorded.status());
        assertEquals("boom", recorded.error());
    }

    @Test
    public void startChildUsesExplicitParentAcrossThreads() throws Exception {
        InMemoryTraceStore store = new InMemoryTraceStore();
        Tracer tracer = new Tracer(store);

        // 在主线程建 root，捕获 context
        final Tracer.ScopedSpan root = tracer.startSpan("agent_run", "agent");
        final SpanContext captured = root.context();

        // 在另一个线程用 startChild（worker 线程 ThreadLocal 是空的，靠显式 ctx）
        Thread worker = new Thread(() -> {
            try (Tracer.ScopedSpan child = tracer.startChild(captured, "tool", "t1")) {
                // 跨线程：runtimeId 继承 root，parent 指向 root
                assertEquals(root.runtimeId(), child.runtimeId());
                assertEquals(root.spanId(), child.span().parentSpanId());
                child.putAttribute(SpanAttributes.TOOL_NAME, "t1");
            }
        });
        worker.start();
        worker.join();

        root.close();

        Trace trace = store.getTrace(root.runtimeId()).get();
        assertEquals(2, trace.spans().size());
        Span toolSpan = trace.spans().stream()
                .filter(s -> "tool".equals(s.type())).findFirst().get();
        assertEquals(root.spanId(), toolSpan.parentSpanId());
        assertEquals("t1", toolSpan.attributes().get(SpanAttributes.TOOL_NAME));
    }

    @Test
    public void currentSpanReturnsOpenedScope() {
        InMemoryTraceStore store = new InMemoryTraceStore();
        Tracer tracer = new Tracer(store);

        assertNull(Tracer.currentSpan());
        Tracer.ScopedSpan span = tracer.startSpan("llm", "llm");
        try {
            assertSame(span, Tracer.currentSpan());
        } finally {
            span.close();
        }
        assertNull(Tracer.currentSpan());
    }
}
