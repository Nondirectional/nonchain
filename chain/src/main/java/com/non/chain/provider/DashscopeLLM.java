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
        this(null, model, null, null);
    }

    public DashscopeLLM(String model, Integer maxCompletionTokens) {
        this(null, model, maxCompletionTokens, null);
    }

    public DashscopeLLM(String apiKey, String model, Integer maxCompletionTokens) {
        this(apiKey, model, maxCompletionTokens, null);
    }

    public DashscopeLLM(String apiKey, String model, Integer maxCompletionTokens, ChainCallback callback) {
        super(DEFAULT_BASE_URL, resolveApiKey(apiKey), model, maxCompletionTokens, callback);
    }

    /**
     * 通过 ChainContext 构造
     */
    public static DashscopeLLM fromContext(String apiKey, String model, Integer maxCompletionTokens, ChainContext chainContext) {
        return new DashscopeLLM(apiKey, model, maxCompletionTokens, chainContext != null ? chainContext.callback() : null);
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

    // ---- 覆写：添加 DashScope 特有的 topK 参数 ----

    @Override
    protected void applyAdditionalParams(ChatCompletionCreateParams.Builder builder, OutputFormat outputFormat) {
        super.applyAdditionalParams(builder, outputFormat);
        if (topK != null) {
            builder.putAdditionalBodyProperty("top_k", JsonValue.from(topK));
        }
    }
}
