package com.non.chain.example;

import com.non.chain.ChatChunk;
import com.non.chain.ChatResult;
import com.non.chain.Message;
import com.non.chain.provider.DashscopeLLM;
import com.non.chain.provider.LLM;

import java.util.ArrayList;
import java.util.List;

/**
 * Streaming Chat Demo — 流式输出
 *
 * 逐 token 输出 LLM 回复，适合聊天类应用实时展示
 */
public class StreamingChatExample {

    public static void main(String[] args) {
        LLM llm = new DashscopeLLM("qwen-plus");

        // 基本流式调用
        System.out.println("=== 基本流式对话 ===");
        ChatResult result = llm.streamChat("你是一个有帮助的AI助手。", "用三句话介绍 Java 语言。", chunk -> {
            if (chunk.hasContent()) {
                System.out.print(chunk.deltaContent());
            }
            if (chunk.hasThinking()) {
                System.out.print("[思考]" + chunk.deltaThinking());
            }
        });
        System.out.println();
        System.out.println("--- 流结束 ---");
        System.out.println("完整内容长度: " + result.content().length());

        // 多轮对话流式
        System.out.println("\n=== 多轮流式对话 ===");
        List<Message> messages = new ArrayList<>();
        messages.add(Message.user("你好！"));
        messages.add(Message.assistant("你好！有什么我可以帮你的吗？"));
        messages.add(Message.user("说说 Spring Boot 的核心特性"));

        ChatResult result2 = llm.streamChat(messages, chunk -> {
            if (chunk.hasContent()) {
                System.out.print(chunk.deltaContent());
            }
        });
        System.out.println();
        System.out.println("--- 流结束 ---");
        System.out.println("finishReason: " + (result2.hasToolCalls() ? "tool_calls" : "stop"));
    }
}
