package com.non.chain.example;

import com.non.chain.ChatResult;
import com.non.chain.agent.Agent;
import com.non.chain.callback.ChainCallback;
import com.non.chain.callback.event.LlmCompleteEvent;
import com.non.chain.callback.event.ToolCompleteEvent;
import com.non.chain.provider.DashscopeLLM;
import com.non.chain.provider.LLM;
import com.non.chain.tool.*;

/**
 * Agent Loop Demo — 展示 Agent 自动循环调用工具
 *
 * <p>场景：旅行助手，拥有查天气、查汇率、算费用三个工具。
 * 用户问一个需要多步骤推理的问题，Agent 自动在循环中完成所有工具调用。</p>
 *
 * <p>对比 {@link FunctionCallExample} 中手动编写的 while 循环，
 * Agent 将整个循环封装为一行 {@code agent.run(query)}。</p>
 */
public class AgentLoopExample {

    // ---- 工具定义（注解方式） ----

    static class TravelTools {

        @ToolDef(name = "get_weather", description = "查询指定城市的天气情况")
        public String getWeather(
                @ToolParam(name = "city", description = "城市名") String city) {
            // 模拟数据
            if (city.contains("东京")) return "东京：晴，12°C，适合出行";
            if (city.contains("巴黎")) return "巴黎：多云，8°C，建议带外套";
            return city + "：晴，20°C";
        }

        @ToolDef(name = "get_exchange_rate", description = "查询两种货币之间的汇率")
        public String getExchangeRate(
                @ToolParam(name = "from_currency", description = "源货币代码，如 CNY、USD") String from,
                @ToolParam(name = "to_currency", description = "目标货币代码，如 JPY、EUR") String to) {
            if (from.equals("CNY") && to.equals("JPY")) return "1 CNY = 20.5 JPY";
            if (from.equals("CNY") && to.equals("EUR")) return "1 CNY = 0.13 EUR";
            return "1 " + from + " = 1.0 " + to;
        }

        @ToolDef(name = "calculate_cost", description = "计算旅行预算")
        public String calculateCost(
                @ToolParam(name = "days", description = "旅行天数") int days,
                @ToolParam(name = "daily_budget", description = "每日预算（当地货币）") double dailyBudget,
                @ToolParam(name = "currency", description = "货币代码") String currency) {
            double total = days * dailyBudget;
            return String.format("预算: %d天 × %.0f %s/天 = %.0f %s",
                    days, dailyBudget, currency, total, currency);
        }
    }

    // ---- 工具定义（Fluent API 方式） ----

    static ToolRegistry createRegistryWithFluent() {
        ToolRegistry registry = new ToolRegistry();

        registry.register("get_weather", "查询指定城市的天气情况")
                .param("city", "string", "城市名", true)
                .handle(args -> {
                    String city = args.getString("city");
                    if (city.contains("东京")) return "东京：晴，12°C，适合出行";
                    if (city.contains("巴黎")) return "巴黎：多云，8°C，建议带外套";
                    return city + "：晴，20°C";
                });

        registry.register("get_exchange_rate", "查询两种货币之间的汇率")
                .param("from_currency", "string", "源货币代码", true)
                .param("to_currency", "string", "目标货币代码", true)
                .handle(args -> {
                    String from = args.getString("from_currency");
                    String to = args.getString("to_currency");
                    if (from.equals("CNY") && to.equals("JPY")) return "1 CNY = 20.5 JPY";
                    if (from.equals("CNY") && to.equals("EUR")) return "1 CNY = 0.13 EUR";
                    return "1 " + from + " = 1.0 " + to;
                });

        registry.register("calculate_cost", "计算旅行预算")
                .param("days", "number", "旅行天数", true)
                .param("daily_budget", "number", "每日预算（当地货币）", true)
                .param("currency", "string", "货币代码", true)
                .handle(args -> {
                    int days = Integer.parseInt(args.getString("days"));
                    double dailyBudget = Double.parseDouble(args.getString("daily_budget"));
                    String currency = args.getString("currency");
                    double total = days * dailyBudget;
                    return String.format("预算: %d天 × %.0f %s/天 = %.0f %s",
                            days, dailyBudget, currency, total, currency);
                });

        return registry;
    }

    public static void main(String[] args) {
        LLM llm = new DashscopeLLM("qwen-plus", 1024);

        // 使用注解方式注册工具
        ToolRegistry registry = new ToolRegistry().scan(new TravelTools());

        // 构建 Agent（启用 ChainCallback 观察执行过程）
        ChainCallback callback = new ChainCallback() {
            @Override
            public void onLlmComplete(LlmCompleteEvent event) {
                System.out.println("[LLM] 耗时: " + event.latencyMs() + "ms");
            }

            @Override
            public void onToolComplete(ToolCompleteEvent event) {
                System.out.println("[Tool] " + event.toolName() + " 耗时: " + event.latencyMs() + "ms → " + event.result());
            }
        };

        Agent agent = Agent.builder(llm, registry)
                .systemPrompt("你是一个旅行助手。根据用户问题，主动调用工具查询天气、汇率等信息，给出实用建议。")
                .maxIterations(5)
                .callback(callback)
                .build();

        // 测试 1：多步骤问题（需要 agent 自动循环调用多个工具）
        String query1 = "我想去东京玩5天，帮我看看天气和汇率，算一下大概要花多少钱，每天大概15000日元";
        System.out.println("=== 测试 1: 多步骤推理 ===");
        System.out.println("用户: " + query1);
        System.out.println();

        ChatResult result1 = agent.run(query1);
        System.out.println("助手: " + result1.content());
        System.out.println();

        // 测试 2：单步骤问题（只需要一个工具调用）
        String query2 = "巴黎天气怎么样？";
        System.out.println("=== 测试 2: 单步骤查询 ===");
        System.out.println("用户: " + query2);
        System.out.println();

        ChatResult result2 = agent.run(query2);
        System.out.println("助手: " + result2.content());
    }
}
