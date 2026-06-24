package com.non.chain.example;

import com.non.chain.agent.AfterResult;
import com.non.chain.agent.Agent;
import com.non.chain.agent.BeforeResult;
import com.non.chain.agent.AgentEvent;
import com.non.chain.provider.DashscopeLLM;
import com.non.chain.provider.LLM;
import com.non.chain.tool.ToolRegistry;

/**
 * Tool Interceptor Demo — 展示 before/after 工具拦截器
 *
 * <p>场景：一个简单的查询助手，演示两类拦截：</p>
 * <ul>
 *   <li><b>before</b>：审核工具参数，危险关键词（如 "rm -rf"）直接 block</li>
 *   <li><b>after</b>：对工具返回结果脱敏，把手机号替换为 ***</li>
 * </ul>
 *
 * <p>对比 {@link AgentLoopExample} 的纯执行，拦截器在不改工具实现的前提下
 * 增加了「控制（block）」与「改写（脱敏）」能力。</p>
 *
 * <p>运行需要环境变量 {@code DASHSCOPE_API_KEY}。</p>
 */
public class ToolInterceptorExample {

    public static void main(String[] args) {
        LLM llm = new DashscopeLLM("qwen-plus").maxCompletionTokens(512);

        ToolRegistry registry = new ToolRegistry();
        registry.register("query_user", "查询用户信息")
                .param("keyword", "string", "查询关键词", true)
                .handle(queryArgs -> {
                    // 模拟返回含敏感信息的结果
                    String kw = queryArgs.getString("keyword");
                    return "用户[" + kw + "]，手机: 13800138000，余额: 9999";
                });

        Agent agent = Agent.builder(llm, registry)
                .systemPrompt("你是查询助手，使用 query_user 工具回答用户问题。")
                // before: 拦截危险关键词
                .addBeforeToolCall(ctx -> {
                    String argsJson = ctx.arguments();
                    if (argsJson != null && argsJson.contains("rm -rf")) {
                        System.out.println("⚠ before 拦截: 危险关键词，阻止执行");
                        return BeforeResult.block("危险操作已被拦截");
                    }
                    return BeforeResult.pass();
                })
                // after: 脱敏手机号
                .addAfterToolCall(ctx ->
                        AfterResult.content(ctx.result().replaceAll("\\d{11}", "***********")))
                .build();

        String query = "帮我查一下用户 张三 的信息";
        System.out.println("用户: " + query);
        System.out.println();

        agent.run(query, ToolInterceptorExample::handleEvent);
    }

    private static void handleEvent(AgentEvent event) {
        if (event instanceof AgentEvent.ToolStart) {
            AgentEvent.ToolStart ts = (AgentEvent.ToolStart) event;
            System.out.println("→ 调用工具 " + ts.toolName() + "，参数: " + ts.arguments());
        } else if (event instanceof AgentEvent.ToolEnd) {
            System.out.println("← 工具结果（已脱敏）: " + ((AgentEvent.ToolEnd) event).result());
            System.out.println();
        } else if (event instanceof AgentEvent.TextDelta) {
            System.out.print(((AgentEvent.TextDelta) event).delta());
        } else if (event instanceof AgentEvent.Complete) {
            System.out.println();
        }
    }
}
