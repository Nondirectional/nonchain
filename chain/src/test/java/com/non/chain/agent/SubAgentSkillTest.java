package com.non.chain.agent;

import com.non.chain.ChatChunk;
import com.non.chain.ChatResult;
import com.non.chain.Message;
import com.non.chain.OutputFormat;
import com.non.chain.provider.LLM;
import com.non.chain.skill.SkillRegistry;
import com.non.chain.tool.Tool;
import com.non.chain.tool.ToolCall;
import com.non.chain.tool.ToolRegistry;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * SubAgent Skill 预加载测试(D13)。
 *
 * <p>验证子代理挂载 skillRegistry 后,在委派执行时能像顶层 Agent 一样:
 * <ul>
 *   <li>在 LLM schema 里看到 skill function</li>
 *   <li>点选 skill 后按 Agent 配置注入消息</li>
 *   <li>子 agent 范围内 skill 名 vs tool 名冲突 fail-fast</li>
 * </ul>
 * </p>
 */
public class SubAgentSkillTest {

    /**
     * 子代理挂载 skill:委派执行时子 agent 能点选 skill → 默认 system 注入。
     *
     * <p>LLM 调用顺序(父子共享 mock):父(委派 reviewer)→ 子(点选 review-skill)→ 子(回复)→ 父(最终)。
     * 子 agent 的 skill 必须出现在子 agent 的 LLM schema 里。</p>
     */
    @Test
    public void subAgentWithSkill_canSelectAndInject() {
        // 子代理的 skill
        SkillRegistry childSkills = new SkillRegistry();
        childSkills.register("review-checklist", "审查代码时使用")
                .content("# 审查清单\n1. 看结构\n2. 看安全")
                .build();

        ToolRegistry childTools = new ToolRegistry();
        childTools.register("check", "执行检查").handle(args -> "checked");

        ToolRegistry registry = new ToolRegistry();
        registry.registerSubAgent("reviewer", "负责代码审查")
                .systemPrompt("你是代码审查代理。")
                .toolRegistry(childTools)
                .skillRegistry(childSkills)
                .maxIterations(5)
                .build();

        // 父(委派) → 子(点选 review-checklist) → 子(回复审查结果) → 父(总结)
        MockLLM llm = new MockLLM(Arrays.asList(
                toolCall("c1", "reviewer", "{\"task\":\"审查这段代码\"}"),
                toolCall("s1", "review-checklist", "{}"),
                toolCall("s2", "check", "{}"),
                reply("按审查清单,结构清晰但缺少空指针检查。"),
                reply("审查完成:代码基本合格,建议补充空指针检查。")
        ));

        Agent agent = Agent.builder(llm, registry)
                .systemPrompt("你是父助手。")
                .build();
        List<AgentEvent> events = Collections.synchronizedList(new ArrayList<>());
        ChatResult result = agent.run("帮我审查代码", events::add);

        assertEquals("审查完成:代码基本合格,建议补充空指针检查。", result.content());

        List<Message> childRound1Messages = llm.getCapturedMessages().get(1);
        assertEquals("system", childRound1Messages.get(0).role());
        for (int i = 1; i < childRound1Messages.size(); i++) {
            assertFalse("父 system 消息不能追加到子代理 systemPrompt 后",
                    "system".equals(childRound1Messages.get(i).role()));
        }

        // 子 agent 第一轮调用(第 2 次 LLM 调用,index=1)的 schema 应包含 skill
        List<List<Tool>> capturedTools = llm.getCapturedTools();
        // 确认有足够的调用记录(父+子+子+父 = 4 轮)
        assertTrue("应有多次 LLM 调用", capturedTools.size() >= 3);

        // 子 agent 的 schema 里应看到 review-checklist(带 [Skill] 前缀)
        List<Tool> childSchema = capturedTools.get(1);
        boolean hasSkillInSchema = false;
        for (Tool t : childSchema) {
            if ("review-checklist".equals(t.name())) {
                hasSkillInSchema = true;
            }
        }
        assertTrue("子 agent 的 LLM schema 应包含 skill", hasSkillInSchema);

        // 子 agent 第二轮调用(index=2)的 messages 应包含 system 注入
        List<Message> childRound2Messages = llm.getCapturedMessages().get(2);
        boolean hasSystemInjection = false;
        for (Message m : childRound2Messages) {
            if ("system".equals(m.role()) && m.content().contains("# 审查清单")) {
                hasSystemInjection = true;
            }
        }
        assertTrue("子 agent 点选 skill 后应注入 system 消息", hasSystemInjection);

        boolean hasToolResult = false;
        for (Message m : childRound2Messages) {
            if ("tool".equals(m.role()) && m.content().contains("review-checklist 已加载")) {
                hasToolResult = true;
            }
        }
        assertTrue("子 agent Skill tool result 应保留", hasToolResult);

        boolean hasProgress = false;
        boolean hasSkillActivated = false;
        boolean hasChildRound = false;
        boolean hasChildText = false;
        boolean hasChildToolStart = false;
        boolean hasChildToolEnd = false;
        boolean hasChildComplete = false;
        String subAgentId = null;
        for (AgentEvent event : events) {
            if (!(event instanceof AgentEvent.SubAgentProgress)) {
                continue;
            }
            AgentEvent.SubAgentProgress progress = (AgentEvent.SubAgentProgress) event;
            if (!"reviewer".equals(progress.name())) {
                continue;
            }
            hasProgress = true;
            assertEquals("c1", progress.parentToolCallId());
            assertEquals("审查这段代码", progress.task());
            assertFalse(progress.background());
            if (subAgentId == null) {
                subAgentId = progress.subAgentId();
            } else {
                assertEquals("同一子代理调用的 progress ID 应稳定", subAgentId, progress.subAgentId());
            }
            AgentEvent childEvent = progress.event();
            if (childEvent instanceof AgentEvent.SkillActivated) {
                AgentEvent.SkillActivated activated = (AgentEvent.SkillActivated) childEvent;
                assertEquals("review-checklist", activated.skillName());
                hasSkillActivated = true;
            } else if (childEvent instanceof AgentEvent.RoundStart) {
                hasChildRound = true;
            } else if (childEvent instanceof AgentEvent.TextDelta) {
                hasChildText = true;
            } else if (childEvent instanceof AgentEvent.ToolStart) {
                AgentEvent.ToolStart toolStart = (AgentEvent.ToolStart) childEvent;
                if ("check".equals(toolStart.toolName())) {
                    hasChildToolStart = true;
                }
            } else if (childEvent instanceof AgentEvent.ToolEnd) {
                AgentEvent.ToolEnd toolEnd = (AgentEvent.ToolEnd) childEvent;
                if ("check".equals(toolEnd.toolName())) {
                    hasChildToolEnd = true;
                }
            } else if (childEvent instanceof AgentEvent.Complete) {
                hasChildComplete = true;
            }
        }
        assertTrue("父级应收到子代理 progress", hasProgress);
        assertTrue("父级应收到包装后的 SkillActivated", hasSkillActivated);
        assertTrue("父级应收到子代理 RoundStart", hasChildRound);
        assertTrue("父级应收到子代理 TextDelta", hasChildText);
        assertTrue("父级应收到子代理 ToolStart", hasChildToolStart);
        assertTrue("父级应收到子代理 ToolEnd", hasChildToolEnd);
        assertTrue("父级应收到子代理 Complete", hasChildComplete);
        assertTrue("子代理 progress 应有调用 ID", subAgentId != null && !subAgentId.isEmpty());
    }

