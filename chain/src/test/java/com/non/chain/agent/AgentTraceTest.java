package com.non.chain.agent;

import com.non.chain.ChatChunk;
import com.non.chain.ChatResult;
import com.non.chain.Message;
import com.non.chain.provider.LLM;
import com.non.chain.tool.ToolCall;
import com.non.chain.tool.ToolRegistry;
import com.non.chain.trace.InMemoryTraceStore;
import com.non.chain.trace.Span;
import com.non.chain.trace.SpanAttributes;
import com.non.chain.trace.Trace;
import com.non.chain.trace.TraceRuntimeIds;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.*;

/**
 * Agent 执行链路遥测测试：单 Agent run → 树结构正确、runtimeId 回填、工具 span 父子关系。
 */
public class AgentTraceTest {

    /** 可编程 mock LLM：按预设顺序返回结果（复用 SubAgentTest 风格，独立实例避免跨测试串扰）。 */
    static class MockLLM implements LLM {
        private final List<ChatResult> responses;
        private int callIndex = 0;
        MockLLM(List<ChatResult> responses) { this.responses = responses; }

        @Override public ChatResult chat(String s, String u, com.non.chain.OutputFormat f) { throw new UnsupportedOperationException(); }
        @Override public ChatResult chat(List<Message> m, com.non.chain.OutputFormat f) { throw new UnsupportedOperationException(); }
        @Override public ChatResult chat(String s, String u, List<com.non.chain.tool.Tool> t, com.non.chain.OutputFormat f) { throw new UnsupportedOperationException(); }
        @Override public ChatResult chat(List<Message> messages, List<com.non.chain.tool.Tool> tools, com.non.chain.OutputFormat f) {
            return responses.get(callIndex++);
        }
        @Override public ChatResult streamChat(String s, String u, com.non.chain.OutputFormat f, java.util.function.Consumer<ChatChunk> c) { throw new UnsupportedOperationException(); }
        @Override public ChatResult streamChat(List<Message> m, com.non.chain.OutputFormat f, java.util.function.Consumer<ChatChunk> c) { throw new UnsupportedOperationException(); }
        @Override public ChatResult streamChat(String s, String u, List<com.non.chain.tool.Tool> t, com.non.chain.OutputFormat f, java.util.function.Consumer<ChatChunk> c) { throw new UnsupportedOperationException(); }
        @Override public ChatResult streamChat(List<Message> messages, List<com.non.chain.tool.Tool> tools, com.non.chain.OutputFormat f, java.util.function.Consumer<ChatChunk> callback) {
            ChatResult response = responses.get(callIndex++);
            if (response.content() != null && !response.content().isEmpty()) {
                callback.accept(new ChatChunk(response.content(), null, null, null));
            }
            if (response.hasToolCalls()) {
                List<ChatChunk.DeltaToolCall> deltas = new ArrayList<>();
                for (int i = 0; i < response.toolCalls().size(); i++) {
                    ToolCall tc = response.toolCalls().get(i);
                    deltas.add(new ChatChunk.DeltaToolCall(i, tc.id(), tc.name(), tc.arguments()));
                }
                callback.accept(new ChatChunk(null, null, deltas, null));
            }
            callback.accept(new ChatChunk(null, null, null, "stop"));
            return response;
        }
    }

    private static ChatResult reply(String content) { return new ChatResult(content, null); }
    private static ChatResult toolCall(String callId, String toolName, String argsJson) {
        return new ChatResult("", null, Collections.singletonList(new ToolCall(callId, toolName, argsJson)));
    }

    @Test
    public void singleAgentRunProducesAgentRunRootWithLlmAndToolSpans() {
        InMemoryTraceStore store = new InMemoryTraceStore();
        MockLLM llm = new MockLLM(Arrays.asList(
                toolCall("c1", "search", "{\"q\":\"x\"}"),
                reply("最终答案")
        ));
        ToolRegistry registry = new ToolRegistry();
        registry.register("search", "搜索").handle(args -> "搜索结果");

        Agent agent = Agent.builder(llm, registry)
                .systemPrompt("你是助手")
                .trace(store)
                .build();

        ChatResult result = agent.run("查询");

        // 成功路径：runtimeId 回填到 ChatResult
        String runtimeId = result.runtimeId();
        assertNotNull("成功路径应回填 runtimeId", runtimeId);

        Optional<Trace> opt = store.getTrace(runtimeId);
        assertTrue(opt.isPresent());
        Trace trace = opt.get();
        assertEquals(runtimeId, trace.runtimeId());

        // 树结构：1 agent_run 根 + 2 llm span（两轮）+ 1 tool span
        long agentRunCount = trace.spans().stream().filter(s -> "agent_run".equals(s.type())).count();
        long llmCount = trace.spans().stream().filter(s -> "llm".equals(s.type())).count();
        long toolCount = trace.spans().stream().filter(s -> "tool".equals(s.type())).count();
        assertEquals(1, agentRunCount);
        assertEquals(2, llmCount);
        assertEquals(1, toolCount);

        // agent_run 根：parentSpanId=null，runtimeId=自身 spanId
        Span root = trace.spans().stream().filter(s -> "agent_run".equals(s.type())).findFirst().get();
        assertNull(root.parentSpanId());
        assertEquals(root.spanId(), root.runtimeId());
        // 根载荷
        assertEquals("你是助手", root.attributes().get(SpanAttributes.SYSTEM_PROMPT));

        // tool span 的 parent 应是第一轮的 llm span
        Span toolSpan = trace.spans().stream().filter(s -> "tool".equals(s.type())).findFirst().get();
        Span firstLlm = trace.spans().stream().filter(s -> "llm".equals(s.type()))
                .min(java.util.Comparator.comparingLong(Span::startTimeMs)).get();
        assertEquals("tool span 应挂在触发它的 llm 调用下", firstLlm.spanId(), toolSpan.parentSpanId());
        // tool 载荷
        assertEquals("search", toolSpan.attributes().get(SpanAttributes.TOOL_NAME));
        assertEquals("搜索结果", toolSpan.attributes().get(SpanAttributes.RESULT));
    }

