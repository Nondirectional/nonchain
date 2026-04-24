package com.non.chain.example;

import com.non.chain.ChatResult;
import com.non.chain.agent.Agent;
import com.non.chain.agent.AgentEvent;
import com.non.chain.provider.DashscopeLLM;
import com.non.chain.provider.LLM;
import com.non.chain.tool.*;

/**
 * Streaming Agent Demo — 流式输出 Agent 执行过程
 *
 * <p>展示 Agent 在 LLM + Tool 循环中的实时事件流：</p>
 * <ul>
 *   <li>LLM 文本/思考内容的增量输出</li>
 *   <li>工具调用的开始、参数生成、执行结果</li>
 *   <li>多轮循环的轮次标记</li>
 * </ul>
 */
public class StreamingAgentExample {

    static class TravelTools {

        @ToolDef(name = "get_weather", description = "查询指定城市的天气情况")
        public String getWeather(
                @ToolParam(name = "city", description = "城市名") String city) {
            if (city.contains("东京")) return "东京：晴，12°C，适合出行";
            if (city.contains("巴黎")) return "巴黎：多云，8°C，建议带外套";
            return city + "：晴，20°C";
        }

        @ToolDef(name = "get_exchange_rate", description = "查询两种货币之间的汇率")
        public String getExchangeRate(
                @ToolParam(name = "from_currency", description = "源货币代码") String from,
                @ToolParam(name = "to_currency", description = "目标货币代码") String to) {
            if (from.equals("CNY") && to.equals("JPY")) return "1 CNY = 20.5 JPY";
            return "1 " + from + " = 1.0 " + to;
        }
    }

    public static void main(String[] args) {
        LLM llm = new DashscopeLLM("qwen-plus")
                .maxCompletionTokens(1024);

        ToolRegistry registry = new ToolRegistry().scan(new TravelTools());

        Agent agent = Agent.builder(llm, registry)
                .systemPrompt("你是一个旅行助手。根据用户问题，主动调用工具查询信息。")
                .maxIterations(5)
                .build();

        String query = "东京天气怎么样？日元汇率多少？";
        System.out.println("用户: " + query);
        System.out.println();

        ChatResult result = agent.run(query, StreamingAgentExample::handleEvent);
        System.out.println();
        System.out.println("--- Agent 结束 ---");
    }

    private static void handleEvent(AgentEvent event) {
        if (event instanceof AgentEvent.RoundStart) {
            System.out.println("\n=== 第 " + ((AgentEvent.RoundStart) event).round() + " 轮 ===");
        } else if (event instanceof AgentEvent.TextDelta) {
            System.out.print(((AgentEvent.TextDelta) event).delta());
        } else if (event instanceof AgentEvent.ThinkingDelta) {
            System.out.print("[思考]" + ((AgentEvent.ThinkingDelta) event).delta());
        } else if (event instanceof AgentEvent.ToolStart) {
            AgentEvent.ToolStart ts = (AgentEvent.ToolStart) event;
            System.out.println("\n[工具调用] " + ts.toolName() + "(" + ts.arguments() + ")");
        } else if (event instanceof AgentEvent.ToolEnd) {
            AgentEvent.ToolEnd te = (AgentEvent.ToolEnd) event;
            System.out.println("[工具结果] " + te.result());
        } else if (event instanceof AgentEvent.AgentError) {
            System.err.println("[错误] " + ((AgentEvent.AgentError) event).error().getMessage());
        }
    }
}
