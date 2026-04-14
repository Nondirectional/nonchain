package com.non.chain.callback;

import com.non.chain.ChatResult;
import com.non.chain.Message;
import com.non.chain.agent.Agent;
import com.non.chain.callback.event.*;
import com.non.chain.flow.*;
import com.non.chain.provider.LLM;
import com.non.chain.tool.ToolCall;
import com.non.chain.tool.ToolRegistry;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * ChainCallback 集成测试：验证 Agent、Graph、ToolRegistry 正确触发回调事件
 */
public class ChainCallbackTest {

    // ---- Mock LLM ----

    static class MockLLM implements LLM {

        private final List<ChatResult> responses;
        private int callIndex = 0;

        MockLLM(List<ChatResult> responses) {
            this.responses = responses;
        }

        @Override
        public ChatResult chat(String systemMessage, String userMessage, com.non.chain.OutputFormat outputFormat) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChatResult chat(List<Message> messages, com.non.chain.OutputFormat outputFormat) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChatResult chat(String systemMessage, String userMessage, List<com.non.chain.tool.Tool> tools, com.non.chain.OutputFormat outputFormat) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChatResult chat(List<Message> messages, List<com.non.chain.tool.Tool> tools, com.non.chain.OutputFormat outputFormat) {
            return responses.get(callIndex++);
        }

        @Override
        public ChatResult streamChat(String systemMessage, String userMessage, com.non.chain.OutputFormat outputFormat, java.util.function.Consumer<com.non.chain.ChatChunk> callback) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChatResult streamChat(List<Message> messages, com.non.chain.OutputFormat outputFormat, java.util.function.Consumer<com.non.chain.ChatChunk> callback) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChatResult streamChat(String systemMessage, String userMessage, List<com.non.chain.tool.Tool> tools, com.non.chain.OutputFormat outputFormat, java.util.function.Consumer<com.non.chain.ChatChunk> callback) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChatResult streamChat(List<Message> messages, List<com.non.chain.tool.Tool> tools, com.non.chain.OutputFormat outputFormat, java.util.function.Consumer<com.non.chain.ChatChunk> callback) {
            throw new UnsupportedOperationException();
        }
    }

    // ---- 事件收集器 ----

    static class EventCollector implements ChainCallback {
        final List<LlmStartEvent> llmStarts = new ArrayList<>();
        final List<LlmCompleteEvent> llmCompletes = new ArrayList<>();
        final List<LlmErrorEvent> llmErrors = new ArrayList<>();
        final List<ToolStartEvent> toolStarts = new ArrayList<>();
        final List<ToolCompleteEvent> toolCompletes = new ArrayList<>();
        final List<ToolErrorEvent> toolErrors = new ArrayList<>();
        final List<GraphEvent> graphEvents = new ArrayList<>();

        @Override
        public void onLlmStart(LlmStartEvent event) { llmStarts.add(event); }

        @Override
        public void onLlmComplete(LlmCompleteEvent event) { llmCompletes.add(event); }

        @Override
        public void onLlmError(LlmErrorEvent event) { llmErrors.add(event); }

        @Override
        public void onToolStart(ToolStartEvent event) { toolStarts.add(event); }

        @Override
        public void onToolComplete(ToolCompleteEvent event) { toolCompletes.add(event); }

        @Override
        public void onToolError(ToolErrorEvent event) { toolErrors.add(event); }

        @Override
        public void onGraphEvent(GraphEvent event) { graphEvents.add(event); }
    }

    // ---- Agent 回调测试 ----

    @Test
    public void agentShouldFireLlmEvents() {
        MockLLM llm = new MockLLM(Collections.singletonList(
                new ChatResult("回复", null)
        ));
        ToolRegistry registry = new ToolRegistry();
        EventCollector collector = new EventCollector();

        Agent agent = Agent.builder(llm, registry)
                .callback(collector)
                .build();
        agent.run("你好");

        assertEquals(1, collector.llmStarts.size());
        assertEquals(1, collector.llmCompletes.size());
        assertTrue(collector.llmCompletes.get(0).latencyMs() >= 0);
        assertEquals("回复", collector.llmCompletes.get(0).result().content());
    }

