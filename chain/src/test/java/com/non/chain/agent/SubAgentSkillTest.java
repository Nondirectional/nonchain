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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * SubAgent Skill 预加载测试(D13)。
 *
 * <p>验证子代理挂载 skillRegistry 后,在委派执行时能像顶层 Agent 一样:
 * <ul>
 *   <li>在 LLM schema 里看到 skill function</li>
 *   <li>点选 skill 后注入 system 消息</li>
 *   <li>子 agent 范围内 skill 名 vs tool 名冲突 fail-fast</li>
 * </ul>
 * </p>
 */
public class SubAgentSkillTest {

    /**
     * 子代理挂载 skill:委派执行时子 agent 能点选 skill → system 注入。
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

        ToolRegistry registry = new ToolRegistry();
        registry.registerSubAgent("reviewer", "负责代码审查")
                .systemPrompt("你是代码审查代理。")
                .skillRegistry(childSkills)
                .maxIterations(5)
                .build();

        // 父(委派) → 子(点选 review-checklist) → 子(回复审查结果) → 父(总结)
        MockLLM llm = new MockLLM(Arrays.asList(
                toolCall("c1", "reviewer", "{\"task\":\"审查这段代码\"}"),
                toolCall("s1", "review-checklist", "{}"),
                reply("按审查清单,结构清晰但缺少空指针检查。"),
                reply("审查完成:代码基本合格,建议补充空指针检查。")
        ));

        Agent agent = Agent.builder(llm, registry).build();
        ChatResult result = agent.run("帮我审查代码");

        assertEquals("审查完成:代码基本合格,建议补充空指针检查。", result.content());

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
}
