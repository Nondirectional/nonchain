package com.non.chain.example;

import com.non.chain.*;
import com.non.chain.provider.DashscopeLLM;
import com.non.chain.provider.LLM;
import com.non.chain.tool.Tool;
import com.non.chain.tool.ToolCall;
import com.non.chain.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Function Call Demo — Fluent API 方式定义工具
 *
 * 使用 ToolRegistry.register().param().handle() 链式定义工具
 */
public class FunctionCallRawExample {

    public static void main(String[] args) {
        LLM llm = new DashscopeLLM(
                "qwen-plus",
                512
        );

        // fluent API 定义工具：Schema + 执行逻辑在一处
        ToolRegistry registry = new ToolRegistry();
        registry.register("get_current_weather", "当你想查询指定城市的天气时非常有用。")
                .param("location", "string", "城市或县区，比如北京市、杭州市、余杭区等。", true)
                .handle(a -> {
                    String location = a.getString("location");
                    String[] conditions = {"晴天", "多云", "雨天"};
                    return location + "今天是" + conditions[new Random().nextInt(conditions.length)] + "。";
                });

        List<Tool> tools = registry.getTools();

        String userQuestion = "北京天气咋样";
        System.out.println("用户问题: " + userQuestion);
        System.out.println();

        List<Message> messages = new ArrayList<>();
        messages.add(Message.user(userQuestion));

        ChatResult response = llm.chat(messages, tools);

        if (!response.hasToolCalls()) {
            System.out.println("无需调用工具，直接回复: " + response.content());
            return;
        }

        messages.add(response.toMessage());

        while (response.hasToolCalls()) {
            for (ToolCall toolCall : response.toolCalls()) {
                System.out.println("正在调用工具 [" + toolCall.name() + "]，参数: " + toolCall.arguments());

                String toolResult = registry.execute(toolCall.name(), toolCall.arguments());
                System.out.println("工具返回: " + toolResult);

                messages.add(Message.toolResult(toolCall.id(), toolResult));
            }

            response = llm.chat(messages, tools);
            messages.add(response.toMessage());
        }

        System.out.println();
        System.out.println("助手最终回复: " + response.content());
    }
}
