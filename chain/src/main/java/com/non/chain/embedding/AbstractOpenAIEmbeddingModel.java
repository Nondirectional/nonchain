package com.non.chain.embedding;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.Embedding;
import com.openai.models.embeddings.EmbeddingCreateParams;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * OpenAI 兼容 Embedding 抽象基类，封装通用的 Embeddings API 调用逻辑。
 * 子类只需提供 base URL、API key 等配置即可。
 */
public abstract class AbstractOpenAIEmbeddingModel implements EmbeddingModel {

    protected final OpenAIClient client;
    protected final String model;
    protected final Integer dimensions;
    private Integer inferredDimension;

    protected AbstractOpenAIEmbeddingModel(String baseUrl, String apiKey, String model, Integer dimensions) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("Embedding 模型名称不能为空");
        }
        this.client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
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
}
