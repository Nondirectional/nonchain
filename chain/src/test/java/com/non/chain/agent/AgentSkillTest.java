package com.non.chain.agent;

import com.non.chain.ChatChunk;
import com.non.chain.ChatResult;
import com.non.chain.Message;
import com.non.chain.OutputFormat;
import com.non.chain.provider.LLM;
import com.non.chain.skill.SkillDefinition;
import com.non.chain.skill.SkillRegistry;
import com.non.chain.tool.Tool;
import com.non.chain.tool.ToolCall;
import com.non.chain.tool.ToolRegistry;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Skill 机制端到端测试:LLM 通过 tool-calling 点选 skill → system 消息注入 → 下一轮遵循。
 *
 * <p>覆盖 design §4-§9:基础注入流、无 skill 回归、命名冲突 fail-fast(D12)、
 * SkillActivated 事件(D9)、多 skill 叠加(PERSISTENT)。</p>
 */
public class AgentSkillTest {

    /**
     * 基础流:LLM 第一轮点选 skill → 对话出现 tool result + system 注入 → 第二轮 LLM 看到注入后正常回复。
     */
    @Test
    public void skillSelected_systemInjectedAndAcknowledged() {
        SkillRegistry sr = new SkillRegistry();
        sr.register("code-review", "审查代码时使用")
                .content("# 审查流程\n1. 看结构\n2. 看命名")
                .build();

        // 第一轮:LLM 点选 code-review;第二轮:LLM 回复最终结果(无 toolCall → Complete)
        MockLLM llm = new MockLLM(List.of(
                toolCall("c1", "code-review", "{}"),
                reply("好的,按审查流程分析...")
        ));

        Agent agent = Agent.builder(llm, new ToolRegistry())
                .skillRegistry(sr)
                .build();

        List<Message> messages = new ArrayList<>();
        messages.add(Message.user("帮我审查代码"));
        ChatResult result = agent.run(messages);

        // 最终回复
        assertEquals("好的,按审查流程分析...", result.content());

        // 验证第二轮 LLM 收到的消息里有 system 注入
        List<Message> round2Messages = llm.getCapturedMessages().get(1);
        boolean hasSystemInjection = false;
        boolean hasToolResult = false;
        for (Message m : round2Messages) {
            if ("system".equals(m.role()) && m.content().contains("# 审查流程")) {
                hasSystemInjection = true;
            }
            if ("tool".equals(m.role()) && m.content().contains("code-review 已加载")) {
                hasToolResult = true;
            }
        }
        assertTrue("第二轮 LLM 应看到 system 注入的 skill 内容", hasSystemInjection);
        assertTrue("第二轮 LLM 应看到 tool result 确认", hasToolResult);
    }

    /**
     * 无 skill 回归:不挂 skillRegistry 的 Agent,LLM schema 里没有 skill function。
     */
    @Test
    public void noSkillRegistry_noSkillInSchema() {
        MockLLM llm = new MockLLM(List.of(reply("done")));
        Agent agent = Agent.builder(llm, new ToolRegistry()).build();

        agent.run(new ArrayList<>(List.of(Message.user("hi"))));

        List<Tool> toolsSeen = llm.getCapturedTools().get(0);
        assertTrue("无 skill 时 tool 列表应为空", toolsSeen.isEmpty());
    }

    /**
     * 有 skill 时,LLM schema 里出现无参数 function,description 带 [Skill] 前缀。
     */
    @Test
    public void skillRegistry_skillAppearsInSchema() {
        SkillRegistry sr = new SkillRegistry();
        sr.register("commit", "生成 commit message")
                .content("约定式提交")
                .build();

        MockLLM llm = new MockLLM(List.of(reply("ok")));
        Agent agent = Agent.builder(llm, new ToolRegistry())
                .skillRegistry(sr)
                .build();

        agent.run(new ArrayList<>(List.of(Message.user("hi"))));

        List<Tool> toolsSeen = llm.getCapturedTools().get(0);
        assertEquals(1, toolsSeen.size());
        assertEquals("commit", toolsSeen.get(0).name());
        assertTrue("description 应带 [Skill] 前缀", toolsSeen.get(0).description().startsWith("[Skill]"));
    }

