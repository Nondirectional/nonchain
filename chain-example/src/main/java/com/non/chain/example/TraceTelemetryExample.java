package com.non.chain.example;

import com.non.chain.ChatResult;
import com.non.chain.agent.Agent;
import com.non.chain.flow.Edge;
import com.non.chain.flow.Graph;
import com.non.chain.flow.GraphResult;
import com.non.chain.flow.State;
import com.non.chain.provider.DashscopeLLM;
import com.non.chain.provider.LLM;
import com.non.chain.tool.ToolRegistry;
import com.non.chain.trace.InMemoryTraceStore;
import com.non.chain.trace.Span;
import com.non.chain.trace.Trace;
import com.non.chain.trace.TraceRuntimeIds;

/**
 * Trace Telemetry Demo — 执行链路遥测
 *
 * <p>展示如何开启执行链路录制，拿到 runtimeId 后从 {@link InMemoryTraceStore} 拉回完整 span 树，
 * 并序列化为 JSON 供可视化/归档分析。</p>
 *
 * <p><b>核心概念</b>：</p>
 * <ul>
 *   <li><b>runtimeId</b>：一次顶层执行的 id，等于根 span 的 spanId。整棵执行树（Agent/Flow/
 *       SubAgent/LLM/工具）共享同一个 runtimeId，<b>不切新根</b>。</li>
 *   <li><b>opt-in</b>：默认全关、零开销；显式 {@code .trace(store)} / {@code .traceStore(store)} 才录。</li>
 *   <li><b>正交于用户面 callback</b>：录制层有自己的 span 传播路径，不寄生 ChainCallback，
 *       因此能绕开 SubAgent 的 {@code noop()} 隔离实现全树下钻。</li>
 * </ul>
 *
 * <p>本示例三个场景：① 单 Agent + 工具 ② SubAgent 全树下钻 ③ Flow → 节点内 Agent 嵌套。
 * 运行需要环境变量 {@code DASHSCOPE_API_KEY}。</p>
 */
public class TraceTelemetryExample {

    public static void main(String[] args) {
        LLM llm = new DashscopeLLM("qwen-plus").maxCompletionTokens(512);

        // ---------- 场景 1：单 Agent + 工具，拿 runtimeId 拉回 trace ----------
        InMemoryTraceStore store1 = new InMemoryTraceStore();
        ToolRegistry tools = new ToolRegistry();
        tools.register("time", "获取当前时间").handle(a -> "2026-06-29 10:00:00");

        Agent agent = Agent.builder(llm, tools)
                .systemPrompt("你是助手。需要时调用 time 工具。")
                .trace(store1)               // ← 启用录制
                .build();

        ChatResult result = agent.run("现在几点？");
        System.out.println("=== 场景1：单 Agent trace ===");
        System.out.println("回复: " + result.content());
        // 成功路径：runtimeId 直接从返回值拿
        Trace trace1 = store1.getTrace(result.runtimeId()).orElseThrow();
        System.out.println("span 树（runtimeId=" + trace1.runtimeId() + "）:");
        printTree(trace1);
        System.out.println("JSON 片段: " + trace1.toJson().substring(0, Math.min(120, trace1.toJson().length())) + "...");
        System.out.println();

        // ---------- 场景 2：SubAgent 全树下钻 ----------
        InMemoryTraceStore store2 = new InMemoryTraceStore();
        ToolRegistry childTools = new ToolRegistry();
        childTools.register("lookup", "查询").handle(a -> "事实：X");
        ToolRegistry reg2 = new ToolRegistry();
        reg2.registerSubAgent("research", "调研子代理")
                .systemPrompt("你是调研代理，需要时调用 lookup 工具。")
                .toolRegistry(childTools)
                .build();

        Agent agent2 = Agent.builder(llm, reg2)
                .systemPrompt("你是主助手，把调研任务委派给 research 子代理。")
                .trace(store2)
                .build();
        ChatResult r2 = agent2.run("帮我调研一下 X");
        System.out.println("=== 场景2：SubAgent 全树下钻 ===");
        Trace trace2 = store2.getTrace(r2.runtimeId()).orElseThrow();
        // 子代理内部的 llm/lookup span 自动进同一棵树，挂在父委派 tool span 下
        System.out.println("span 总数: " + trace2.spans().size() + "（含子代理内部 span，不切新根）");
        printTree(trace2);
        System.out.println();

        // ---------- 场景 3：Flow → 节点内 Agent 嵌套 ----------
        InMemoryTraceStore store3 = new InMemoryTraceStore();
        final Agent nodeAgent = Agent.builder(llm, tools)
                .systemPrompt("你是节点内 Agent。")
                .trace(store3)
                .build();

        Graph graph = Graph.builder("telemetry-flow")
                .traceStore(store3)          // ← Graph 也启用录制
                .addNode(new com.non.chain.flow.Node("plan", state -> {
                    // 节点体内调 Agent —— 其 span 靠 ThreadLocal 自动挂在 graph_node span 下
                    ChatResult nr = nodeAgent.run("现在几点？");
                    return state.put("answer", nr.content());
                }))
                .addEdge(Edge.of("plan", Graph.END))
                .start("plan")
                .build();

        System.out.println("=== 场景3：Flow → 节点内 Agent 嵌套 ===");
        GraphResult gr = graph.run(new State());
        Trace trace3 = store3.getTrace(gr.runtimeId()).orElseThrow();
        System.out.println("整棵树单一根（graph_run），节点内 Agent 不切新根:");
        printTree(trace3);
        System.out.println();

        // ---------- 失败路径：从异常里提取 runtimeId ----------
        System.out.println("=== 失败路径：从异常提取 runtimeId ===");
        InMemoryTraceStore store4 = new InMemoryTraceStore();
        LLM failingLlm = new DashscopeLLM("qwen-plus") {
            @Override
            public ChatResult chat(java.util.List<com.non.chain.Message> messages,
                                   java.util.List<com.non.chain.tool.Tool> t,
                                   com.non.chain.OutputFormat f) {
                throw new RuntimeException("模拟 LLM 故障");
            }
        };
        Agent failAgent = Agent.builder(failingLlm, new ToolRegistry()).trace(store4).build();
        try {
            failAgent.run("会失败");
        } catch (RuntimeException e) {
            // 原异常类型不变；runtimeId 从异常链提取
            TraceRuntimeIds.find(e).ifPresent(rid -> {
                System.out.println("失败仍能提取 runtimeId=" + rid);
                System.out.println("回捞的失败 trace span 数: " + store4.getTrace(rid).get().spans().size());
            });
        }
    }

    /** 简易缩进打印 span 树（按 parentSpanId 重建层级）。 */
    private static void printTree(Trace trace) {
        java.util.Map<String, java.util.List<Span>> children = new java.util.HashMap<>();
        Span root = null;
        for (Span s : trace.spans()) {
            if (s.parentSpanId() == null) {
                root = s;
            } else {
                children.computeIfAbsent(s.parentSpanId(), k -> new java.util.ArrayList<>()).add(s);
            }
        }
        if (root == null) {
            System.out.println("  (无根 span)");
            return;
        }
        printSpan(root, "", children);
    }

    private static void printSpan(Span s, String indent,
                                  java.util.Map<String, java.util.List<Span>> children) {
        System.out.println(indent + "├─ " + s.type() + " [" + s.name() + "] status=" + s.status()
                + " " + (s.endTimeMs() - s.startTimeMs()) + "ms");
        java.util.List<Span> kids = children.get(s.spanId());
        if (kids != null) {
            for (Span c : kids) {
                printSpan(c, indent + "  ", children);
            }
        }
    }
}
