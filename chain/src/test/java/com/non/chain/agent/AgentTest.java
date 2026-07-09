package com.non.chain.agent;

import com.non.chain.ChatChunk;
import com.non.chain.ChatResult;
import com.non.chain.Message;
import com.non.chain.provider.LLM;
import com.non.chain.tool.ToolCall;
import com.non.chain.tool.ToolRegistry;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

public class AgentTest {

    // ---- 测试用 mock LLM ----

    /**
     * 可编程的 mock LLM，按预设顺序返回结果
     */
    static class MockLLM implements LLM {

        private final List<ChatResult> responses;
        private int callIndex = 0;
        private List<List<Message>> capturedMessages = new ArrayList<>();

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
            capturedMessages.add(new ArrayList<>(messages));
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
        public ChatResult streamChat(List<Message> messages, List<com.non.chain.tool.Tool> tools, com.non.chain.OutputFormat outputFormat, java.util.function.Consumer<ChatChunk> callback) {
            capturedMessages.add(new ArrayList<>(messages));
            ChatResult response = responses.get(callIndex++);
            // Simulate streaming: emit content chunk then finish chunk
            if (response.content() != null && !response.content().isEmpty()) {
                callback.accept(new ChatChunk(response.content(), null, null, null));
            }
            if (response.thinkingContent() != null && !response.thinkingContent().isEmpty()) {
                callback.accept(new ChatChunk(null, response.thinkingContent(), null, null));
            }
            if (response.hasToolCalls()) {
                // Emit tool call deltas (one per tool call, with full args)
                java.util.List<ChatChunk.DeltaToolCall> deltas = new java.util.ArrayList<>();
                for (int i = 0; i < response.toolCalls().size(); i++) {
                    ToolCall tc = response.toolCalls().get(i);
                    deltas.add(new ChatChunk.DeltaToolCall(i, tc.id(), tc.name(), tc.arguments()));
                }
                callback.accept(new ChatChunk(null, null, deltas, null));
            }
            callback.accept(new ChatChunk(null, null, null, "stop"));
            return response;
        }

        List<List<Message>> getCapturedMessages() {
            return capturedMessages;
        }
    }

    // ---- 测试 ----

    @Test
    public void testDirectResponseWithoutToolCalls() {
        // LLM 直接回复，不调用工具
        MockLLM llm = new MockLLM(Collections.singletonList(
                new ChatResult("北京今天是晴天", null)
        ));
        ToolRegistry registry = new ToolRegistry();

        Agent agent = Agent.builder(llm, registry).build();
        ChatResult result = agent.run("北京天气");

        assertEquals("北京今天是晴天", result.content());
        assertFalse(result.hasToolCalls());
    }

    @Test
    public void testSingleToolCallLoop() {
        // 第一轮：LLM 调用工具；第二轮：LLM 根据工具结果回复
        ToolCall toolCall = new ToolCall("call_1", "get_weather", "{\"location\":\"北京\"}");

        List<ChatResult> responses = new ArrayList<>();
        responses.add(new ChatResult("", null, Collections.singletonList(toolCall)));
        responses.add(new ChatResult("北京今天是晴天，气温25°C", null));

        MockLLM llm = new MockLLM(responses);

        ToolRegistry registry = new ToolRegistry();
        registry.register("get_weather", "查询天气")
                .handle(args -> args.getString("location") + "今天是晴天，气温25°C");

        Agent agent = Agent.builder(llm, registry).build();
        ChatResult result = agent.run("北京天气");

        assertEquals("北京今天是晴天，气温25°C", result.content());

        // 验证 LLM 被调用了两次
        assertEquals(2, llm.getCapturedMessages().size());

        // 第二次调用时，消息历史应包含：user, assistant(toolCall), tool_result
        List<Message> secondCallMessages = llm.getCapturedMessages().get(1);
        assertEquals(3, secondCallMessages.size());
        assertEquals("user", secondCallMessages.get(0).role());
        assertEquals("assistant", secondCallMessages.get(1).role());
        assertTrue(secondCallMessages.get(1).toolCalls().size() > 0);
        assertEquals("tool", secondCallMessages.get(2).role());
        assertEquals("call_1", secondCallMessages.get(2).toolCallId());
    }

