package com.non.chain.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.non.chain.Message;
import com.non.chain.tool.ToolCall;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;

/**
 * Trace JSON 序列化往返测试：toJson/fromJson 等价，含各 type attributes。
 */
public class TraceSerializationTest {

    private final ObjectMapper MAPPER = new ObjectMapper();

    private Trace buildSampleTrace() {
        String rid = "rt-serialization";
        Span root = new Span(rid, null, rid, SpanAttributes.SpanType.AGENT_RUN, "agent", 1000L);
        root.putAttribute(SpanAttributes.SYSTEM_PROMPT, "you are helpful");
        root.putAttribute(SpanAttributes.MAX_ITERATIONS, 10);

        Span llm = new Span("llm-1", rid, rid, SpanAttributes.SpanType.LLM, "llm", 1100L);
        llm.putAttribute(SpanAttributes.MESSAGES, Arrays.asList(
                Message.system("you are helpful"),
                Message.user("你好")
        ));
        llm.putAttribute(SpanAttributes.RESULT_CONTENT, "你好！");
        llm.putAttribute(SpanAttributes.PROMPT_TOKENS, 12L);
        llm.putAttribute(SpanAttributes.TOTAL_TOKENS, 20L);

        Span tool = new Span("tool-1", llm.spanId(), rid, SpanAttributes.SpanType.TOOL, "search", 1200L);
        tool.putAttribute(SpanAttributes.TOOL_CALL_ID, "call-1");
        tool.putAttribute(SpanAttributes.ARGUMENTS, "{\"q\":\"x\"}");
        tool.putAttribute(SpanAttributes.RESULT_TOOL_CALLS, Arrays.asList(
                new ToolCall("call-1", "search", "{\"q\":\"x\"}")
        ));
        tool.putAttribute(SpanAttributes.IS_ERROR, false);

        root.end(2000L);
        llm.end(1180L);
        tool.end(1250L);
        return new Trace(rid, "conv-1", Arrays.asList(root, llm, tool));
    }

    @Test
    public void toJsonHasStableStructure() throws Exception {
        Trace trace = buildSampleTrace();
        String json = trace.toJson();

        JsonNode root = MAPPER.readTree(json);
        assertEquals("rt-serialization", root.get("runtimeId").asText());
        assertEquals("conv-1", root.get("conversationId").asText());
        assertTrue(root.get("spans").isArray());
        assertEquals(3, root.get("spans").size());
        // spans 按 startTime 排序
        assertEquals("agent_run", root.get("spans").get(0).get("type").asText());
        assertEquals("llm", root.get("spans").get(1).get("type").asText());
        assertEquals("tool", root.get("spans").get(2).get("type").asText());
    }

    @Test
    public void messagesAttributeSerializedAsStructuredObjects() throws Exception {
        Trace trace = buildSampleTrace();
        JsonNode spans = MAPPER.readTree(trace.toJson()).get("spans");
        JsonNode llmSpan = spans.get(1); // llm 排第二
        JsonNode messages = llmSpan.get("attributes").get(SpanAttributes.MESSAGES);
        assertTrue(messages.isArray());
        assertEquals("system", messages.get(0).get("role").asText());
        assertEquals("you are helpful", messages.get(0).get("content").asText());
        assertEquals("user", messages.get(1).get("role").asText());
    }

    @Test
    public void numberAndBooleanAttributesPreserved() throws Exception {
        Trace trace = buildSampleTrace();
        JsonNode spans = MAPPER.readTree(trace.toJson()).get("spans");
        JsonNode rootSpan = spans.get(0);
        assertEquals(10, rootSpan.get("attributes").get(SpanAttributes.MAX_ITERATIONS).asInt());

        JsonNode toolSpan = spans.get(2);
        assertFalse(toolSpan.get("attributes").get(SpanAttributes.IS_ERROR).asBoolean());
        assertEquals(12, spans.get(1).get("attributes").get(SpanAttributes.PROMPT_TOKENS).asInt());
    }

    @Test
    public void roundTripPreservesStructure() {
        Trace original = buildSampleTrace();
        String json = original.toJson();
        Trace restored = Trace.fromJson(json);

        assertEquals(original.runtimeId(), restored.runtimeId());
        assertEquals(original.conversationId(), restored.conversationId());
        assertEquals(original.spans().size(), restored.spans().size());

        // 逐 span 校验骨架字段
        for (int i = 0; i < original.spans().size(); i++) {
            Span a = original.spans().get(i);
            Span b = restored.spans().get(i);
            assertEquals(a.spanId(), b.spanId());
            assertEquals(a.parentSpanId(), b.parentSpanId());
            assertEquals(a.runtimeId(), b.runtimeId());
            assertEquals(a.type(), b.type());
            assertEquals(a.startTimeMs(), b.startTimeMs());
            assertEquals(a.endTimeMs(), b.endTimeMs());
            assertEquals(a.status(), b.status());
        }
        // 往返后再 toJson 应等价
        assertEquals(json, restored.toJson());
    }

    @Test
    public void errorSpanRoundTrip() {
        String rid = "rt-err";
        Span span = new Span("s1", null, rid, SpanAttributes.SpanType.LLM, "llm", 1L);
        span.endWithError(2L, "connection refused");
        Trace trace = new Trace(rid, null, List.of(span));

        Trace restored = Trace.fromJson(trace.toJson());
        Span s = restored.spans().get(0);
        assertEquals("error", s.status());
        assertEquals("connection refused", s.error());
    }

    @Test
    public void nullConversationIdSerialized() {
        String rid = "rt-null-cid";
        Span span = new Span(rid, null, rid, SpanAttributes.SpanType.GRAPH_RUN, "flow", 1L);
        span.end(2L);
        Trace trace = new Trace(rid, null, List.of(span));

        Trace restored = Trace.fromJson(trace.toJson());
        assertNull(restored.conversationId());
    }

    @Test
    public void attributesCodecRoundTripForPersistence() {
        // 持久化 store 复用 serializeAttributes/deserializeAttributes：往返须等价
        java.util.Map<String, Object> attrs = new java.util.LinkedHashMap<>();
        attrs.put(SpanAttributes.RESULT_CONTENT, "hello");
        attrs.put(SpanAttributes.MESSAGES, Arrays.asList(Message.user("hi")));
        attrs.put(SpanAttributes.PROMPT_TOKENS, 42L);
        attrs.put(SpanAttributes.IS_ERROR, false);

        String json = TraceSerializer.serializeAttributes(attrs);
        java.util.Map<String, Object> restored = TraceSerializer.deserializeAttributes(json);

        assertEquals("hello", restored.get(SpanAttributes.RESULT_CONTENT));
        assertEquals(false, restored.get(SpanAttributes.IS_ERROR));
        assertEquals(42, ((Number) restored.get(SpanAttributes.PROMPT_TOKENS)).intValue());
        assertTrue(restored.get(SpanAttributes.MESSAGES) instanceof java.util.List);
    }

    @Test
    public void deserializeEmptyAttributes() {
        assertTrue(TraceSerializer.deserializeAttributes(null).isEmpty());
        assertTrue(TraceSerializer.deserializeAttributes("").isEmpty());
        assertTrue(TraceSerializer.deserializeAttributes("   ").isEmpty());
    }
}
