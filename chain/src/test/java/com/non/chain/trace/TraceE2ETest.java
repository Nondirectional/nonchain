package com.non.chain.trace;


import com.non.chain.ChatChunk;
import com.non.chain.ChatResult;
import com.non.chain.Message;
import com.non.chain.OutputFormat;
import com.non.chain.agent.Agent;
import com.non.chain.provider.LLM;
import com.non.chain.flow.Edge;
import com.non.chain.flow.Graph;
import com.non.chain.flow.GraphResult;
import com.non.chain.flow.Node;
import com.non.chain.flow.State;
import com.non.chain.tool.Tool;
import com.non.chain.tool.ToolCall;
import com.non.chain.tool.ToolRegistry;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static com.non.chain.trace.SpanAttributes.SpanType.LLM;
import static org.junit.Assert.*;

/**
 * 端到端测试：Flow → 节点内 Agent → SubAgent → 工具 的完整嵌套树。
 *
 * <p>断言：单一 runtimeId、单一根（graph_run）、parent 链从根贯穿到叶子、所有层不断；
 * 失败路径也能拿到 runtimeId 并回捞 trace；JSON 序列化整棵树往返。</p>
 *
 * <p>关键：ThreadLocal current-span 是 {@code Tracer.CURRENT} 静态字段，跨 Tracer 实例共享。
 * Graph 节点体 push node span → 节点体内的 Agent.run 读 {@code Tracer.current()}（node span）
 * → agent_run 作为 node span 的子 span，自然嵌套。</p>
 */
public class TraceE2ETest {

    static class MockLLM implements LLM {
        private final List<ChatResult> responses;
        private int callIndex = 0;
        MockLLM(List<ChatResult> responses) { this.responses = responses; }

