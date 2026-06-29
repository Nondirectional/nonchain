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
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

/**
 * 边界1 测试：SubAgent 全树下钻。父 Agent 委派子代理后，子代理内部的 LLM/Tool 调用
 * 应作为父委派 tool span 的子 span 录制进同一棵树。同时验证用户面 callback 隔离不变。
 */
public class SubAgentTraceTest {

    static class MockLLM implements LLM {
        private final List<ChatResult> responses;
        private int callIndex = 0;
        private final List<List<com.non.chain.tool.Tool>> capturedTools = new ArrayList<>();
        MockLLM(List<ChatResult> responses) { this.responses = responses; }

        @Override public ChatResult chat(String s, String u, com.non.chain.OutputFormat f) { throw new UnsupportedOperationException(); }
        @Override public ChatResult chat(List<Message> m, com.non.chain.OutputFormat f) { throw new UnsupportedOperationException(); }
        @Override public ChatResult chat(String s, String u, List<com.non.chain.tool.Tool> t, com.non.chain.OutputFormat f) { throw new UnsupportedOperationException(); }
        @Override public ChatResult chat(List<Message> messages, List<com.non.chain.tool.Tool> tools, com.non.chain.OutputFormat f) {
            capturedTools.add(new ArrayList<>(tools));
            return responses.get(callIndex++);
        }
        @Override public ChatResult streamChat(String s, String u, com.non.chain.OutputFormat f, java.util.function.Consumer<ChatChunk> c) { throw new UnsupportedOperationException(); }
        @Override public ChatResult streamChat(List<Message> m, com.non.chain.OutputFormat f, java.util.function.Consumer<ChatChunk> c) { throw new UnsupportedOperationException(); }
        @Override public ChatResult streamChat(String s, String u, List<com.non.chain.tool.Tool> t, com.non.chain.OutputFormat f, java.util.function.Consumer<ChatChunk> c) { throw new UnsupportedOperationException(); }
        @Override public ChatResult streamChat(List<Message> messages, List<com.non.chain.tool.Tool> tools, com.non.chain.OutputFormat f, java.util.function.Consumer<ChatChunk> callback) {
            capturedTools.add(new ArrayList<>(tools));
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
    public void subAgentDrillsDownIntoSameTree() {
        InMemoryTraceStore store = new InMemoryTraceStore();
        // 父 LLM 第一轮委派 research；子 LLM 第一轮调 kid_tool，第二轮给结论；
        // 父 LLM 第二轮据子结果给最终答复。
        // 调用顺序：父(委派 research) → 子(调 kid_tool) → 子(结论) → 父(最终)
        MockLLM sharedLlm = new MockLLM(Arrays.asList(
                toolCall("c1", "research", "{\"task\":\"调研\"}"),
                toolCall("k1", "kid_tool", "{\"q\":\"x\"}"),
                reply("调研结论：A"),
                reply("最终：A")
        ));

        // 子代理有自己的工具 kid_tool
        ToolRegistry childTools = new ToolRegistry();
        childTools.register("kid_tool", "子工具").handle(args -> "子工具结果");

        ToolRegistry registry = new ToolRegistry();
        registry.registerSubAgent("research", "调研")
                .systemPrompt("你是调研代理。")
                .toolRegistry(childTools)
                .build();

        Agent agent = Agent.builder(sharedLlm, registry).trace(store).build();
        ChatResult result = agent.run("帮我调研");

        String parentRuntimeId = result.runtimeId();
        assertNotNull(parentRuntimeId);
        Trace trace = store.getTrace(parentRuntimeId).get();

        // 整棵树只有一个根（父 agent_run），子代理不切新根
        List<Span> roots = trace.spans().stream()
                .filter(s -> "agent_run".equals(s.type()) && s.parentSpanId() == null)
                .collect(Collectors.toList());
        assertEquals("子代理不切新根，全树下钻进同一棵树", 1, roots.size());

        // 子代理内部也有 agent_run span（但 parent 非空 → 是 startChild 当根，挂在父委派 tool 下）
        List<Span> agentRunSpans = trace.spans().stream()
                .filter(s -> "agent_run".equals(s.type())).collect(Collectors.toList());
        assertEquals(2, agentRunSpans.size()); // 父 + 子

        Span parentRoot = roots.get(0);
        Span subAgentRun = agentRunSpans.stream().filter(s -> s.parentSpanId() != null).findFirst().get();
        // 子 agent_run 的 parent 应是父委派的 tool span（tool name = research / delegate）
        Span delegateTool = trace.spans().stream()
                .filter(s -> "tool".equals(s.type()) && s.spanId().equals(subAgentRun.parentSpanId()))
                .findFirst().orElse(null);
        assertNotNull("子 agent_run 应挂在父委派 tool span 下", delegateTool);

        // 委派 tool span 的 parent 是父的某个 llm span
        // 子代理内部的 kid_tool span 应挂在子 agent_run 下（间接通过子 llm span）
        Span kidTool = trace.spans().stream()
                .filter(s -> "tool".equals(s.type())
                        && "kid_tool".equals(s.attributes().get(SpanAttributes.TOOL_NAME)))
                .findFirst().orElse(null);
        assertNotNull("子代理内部工具 span 应下钻进同一棵树", kidTool);

        // kid_tool 的 parent 链最终追溯到父根：kid_tool → 子llm → 子agent_run → 委派tool → 父llm → 父agent_run
        assertTrue(isDescendant(kidTool, parentRoot, trace.spans()));
    }

    /** 校验 candidate 的 parent 链能追溯到 ancestor。 */
    private static boolean isDescendant(Span candidate, Span ancestor, List<Span> all) {
        Map<String, Span> byId = all.stream()
                .collect(Collectors.toMap(Span::spanId, s -> s));
        Span cur = candidate;
        int guard = 0;
        while (cur != null && guard++ < 100) {
            if (cur.spanId().equals(ancestor.spanId())) {
                return true;
            }
            cur = cur.parentSpanId() == null ? null : byId.get(cur.parentSpanId());
        }
        return false;
    }

    @Test
    public void userCallbackStillIsolatedWhenTraceEnabled() {
        // trace 启用时，父用户 callback 仍只能看到外层委派的 Tool 事件，看不到子代理内部 kid_tool 事件
        InMemoryTraceStore store = new InMemoryTraceStore();
        java.util.List<String> seenToolNames = Collections.synchronizedList(new ArrayList<>());
        com.non.chain.callback.ChainCallback userCb = new com.non.chain.callback.ChainCallback() {
            @Override public void onToolStart(com.non.chain.callback.event.ToolStartEvent e) {
                seenToolNames.add(e.toolCall().name());
            }
        };

        MockLLM sharedLlm = new MockLLM(Arrays.asList(
                toolCall("c1", "research", "{\"task\":\"x\"}"),
                toolCall("k1", "kid_tool", "{}"),
                reply("子结论"),
                reply("最终")
        ));
        ToolRegistry childTools = new ToolRegistry();
        childTools.register("kid_tool", "子工具").handle(args -> "kr");
        ToolRegistry registry = new ToolRegistry();
        registry.registerSubAgent("research", "调研").systemPrompt("子").toolRegistry(childTools).build();

        Agent agent = Agent.builder(sharedLlm, registry).trace(store).callback(userCb).build();
        agent.run("x");

        // 父 callback 只看到外层 research 委派，看不到子代理内部 kid_tool
        assertTrue("父用户 callback 应看到外层委派", seenToolNames.contains("research"));
        assertFalse("子代理内部事件不应透出父 callback（隔离不变）",
                seenToolNames.contains("kid_tool"));
    }
}
