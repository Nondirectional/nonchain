package com.non.chain.example;

import com.non.chain.ChatChunk;
import com.non.chain.ChatResult;
import com.non.chain.provider.LLM;
import com.non.chain.provider.VLLM;

/**
 * vLLM Provider 示例 — 演示 vLLM 部署模型的 thinking 模式和思考预算控制
 *
 * <p>vLLM 的 thinking 参数使用嵌套格式（chat_template_kwargs），
 * 与 DashScope 的平级格式不同，因此需要使用 VLLM 而非 OpenAICompatibleLLM。
 */
public class VLLMExample {

    // ===== 按实际部署修改 =====
    private static final String BASE_URL = "http://10.100.10.21:40000/v1";
    private static final String MODEL = "qwen3-14b";

    public static void main(String[] args) {
        // ---- 1. 关闭 thinking ----
        System.out.println("========== 1. thinking=false ==========");
        LLM llm = new VLLM(BASE_URL, MODEL)
                .enableThinking(false);
        ChatResult result = llm.chat("你是一个有帮助的AI助手。", "用一句话介绍 Java 语言。");
        printResult(result);

        // ---- 2. 开启 thinking ----
        System.out.println("\n========== 2. thinking=true ==========");
        LLM thinkingLlm = new VLLM(BASE_URL, MODEL)
                .enableThinking(true);
        ChatResult thinkingResult = thinkingLlm.chat(null, "9.11 和 9.8 哪个大？");
        printResult(thinkingResult);

        // ---- 3. thinking + 思考预算控制 ----
        System.out.println("\n========== 3. thinking=true, budget=256 ==========");
        LLM budgetLlm = new VLLM(BASE_URL, MODEL)
                .enableThinking(true)
                .thinkingBudget(256);
        ChatResult budgetResult = budgetLlm.chat(null, "解释一下什么是递归");
        printResult(budgetResult);

        // ---- 4. 流式 + thinking ----
        System.out.println("\n========== 4. 流式 thinking=true ==========");
        LLM streamLlm = new VLLM(BASE_URL, MODEL)
                .enableThinking(true);
        System.out.println("---- 流式输出 ----");
        ChatResult streamResult = streamLlm.streamChat("你是一个有帮助的AI助手。", "解释量子纠缠", chunk -> {
            if (chunk.hasThinking()) {
                System.out.print(chunk.deltaThinking());
            }
            if (chunk.hasContent()) {
                System.out.print(chunk.deltaContent());
            }
        });
        System.out.println("\n---- 流式结束 ----");
        System.out.println("Token 使用: " + streamResult.tokenUsage());
    }

    private static void printResult(ChatResult result) {
        System.out.println("【思考内容】");
        System.out.println(result.hasThinking() ? result.thinkingContent() : "(无)");
        System.out.println("【回复内容】");
        System.out.println(result.content());
        System.out.println("Token 使用: " + result.tokenUsage());
    }
}