        @Override public ChatResult chat(String s, String u, OutputFormat f) { throw new UnsupportedOperationException(); }
        @Override public ChatResult chat(List<Message> m, OutputFormat f) { throw new UnsupportedOperationException(); }
        @Override public ChatResult chat(String s, String u, List<Tool> t, OutputFormat f) { throw new UnsupportedOperationException(); }
        @Override public ChatResult chat(List<Message> m, List<Tool> t, OutputFormat f) {
            return responses.get(callIndex++);
        }
        @Override public ChatResult streamChat(String s, String u, OutputFormat f, Consumer<ChatChunk> c) { throw new UnsupportedOperationException(); }
        @Override public ChatResult streamChat(List<Message> m, OutputFormat f, Consumer<ChatChunk> c) { throw new UnsupportedOperationException(); }
        @Override public ChatResult streamChat(String s, String u, List<Tool> t, OutputFormat f, Consumer<ChatChunk> c) { throw new UnsupportedOperationException(); }
        @Override public ChatResult streamChat(List<Message> messages, List<Tool> tools, OutputFormat f, Consumer<ChatChunk> callback) {
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

    /** 校验 candidate 的 parent 链能追溯到 ancestor。 */
    private static boolean isDescendant(Span candidate, Span ancestor, List<Span> all) {
        Map<String, Span> byId = new HashMap<>();
        for (Span s : all) {
            byId.put(s.spanId(), s);
        }
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
    public void flowAgentSubAgentToolFullNestingSingleTree() {
        InMemoryTraceStore store = new InMemoryTraceStore();
        // 共享 mock：节点体内的 Agent(父) 委派 research → 子(调 leaf_tool) → 子(结论) → 父(最终)
        // 调用顺序：父Agent(委派 research) → 子Agent(调 leaf_tool) → 子Agent(结论) → 父Agent(最终)
        MockLLM sharedLlm = new MockLLM(Arrays.asList(
                toolCall("c1", "research", "{\"task\":\"调研\"}"),
                toolCall("k1", "leaf_tool", "{\"q\":\"x\"}"),
                reply("子结论：Z"),
                reply("父最终：Z")
        ));

        // 子代理有自己的工具 leaf_tool
        ToolRegistry childTools = new ToolRegistry();
        childTools.register("leaf_tool", "叶子工具").handle(args -> "叶子结果");

        ToolRegistry agentTools = new ToolRegistry();
        agentTools.registerSubAgent("research", "调研")
                .systemPrompt("你是调研子代理。")
                .toolRegistry(childTools)
                .build();

        final Agent agent = Agent.builder(sharedLlm, agentTools)
                .systemPrompt("你是父代理。")
                .trace(store)
                .build();

        // Graph：单节点 outer，节点体内调 agent.run，然后 END
        Graph graph = Graph.builder("e2e-flow")
                .traceStore(store)
                .addNode(new Node("outer", state -> {
                    // 节点体内调 Agent —— 靠 ThreadLocal 继承 node span 当 parent
                    ChatResult r = agent.run("请调研");
                    return state.put("answer", r.content());
                }))
                .addEdge(Edge.of("outer", Graph.END))
                .start("outer")
                .build();

        GraphResult gr = graph.run(new State());
        String runtimeId = gr.runtimeId();
        assertNotNull(runtimeId);

        Trace trace = store.getTrace(runtimeId).get();
        // 单一根：graph_run（Agent/SubAgent 都不切新根）
        List<Span> roots = new ArrayList<>();
        for (Span s : trace.spans()) {
            if (s.parentSpanId() == null) {
                roots.add(s);
            }
        }
        assertEquals("整棵嵌套树只有一个根（graph_run）", 1, roots.size());
        Span root = roots.get(0);
        assertEquals("graph_run", root.type());

        // 层次齐全：graph_run + graph_node + agent_run(父) + agent_run(子) + llm(多个) + tool(委派 + leaf_tool)
        long graphRun = trace.spans().stream().filter(s -> "graph_run".equals(s.type())).count();
        long graphNode = trace.spans().stream().filter(s -> "graph_node".equals(s.type())).count();
        long agentRun = trace.spans().stream().filter(s -> "agent_run".equals(s.type())).count();
        long toolSpans = trace.spans().stream().filter(s -> "tool".equals(s.type())).count();
        assertEquals(1, graphRun);
        assertEquals(1, graphNode);
        assertEquals("父 + 子两个 agent_run，不切新根", 2, agentRun);
        assertTrue("委派 tool + 子代理内 leaf_tool", toolSpans >= 2);

        // leaf_tool span 的 parent 链应贯穿到 graph_run 根（所有层不断）
        Optional<Span> leafTool = trace.spans().stream()
                .filter(s -> "tool".equals(s.type())
                        && "leaf_tool".equals(s.attributes().get(SpanAttributes.TOOL_NAME)))
                .findFirst();
        assertTrue("子代理内部工具 span 应下钻进同一棵树", leafTool.isPresent());
        assertTrue("leaf_tool → ... → graph_run 链路不断",
                isDescendant(leafTool.get(), root, trace.spans()));

        // 父 agent_run 应挂在 graph_node 下（ThreadLocal 继承）
        Optional<Span> parentAgentRun = trace.spans().stream()
                .filter(s -> "agent_run".equals(s.type()) && s.parentSpanId() != null)
                .filter(s -> {
                    // 找出挂在 node span 下的那个（父 agent），而非挂在委派 tool 下的（子 agent）
                    return trace.spans().stream().anyMatch(p -> p.spanId().equals(s.parentSpanId())
                            && "graph_node".equals(p.type()));
                })
                .findFirst();
        assertTrue("父 agent_run 应挂在 graph_node 下（边界3 ThreadLocal 继承）",
                parentAgentRun.isPresent());

        // JSON 序列化整棵树往返
        String json = trace.toJson();
        Trace restored = Trace.fromJson(json);
        assertEquals(trace.spans().size(), restored.spans().size());
        assertEquals(trace.runtimeId(), restored.runtimeId());
    }

    @Test
    public void jsonExportContainsAllSpanTypes() {
        InMemoryTraceStore store = new InMemoryTraceStore();
        MockLLM llm = new MockLLM(Arrays.asList(
                toolCall("c1", "research", "{\"task\":\"t\"}"),
                reply("子结论"),
                reply("父最终")
        ));
        ToolRegistry childTools = new ToolRegistry();
        ToolRegistry agentTools = new ToolRegistry();
        agentTools.registerSubAgent("research", "调研").systemPrompt("子").toolRegistry(childTools).build();
        Agent agent = Agent.builder(llm, agentTools).trace(store).build();

        Graph graph = Graph.builder("json-flow")
                .traceStore(store)
                .addNode(new Node("n", state -> {
                    agent.run("x");
                    return state;
                }))
                .addEdge(Edge.of("n", Graph.END))
                .start("n")
                .build();
        GraphResult gr = graph.run(new State());

        Trace trace = store.getTrace(gr.runtimeId()).get();
        String json = trace.toJson();
        // 验证 JSON 含全部 type 关键字
        assertTrue(json.contains("graph_run"));
        assertTrue(json.contains("graph_node"));
        assertTrue(json.contains("agent_run"));
        assertTrue(json.contains("llm"));
        assertTrue(json.contains("tool"));
    }
}