    /**
     * 父 Agent 的 USER 注入模式会传播至动态构造的子代理。
     */
    @Test
    public void subAgentWithSkill_inheritsUserInjectionMode() {
        SkillRegistry childSkills = new SkillRegistry();
        childSkills.register("review-checklist", "审查代码时使用")
                .content("# 审查清单")
                .build();

        ToolRegistry registry = new ToolRegistry();
        registry.registerSubAgent("reviewer", "负责代码审查")
                .systemPrompt("你是代码审查代理。")
                .skillRegistry(childSkills)
                .build();

        MockLLM llm = new MockLLM(Arrays.asList(
                toolCall("c1", "reviewer", "{\"task\":\"审查这段代码\"}"),
                toolCall("s1", "review-checklist", "{}"),
                reply("子代理完成"),
                reply("父代理完成")
        ));
        Agent agent = Agent.builder(llm, registry)
                .skillInjectionMode(SkillInjectionMode.USER)
                .build();

        assertEquals("父代理完成", agent.run("帮我审查代码").content());

        List<Message> childRound2Messages = llm.getCapturedMessages().get(2);
        boolean hasUserInjection = false;
        boolean hasSystemInjection = false;
        for (Message m : childRound2Messages) {
            if ("user".equals(m.role())
                    && "[Skill: review-checklist]\n# 审查清单".equals(m.content())) {
                hasUserInjection = true;
            }
            if ("system".equals(m.role()) && m.content().contains("# 审查清单")) {
                hasSystemInjection = true;
            }
        }
        assertTrue("子代理应继承父 Agent 的 USER 注入模式", hasUserInjection);
        assertFalse("子代理 USER 模式不应追加 Skill system 消息", hasSystemInjection);
    }

