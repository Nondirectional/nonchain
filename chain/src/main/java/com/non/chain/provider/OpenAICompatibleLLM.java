package com.non.chain.provider;

import com.non.chain.callback.ChainCallback;
import com.non.chain.callback.ChainContext;

/**
 * 通用 OpenAI 兼容 LLM provider，适用于 vllm-openai、Ollama、LiteLLM 等
 * 任何兼容 OpenAI Chat Completions API 的服务端点。
 *
 * <p>使用示例：
 * <pre>{@code
 * // 连接 vllm 本地模型
 * LLM llm = new OpenAICompatibleLLM(
 *     "http://10.100.10.21:40000/v1",
 *     "qwen3-14b"
 * );
 *
 * // 带参数
 * LLM llm = new OpenAICompatibleLLM("http://localhost:11434/v1", "qwen3-14b")
 *     .temperature(0.7)
 *     .enableThinking(true);
 * }</pre>
 */
public class OpenAICompatibleLLM extends AbstractOpenAILLM {

    /**
     * 无 API Key 构造（适用于内网无认证部署）
     *
     * @param baseUrl  服务端点地址，如 "http://10.100.10.21:40000/v1"
     * @param model    模型名称，如 "qwen3-14b"
     */
    public OpenAICompatibleLLM(String baseUrl, String model) {
        super(baseUrl, resolveApiKey(null), model);
    }

    /**
     * 指定 API Key 构造
     *
     * @param baseUrl  服务端点地址
     * @param apiKey   API Key（可选，无认证时传 null）
     * @param model    模型名称
     */
    public OpenAICompatibleLLM(String baseUrl, String apiKey, String model) {
        super(baseUrl, resolveApiKey(apiKey), model);
    }

    /**
     * 通过 ChainContext 构造
     */
    public static OpenAICompatibleLLM fromContext(String baseUrl, String apiKey, String model,
                                                  Integer maxCompletionTokens, ChainContext chainContext) {
        OpenAICompatibleLLM llm = new OpenAICompatibleLLM(baseUrl, apiKey, model);
        if (maxCompletionTokens != null) {
            llm.maxCompletionTokens(maxCompletionTokens);
        }
        if (chainContext != null && chainContext.callback() != null) {
            llm.callback(chainContext.callback());
        }
        return llm;
    }

    private static String resolveApiKey(String apiKey) {
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKey;
        }
        // 无认证场景使用占位符（openai-java SDK 要求 apiKey 非空）
        return "no-api-key";
    }

    // ---- Fluent setters（返回具体类型） ----

    @Override
    public OpenAICompatibleLLM enableThinking(boolean enable) {
        super.enableThinking(enable);
        return this;
    }

    @Override
    public OpenAICompatibleLLM thinkingBudget(Integer budget) {
        super.thinkingBudget(budget);
        return this;
    }

    @Override
    public OpenAICompatibleLLM enableJsonObjectMode(boolean enable) {
        super.enableJsonObjectMode(enable);
        return this;
    }

    @Override
    public OpenAICompatibleLLM temperature(Double temperature) {
        super.temperature(temperature);
        return this;
    }

    @Override
    public OpenAICompatibleLLM topP(Double topP) {
        super.topP(topP);
        return this;
    }

    @Override
    public OpenAICompatibleLLM supportsMultipleSystemMessages(boolean supported) {
        super.supportsMultipleSystemMessages(supported);
        return this;
    }
}
