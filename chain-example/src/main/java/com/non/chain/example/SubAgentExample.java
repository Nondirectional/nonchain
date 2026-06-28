package com.non.chain.example;

import com.non.chain.agent.Agent;
import com.non.chain.agent.AgentEvent;
import com.non.chain.agent.SubAgentExposureMode;
import com.non.chain.provider.DashscopeLLM;
import com.non.chain.provider.LLM;
import com.non.chain.tool.ToolRegistry;

/**
 * SubAgent Demo — 展示委派型子代理
 *
 * <p>场景：一个「主 Agent」拥有两个专职子代理——{@code research}（调研）和 {@code writer}（撰写）。
 * 主 Agent 自主决定把子任务委派给合适的子代理，子代理独立运行后把最终结果回传。</p>
 *
 * <p>本示例展示两种暴露模式：</p>
 * <ul>
 *   <li><b>DIRECT</b>（默认）：每个子代理暴露为一个独立 tool（{@code research(task)} / {@code writer(task)}）。</li>
 *   <li><b>DELEGATE</b>（显式）：只暴露一个通用 {@code delegate_to_subagent(agentName, task)} tool。</li>
 * </ul>
 *
 * <p>子代理默认：独立 systemPrompt、独立工具集（可空）、默认继承父 LLM、父/子 callback 与 trace 隔离、
 * 仅支持一层委派。运行需要环境变量 {@code DASHSCOPE_API_KEY}。</p>
 */
public class SubAgentExample {

    public static void main(String[] args) {
        LLM llm = new DashscopeLLM("qwen-plus").maxCompletionTokens(1024);

        // 子代理的专属工具集（可为空，表示无工具子代理）
        ToolRegistry researchTools = new ToolRegistry();
        researchTools.register("lookup", "查询知识库")
                .param("keyword", "string", "关键词", true)
                .handle(a -> "[" + a.getString("keyword") + "] 相关事实：...");

        ToolRegistry registry = new ToolRegistry();

        // 注册子代理（声明式 Builder：description 与 systemPrompt 分开，description 必填）
        registry.registerSubAgent("research", "负责调研与归纳，从知识库中检索事实")
                .systemPrompt("你是调研代理。优先归纳可查证的事实，不编造。需要时调用 lookup 工具。")
                .toolRegistry(researchTools)
                .maxIterations(3)
                .build();

        registry.registerSubAgent("writer", "负责根据素材撰写通顺的中文回复")
                .systemPrompt("你是撰写代理。基于给定的素材，输出结构清晰的中文回复。")
                .maxIterations(2)
                .build();

        // 父 Agent：默认 DIRECT 模式（每个子代理一个独立 tool）
        Agent directAgent = Agent.builder(llm, registry)
                .systemPrompt("你是主助手。遇到需要调研或撰写的问题，把子任务委派给对应子代理，"
                        + "再综合它们的返回给出最终答复。")
                .maxIterations(6)
                .build();

        String query = "帮我调研一下量子计算的现状，然后写一段通俗的介绍。";
        System.out.println("=== DIRECT 模式：每个子代理暴露为独立 tool ===");
        System.out.println("用户: " + query);
        System.out.println();

        directAgent.run(query, SubAgentExample::handleEvent);

        // 切换为 DELEGATE 模式：只暴露单个 delegate_to_subagent(agentName, task)
        Agent delegateAgent = Agent.builder(llm, registry)
                .systemPrompt("你是主助手。需要委派时调用 delegate_to_subagent 选择目标子代理。")
                .subAgentExposureMode(SubAgentExposureMode.DELEGATE)
                .maxIterations(6)
                .build();

        System.out.println();
        System.out.println("=== DELEGATE 模式：单个通用 delegate tool ===");
        System.out.println("用户: " + query);
        System.out.println();

        delegateAgent.run(query, SubAgentExample::handleEvent);
    }

    private static void handleEvent(AgentEvent event) {
        if (event instanceof AgentEvent.ToolStart) {
            AgentEvent.ToolStart ts = (AgentEvent.ToolStart) event;
            System.out.println("→ 委派/调用: " + ts.toolName());
        } else if (event instanceof AgentEvent.ToolEnd) {
            System.out.println("← 结果: " + ((AgentEvent.ToolEnd) event).result());
            System.out.println();
        } else if (event instanceof AgentEvent.TextDelta) {
            System.out.print(((AgentEvent.TextDelta) event).delta());
        } else if (event instanceof AgentEvent.Complete) {
            System.out.println();
        }
    }
}