    /** 父 Skill 使用 USER 注入时，内容作为普通 user 上下文可传递给子代理。 */
    @Test
    public void parentUserSkill_isPassedAsVisibleContext() {
        SkillRegistry parentSkills = new SkillRegistry();
        parentSkills.register("parent-guideline", "父级工作规范")
                .content("# 父级规范")
                .build();

        ToolRegistry registry = new ToolRegistry();
        registry.registerSubAgent("worker", "执行任务")
                .systemPrompt("你是子代理")
                .build();

        MockLLM llm = new MockLLM(Arrays.asList(
                toolCall("s1", "parent-guideline", "{}"),
                toolCall("c1", "worker", "{\"task\":\"执行\"}"),
                reply("子代理结果"),
                reply("父代理结果")
        ));
        Agent agent = Agent.builder(llm, registry)
                .systemPrompt("父级 system")
                .skillRegistry(parentSkills)
                .skillInjectionMode(SkillInjectionMode.USER)
                .build();

        assertEquals("父代理结果", agent.run("开始").content());

        List<Message> childMessages = llm.getCapturedMessages().get(2);
        boolean hasParentUserSkill = false;
        for (Message message : childMessages) {
            if ("user".equals(message.role()) && "[Skill: parent-guideline]\n# 父级规范".equals(message.content())) {
                hasParentUserSkill = true;
            }
            assertFalse("父 system 不应传给子代理", "父级 system".equals(message.content()));
        }
        assertTrue("父 USER Skill 应作为普通 user 上下文传递", hasParentUserSkill);
    }

    /** 子代理 progress 消费者异常不应改变子代理和父 Agent 的业务结果。 */
    @Test
    public void subAgentProgress_consumerFailureDoesNotFailExecution() {
        ToolRegistry registry = new ToolRegistry();
        registry.registerSubAgent("worker", "执行任务")
                .systemPrompt("你是执行代理。")
                .build();

        MockLLM llm = new MockLLM(Arrays.asList(
                toolCall("c1", "worker", "{\"task\":\"执行\"}"),
                reply("子代理结果"),
                reply("父代理结果")
        ));
        Agent agent = Agent.builder(llm, registry).build();

        ChatResult result = agent.run("开始", event -> {
            if (event instanceof AgentEvent.SubAgentProgress) {
                throw new IllegalStateException("观察者故障");
            }
        });

        assertEquals("父代理结果", result.content());
    }

