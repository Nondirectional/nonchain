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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 委派型 SubAgent MVP 端到端测试：父 Agent 通过 tool calling 委派子任务给子代理。
 *
 * <p>覆盖 design.md 第 10 节测试重点：DIRECT/DELEGATE 暴露、继承/覆盖 LLM、
 * 子代理失败软回灌、并行委派、上下文裁剪（排除 llmVisible=false/不含父 systemPrompt）、
 * 父/子 callback 隔离、fail-fast。注册层契约见 {@code tool.SubAgentToolRegistryTest}。</p>
 */
public class SubAgentTest {

    /**
     * 可编程 mock LLM：按预设顺序返回结果，记录每次调用收到的消息与工具列表。
     * 多个 Agent 共享同一实例时，所有调用串行消费 responses。
     */
    static class MockLLM implements LLM {

        private final List<ChatResult> responses;
        private int callIndex = 0;
        private final List<List<Message>> capturedMessages = new ArrayList<>();
        private final List<List<com.non.chain.tool.Tool>> capturedTools = new ArrayList<>();

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
            capturedTools.add(new ArrayList<>(tools));
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

        List<List<Message>> getCapturedMessages() {
            return capturedMessages;
        }

        List<List<com.non.chain.tool.Tool>> getCapturedTools() {
            return capturedTools;
        }
    }

    private static ChatResult reply(String content) {
        return new ChatResult(content, null);
    }

    private static ChatResult toolCall(String callId, String toolName, String argsJson) {
        return new ChatResult("", null, Collections.singletonList(new ToolCall(callId, toolName, argsJson)));
    }

    private static ChatResult toolCalls(ToolCall... calls) {
        return new ChatResult("", null, Arrays.asList(calls));
    }

    // ====================== DIRECT 模式 ======================

    @Test
    public void directModeExposesIndependentSubAgentTool() {
        MockLLM llm = new MockLLM(Collections.singletonList(reply("ok")));
        ToolRegistry registry = new ToolRegistry();
        registry.register("normal", "普通工具").handle(args -> "n");
        registry.registerSubAgent("research", "调研")
                .systemPrompt("你是调研代理。")
                .build();

        Agent agent = Agent.builder(llm, registry).build();
        agent.run("hi");

        List<com.non.chain.tool.Tool> tools = llm.getCapturedTools().get(0);
        List<String> names = new ArrayList<>();
        for (com.non.chain.tool.Tool t : tools) {
            names.add(t.name());
        }
        // DIRECT 模式：普通工具 + 独立子代理 tool；无 delegate tool
        assertTrue(names.contains("normal"));
        assertTrue(names.contains("research"));
        assertFalse(names.contains(ToolRegistry.DELEGATE_TOOL_NAME));
    }

    @Test
    public void directModeDelegatesAndReturnsChildResult() {
        // 父 LLM 第一轮委派 research；子 LLM 返回调研结果；
        // 父 LLM 第二轮据子结果给出最终答复。
        // 父与子共享同一 mock，调用顺序：父(委派) → 子(结果) → 父(最终)
        MockLLM sharedLlm = new MockLLM(Arrays.asList(
                toolCall("c1", "research", "{\"task\":\"调研量子计算\"}"),
                reply("量子计算调研结论：..."),
                reply("根据调研：量子计算结论。")
        ));

        ToolRegistry registry = new ToolRegistry();
        registry.registerSubAgent("research", "调研")
                .systemPrompt("你是调研代理。")
                .build();

        Agent agent = Agent.builder(sharedLlm, registry).build();
        ChatResult result = agent.run("帮我调研量子计算");

        assertEquals("根据调研：量子计算结论。", result.content());

        // 第二次 LLM 调用（即父第二轮）的消息应含子代理结果作为 tool result
        List<Message> parentSecondCall = sharedLlm.getCapturedMessages().get(2);
        Message toolResult = parentSecondCall.get(2);
        assertEquals("tool", toolResult.role());
        assertEquals("c1", toolResult.toolCallId());
        assertEquals("量子计算调研结论：...", toolResult.content());
    }