    /**
     * D12: skill 名与 tool 名冲突 → build() fail-fast。
     */
    @Test
    public void namingConflict_skillVsTool_throws() {
        ToolRegistry tr = new ToolRegistry();
        tr.register("dup-name", "a tool").param("x", "string", "x", false).handle(args -> "ok");

        SkillRegistry sr = new SkillRegistry();
        sr.register("dup-name", "a skill").content("content").build();

        try {
            Agent.builder(new MockLLM(List.of()), tr).skillRegistry(sr).build();
            fail("应抛异常:skill 名与 tool 名冲突");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("dup-name"));
        }
    }

    /**
     * D12: skill 名与 sub-agent 名冲突 → build() fail-fast。
     */
    @Test
    public void namingConflict_skillVsSubAgent_throws() {
        ToolRegistry tr = new ToolRegistry();
        tr.registerSubAgent("dup-agent", "a subagent")
                .systemPrompt("you are sub")
                .build();

        SkillRegistry sr = new SkillRegistry();
        sr.register("dup-agent", "a skill").content("content").build();

        try {
            Agent.builder(new MockLLM(List.of()), tr).skillRegistry(sr).build();
            fail("应抛异常:skill 名与 sub-agent 名冲突");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("dup-agent"));
        }
    }

    /**
     * D12: skill 名与框架保留名(delegate_to_subagent)冲突 → build() fail-fast。
     */
    @Test
    public void namingConflict_skillVsReserved_throws() {
        ToolRegistry tr = new ToolRegistry();
        // 注册一个 sub-agent 让保留名有出现的场景
        tr.registerSubAgent("real-agent", "d").systemPrompt("s").build();

        SkillRegistry sr = new SkillRegistry();
        sr.register("delegate_to_subagent", "hijack").content("evil").build();

        try {
            Agent.builder(new MockLLM(List.of()), tr).skillRegistry(sr).build();
            fail("应抛异常:skill 名与保留名冲突");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("delegate_to_subagent"));
        }
    }

    /**
     * D9: SkillActivated 事件在 skill 点选时触发,携带 name 和 contentLength。
     */
    @Test
    public void skillActivated_eventFired() {
        SkillRegistry sr = new SkillRegistry();
        String content = "# 审查流程\n1. 看结构";
        sr.register("review", "d").content(content).build();

        MockLLM llm = new MockLLM(List.of(
                toolCall("c1", "review", "{}"),
                reply("done")
        ));

        List<AgentEvent> events = new ArrayList<>();
        Agent agent = Agent.builder(llm, new ToolRegistry())
                .skillRegistry(sr)
                .build();

        agent.run(new ArrayList<>(List.of(Message.user("hi"))), events::add);

        boolean found = false;
        for (AgentEvent e : events) {
            if (e instanceof AgentEvent.SkillActivated) {
                AgentEvent.SkillActivated sa = (AgentEvent.SkillActivated) e;
                assertEquals("review", sa.skillName());
                assertEquals(content.length(), sa.contentLength());
                found = true;
            }
        }
        assertTrue("应触发 SkillActivated 事件", found);
    }

    /**
     * PERSISTENT 多 skill 叠加:LLM 连续点两个 skill,两条 system 消息共存。
     */
    @Test
    public void multipleSkills_bothSystemMessagesPersist() {
        SkillRegistry sr = new SkillRegistry();
        sr.register("skill-a", "d").content("# A 内容").build();
        sr.register("skill-b", "d").content("# B 内容").build();

        MockLLM llm = new MockLLM(List.of(
                toolCall("c1", "skill-a", "{}"),   // 第1轮点 skill-a
                toolCall("c2", "skill-b", "{}"),   // 第2轮点 skill-b
                reply("最终回复")                    // 第3轮回复
        ));

        Agent agent = Agent.builder(llm, new ToolRegistry())
                .skillRegistry(sr)
                .build();

        ChatResult result = agent.run(new ArrayList<>(List.of(Message.user("hi"))));
        assertEquals("最终回复", result.content());

        // 第三轮 LLM 收到的消息里应同时有 A 和 B 的 system 注入
        List<Message> round3Messages = llm.getCapturedMessages().get(2);
        boolean hasA = false;
        boolean hasB = false;
        for (Message m : round3Messages) {
            if ("system".equals(m.role())) {
                if (m.content().contains("# A 内容")) hasA = true;
                if (m.content().contains("# B 内容")) hasB = true;
            }
        }
        assertTrue("第三轮应保留 skill-a 的 system 注入(PERSISTENT)", hasA);
        assertTrue("第三轮应有 skill-b 的 system 注入", hasB);
    }

    /**
     * skill 与普通 tool 在同一轮被调用:skill 走注入路径,tool 走执行路径,互不干扰。
     */
    @Test
    public void skillAndToolSameRound_bothHandled() {
        ToolRegistry tr = new ToolRegistry();
        tr.register("get-time", "获取时间").handle(args -> "12:00");

        SkillRegistry sr = new SkillRegistry();
        sr.register("tips", "提示").content("小贴士内容").build();

        // LLM 同一轮调用 get-time 和 tips(两个 toolCall)
        MockLLM llm = new MockLLM(List.of(
                new ChatResult("", null, List.of(
                        new ToolCall("c1", "get-time", "{}"),
                        new ToolCall("c2", "tips", "{}")
                )),
                reply("现在 12:00,小贴士是...")
        ));

        Agent agent = Agent.builder(llm, tr).skillRegistry(sr).build();
        // 用串行路径(executor=null 禁用并行)
        Agent agentSerial = Agent.builder(llm, tr).skillRegistry(sr).executor(null).build();

        // reset llm responses (agent 构造不消费,run 才消费)
        ChatResult r = agentSerial.run(new ArrayList<>(List.of(Message.user("hi"))));
        assertEquals("现在 12:00,小贴士是...", r.content());

        // 第二轮 LLM 应同时看到 tool result(12:00)和 system 注入(小贴士)
        List<Message> round2 = llm.getCapturedMessages().get(1);
        boolean hasTimeResult = false;
        boolean hasTipsInjection = false;
        for (Message m : round2) {
            if ("tool".equals(m.role()) && m.content().contains("12:00")) hasTimeResult = true;
            if ("system".equals(m.role()) && m.content().contains("小贴士内容")) hasTipsInjection = true;
        }
        assertTrue("tool 执行结果应在对话里", hasTimeResult);
        assertTrue("skill 注入应在对话里", hasTipsInjection);
    }

    // ---- helpers ----

    private static ChatResult reply(String content) {
        return new ChatResult(content, null);
    }

    private static ChatResult toolCall(String callId, String toolName, String argsJson) {
        return new ChatResult("", null, Collections.singletonList(new ToolCall(callId, toolName, argsJson)));
    }

    /**
     * 可编程 mock LLM:按预设顺序返回结果,记录每次调用的 messages 与 tools。
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
}
