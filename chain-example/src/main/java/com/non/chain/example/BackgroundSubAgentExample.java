package com.non.chain.example;

import com.non.chain.agent.Agent;
import com.non.chain.agent.AgentEvent;
import com.non.chain.agent.SubAgentExposureMode;
import com.non.chain.memory.InMemoryChatMemoryStore;
import com.non.chain.provider.DashscopeLLM;
import com.non.chain.provider.LLM;
import com.non.chain.tool.ToolRegistry;

/**
 * 后台子代理 Demo — 展示 SubAgent 重做后的前台/后台并行执行能力。
 *
 * <p>场景:一个「主 Agent」拥有两个专职子代理——{@code research}(调研)和 {@code writer}(撰写)。
 * 本次演示:</p>
 * <ul>
 *   <li><b>后台并行</b>:主 Agent 后台 spawn 多个子代理,自己继续推理,完成后自动 join</li>
 *   <li><b>steer 转向</b>:运行中向后台子代理注入转向消息</li>
 *   <li><b>resume 会话恢复</b>:配置 ChatMemoryStore 的子代理可跨委派保留对话历史</li>
 *   <li><b>graceful max turns</b>:超 maxIterations 后优雅收尾,不硬中断</li>
 * </ul>
 *
 * <p>运行需要环境变量 {@code DASHSCOPE_API_KEY}。</p>
 */
public class BackgroundSubAgentExample {

    public static void main(String[] args) {
        LLM llm = new DashscopeLLM("qwen-plus").maxCompletionTokens(1024);

        // 子代理的专属工具集
        ToolRegistry researchTools = new ToolRegistry();
        researchTools.register("lookup", "查询知识库")
                .param("keyword", "string", "关键词", true)
                .handle(a -> "[" + a.getString("keyword") + "] 相关事实:...");

        ToolRegistry registry = new ToolRegistry();

        // 注册子代理(research 配置 ChatMemoryStore 支持 resume)
        registry.registerSubAgent("research", "负责调研与归纳")
                .systemPrompt("你是调研代理。优先归纳可查证的事实。需要时调用 lookup 工具。")
                .toolRegistry(researchTools)
                .chatMemoryStore(new InMemoryChatMemoryStore())   // D7:启用 resume
                .maxIterations(3)
                .build();

        registry.registerSubAgent("writer", "负责撰写通顺的中文回复")
                .systemPrompt("你是撰写代理。基于给定的素材,输出结构清晰的中文回复。")
                .maxIterations(2)
                .build();

        // 父 Agent:DIRECT 模式 + 后台并行配置
        Agent agent = Agent.builder(llm, registry)
                .systemPrompt("你是主助手。遇到需要调研或撰写的问题,"
                        + "可以后台派发子代理并行工作(run_in_background=true),"
                        + "用 get_subagent_result 查询结果,用 steer_subagent 转向。")
                .subAgentExposureMode(SubAgentExposureMode.DIRECT)
                .maxBackgroundRunning(4)     // D4:最多 4 个后台子代理并行
                .graceTurns(3)               // D9:超 maxIterations 后给 3 轮收尾
                .maxIterations(6)
                .build();

        String query = "帮我同时调研量子计算和 AI 的现状,然后分别写一段介绍。";
        System.out.println("=== 后台并行子代理演示 ===");
        System.out.println("用户: " + query);
        System.out.println();

        agent.run(query, BackgroundSubAgentExample::handleEvent);
    }

    private static void handleEvent(AgentEvent event) {
        if (event instanceof AgentEvent.ToolStart) {
            AgentEvent.ToolStart ts = (AgentEvent.ToolStart) event;
            System.out.println("→ 调用: " + ts.toolName() + " " + ts.arguments());
        } else if (event instanceof AgentEvent.ToolEnd) {
            System.out.println("← 结果: " + ((AgentEvent.ToolEnd) event).result());
            System.out.println();
        } else if (event instanceof AgentEvent.SubAgentSpawned) {
            AgentEvent.SubAgentSpawned s = (AgentEvent.SubAgentSpawned) event;
            System.out.println("★ 后台子代理派发: " + s.name() + " (id=" + s.subAgentId() + ") task=" + s.task());
        } else if (event instanceof AgentEvent.SubAgentCompleted) {
            AgentEvent.SubAgentCompleted c = (AgentEvent.SubAgentCompleted) event;
            System.out.println("★ 后台子代理完成: " + c.name() + " [" + c.status() + "]");
        } else if (event instanceof AgentEvent.SubAgentSteered) {
            AgentEvent.SubAgentSteered st = (AgentEvent.SubAgentSteered) event;
            System.out.println("★ 后台子代理转向: id=" + st.subAgentId() + " msg=" + st.message());
        } else if (event instanceof AgentEvent.TextDelta) {
            System.out.print(((AgentEvent.TextDelta) event).delta());
        } else if (event instanceof AgentEvent.Complete) {
            System.out.println();
        }
    }
}