    @Test
    public void testMultipleToolCallsInOneIteration() {
        // 一轮中 LLM 同时调用多个工具
        ToolCall tc1 = new ToolCall("call_1", "get_weather", "{\"location\":\"北京\"}");
        ToolCall tc2 = new ToolCall("call_2", "get_weather", "{\"location\":\"上海\"}");

        List<ChatResult> responses = new ArrayList<>();
        responses.add(new ChatResult("", null, List.of(tc1, tc2)));
        responses.add(new ChatResult("北京和上海都是晴天", null));

        MockLLM llm = new MockLLM(responses);

        ToolRegistry registry = new ToolRegistry();
        registry.register("get_weather", "查询天气")
                .handle(args -> args.getString("location") + "晴天");

        Agent agent = Agent.builder(llm, registry).build();
        ChatResult result = agent.run("北京和上海天气");

        assertEquals("北京和上海都是晴天", result.content());
    }

    @Test
    public void testParallelToolExecution() {
        // 验证多个工具并行执行，结果按源顺序组装
        ToolCall tc1 = new ToolCall("call_1", "get_weather", "{\"location\":\"北京\"}");
        ToolCall tc2 = new ToolCall("call_2", "get_weather", "{\"location\":\"上海\"}");
        ToolCall tc3 = new ToolCall("call_3", "get_time", "{\"city\":\"北京\"}");

        List<ChatResult> responses = new ArrayList<>();
        responses.add(new ChatResult("", null, List.of(tc1, tc2, tc3)));
        responses.add(new ChatResult("三城天气和时间已查", null));

        MockLLM llm = new MockLLM(responses);

        // 用线程安全列表记录执行顺序
        List<String> executionOrder = Collections.synchronizedList(new ArrayList<>());

        ToolRegistry registry = new ToolRegistry();
        registry.register("get_weather", "查询天气")
                .handle(args -> {
                    executionOrder.add("weather:" + args.getString("location"));
                    return args.getString("location") + "晴天";
                });
        registry.register("get_time", "查询时间")
                .handle(args -> {
                    executionOrder.add("time:" + args.getString("city"));
                    return args.getString("city") + "12:00";
                });

        Agent agent = Agent.builder(llm, registry).build();
        ChatResult result = agent.run("北京和上海天气及时间");

        assertEquals("三城天气和时间已查", result.content());

        // 验证三个工具都被执行
        assertEquals(3, executionOrder.size());

        // 验证第二次 LLM 调用中消息按原始顺序排列
        List<Message> secondCallMessages = llm.getCapturedMessages().get(1);
        // user, assistant(toolCalls), tool_result_1, tool_result_2, tool_result_3
        assertEquals("tool", secondCallMessages.get(2).role());
        assertEquals("call_1", secondCallMessages.get(2).toolCallId());
        assertTrue(secondCallMessages.get(2).content().contains("北京"));

        assertEquals("call_2", secondCallMessages.get(3).toolCallId());
        assertTrue(secondCallMessages.get(3).content().contains("上海"));

        assertEquals("call_3", secondCallMessages.get(4).toolCallId());
        assertTrue(secondCallMessages.get(4).content().contains("12:00"));
    }