    @Test
    public void directModeChildInheritsParentLlm() {
        // 不显式设置子代理 LLM，应继承父 LLM（共享 mock 的 responses 按序消费）
        MockLLM parentLlm = new MockLLM(Arrays.asList(
                toolCall("c1", "sub", "{\"task\":\"t\"}"),
                reply("子结果"),      // 子代理用继承的父 LLM 调用
                reply("最终答复")     // 父第二轮
        ));

        ToolRegistry registry = new ToolRegistry();
        registry.registerSubAgent("sub", "子代理")
                .systemPrompt("子角色")
                .build();

        Agent agent = Agent.builder(parentLlm, registry).build();
        ChatResult result = agent.run("test");
        assertEquals("最终答复", result.content());
    }

    @Test
    public void directModeChildOverridesLlm() {
        // 父 LLM 委派；子 LLM 独立返回；各自只被调用该用的次数
        MockLLM parentLlm = new MockLLM(Arrays.asList(
                toolCall("c1", "sub", "{\"task\":\"t\"}"),
                reply("父最终答复")
        ));
        MockLLM childLlm = new MockLLM(Collections.singletonList(reply("子专用LLM结果")));

        ToolRegistry registry = new ToolRegistry();
        registry.registerSubAgent("sub", "子代理")
                .systemPrompt("子角色")
                .llm(childLlm)
                .build();

        Agent agent = Agent.builder(parentLlm, registry).build();
        ChatResult result = agent.run("test");

        assertEquals("父最终答复", result.content());
        // 子 LLM 被调用 1 次（子代理执行）
        assertEquals(1, childLlm.getCapturedMessages().size());
        // 父第二轮消息含子 LLM 的结果
        List<Message> parentSecondCall = parentLlm.getCapturedMessages().get(1);
        assertEquals("子专用LLM结果", parentSecondCall.get(2).content());
    }

    @Test
    public void directModeChildOverridesMaxIterations() {
        // 子代理 maxIterations=1，子 LLM 持续调用工具 → 子代理超出迭代抛异常 → 父软失败回灌
        ToolCall childToolCall = new ToolCall("cc", "kid_tool", "{}");
        List<ChatResult> childResponses = new ArrayList<>();
        childResponses.add(new ChatResult("", null, Collections.singletonList(childToolCall))); // 始终调工具

        MockLLM childLlm = new MockLLM(childResponses);

        ToolRegistry childRegistry = new ToolRegistry();
        childRegistry.register("kid_tool", "子工具").handle(args -> "k");

        ToolRegistry registry = new ToolRegistry();
        registry.registerSubAgent("sub", "子代理")
                .systemPrompt("子角色")
                .llm(childLlm)
                .toolRegistry(childRegistry)
                .maxIterations(1)
                .build();

        MockLLM parentLlm = new MockLLM(Arrays.asList(
                toolCall("c1", "sub", "{\"task\":\"t\"}"),
                reply("父收到错误后答复")
        ));

        Agent agent = Agent.builder(parentLlm, registry).build();
        ChatResult result = agent.run("test");

        assertEquals("父收到错误后答复", result.content());
        // 父第二轮的 tool result 应是子代理失败文本（软失败回灌）
        Message failResult = parentLlm.getCapturedMessages().get(1).get(2);
        assertTrue(failResult.content().contains("工具执行失败"));
    }

    // ====================== DELEGATE 模式 ======================

    @Test
    public void delegateModeExposesSingleDelegateTool() {
        MockLLM llm = new MockLLM(Collections.singletonList(reply("ok")));
        ToolRegistry registry = new ToolRegistry();
        registry.register("normal", "普通工具").handle(args -> "n");
        registry.registerSubAgent("research", "调研").systemPrompt("s").build();
        registry.registerSubAgent("review", "审核").systemPrompt("s").build();

        Agent agent = Agent.builder(llm, registry)
                .subAgentExposureMode(SubAgentExposureMode.DELEGATE)
                .build();
        agent.run("hi");

        List<com.non.chain.tool.Tool> tools = llm.getCapturedTools().get(0);
        List<String> names = new ArrayList<>();
        for (com.non.chain.tool.Tool t : tools) {
            names.add(t.name());
        }
        // DELEGATE 模式：普通工具 + 单个 delegate tool；不暴露独立子代理 tool
        assertTrue(names.contains("normal"));
        assertTrue(names.contains(ToolRegistry.DELEGATE_TOOL_NAME));
        assertFalse(names.contains("research"));
        assertFalse(names.contains("review"));
    }

