package com.non.chain.embedding;

/**
 * 通用 OpenAI 兼容 Embedding provider，适用于 vllm-openai、Ollama 等任何
 * 兼容 OpenAI Embeddings API 的服务端点。
 *
 * <p>使用示例：
 * <pre>{@code
 * // 连接本地部署的 embedding 模型
 * EmbeddingModel model = new OpenAICompatibleEmbeddingModel(
 *     "http://10.100.10.21:40000/v1",
 *     "bge-large-zh-v1.5"
 * );
 *
 * float[] vector = model.embed("你好世界");
 * }</pre>
 */
public class OpenAICompatibleEmbeddingModel extends AbstractOpenAIEmbeddingModel {

    /**
     * 无 API Key 构造（适用于内网无认证部署）
     *
     * @param baseUrl 服务端点地址，如 "http://10.100.10.21:40000/v1"
     * @param model   模型名称
     */
    public OpenAICompatibleEmbeddingModel(String baseUrl, String model) {
        this(baseUrl, null, model, null);
    }

    /**
     * 指定维度
     *
     * @param baseUrl    服务端点地址
     * @param model      模型名称
     * @param dimensions 向量维度
     */
    public OpenAICompatibleEmbeddingModel(String baseUrl, String model, Integer dimensions) {
        this(baseUrl, null, model, dimensions);
    }

    /**
     * 完整参数构造
     *
     * @param baseUrl    服务端点地址
     * @param apiKey     API Key（可选，无认证时传 null）
     * @param model      模型名称
     * @param dimensions 向量维度（可选）
     */
    public OpenAICompatibleEmbeddingModel(String baseUrl, String apiKey, String model, Integer dimensions) {
        super(baseUrl, resolveApiKey(apiKey), model, dimensions);
    }

    private static String resolveApiKey(String apiKey) {
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKey;
        }
        // 无认证场景使用占位符（openai-java SDK 要求 apiKey 非空）
        return "no-api-key";
    }
}
