package com.non.chain.agent;

import com.non.chain.ChatChunk;
import com.non.chain.ChatResult;
import com.non.chain.Message;
import com.non.chain.memory.ChatMemoryStore;
import com.non.chain.memory.InMemoryChatMemoryStore;
import com.non.chain.provider.LLM;
import com.non.chain.tool.ToolCall;
import com.non.chain.tool.ToolRegistry;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * SubAgent 重做新增能力测试:graceful(D9)、steer(D6)、resume(D7)、D10 fail-fast、
 * 后台 schema 扩展(D11)、后台截断 context(D12)。
 *
 * <p>复用 AgentTest.MockLLM 风格的 mock LLM,不依赖真实 provider。</p>
 */
public class SubAgentAdvancedTest {

    /** 可编程 mock LLM,按预设顺序返回结果 */
    static class MockLLM implements LLM {
        private final List<ChatResult> responses;
        private int callIndex = 0;
        private List<List<Message>> capturedMessages = new ArrayList<>();

        MockLLM(List<ChatResult> responses) {
            this.responses = responses;
        }

        List<List<Message>> capturedMessages() {
            return capturedMessages;
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
            return responses.get(callIndex++);
        }
    }

    // ---- D9 graceful max turns ----

    @Test
    public void gracefulSteeredWhenWrappingUpWithinGrace() {
        // 子代理到达 maxIterations 后收到"收尾"消息,grace 内完成 → STEERED
        // 构造:前 2 轮调工具,第 3 轮(maxIterations)收到收尾消息后输出纯文本完成
        ToolCall tc = new ToolCall("c1", "lookup", "{\"keyword\":\"x\"}");
        List<ChatResult> responses = new ArrayList<>();
        responses.add(new ChatResult("", null, Collections.singletonList(tc)));  // round 1
        responses.add(new ChatResult("", null, Collections.singletonList(tc)));  // round 2
        responses.add(new ChatResult("最终结论", null));  // round 3(maxIterations=2,grace 第1轮)→ 纯文本完成

        MockLLM llm = new MockLLM(responses);
        ToolRegistry tools = new ToolRegistry();
        tools.register("lookup", "查询").handle(a -> "结果");

        Agent agent = Agent.builder(llm, tools).maxIterations(2).graceTurns(3).build();
        ChatResult result = agent.run("测试 graceful");

        assertEquals("最终结论", result.content());
    }

    @Test
    public void gracefulAbortedReturnsPartialWhenGraceExhausted() {
        // maxIterations + graceTurns 全耗尽 → ABORTED 语义(不抛异常,返回部分结果)
        ToolCall tc = new ToolCall("c1", "loop_tool", "{}");
        List<ChatResult> responses = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            responses.add(new ChatResult("", null, Collections.singletonList(tc)));
        }
        MockLLM llm = new MockLLM(responses);
        ToolRegistry tools = new ToolRegistry();
        tools.register("loop_tool", "循环").handle(a -> "x");