    @Test
    public void delegateModeRoutesByAgentName() {
        MockLLM sharedLlm = new MockLLM(Arrays.asList(
                toolCall("c1", ToolRegistry.DELEGATE_TOOL_NAME,
                        "{\"agentName\":\"research\",\"task\":\"调研\"}"),
                reply("调研结果"),
                reply("父最终答复")
        ));

        ToolRegistry registry = new ToolRegistry();
        registry.registerSubAgent("research", "调研").systemPrompt("s").build();

        Agent agent = Agent.builder(sharedLlm, registry)
                .subAgentExposureMode(SubAgentExposureMode.DELEGATE)
                .build();
        ChatResult result = agent.run("test");

        assertEquals("父最终答复", result.content());
        // 父第二轮 tool result 来自被 delegate 的 research 子代理
        List<Message> parentSecondCall = sharedLlm.getCapturedMessages().get(2);
        assertEquals("调研结果", parentSecondCall.get(2).content());
    }

    // ====================== 上下文裁剪 ======================

    @Test
    public void childContextExcludesInvisibleAndParentSystemPrompt() {
        // 用子代理 LLM 捕获子代理实际收到的消息，验证：
        // 1) 含子代理 systemPrompt（第一条 system）
        // 2) 含父上下文中 llmVisible 的消息
        // 3) 不含 llmVisible=false 的 note 消息
        // 4) 不含父 systemPrompt
        MockLLM childLlm = new MockLLM(Collections.singletonList(reply("子结果")));

        ToolRegistry registry = new ToolRegistry();
        registry.registerSubAgent("sub", "子代理")
                .systemPrompt("子代理角色")
                .llm(childLlm)
                .build();

        MockLLM parentLlm = new MockLLM(Arrays.asList(
                toolCall("c1", "sub", "{\"task\":\"t\"}"),
                reply("父最终答复")
        ));

        Agent agent = Agent.builder(parentLlm, registry)
                .systemPrompt("父级系统提示")
                .build();

        // 直接用消息列表入口构造含 note 的父上下文
        List<Message> parentMessages = new ArrayList<>();
        parentMessages.add(Message.user("可见的用户问题"));
        parentMessages.add(Message.note("status", "正在思考")); // llmVisible=false

        agent.run(parentMessages);

        List<Message> childMessages = childLlm.getCapturedMessages().get(0);
        // 子代理消息：子 systemPrompt + 父可见消息 + task(user)
        // 第一条应是子代理 systemPrompt，而非父 systemPrompt
        assertEquals("system", childMessages.get(0).role());
        assertEquals("子代理角色", childMessages.get(0).content());

        // 不含父 systemPrompt
        for (Message m : childMessages) {
            if ("system".equals(m.role())) {
                assertEquals("子代理角色", m.content());
            }
        }
        // 不含 llmVisible=false 的 note
        for (Message m : childMessages) {
            assertTrue("子代理上下文不应含 llmVisible=false 消息", m.llmVisible());
        }
        // 含可见的父用户消息
        boolean hasVisibleUser = false;
        for (Message m : childMessages) {
            if ("可见的用户问题".equals(m.content())) {
                hasVisibleUser = true;
            }
        }
        assertTrue(hasVisibleUser);
        // 最后一条是 task
        Message last = childMessages.get(childMessages.size() - 1);
        assertEquals("user", last.role());
        assertEquals("t", last.content());
    }

    @Test
    public void childContextCustomSelectorApplied() {
        MockLLM childLlm = new MockLLM(Collections.singletonList(reply("子结果")));

        ToolRegistry registry = new ToolRegistry();
        registry.registerSubAgent("sub", "子代理")
                .systemPrompt("子角色")
                .llm(childLlm)
                .contextSelector((parent, assistant, task) -> {
                    // 自定义：只返回一个固定占位消息
                    return Collections.singletonList(Message.user("自定义裁剪上下文"));
                })
                .build();

        MockLLM parentLlm = new MockLLM(Arrays.asList(
                toolCall("c1", "sub", "{\"task\":\"t\"}"),
                reply("父答复")
        ));

        Agent agent = Agent.builder(parentLlm, registry).build();
        agent.run("父问题");

        List<Message> childMessages = childLlm.getCapturedMessages().get(0);
        // 子 systemPrompt + 自定义裁剪(1) + task(1)
        assertEquals(3, childMessages.size());
        assertEquals("自定义裁剪上下文", childMessages.get(1).content());
    }

