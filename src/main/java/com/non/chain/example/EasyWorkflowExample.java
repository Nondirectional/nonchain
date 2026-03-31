package com.non.chain.example;

import com.non.chain.*;
import com.non.chain.flow.*;
import com.non.chain.provider.DashscopeLLM;
import com.non.chain.provider.LLM;
import com.non.chain.tool.*;

/**
 * Graph 工作流 Demo — 条件路由：根据问题类型走不同分支
 *
 * 流程：
 *   classify → (条件) → technical / general → summarize → END
 */
public class EasyWorkflowExample {

    public static void main(String[] args) {
        LLM llm = new DashscopeLLM(
                "qwen3.5-35b-a3b",
                512
        ).enableThinking(true).thinkingBudget(512);

        Graph graph = Graph.builder("conditional-pipeline")
                .start("classify")

                // ---- 节点 ----

                .addNode(new Node("classify", state -> {
                    LLM chat = state.<LLM>get("llm").orElseThrow();
                    String userInput = state.getOrDefault("userInput", "");

                    ChatResult result = chat.chat(
                            "你是一个分类器。只回答 'technical' 或 'general'。"
                                    + "技术类问题（编程、数学、科学）回答 technical，其他回答 general。",
                            userInput
                    );

                    state.put("category", result.content().trim().toLowerCase());
                    state.addMessage(Message.user(userInput));
                    System.out.println("[classify] 分类结果: " + state.getOrDefault("category", ""));
                    return state;
                }))

                .addNode(new Node("technical", state -> {
                    LLM chat = state.<LLM>get("llm").orElseThrow();
                    String userInput = state.getOrDefault("userInput", "");

                    ChatResult result = chat.chat(
                            "你是一个技术专家。用专业但易懂的方式回答以下技术问题，包含具体细节。",
                            userInput
                    );

                    state.put("draftAnswer", result.content());
                    System.out.println("[technical] 技术解答完成");
                    return state;
                }))

                .addNode(new Node("general", state -> {
                    LLM chat = state.<LLM>get("llm").orElseThrow();
                    String userInput = state.getOrDefault("userInput", "");

                    ChatResult result = chat.chat(
                            "你是一个友好的助手。用通俗简洁的方式回答以下问题。",
                            userInput
                    );

                    state.put("draftAnswer", result.content());
                    System.out.println("[general] 通用解答完成");
                    return state;
                }))

                .addNode(new Node("summarize", state -> {
                    LLM chat = state.<LLM>get("llm").orElseThrow();
                    String draft = state.getOrDefault("draftAnswer", "");

                    ChatResult result = chat.chat(
                            "将以下内容总结为简洁清晰的最终回答。",
                            draft
                    );

                    state.put("finalAnswer", result.content());
                    state.addMessage(Message.assistant(result.content()));
                    System.out.println("[summarize] 总结完成");
                    return state;
                }))

                // ---- 边 ----

                // classify 根据分类结果路由到不同节点
                .addEdge(Edge.conditional("classify", state -> {
                    String category = state.getOrDefault("category", "general");
                    if (category.contains("technical")) {
                        return "technical";
                    }
                    return "general";
                }))

                // technical / general 都走向 summarize
                .addEdge(Edge.of("technical", "summarize"))
                .addEdge(Edge.of("general", "summarize"))

                // summarize 后结束
                .addEdge(Edge.of("summarize", Graph.END))

                .build();

        State initialState = new State()
                .put("llm", llm)
                .put("userInput", "量子计算是什么？它有哪些潜在应用？");

        System.out.println("=== 开始执行 Pipeline: " + graph.name() + " ===\n");
        GraphResult result = graph.run(initialState);

        System.out.println("\n=== 执行路径 ===");
        System.out.println(String.join(" → ", result.executedNodes()) + " → END");

        System.out.println("\n=== 最终回答 ===");
        System.out.println(result.finalState().getOrDefault("finalAnswer", ""));

        System.out.println("\n=== 历史状态追踪 ===");
        result.printTrace();
    }
}