    @Test
    public void agentShouldFireToolEvents() {
        ToolCall toolCall = new ToolCall("call_1", "get_weather", "{\"city\":\"北京\"}");
        List<ChatResult> responses = new ArrayList<>();
        responses.add(new ChatResult("", null, Collections.singletonList(toolCall)));
        responses.add(new ChatResult("北京晴天", null));

        MockLLM llm = new MockLLM(responses);
        ToolRegistry registry = new ToolRegistry();
        registry.register("get_weather", "查询天气")
                .param("city", "string", "城市", true)
                .handle(args -> "北京晴天");

        EventCollector collector = new EventCollector();
        Agent agent = Agent.builder(llm, registry)
                .callback(collector)
                .build();
        agent.run("天气");

        // 2 LLM calls
        assertEquals(2, collector.llmStarts.size());
        assertEquals(2, collector.llmCompletes.size());

        // 1 tool call
        assertEquals(1, collector.toolStarts.size());
        assertEquals(1, collector.toolCompletes.size());
        assertEquals("get_weather", collector.toolCompletes.get(0).toolName());
        assertTrue(collector.toolCompletes.get(0).latencyMs() >= 0);
    }

    @Test
    public void agentTraceIdShouldCorrelateEvents() {
        MockLLM llm = new MockLLM(Collections.singletonList(
                new ChatResult("回复", null)
        ));
        ToolRegistry registry = new ToolRegistry();
        EventCollector collector = new EventCollector();

        Agent agent = Agent.builder(llm, registry)
                .callback(collector)
                .build();
        agent.run("你好");

        String traceId = collector.llmStarts.get(0).traceId();
        assertNotNull(traceId);
        assertEquals(traceId, collector.llmCompletes.get(0).traceId());
    }

    @Test
    public void agentShouldFireToolErrorEvent() {
        ToolCall toolCall = new ToolCall("call_1", "fail_tool", "{}");
        List<ChatResult> responses = new ArrayList<>();
        responses.add(new ChatResult("", null, Collections.singletonList(toolCall)));
        responses.add(new ChatResult("工具失败了", null));

        MockLLM llm = new MockLLM(responses);
        ToolRegistry registry = new ToolRegistry();
        registry.register("fail_tool", "会失败的工具")
                .handle(args -> { throw new RuntimeException("连接超时"); });

        EventCollector collector = new EventCollector();
        Agent agent = Agent.builder(llm, registry)
                .callback(collector)
                .build();
        agent.run("测试");

        assertEquals(1, collector.toolStarts.size());
        assertEquals(1, collector.toolErrors.size());
        assertEquals("fail_tool", collector.toolErrors.get(0).toolName());
        assertTrue(collector.toolErrors.get(0).latencyMs() >= 0);
    }

    // ---- Graph 回调测试 ----

    @Test
    public void graphShouldBridgeToChainCallback() {
        EventCollector collector = new EventCollector();

        Graph graph = Graph.builder("test")
                .start("a")
                .addNode(new Node("a", state -> state.put("a", true)))
                .addEdge(Edge.of("a", Graph.END))
                .callback(collector)
                .build();

        graph.run(new State());

        // GRAPH_START + NODE_START a + NODE_END a + GRAPH_END = 4
        assertEquals(4, collector.graphEvents.size());
        assertEquals(GraphEvent.Type.GRAPH_START, collector.graphEvents.get(0).type());
        assertEquals(GraphEvent.Type.NODE_START, collector.graphEvents.get(1).type());
        assertEquals(GraphEvent.Type.NODE_END, collector.graphEvents.get(2).type());
        assertEquals(GraphEvent.Type.GRAPH_END, collector.graphEvents.get(3).type());
    }