    @Test
    public void childContextSanitizesSystemInvisibleAndIncompleteToolGroups() {
        MockLLM childLlm = new MockLLM(Collections.singletonList(reply("子结果")));

        ToolRegistry registry = new ToolRegistry();
        registry.registerSubAgent("sub", "子代理")
                .systemPrompt("子角色")
                .llm(childLlm)
                .contextSelector((parent, assistant, task) -> Arrays.asList(
                        Message.system("父 selector system"),
                        Message.note("status", "隐藏状态"),
                        Message.assistantWithToolCalls("", Collections.singletonList(
                                new ToolCall("orphan-call", "lookup", "{}"))),
                        Message.toolResult("other-call", "孤立结果"),
                        Message.user("保留的上下文")
                ))
                .build();

        MockLLM parentLlm = new MockLLM(Arrays.asList(
                toolCall("c1", "sub", "{\"task\":\"t\"}"),
                reply("父答复")
        ));

        Agent agent = Agent.builder(parentLlm, registry).build();
        agent.run("父问题");

        List<Message> childMessages = childLlm.getCapturedMessages().get(0);
        assertEquals("system", childMessages.get(0).role());
        assertEquals("子角色", childMessages.get(0).content());
        assertEquals("user", childMessages.get(1).role());
        assertEquals("保留的上下文", childMessages.get(1).content());
        assertEquals("t", childMessages.get(2).content());
        for (Message message : childMessages) {
            assertTrue("子代理消息不应包含不可见消息", message.llmVisible());
            assertFalse("父 system 不应绕过 selector 过滤", "父 selector system".equals(message.content()));
            assertFalse("不应留下孤立工具调用", "tool".equals(message.role()));
            assertFalse("不应留下孤立 assistant(toolCalls)",
                    "assistant".equals(message.role())
                            && message.toolCalls() != null && !message.toolCalls().isEmpty());
        }
    }

    @Test
    public void childContextPreservesCompleteToolCallGroup() {
        MockLLM childLlm = new MockLLM(Collections.singletonList(reply("子结果")));
        ToolCall c1 = new ToolCall("call-1", "lookup", "{}");
        ToolCall c2 = new ToolCall("call-2", "read", "{}");

        ToolRegistry registry = new ToolRegistry();
        registry.registerSubAgent("sub", "子代理")
                .systemPrompt("子角色")
                .llm(childLlm)
                .contextSelector((parent, assistant, task) -> Arrays.asList(
                        Message.user("前置上下文"),
                        Message.assistantWithToolCalls("", Arrays.asList(c1, c2)),
                        Message.toolResult("call-2", "第二个结果"),
                        Message.toolResult("call-1", "第一个结果"),
                        Message.note("ui", "不进模型")
                ))
                .build();

        MockLLM parentLlm = new MockLLM(Arrays.asList(
                toolCall("c1", "sub", "{\"task\":\"t\"}"),
                reply("父答复")
        ));

        Agent agent = Agent.builder(parentLlm, registry).build();
        agent.run("父问题");

        List<Message> childMessages = childLlm.getCapturedMessages().get(0);
        assertEquals(6, childMessages.size());
        assertEquals("前置上下文", childMessages.get(1).content());
        assertEquals("assistant", childMessages.get(2).role());
        assertEquals(2, childMessages.get(2).toolCalls().size());
        assertEquals("call-2", childMessages.get(3).toolCallId());
        assertEquals("call-1", childMessages.get(4).toolCallId());
        assertEquals("t", childMessages.get(5).content());
    }