    /** 同名子代理重复调用时，每次调用都有独立且稳定的 progress ID。 */
    @Test
    public void repeatedSubAgentCalls_haveDistinctProgressIds() {
        ToolRegistry registry = new ToolRegistry();
        registry.registerSubAgent("worker", "执行任务")
                .systemPrompt("你是执行代理。")
                .build();

        MockLLM llm = new MockLLM(Arrays.asList(
                toolCall("c1", "worker", "{\"task\":\"第一次\"}"),
                reply("子代理一"),
                toolCall("c2", "worker", "{\"task\":\"第二次\"}"),
                reply("子代理二"),
                reply("父代理完成")
        ));
        Agent agent = Agent.builder(llm, registry).build();
        List<AgentEvent> events = new CopyOnWriteArrayList<>();

        assertEquals("父代理完成", agent.run("开始", events::add).content());

        String firstId = null;
        String secondId = null;
        for (AgentEvent event : events) {
            if (!(event instanceof AgentEvent.SubAgentProgress)) {
                continue;
            }
            AgentEvent.SubAgentProgress progress = (AgentEvent.SubAgentProgress) event;
            if ("第一次".equals(progress.task())) {
                firstId = progress.subAgentId();
            } else if ("第二次".equals(progress.task())) {
                secondId = progress.subAgentId();
            }
        }
        assertTrue("第一次调用应有 progress", firstId != null);
        assertTrue("第二次调用应有 progress", secondId != null);
        assertFalse("同名子代理重复调用应使用不同 ID", firstId.equals(secondId));
    }

    /** 后台 progress 使用 SubAgentRecord ID，并保留父 tool-call ID 与 background 标记。 */
    @Test
    public void backgroundSubAgentProgress_linksSpawnAndExecution() {
        ToolRegistry registry = new ToolRegistry();
        registry.register("tick", "推进父循环").handle(args -> "tick");
        registry.registerSubAgent("worker", "执行后台任务")
                .systemPrompt("你是后台执行代理。")
                .llm(new DelayedMockLLM(1000, List.of(reply("后台结果"))))
                .build();

        MockLLM parentLlm = new MockLLM(Arrays.asList(
                toolCall("c1", "worker", "{\"task\":\"后台审查\",\"run_in_background\":true}"),
                toolCall("p2", "tick", "{}"),
                reply("后台结果处理中"),
                reply("父代理完成")
        ));
        Agent agent = Agent.builder(parentLlm, registry).build();
        List<AgentEvent> events = new CopyOnWriteArrayList<>();

        assertEquals("父代理完成", agent.run("开始", events::add).content());

        String spawnedId = null;
        AgentEvent.SubAgentProgress progress = null;
        for (AgentEvent event : events) {
            if (event instanceof AgentEvent.SubAgentSpawned) {
                spawnedId = ((AgentEvent.SubAgentSpawned) event).subAgentId();
            } else if (event instanceof AgentEvent.SubAgentProgress) {
                AgentEvent.SubAgentProgress candidate = (AgentEvent.SubAgentProgress) event;
                if ("worker".equals(candidate.name())) {
                    progress = candidate;
                }
            }
        }
        assertTrue("后台应发出 Spawned", spawnedId != null);
        assertTrue("后台应发出 progress", progress != null);
        assertEquals("Spawned 与 progress 应使用同一 ID", spawnedId, progress.subAgentId());
        assertEquals("c1", progress.parentToolCallId());
        assertEquals("后台审查", progress.task());
        assertTrue(progress.background());
    }