    @Test
    public void noTraceWhenStoreNotConfigured() {
        // 不配 trace：runtimeId 为 null，行为与现状一致
        MockLLM llm = new MockLLM(Collections.singletonList(reply("ok")));
        ToolRegistry registry = new ToolRegistry();
        Agent agent = Agent.builder(llm, registry).build();
        ChatResult result = agent.run("hi");
        assertNull(result.runtimeId());
    }

    @Test
    public void errorPathAttachesMarkerSoRuntimeIdIsExtractable() {
        // LLM 抛异常 → 失败路径：原异常类型不变，runtimeId 通过 marker 可提取
        InMemoryTraceStore store = new InMemoryTraceStore();
        LLM throwingLlm = new LLM() {
            @Override public ChatResult chat(String s, String u, com.non.chain.OutputFormat f) { throw new UnsupportedOperationException(); }
            @Override public ChatResult chat(List<Message> m, com.non.chain.OutputFormat f) { throw new UnsupportedOperationException(); }
            @Override public ChatResult chat(String s, String u, List<com.non.chain.tool.Tool> t, com.non.chain.OutputFormat f) { throw new UnsupportedOperationException(); }
            @Override public ChatResult chat(List<Message> m, List<com.non.chain.tool.Tool> t, com.non.chain.OutputFormat f) { throw new RuntimeException("LLM 连接失败"); }
            @Override public ChatResult streamChat(String s, String u, com.non.chain.OutputFormat f, java.util.function.Consumer<ChatChunk> c) { throw new UnsupportedOperationException(); }
            @Override public ChatResult streamChat(List<Message> m, com.non.chain.OutputFormat f, java.util.function.Consumer<ChatChunk> c) { throw new UnsupportedOperationException(); }
            @Override public ChatResult streamChat(String s, String u, List<com.non.chain.tool.Tool> t, com.non.chain.OutputFormat f, java.util.function.Consumer<ChatChunk> c) { throw new UnsupportedOperationException(); }
            @Override public ChatResult streamChat(List<Message> m, List<com.non.chain.tool.Tool> t, com.non.chain.OutputFormat f, java.util.function.Consumer<ChatChunk> c) { throw new RuntimeException("LLM 连接失败"); }
        };
        Agent agent = Agent.builder(throwingLlm, new ToolRegistry()).trace(store).build();

        RuntimeException caught = null;
        try {
            agent.run("触发异常");
        } catch (RuntimeException e) {
            caught = e;
        }
        assertNotNull(caught);
        // 原异常类型/消息不变（不包装）
        assertEquals("LLM 连接失败", caught.getMessage());
        // runtimeId 可从异常提取
        Optional<String> rid = TraceRuntimeIds.find(caught);
        assertTrue("失败路径应能提取 runtimeId", rid.isPresent());
        // 且能回捞已录制的 trace（agent_run 根 + 失败的 llm span）
        Optional<Trace> trace = store.getTrace(rid.get());
        assertTrue(trace.isPresent());
        assertTrue(trace.get().spans().stream().anyMatch(s -> "agent_run".equals(s.type())));
        Span errLlm = trace.get().spans().stream()
                .filter(s -> "llm".equals(s.type())).findFirst().orElse(null);
        assertNotNull(errLlm);
        assertEquals("error", errLlm.status());
    }

    @Test
    public void traceIsJsonSerializable() {
        InMemoryTraceStore store = new InMemoryTraceStore();
        MockLLM llm = new MockLLM(Collections.singletonList(reply("done")));
        Agent agent = Agent.builder(llm, new ToolRegistry()).trace(store).build();
        ChatResult result = agent.run("hi");

        Trace trace = store.getTrace(result.runtimeId()).get();
        String json = trace.toJson();
        // 往返
        Trace restored = Trace.fromJson(json);
        assertEquals(trace.runtimeId(), restored.runtimeId());
        assertEquals(trace.spans().size(), restored.spans().size());
    }
}