    @Test
    public void backgroundContextWindowDropsSplitToolGroup() {
        MockLLM childLlm = new MockLLM(Collections.singletonList(reply("后台子结果")));
        ToolRegistry registry = new ToolRegistry();
        registry.registerSubAgent("worker", "后台子代理")
                .systemPrompt("后台角色")
                .llm(childLlm)
                .build();

        MockLLM parentLlm = new MockLLM(Arrays.asList(
                toolCall("bg-1", "worker", "{\"task\":\"后台任务\",\"run_in_background\":true}"),
                reply("父完成"),
                reply("父完成"),
                reply("父完成")
        ));
        Agent agent = Agent.builder(parentLlm, registry).build();

        List<Message> parentMessages = Arrays.asList(
                Message.user("旧问题"),
                Message.assistantWithToolCalls("", Arrays.asList(
                        new ToolCall("old-1", "lookup", "{}"),
                        new ToolCall("old-2", "read", "{}"))),
                Message.toolResult("old-1", "旧结果一"),
                Message.toolResult("old-2", "旧结果二"),
                Message.user("最新问题")
        );
        assertEquals("父完成", agent.run(parentMessages).content());

        List<Message> childMessages = childLlm.getCapturedMessages().get(0);
        assertEquals("system", childMessages.get(0).role());
        assertEquals("最新问题", childMessages.get(1).content());
        assertEquals("后台任务", childMessages.get(2).content());
        for (Message message : childMessages) {
            assertFalse("后台窗口截断后不应保留孤立 tool", "tool".equals(message.role()));
            assertFalse("后台窗口截断后不应保留孤立 tool-call",
                    "assistant".equals(message.role())
                            && message.toolCalls() != null && !message.toolCalls().isEmpty());
        }
    }

    // ====================== 并行委派 ======================

    @Test
    public void parallelSubAgentsReturnInOriginalOrder() {
        // 同一轮父 LLM 委派两个子代理（并行），结果按 tool call 原始顺序回灌。
        // 用独立子 LLM，使两个子代理各自返回可区分的固定结果，避免共享 mock 的顺序耦合。
        ToolCall tc1 = new ToolCall("c1", "research", "{\"task\":\"A\"}");
        ToolCall tc2 = new ToolCall("c2", "review", "{\"task\":\"B\"}");

        MockLLM researchLlm = new MockLLM(Collections.singletonList(reply("research 结果")));
        MockLLM reviewLlm = new MockLLM(Collections.singletonList(reply("review 结果")));

        ToolRegistry registry = new ToolRegistry();
        registry.registerSubAgent("research", "调研")
                .systemPrompt("s").llm(researchLlm).build();
        registry.registerSubAgent("review", "审核")
                .systemPrompt("s").llm(reviewLlm).build();

        MockLLM parentLlm = new MockLLM(Arrays.asList(
                toolCalls(tc1, tc2),
                reply("父最终答复")
        ));

        Agent agent = Agent.builder(parentLlm, registry).build();
        ChatResult result = agent.run("test");

        assertEquals("父最终答复", result.content());

        // 父第二轮消息的 tool result 按原始 c1/research → c2/review 顺序（即使并行）
        List<Message> parentSecondCall = parentLlm.getCapturedMessages().get(1);
        // [user, assistant(toolCalls), tool(c1), tool(c2)]
        Message r1 = parentSecondCall.get(2);
        Message r2 = parentSecondCall.get(3);
        assertEquals("c1", r1.toolCallId());
        assertEquals("research 结果", r1.content());
        assertEquals("c2", r2.toolCallId());
        assertEquals("review 结果", r2.content());
    }

    // ====================== 拦截器边界 ======================

    @Test
    public void parentBeforeInterceptorBlocksDelegation() {
        // 父 before 拦截 block 了委派 → 子代理不执行 → 父只被调用 2 次（委派 + 最终）
        MockLLM sharedLlm = new MockLLM(Arrays.asList(
                toolCall("c1", "sub", "{\"task\":\"t\"}"),
                reply("父最终答复")
        ));

        ToolRegistry registry = new ToolRegistry();
        registry.registerSubAgent("sub", "子代理").systemPrompt("s").build();

        Agent agent = Agent.builder(sharedLlm, registry)
                .addBeforeToolCall(ctx -> BeforeResult.block("禁止委派"))
                .build();
        ChatResult result = agent.run("test");

        assertEquals("父最终答复", result.content());
        // 子代理被 block，sharedLlm 只被调用 2 次（父委派 + 父最终），无子结果调用
        assertEquals(2, sharedLlm.getCapturedMessages().size());
        // 父第二轮 tool result 是 block reason
        Message blocked = sharedLlm.getCapturedMessages().get(1).get(2);
        assertEquals("禁止委派", blocked.content());
    }