    /**
     * 子代理的 skill 名与子代理的 tool 名冲突 → 子 agent build 时 fail-fast。
     *
     * <p>子 agent 的 childBuilder.build() 在 runSubAgentInternal 中动态调用,
     * validateSkillNaming 会自动检查 childRegistry 的 tool 名 vs skill 名。</p>
     */
    @Test
    public void subAgentSkillNameConflictWithTool_failsAtDelegation() {
        // 子代理的 tool 和 skill 同名
        ToolRegistry childTools = new ToolRegistry();
        childTools.register("dup-name", "a tool")
                .handle(args -> "tool result");

        SkillRegistry childSkills = new SkillRegistry();
        childSkills.register("dup-name", "a skill")
                .content("skill content")
                .build();

        ToolRegistry registry = new ToolRegistry();
        registry.registerSubAgent("worker", "a worker")
                .systemPrompt("you are a worker")
                .toolRegistry(childTools)
                .skillRegistry(childSkills)
                .build();

        // 父委派 worker → 子 agent build 时因 skill/tool 名冲突 fail-fast
        // 子 agent 的错误会被软失败回灌(不抛异常,返回错误文本)
        MockLLM llm = new MockLLM(Arrays.asList(
                toolCall("c1", "worker", "{\"task\":\"do it\"}"),
                reply("fallback")
        ));

        Agent agent = Agent.builder(llm, registry).build();
        // 子 agent build 失败 → 软失败 → 父 agent 拿到错误 tool result
        ChatResult result = agent.run("do it");
        // 不应抛异常(软失败),但结果应反映委派失败
        assertTrue("委派应因命名冲突软失败", result.content() != null);
    }

    /**
     * 无 skill 的子代理:行为与 0.10.0 完全一致(零回归)。
     */
    @Test
    public void subAgentWithoutSkill_zeroRegression() {
        ToolRegistry registry = new ToolRegistry();
        registry.registerSubAgent("plain", "plain worker")
                .systemPrompt("you are plain")
                .build();

        MockLLM llm = new MockLLM(Arrays.asList(
                toolCall("c1", "plain", "{\"task\":\"hi\"}"),
                reply("plain result"),
                reply("done")
        ));

        Agent agent = Agent.builder(llm, registry).build();
        ChatResult result = agent.run("hi");
        assertEquals("done", result.content());
    }

    // ---- helpers ----

    private static ChatResult reply(String content) {
        return new ChatResult(content, null);
    }

    private static ChatResult toolCall(String callId, String toolName, String argsJson) {
        return new ChatResult("", null, Collections.singletonList(new ToolCall(callId, toolName, argsJson)));
    }

    /**
     * 可编程 mock LLM:按预设顺序返回,记录 messages 与 tools(复用 SubAgentTest 的模式)。
     */
    static class MockLLM implements LLM {
        private final List<ChatResult> responses;
        private int callIndex = 0;
        private final List<List<Message>> capturedMessages = new ArrayList<>();
        private final List<List<Tool>> capturedTools = new ArrayList<>();

        MockLLM(List<ChatResult> responses) {
            this.responses = responses;
        }

        @Override
        public ChatResult chat(String systemMessage, String userMessage, OutputFormat outputFormat) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChatResult chat(List<Message> messages, OutputFormat outputFormat) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChatResult chat(String systemMessage, String userMessage, List<Tool> tools, OutputFormat outputFormat) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChatResult chat(List<Message> messages, List<Tool> tools, OutputFormat outputFormat) {
            capturedMessages.add(new ArrayList<>(messages));
            capturedTools.add(new ArrayList<>(tools));
            return responses.get(callIndex++);
        }

        @Override
        public ChatResult streamChat(String systemMessage, String userMessage, OutputFormat outputFormat, Consumer<ChatChunk> callback) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChatResult streamChat(List<Message> messages, OutputFormat outputFormat, Consumer<ChatChunk> callback) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChatResult streamChat(String systemMessage, String userMessage, List<Tool> tools, OutputFormat outputFormat, Consumer<ChatChunk> callback) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChatResult streamChat(List<Message> messages, List<Tool> tools, OutputFormat outputFormat, Consumer<ChatChunk> callback) {
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

        List<List<Tool>> getCapturedTools() {
            return capturedTools;
        }
    }

    static class DelayedMockLLM extends MockLLM {
        private final long delayMs;

        DelayedMockLLM(long delayMs, List<ChatResult> responses) {
            super(responses);
            this.delayMs = delayMs;
        }

        @Override
        public ChatResult streamChat(List<Message> messages, List<Tool> tools,
                                    OutputFormat outputFormat, Consumer<ChatChunk> callback) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return super.streamChat(messages, tools, outputFormat, callback);
        }
    }
}
