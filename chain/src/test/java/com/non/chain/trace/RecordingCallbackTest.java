package com.non.chain.trace;

import com.non.chain.ChatResult;
import com.non.chain.Message;
import com.non.chain.callback.event.LlmCompleteEvent;
import com.non.chain.callback.event.LlmStartEvent;
import com.non.chain.callback.event.ToolCompleteEvent;
import com.non.chain.callback.event.ToolStartEvent;
import com.non.chain.tool.ToolCall;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

/**
 * RecordingCallback 单元测试（重构后：纯载荷填充器）。
 * RecordingCallback 不再建/关 span，只把事件载荷填进当前 span。
 * 测试用 Tracer 先建好 llm/tool span，再触发 callback 验证填充。
 */
public class RecordingCallbackTest {

    @Test
    public void onLlmStartFillsMessagesIntoCurrentLlmSpan() {
        InMemoryTraceStore store = new InMemoryTraceStore();
        Tracer tracer = new Tracer(store);
        RecordingCallback cb = new RecordingCallback();

        try (Tracer.ScopedSpan llm = tracer.startSpan(SpanAttributes.SpanType.LLM, "llm")) {
            cb.onLlmStart(new LlmStartEvent("tid",
                    Arrays.asList(Message.user("hi")), Collections.emptyList()));
            // 当前 llm span 的 attributes 应含 messages
            assertEquals(1, ((java.util.List<?>) llm.span().attributes().get(SpanAttributes.MESSAGES)).size());
        }
    }

    @Test
    public void onLlmCompleteFillsResultAndLatency() {
        InMemoryTraceStore store = new InMemoryTraceStore();
        Tracer tracer = new Tracer(store);
        RecordingCallback cb = new RecordingCallback();

        String runtimeId;
        try (Tracer.ScopedSpan llm = tracer.startSpan(SpanAttributes.SpanType.LLM, "llm")) {
            runtimeId = llm.runtimeId();
            cb.onLlmStart(new LlmStartEvent("tid", Arrays.asList(Message.user("hi")), Collections.emptyList()));
            cb.onLlmComplete(new LlmCompleteEvent("tid", new ChatResult("hello", null), null, 42L));
        }

        Span recorded = store.getTrace(runtimeId).get().spans().get(0);
        assertEquals("hello", recorded.attributes().get(SpanAttributes.RESULT_CONTENT));
        assertEquals(42L, recorded.attributes().get(SpanAttributes.LATENCY_MS));
    }

    @Test
    public void onToolStartFillsToolAttributesIntoCurrentToolSpan() {
        InMemoryTraceStore store = new InMemoryTraceStore();
        Tracer tracer = new Tracer(store);
        RecordingCallback cb = new RecordingCallback();

        String runtimeId;
        try (Tracer.ScopedSpan llm = tracer.startSpan(SpanAttributes.SpanType.LLM, "llm")) {
            runtimeId = llm.runtimeId();
            try (Tracer.ScopedSpan tool = tracer.startSpan(SpanAttributes.SpanType.TOOL, "search")) {
                cb.onToolStart(new ToolStartEvent("tid", new ToolCall("c1", "search", "{\"q\":\"x\"}")));
                cb.onToolComplete(new ToolCompleteEvent("tid", "c1", "search", "result-text", 5L));
            }
        }

        Span toolSpan = store.getTrace(runtimeId).get().spans().stream()
                .filter(s -> "tool".equals(s.type())).findFirst().get();
        assertEquals("c1", toolSpan.attributes().get(SpanAttributes.TOOL_CALL_ID));
        assertEquals("search", toolSpan.attributes().get(SpanAttributes.TOOL_NAME));
        assertEquals("result-text", toolSpan.attributes().get(SpanAttributes.RESULT));
        assertEquals(false, toolSpan.attributes().get(SpanAttributes.IS_ERROR));
        // tool 的 parent 应是 llm
        Span llmSpan = store.getTrace(runtimeId).get().spans().stream()
                .filter(s -> "llm".equals(s.type())).findFirst().get();
        assertEquals(llmSpan.spanId(), toolSpan.parentSpanId());
    }

    @Test
    public void callbackIsSafeWhenNoCurrentSpan() {
        // 没有 span 在 current 栈时，callback 不应抛
        RecordingCallback cb = new RecordingCallback();
        cb.onLlmStart(new LlmStartEvent("tid", Arrays.asList(Message.user("hi")), Collections.emptyList()));
        cb.onLlmComplete(new LlmCompleteEvent("tid", new ChatResult("ok", null), null, 1L));
        cb.onToolStart(new ToolStartEvent("tid", new ToolCall("c1", "t", "{}")));
        cb.onToolComplete(new ToolCompleteEvent("tid", "c1", "t", "r", 1L));
        // 到这里没抛即通过
    }
}