    @Test
    public void childInterceptorsScopedToChildOnly() {
        // 子代理内部拦截器只作用于子代理内部工具，不作用于父层委派调用。
        // 用独立子 LLM，避免与父 mock 共享 response 序列造成混淆。
        List<String> childBeforeHits = Collections.synchronizedList(new ArrayList<>());

        ToolRegistry childRegistry = new ToolRegistry();
        childRegistry.register("kid_tool", "子工具").handle(args -> "k");

        MockLLM childLlm = new MockLLM(Arrays.asList(
                toolCall("cc", "kid_tool", "{}"),  // 子代理内部调用 kid_tool
                reply("子结果")                     // 子代理拿到 kid_tool 结果后回复
        ));

        ToolRegistry registry = new ToolRegistry();
        registry.registerSubAgent("sub", "子代理")
                .systemPrompt("s")
                .llm(childLlm)
                .toolRegistry(childRegistry)
                .addBeforeToolCall(ctx -> {
                    childBeforeHits.add(ctx.toolName());
                    return BeforeResult.pass();
                })
                .build();

        MockLLM parentLlm = new MockLLM(Arrays.asList(
                toolCall("c1", "sub", "{\"task\":\"t\"}"),
                reply("父最终答复")
        ));

        Agent agent = Agent.builder(parentLlm, registry).build();
        ChatResult result = agent.run("test");

        assertEquals("父最终答复", result.content());
        // 子拦截器命中 kid_tool（子代理内部），不命中 sub（父层委派走父拦截器，此处未配置）
        assertEquals(List.of("kid_tool"), childBeforeHits);
    }

    // ====================== fail-fast ======================

    @Test
    public void handWrittenLoopSubAgentFailsFast() {
        ToolRegistry registry = new ToolRegistry();
        registry.registerSubAgent("sub", "子代理").systemPrompt("s").build();

        try {
            registry.execute("sub", "{\"task\":\"t\"}");
            fail("手写循环直接 execute 子代理应 fail-fast");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("仅支持在 Agent 自动循环中执行"));
        }
    }

    // ====================== callback 隔离 ======================

    @Test
    public void childEventsDoNotPropagateToParentCallback() {
        // 子代理有自己的工具（kid_tool），子代理运行时会产生内部 LLM/Tool 事件。
        // 父 callback 隔离：不应看到子代理内部事件，只看到外层委派调用的 Tool 事件。
        ToolRegistry childRegistry = new ToolRegistry();
        childRegistry.register("kid_tool", "子工具").handle(args -> "k");

        MockLLM childLlm = new MockLLM(Arrays.asList(
                toolCall("cc", "kid_tool", "{}"),
                reply("子结果")
        ));

        ToolRegistry registry = new ToolRegistry();
        registry.registerSubAgent("sub", "子代理")
                .systemPrompt("s")
                .llm(childLlm)
                .toolRegistry(childRegistry)
                .build();

        MockLLM parentLlm = new MockLLM(Arrays.asList(
                toolCall("c1", "sub", "{\"task\":\"t\"}"),
                reply("父最终答复")
        ));

        // 记录父 callback 收到的所有事件
        List<String> parentToolEvents = Collections.synchronizedList(new ArrayList<>());
        com.non.chain.callback.ChainCallback parentCallback = new com.non.chain.callback.ChainCallback() {
            @Override
            public void onToolStart(com.non.chain.callback.event.ToolStartEvent event) {
                parentToolEvents.add("start:" + event.toolCall().name());
            }

            @Override
            public void onToolComplete(com.non.chain.callback.event.ToolCompleteEvent event) {
                parentToolEvents.add("complete:" + event.toolName());
            }
        };

        Agent agent = Agent.builder(parentLlm, registry)
                .callback(parentCallback)
                .build();
        agent.run("test");

        // 父 callback 只看到外层 sub 委派调用，看不到子代理内部的 kid_tool
        assertTrue(parentToolEvents.stream().anyMatch(s -> s.contains("sub")));
        assertFalse("子代理内部工具事件不应透出到父 callback: " + parentToolEvents,
                parentToolEvents.stream().anyMatch(s -> s.contains("kid_tool")));
    }
}