    @Test
    public void testParallelExecutionWithOneFailure() {
        // 并行执行中某个工具失败，其他工具正常完成
        ToolCall tc1 = new ToolCall("call_1", "good_tool", "{}");
        ToolCall tc2 = new ToolCall("call_2", "bad_tool", "{}");

        List<ChatResult> responses = new ArrayList<>();
        responses.add(new ChatResult("", null, List.of(tc1, tc2)));
        responses.add(new ChatResult("部分工具执行完成", null));

        MockLLM llm = new MockLLM(responses);

        ToolRegistry registry = new ToolRegistry();
        registry.register("good_tool", "好工具")
                .handle(args -> "成功结果");
        registry.register("bad_tool", "坏工具")
                .handle(args -> {
                    throw new RuntimeException("连接超时");
                });

        Agent agent = Agent.builder(llm, registry).build();
        ChatResult result = agent.run("测试并行失败");

        assertEquals("部分工具执行完成", result.content());

        // 验证两个工具结果都传给 LLM（包括失败的那个）
        List<Message> secondCallMessages = llm.getCapturedMessages().get(1);
        Message goodResult = secondCallMessages.get(2);
        Message badResult = secondCallMessages.get(3);
        assertEquals("call_1", goodResult.toolCallId());
        assertEquals("成功结果", goodResult.content());
        assertEquals("call_2", badResult.toolCallId());
        assertTrue(badResult.content().contains("工具执行失败"));
    }

    @Test
    public void testParallelWithNullExecutorFallsBackToSequential() {
        // executor 设为 null 时回退到串行执行
        ToolCall tc1 = new ToolCall("call_1", "tool_a", "{}");
        ToolCall tc2 = new ToolCall("call_2", "tool_b", "{}");

        List<ChatResult> responses = new ArrayList<>();
        responses.add(new ChatResult("", null, List.of(tc1, tc2)));
        responses.add(new ChatResult("完成", null));

        MockLLM llm = new MockLLM(responses);

        List<String> executionOrder = Collections.synchronizedList(new ArrayList<>());

        ToolRegistry registry = new ToolRegistry();
        registry.register("tool_a", "工具A")
                .handle(args -> {
                    executionOrder.add("A");
                    return "A结果";
                });
        registry.register("tool_b", "工具B")
                .handle(args -> {
                    executionOrder.add("B");
                    return "B结果";
                });

        Agent agent = Agent.builder(llm, registry)
                .executor(null)
                .build();
        ChatResult result = agent.run("测试");

        assertEquals("完成", result.content());
        // 串行执行时顺序保证是 A -> B
        assertEquals(List.of("A", "B"), executionOrder);
    }

    @Test
    public void testMaxIterationsExceeded() {
        // LLM 一直调用工具。review 关键点1:顶层也走 graceful,默认不再抛异常,
        // 而是跑到 maxIterations + graceTurns 后返回部分结果。
        // graceTurns(0) 可回退 0.9.0 硬截断(抛异常)。
        ToolCall toolCall = new ToolCall("call_1", "search", "{\"q\":\"test\"}");
        List<ChatResult> endlessResponses = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            endlessResponses.add(new ChatResult("", null, Collections.singletonList(toolCall)));
        }

        MockLLM llm = new MockLLM(endlessResponses);

        ToolRegistry registry = new ToolRegistry();
        registry.register("search", "搜索")
                .handle(args -> "搜索结果");

        // 1. 默认 graceful(maxIterations=3 + graceTurns=3):不抛异常,返回部分结果
        Agent gracefulAgent = Agent.builder(llm, registry)
                .maxIterations(3)
                .graceTurns(3)
                .build();
        ChatResult gracefulResult = gracefulAgent.run("无限循环测试");
        assertNotNull(gracefulResult);  // graceful 返回结果而非抛异常

