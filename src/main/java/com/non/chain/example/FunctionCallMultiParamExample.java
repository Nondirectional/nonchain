package com.non.chain.example;

import com.non.chain.*;
import com.non.chain.provider.DashscopeLLM;
import com.non.chain.tool.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 多参数 Function Call 对比 Demo — 注解方式 vs fluent 方式
 */
public class FunctionCallMultiParamExample {

    // ===== 注解方式：多参数工具 =====

    static class WeatherService {

        private final Random random = new Random();

        @ToolDef(name = "get_weather", description = "查询指定城市的天气信息")
        public String getWeather(
                @ToolParam(name = "city", description = "城市名称，如北京市") String city,
                @ToolParam(name = "unit", description = "温度单位", required = false) String unit
        ) {
            System.out.println("    → [方法收到的参数] city=" + city + ", unit=" + unit);
            int temp = random.nextInt(35);
            String unitLabel = "celsius".equals(unit) ? "°C" : "°F";
            return city + "今天" + temp + unitLabel + "，天气晴朗。";
        }
    }

    // ===== 主流程 =====

    public static void main(String[] args) {
        ChatResult.LLM llm = new DashscopeLLM(
                "qwen-plus",
                512
        );

        System.out.println("============ 注解方式 ============\n");
        demoAnnotation(llm);

        System.out.println("\n============ Fluent 方式 ============\n");
        demoFluent(llm);
    }

    // ---- 注解方式 ----

    static void demoAnnotation(ChatResult.LLM llm) {
        ToolRegistry registry = new ToolRegistry().scan(new WeatherService());
        List<Tool> tools = registry.getTools();

        System.out.println("生成的工具定义:");
        for (Tool t : tools) {
            System.out.println("  name: " + t.name());
            System.out.println("  description: " + t.description());
        }
        System.out.println();

        runToolLoop(llm, registry, tools, "帮我查一下杭州的天气，用摄氏度");
    }

    // ---- Fluent 方式 ----

    static void demoFluent(ChatResult.LLM llm) {
        ToolRegistry registry = new ToolRegistry();

        registry.register("get_weather", "查询指定城市的天气信息")
                .param("city", "string", "城市名称，如北京市", true)
                .param("unit", "string", "温度单位，celsius 或 fahrenheit", false)
                .handle(args -> {
                    String city = args.getString("city");
                    String unit = args.getString("unit");
                    System.out.println("    → [handler 收到的参数] city=" + city + ", unit=" + unit);
                    int temp = new Random().nextInt(35);
                    String unitLabel = "celsius".equals(unit) ? "°C" : "°F";
                    return city + "今天" + temp + unitLabel + "，天气多云。";
                });

        List<Tool> tools = registry.getTools();

        runToolLoop(llm, registry, tools, "帮我查一下上海的天气，用华氏度");
    }

    // ---- 通用工具调用循环 ----

    static void runToolLoop(ChatResult.LLM llm, ToolRegistry registry, List<Tool> tools, String question) {
        System.out.println("用户: " + question);
        System.out.println();

        List<Message> messages = new ArrayList<>();
        messages.add(Message.user(question));

        ChatResult response = llm.chat(messages, tools);
        messages.add(response.toMessage());

        while (response.hasToolCalls()) {
            for (ToolCall tc : response.toolCalls()) {
                System.out.println("LLM 返回工具调用: " + tc.name());
                System.out.println("    → [LLM 构造的 JSON 参数] " + tc.arguments());

                String result = registry.execute(tc.name(), tc.arguments());
                System.out.println("    → [工具执行结果] " + result);

                messages.add(Message.toolResult(tc.id(), result));
            }
            response = llm.chat(messages, tools);
            messages.add(response.toMessage());
        }

        System.out.println("\n助手: " + response.content());
    }
}
