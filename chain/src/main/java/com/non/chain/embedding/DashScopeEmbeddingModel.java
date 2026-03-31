package com.non.chain.embedding;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.Embedding;
import com.openai.models.embeddings.EmbeddingCreateParams;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class DashScopeEmbeddingModel implements EmbeddingModel {

    private static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final String API_KEY_ENV = "DASHSCOPE_API_KEY";

    private final OpenAIClient client;
    private final String model;
    private final Integer dimensions;
    private Integer inferredDimension;

    public DashScopeEmbeddingModel(String model) {
        this(null, model, null);
    }

    public DashScopeEmbeddingModel(String model, Integer dimensions) {
        this(null, model, dimensions);
    }

    public DashScopeEmbeddingModel(String apiKey, String model, Integer dimensions) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("Embedding 模型名称不能为空");
        }
        this.client = OpenAIOkHttpClient.builder()
                .apiKey(resolveApiKey(apiKey))
                .baseUrl(DEFAULT_BASE_URL)
                .build();
        this.model = model;
        this.dimensions = dimensions;
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            throw new IllegalArgumentException("文本列表不能为空");
        }
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("文本列表中存在空文本");
            }
        }

        EmbeddingCreateParams.Builder paramsBuilder = EmbeddingCreateParams.builder()
                .model(model)
                .inputOfArrayOfStrings(texts);
        if (dimensions != null) {
            paramsBuilder.dimensions(dimensions.longValue());
        }

        CreateEmbeddingResponse response = client.embeddings().create(paramsBuilder.build());
        List<Embedding> data = new ArrayList<>(response.data());
        data.sort(Comparator.comparingLong(Embedding::index));

        List<float[]> vectors = new ArrayList<>(data.size());
        for (Embedding embedding : data) {
            vectors.add(toPrimitiveArray(embedding.embedding()));
        }

        if (vectors.size() != texts.size()) {
            throw new IllegalStateException("Embedding 返回数量与输入不一致");
        }
        if (!vectors.isEmpty()) {
            inferredDimension = vectors.get(0).length;
        }
        return vectors;
    }

    @Override
    public int dimension() {
        if (dimensions != null) {
            return dimensions;
        }
        if (inferredDimension != null) {
            return inferredDimension;
        }
        return EmbeddingModel.super.dimension();
    }

    private float[] toPrimitiveArray(List<Float> values) {
        float[] vector = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            vector[i] = values.get(i);
        }
        return vector;
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