        // 2. graceTurns(0):回退 0.9.0 硬截断抛异常
        MockLLM llm2 = new MockLLM(new ArrayList<>(endlessResponses));
        Agent hardAgent = Agent.builder(llm2, registry)
                .maxIterations(3)
                .graceTurns(0)
                .build();
        try {
            hardAgent.run("无限循环测试");
            fail("graceTurns(0) 应回退 0.9.0 硬截断,抛 AgentException");
        } catch (AgentException e) {
            assertTrue(e.getMessage().contains("超出最大迭代次数"));
            assertTrue(e.getMessage().contains("3"));
        }
    }

    @Test
    public void testToolExecutionErrorPassedToLLM() {
        // 工具执行失败，错误信息传回 LLM，LLM 回复
        ToolCall toolCall = new ToolCall("call_1", "failing_tool", "{}");

        List<ChatResult> responses = new ArrayList<>();
        responses.add(new ChatResult("", null, Collections.singletonList(toolCall)));
        responses.add(new ChatResult("工具出错了，不过没关系", null));

        MockLLM llm = new MockLLM(responses);

        ToolRegistry registry = new ToolRegistry();
        registry.register("failing_tool", "会失败的工具")
                .handle(args -> {
                    throw new RuntimeException("连接超时");
                });

        Agent agent = Agent.builder(llm, registry).build();
        ChatResult result = agent.run("测试失败工具");

        assertEquals("工具出错了，不过没关系", result.content());

        // 第二轮消息中应包含工具错误信息
        List<Message> secondCallMessages = llm.getCapturedMessages().get(1);
        Message toolResultMsg = secondCallMessages.get(2);
        assertEquals("tool", toolResultMsg.role());
        assertTrue(toolResultMsg.content().contains("工具执行失败"));
        assertTrue(toolResultMsg.content().contains("连接超时"));
    }

    @Test
    public void testSystemPromptIncluded() {
        MockLLM llm = new MockLLM(Collections.singletonList(
                new ChatResult("回复", null)
        ));

        ToolRegistry registry = new ToolRegistry();

        Agent agent = Agent.builder(llm, registry)
                .systemPrompt("你是助手")
                .build();
        agent.run("你好");

        List<Message> firstCall = llm.getCapturedMessages().get(0);
        assertEquals(2, firstCall.size());
        assertEquals("system", firstCall.get(0).role());
        assertEquals("你是助手", firstCall.get(0).content());
        assertEquals("user", firstCall.get(1).role());
    }

    @Test
    public void testRunWithMessageList() {
        MockLLM llm = new MockLLM(Collections.singletonList(
                new ChatResult("回复", null)
        ));

        ToolRegistry registry = new ToolRegistry();

        Agent agent = Agent.builder(llm, registry).build();

        List<Message> messages = new ArrayList<>();
        messages.add(Message.user("第一条消息"));
        messages.add(Message.assistant("之前的回复"));
        messages.add(Message.user("继续对话"));

        ChatResult result = agent.run(messages);
        assertEquals("回复", result.content());

        List<Message> captured = llm.getCapturedMessages().get(0);
        assertEquals(3, captured.size());
    }

    @Test
    public void testBuilderDefaults() {
        MockLLM llm = new MockLLM(Collections.singletonList(
                new ChatResult("ok", null)
        ));

        ToolRegistry registry = new ToolRegistry();
        Agent agent = Agent.builder(llm, registry).build();

        // 不抛异常即可验证默认值正常
        ChatResult result = agent.run("test");
        assertEquals("ok", result.content());
    }

    // ---- 流式测试 ----

    @Test
    public void testStreamingDirectResponse() {
        MockLLM llm = new MockLLM(Collections.singletonList(
                new ChatResult("流式回复内容", null)
        ));
        ToolRegistry registry = new ToolRegistry();

        Agent agent = Agent.builder(llm, registry).build();

        List<AgentEvent> events = new ArrayList<>();
        ChatResult result = agent.run("测试", events::add);

        assertEquals("流式回复内容", result.content());

        // Should have: RoundStart, TextDelta, RoundEnd, Complete
        assertEventTypes(events,
                AgentEvent.RoundStart.class,
                AgentEvent.TextDelta.class,
                AgentEvent.RoundEnd.class,
                AgentEvent.Complete.class);
    }

    @Test
    public void testStreamingWithToolCalls() {
        ToolCall toolCall = new ToolCall("call_1", "get_weather", "{\"city\":\"北京\"}");

        List<ChatResult> responses = new ArrayList<>();
        responses.add(new ChatResult("", null, Collections.singletonList(toolCall)));
        responses.add(new ChatResult("北京晴天", null));

        MockLLM llm = new MockLLM(responses);
        ToolRegistry registry = new ToolRegistry();
        registry.register("get_weather", "查询天气")
                .handle(args -> args.getString("city") + "晴天");

        Agent agent = Agent.builder(llm, registry).build();

        List<AgentEvent> events = new ArrayList<>();
        ChatResult result = agent.run("北京天气", events::add);

        assertEquals("北京晴天", result.content());

        // Round 1: RoundStart, ToolCallDelta, RoundEnd, ToolStart, ToolEnd
        // Round 2: RoundStart, TextDelta, RoundEnd, Complete
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.ToolCallDelta));
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.ToolStart));
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.ToolEnd));
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.Complete));

        // Verify ToolStart/ToolEnd contain correct tool info
        AgentEvent.ToolStart toolStart = (AgentEvent.ToolStart) events.stream()
                .filter(e -> e instanceof AgentEvent.ToolStart).findFirst().orElse(null);
        assertNotNull(toolStart);
        assertEquals("get_weather", toolStart.toolName());
        assertEquals("{\"city\":\"北京\"}", toolStart.arguments());
    }

    @Test
    public void testStreamingWithThinking() {
        MockLLM llm = new MockLLM(Collections.singletonList(
                new ChatResult("回复", "思考过程")
        ));
        ToolRegistry registry = new ToolRegistry();

        Agent agent = Agent.builder(llm, registry).build();

        List<AgentEvent> events = new ArrayList<>();
        agent.run("测试", events::add);

        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.ThinkingDelta));
        AgentEvent.ThinkingDelta thinking = (AgentEvent.ThinkingDelta) events.stream()
                .filter(e -> e instanceof AgentEvent.ThinkingDelta).findFirst().orElse(null);
        assertEquals("思考过程", thinking.delta());
    }

    @Test
    public void testStreamingMaxIterationsError() {
        // review 关键点1:默认 graceful,用 graceTurns(0) 回退抛异常路径测试
        ToolCall toolCall = new ToolCall("call_1", "search", "{\"q\":\"test\"}");
        List<ChatResult> endlessResponses = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            endlessResponses.add(new ChatResult("", null, Collections.singletonList(toolCall)));
        }

        MockLLM llm = new MockLLM(endlessResponses);
        ToolRegistry registry = new ToolRegistry();
        registry.register("search", "搜索").handle(args -> "结果");

        Agent agent = Agent.builder(llm, registry).maxIterations(3).graceTurns(0).build();

        List<AgentEvent> events = new ArrayList<>();
        try {
            agent.run("测试", events::add);
            fail("应抛出 AgentException");
        } catch (AgentException e) {
            assertTrue(e.getMessage().contains("超出最大迭代次数"));
            // Should have AgentError event before exception
            assertTrue(events.stream().anyMatch(ev -> ev instanceof AgentEvent.AgentError));
        }
    }

    @Test
    public void testStreamingRunPreservesExistingBehavior() {
        // Without eventConsumer, behavior is identical to non-streaming
        MockLLM llm = new MockLLM(Collections.singletonList(
                new ChatResult("回复", null)
        ));
        ToolRegistry registry = new ToolRegistry();

        Agent agent = Agent.builder(llm, registry).build();
        ChatResult result = agent.run("测试"); // no eventConsumer

        assertEquals("回复", result.content());
    }

    @Test
    public void testStreamingWithMessageList() {
        MockLLM llm = new MockLLM(Collections.singletonList(
                new ChatResult("回复", null)
        ));
        ToolRegistry registry = new ToolRegistry();
        Agent agent = Agent.builder(llm, registry).build();

        List<Message> messages = new ArrayList<>();
        messages.add(Message.user("你好"));

        List<AgentEvent> events = new ArrayList<>();
        ChatResult result = agent.run(messages, events::add);

        assertEquals("回复", result.content());
        assertFalse(events.isEmpty());
    }

    private void assertEventTypes(List<AgentEvent> events, Class<?>... expectedTypes) {
        List<Class<?>> actualTypes = events.stream().map(Object::getClass).collect(Collectors.toList());
        assertEquals(Arrays.asList(expectedTypes), actualTypes);
    }
}