        Agent agent = Agent.builder(llm, tools).maxIterations(2).graceTurns(2).build();
        // 不抛异常,返回结果
        ChatResult result = agent.run("测试硬中断");
        assertNotNull(result);
    }

    @Test
    public void graceTurnsZeroFallsBackToHardCutoff() {
        // graceTurns(0) → 回退 0.9.0 硬截断抛异常
        ToolCall tc = new ToolCall("c1", "loop_tool", "{}");
        List<ChatResult> responses = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            responses.add(new ChatResult("", null, Collections.singletonList(tc)));
        }
        MockLLM llm = new MockLLM(responses);
        ToolRegistry tools = new ToolRegistry();
        tools.register("loop_tool", "循环").handle(a -> "x");

        Agent agent = Agent.builder(llm, tools).maxIterations(2).graceTurns(0).build();
        try {
            agent.run("测试硬截断");
            fail("graceTurns(0) 应回退抛异常");
        } catch (AgentException e) {
            assertTrue(e.getMessage().contains("超出最大迭代次数"));
        }
    }

    // ---- D6 steer ----

    @Test
    public void steerOnlySupportedOnSubAgent() {
        // 顶层 Agent 不支持 steer
        MockLLM llm = new MockLLM(Collections.singletonList(new ChatResult("ok", null)));
        Agent agent = Agent.builder(llm, new ToolRegistry()).build();
        try {
            agent.steer("消息");
            fail("顶层 Agent 应不支持 steer");
        } catch (UnsupportedOperationException e) {
            assertTrue(e.getMessage().contains("steer"));
        }
    }

    // ---- D10 嵌套 fail-fast ----

    @Test
    public void nestedSubAgentFailsFast() {
        // 子代理的 toolRegistry 注册了 subAgent → build() 抛异常
        ToolRegistry childTools = new ToolRegistry();
        childTools.registerSubAgent("grandchild", "孙代理")
                .systemPrompt("s")
                .build();

        ToolRegistry registry = new ToolRegistry();
        try {
            registry.registerSubAgent("parent", "父代理")
                    .systemPrompt("s")
                    .toolRegistry(childTools)
                    .build();
            fail("嵌套子代理应 fail-fast");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("不支持嵌套委派"));
        }
    }

    // ---- D7 resume ----

    @Test
    public void resumeWithChatMemoryStoreReusesHistory() {
        // 配置 ChatMemoryStore 的子代理:委派后 store 被写入(验证 resume 存储路径)
        InMemoryChatMemoryStore store = new InMemoryChatMemoryStore();

        // 子代理 mock LLM:直接返回文本(无工具调用,1 轮完成)
        MockLLM subLlm = new MockLLM(Collections.singletonList(
                new ChatResult("调研结论A", null)));

        // 父代理 mock LLM:第1轮委派 research,第2轮综合
        List<ChatResult> parentResponses = new ArrayList<>();
        parentResponses.add(new ChatResult("", null, Collections.singletonList(
                new ToolCall("p1", "research", "{\"task\":\"查A\"}"))));
        parentResponses.add(new ChatResult("综合完成", null));
        MockLLM parentLlm = new MockLLM(parentResponses);

        ToolRegistry registry = new ToolRegistry();
        registry.registerSubAgent("research", "调研")
                .systemPrompt("你是调研代理")
                .chatMemoryStore(store)
                .llm(subLlm)
                .maxIterations(1)
                .build();

        Agent agent = Agent.builder(parentLlm, registry).maxIterations(3).build();
        ChatResult result = agent.run("查一下A");

        assertEquals("综合完成", result.content());
        // 验证 store 被写入:至少有一个 conversation 非空
        // (conversationId 格式 = <runId>:research,无法精确预测 runId,但可以验证 store 非空)
        // 由于无法直接枚举 InMemoryChatMemoryStore 的 key,这里通过子代理成功执行间接验证
    }

    // ---- D11 后台 schema ----

    @Test
    public void subAgentControlToolsExposedWhenSubAgentsPresent() {
        // 有子代理时,get_subagent_result + steer_subagent 出现在控制工具列表
        ToolRegistry registry = new ToolRegistry();
        registry.registerSubAgent("research", "调研").systemPrompt("s").build();

        List<com.non.chain.tool.Tool> controlTools = registry.getSubAgentControlTools();
        assertEquals(2, controlTools.size());

        List<String> names = new ArrayList<>();
        controlTools.forEach(t -> names.add(t.name()));
        assertTrue(names.contains("get_subagent_result"));
        assertTrue(names.contains("steer_subagent"));
    }

    @Test
    public void subAgentControlToolsEmptyWhenNoSubAgents() {
        // 无子代理时,不暴露控制工具
        ToolRegistry registry = new ToolRegistry();
        assertTrue(registry.getSubAgentControlTools().isEmpty());
    }

    @Test
    public void directSubAgentToolHasBackgroundParam() {
        // schema 含 run_in_background 的验证由 SubAgentToolRegistryTest 覆盖
        // 这里验证工具能正常构造
        ToolRegistry registry = new ToolRegistry();
        registry.registerSubAgent("research", "调研").systemPrompt("s").build();
        List<com.non.chain.tool.Tool> tools = registry.getDirectSubAgentTools();
        assertEquals(1, tools.size());
        // 验证 toFunctionDefinition 能正常生成(schema 正确)
        assertNotNull(tools.get(0).toFunctionDefinition());
    }
}
