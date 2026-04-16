package com.non.chain.provider;

import com.non.chain.callback.ChainCallback;
import com.non.chain.callback.ChainContext;
import com.openai.core.JsonValue;
import com.openai.models.chat.completions.ChatCompletionCreateParams;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * vLLM LLM provider，适用于通过 vLLM 部署的模型（如 Qwen3）。
 *
 * <p>vLLM 的 thinking 参数使用嵌套格式：
 * <pre>{@code
 * {
 *   "chat_template_kwargs": {"enable_thinking": true},
 *   "thinking_token_budget": 2048
 * }
 * }</pre>
 *
 * <p>使用示例：
 * <pre>{@code
 * // 基础用法
 * LLM llm = new VLLM("http://localhost:8000/v1", "Qwen/Qwen3-14B");
 *
 * // 启用 thinking 模式
 * LLM llm = new VLLM("http://localhost:8000/v1", "Qwen/Qwen3-14B")
 *     .enableThinking(true)
 *     .thinkingBudget(2048);
 * }</pre>
 */
public class VLLM extends OpenAICompatibleLLM {

    /**
     * 无 API Key 构造（适用于内网无认证部署）
     *
     * @param baseUrl vLLM 服务端点地址，如 "http://localhost:8000/v1"
     * @param model   模型名称，如 "Qwen/Qwen3-14B"
     */
    public VLLM(String baseUrl, String model) {
        this(baseUrl, null, model, null, null);
    }

    /**
     * 指定 baseUrl 和 maxCompletionTokens
     *
     * @param baseUrl             vLLM 服务端点地址
     * @param model               模型名称
     * @param maxCompletionTokens 最大生成 token 数
     */
    public VLLM(String baseUrl, String model, Integer maxCompletionTokens) {
        this(baseUrl, null, model, maxCompletionTokens, null);
    }

    /**
     * 完整参数构造
     *
     * @param baseUrl             vLLM 服务端点地址
     * @param apiKey              API Key（可选，无认证时传 null）
     * @param model               模型名称
     * @param maxCompletionTokens 最大生成 token 数
     * @param callback            回调（可选）
     */
    public VLLM(String baseUrl, String apiKey, String model,
                Integer maxCompletionTokens, ChainCallback callback) {
        super(baseUrl, apiKey, model, maxCompletionTokens, callback);
    }

    /**
     * 通过 ChainContext 构造
     */
    public static VLLM fromContext(String baseUrl, String apiKey, String model,
                                   Integer maxCompletionTokens, ChainContext chainContext) {
        return new VLLM(baseUrl, apiKey, model, maxCompletionTokens,
                chainContext != null ? chainContext.callback() : null);
    }

    // ---- Fluent setters（返回具体类型） ----

    @Override
    public VLLM enableThinking(boolean enable) {
        super.enableThinking(enable);
        return this;
    }

    @Override
    public VLLM thinkingBudget(Integer budget) {
        super.thinkingBudget(budget);
        return this;
    }

    @Override
    public VLLM enableJsonObjectMode(boolean enable) {
        super.enableJsonObjectMode(enable);
        return this;
    }

    @Override
    public VLLM temperature(Double temperature) {
        super.temperature(temperature);
        return this;
    }

    @Override
    public VLLM topP(Double topP) {
        super.topP(topP);
        return this;
    }

    // ---- vLLM 特有参数 ----

    @Override
    protected String getThinkingFieldName() {
        return "reasoning";
    }

    @Override
    protected void applyThinkingParams(ChatCompletionCreateParams.Builder builder) {
        Map<String, Object> chatTemplateKwargs = new LinkedHashMap<>();
        chatTemplateKwargs.put("enable_thinking", isEnableThinking());
        builder.putAdditionalBodyProperty("chat_template_kwargs", JsonValue.from(chatTemplateKwargs));
        if (getThinkingBudget() != null) {
            builder.putAdditionalBodyProperty("thinking_token_budget", JsonValue.from(getThinkingBudget()));
        }
    }
}
