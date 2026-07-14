package com.non.chain.provider;

import com.non.chain.OutputFormat;
import com.non.chain.callback.ChainCallback;
import com.non.chain.callback.ChainContext;
import com.openai.core.JsonValue;
import com.openai.models.chat.completions.ChatCompletionCreateParams;

/**
 * DashScope LLM provider（阿里云灵积平台）。
 * 继承 {@link AbstractOpenAILLM}，默认连接 DashScope 的 OpenAI 兼容端点，
 * 并支持 DashScope 特有参数（如 topK）。
 */
public class DashscopeLLM extends AbstractOpenAILLM {

    private static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final String API_KEY_ENV = "DASHSCOPE_API_KEY";

    private Integer topK;

    public DashscopeLLM(String model) {
        super(DEFAULT_BASE_URL, resolveApiKey(null), model);
    }

    public DashscopeLLM(String apiKey, String model) {
        super(DEFAULT_BASE_URL, resolveApiKey(apiKey), model);
    }

    /**
     * 通过 ChainContext 构造
     */
    public static DashscopeLLM fromContext(String apiKey, String model, Integer maxCompletionTokens, ChainContext chainContext) {
        DashscopeLLM llm = new DashscopeLLM(apiKey, model);
        if (maxCompletionTokens != null) {
            llm.maxCompletionTokens(maxCompletionTokens);
        }
        if (chainContext != null && chainContext.callback() != null) {
            llm.callback(chainContext.callback());
        }
        return llm;
    }

    private static String resolveApiKey(String explicitApiKey) {
        if (explicitApiKey != null && !explicitApiKey.isBlank()) {
            return explicitApiKey;
        }
        String envApiKey = System.getenv(API_KEY_ENV);
        if (envApiKey != null && !envApiKey.isBlank()) {
            return envApiKey;
        }
        throw new IllegalArgumentException(
                "Dashscope API Key 不能为空：请显式传入，或设置环境变量 " + API_KEY_ENV
        );
    }

    // ---- DashScope 特有参数 ----

    /**
     * 设置 topK 参数（DashScope 特有，非标准 OpenAI 参数）
     */
    public DashscopeLLM topK(Integer topK) {
        this.topK = topK;
        return this;
    }

    // ---- Fluent setters（返回具体类型） ----

    @Override
    public DashscopeLLM enableThinking(boolean enable) {
        super.enableThinking(enable);
        return this;
    }

    @Override
    public DashscopeLLM thinkingBudget(Integer budget) {
        super.thinkingBudget(budget);
        return this;
    }

    @Override
    public DashscopeLLM enableJsonObjectMode(boolean enable) {
        super.enableJsonObjectMode(enable);
        return this;
    }

    @Override
    public DashscopeLLM temperature(Double temperature) {
        super.temperature(temperature);
        return this;
    }

    @Override
    public DashscopeLLM topP(Double topP) {
        super.topP(topP);
        return this;
    }

    @Override
    public DashscopeLLM supportsMultipleSystemMessages(boolean supported) {
        super.supportsMultipleSystemMessages(supported);
        return this;
    }

    // ---- 覆写：DashScope 特有参数 ----

    /**
     * DashScope Qwen3 模型默认 enable_thinking=true，且非流式调用要求 enable_thinking=false。
     * 因此必须始终显式发送 enable_thinking 参数，而非仅在启用时才发送。
     */
    @Override
    protected void applyThinkingParams(ChatCompletionCreateParams.Builder builder) {
        builder.putAdditionalBodyProperty("enable_thinking", JsonValue.from(isEnableThinking()));
        if (getThinkingBudget() != null) {
            builder.putAdditionalBodyProperty("thinking_budget", JsonValue.from(getThinkingBudget()));
        }
    }

    @Override
    protected void applyAdditionalParams(ChatCompletionCreateParams.Builder builder, OutputFormat outputFormat) {
        super.applyAdditionalParams(builder, outputFormat);
        if (topK != null) {
            builder.putAdditionalBodyProperty("top_k", JsonValue.from(topK));
        }
    }
}
