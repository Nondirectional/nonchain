package com.non.chain.embedding;

/**
 * DashScope Embedding provider（阿里云灵积平台）。
 * 继承 {@link AbstractOpenAIEmbeddingModel}，默认连接 DashScope 的 OpenAI 兼容端点。
 */
public class DashScopeEmbeddingModel extends AbstractOpenAIEmbeddingModel {

    private static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final String API_KEY_ENV = "DASHSCOPE_API_KEY";

    public DashScopeEmbeddingModel(String model) {
        this(null, model, null);
    }

    public DashScopeEmbeddingModel(String model, Integer dimensions) {
        this(null, model, dimensions);
    }

    public DashScopeEmbeddingModel(String apiKey, String model, Integer dimensions) {
        super(DEFAULT_BASE_URL, resolveApiKey(apiKey), model, dimensions);
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
}