    @Test
    public void graphCallbackAndOnEventBothFire() {
        List<GraphEvent> onEventList = new ArrayList<>();
        EventCollector collector = new EventCollector();

        Graph graph = Graph.builder("dual")
                .start("a")
                .addNode(new Node("a", state -> state.put("x", 1)))
                .addEdge(Edge.of("a", Graph.END))
                .onEvent(onEventList::add)
                .callback(collector)
                .build();

        graph.run(new State());

        // Both should receive events
        assertEquals(4, onEventList.size());
        assertEquals(4, collector.graphEvents.size());
    }

    // ---- CompositeCallback 测试 ----

    @Test
    public void compositeCallbackShouldDispatchToAll() {
        EventCollector c1 = new EventCollector();
        EventCollector c2 = new EventCollector();

        ChainCallback composite = CompositeCallback.of(c1, c2);

        MockLLM llm = new MockLLM(Collections.singletonList(
                new ChatResult("回复", null)
        ));
        ToolRegistry registry = new ToolRegistry();

        Agent agent = Agent.builder(llm, registry)
                .callback(composite)
                .build();
        agent.run("你好");

        assertEquals(1, c1.llmCompletes.size());
        assertEquals(1, c2.llmCompletes.size());
    }

    @Test
    public void compositeCallbackShouldIsolateExceptions() {
        EventCollector healthy = new EventCollector();
        ChainCallback broken = new ChainCallback() {
            @Override
            public void onLlmComplete(LlmCompleteEvent event) {
                throw new RuntimeException("回调崩溃");
            }
        };

        ChainCallback composite = CompositeCallback.of(broken, healthy);

        MockLLM llm = new MockLLM(Collections.singletonList(
                new ChatResult("回复", null)
        ));
        ToolRegistry registry = new ToolRegistry();

        Agent agent = Agent.builder(llm, registry)
                .callback(composite)
                .build();
        // Should not throw, healthy callback still receives event
        ChatResult result = agent.run("你好");

        assertEquals("回复", result.content());
        assertEquals(1, healthy.llmCompletes.size());
    }

    // ---- ChainContext 测试 ----

    @Test
    public void chainContextShouldProvideCallback() {
        EventCollector collector = new EventCollector();
        ChainContext ctx = ChainContext.builder()
                .callback(collector)
                .build();

        MockLLM llm = new MockLLM(Collections.singletonList(
                new ChatResult("回复", null)
        ));
        ToolRegistry registry = new ToolRegistry();

        Agent agent = Agent.builder(llm, registry)
                .chainContext(ctx)
                .build();
        agent.run("你好");

        assertEquals(1, collector.llmCompletes.size());
    }

    @Test
    public void directCallbackOverridesChainContext() {
        EventCollector ctxCollector = new EventCollector();
        EventCollector directCollector = new EventCollector();

        ChainContext ctx = ChainContext.builder()
                .callback(ctxCollector)
                .build();

        MockLLM llm = new MockLLM(Collections.singletonList(
                new ChatResult("回复", null)
        ));
        ToolRegistry registry = new ToolRegistry();

        // .callback() after .chainContext() should take priority
        Agent agent = Agent.builder(llm, registry)
                .chainContext(ctx)
                .callback(directCollector)
                .build();
        agent.run("你好");

        assertEquals(0, ctxCollector.llmCompletes.size());
        assertEquals(1, directCollector.llmCompletes.size());
    }

    // ---- ChainTrace 测试 ----

    @Test
    public void chainTraceShouldBeClearedAfterAgentRun() {
        ChainTrace.set("test-id");
        assertEquals("test-id", ChainTrace.get());

        MockLLM llm = new MockLLM(Collections.singletonList(
                new ChatResult("回复", null)
        ));
        ToolRegistry registry = new ToolRegistry();
        Agent agent = Agent.builder(llm, registry).build();
        agent.run("你好");

        // Agent should have cleared its own traceId
        assertNull(ChainTrace.get());
    }
}
