# Embedding 向量模型

## 概述

nonchain 的 Embedding 模块（`com.non.chain.embedding`）提供了向量化的抽象接口和开箱即用的实现。Embedding 模型将文本转换为高维向量表示，是语义检索、文本相似度计算、RAG 管道等场景的核心基础设施。

核心设计理念：

- **接口抽象**：通过 `EmbeddingModel` 接口统一向量化能力，便于替换不同实现
- **批量优先**：核心方法是批量接口 `embedAll`，单条 `embed` 默认委托批量方法
- **维度探测**：支持显式指定维度，也支持自动探测

## API 参考

### EmbeddingModel 接口

| 方法 | 说明 |
|------|------|
| `embedAll(List<String> texts)` | 批量向量化，返回与输入顺序一致的向量列表 |
| `embed(String text)` | 单条向量化，默认委托 `embedAll`（default 方法） |
| `dimension()` | 返回向量维度，默认通过 `embed("dimension probe")` 探测（default 方法） |

**接口定义**：

```java
public interface EmbeddingModel {

    List<float[]> embedAll(List<String> texts);

    default float[] embed(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("文本不能为空");
        }
        return embedAll(List.of(text)).get(0);
    }

    default int dimension() {
        return embed("dimension probe").length;
    }
}
```

### DashScopeEmbeddingModel

基于阿里云 DashScope（兼容 OpenAI API 格式）的 Embedding 模型实现。

| 构造器 | 说明 |
|--------|------|
| `DashScopeEmbeddingModel(String model)` | 使用环境变量 `DASHSCOPE_API_KEY` 进行认证 |
| `DashScopeEmbeddingModel(String model, Integer dimensions)` | 使用环境变量认证，并指定输出维度 |
| `DashScopeEmbeddingModel(String apiKey, String model, Integer dimensions)` | 完整参数构造，显式传入 API Key、模型名称和维度 |

**参数说明**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `apiKey` | `String` | 否 | DashScope API Key，未传入时从环境变量 `DASHSCOPE_API_KEY` 读取 |
| `model` | `String` | 是 | 模型名称，如 `text-embedding-v4` |
| `dimensions` | `Integer` | 否 | 输出向量维度，未指定时使用模型默认维度 |

**行为说明**：

- `embedAll` 方法会按照 API 返回的 `index` 字段对结果排序，确保与输入文本顺序一致
- `dimension()` 方法优先返回显式指定的 `dimensions`，其次返回已推断的维度，最后才执行探测
- API 基础地址默认为 `https://dashscope.aliyuncs.com/compatible-mode/v1`

## 使用示例

### 基本用法

```java
import com.non.chain.embedding.DashScopeEmbeddingModel;
import com.non.chain.embedding.EmbeddingModel;

import java.util.Arrays;
import java.util.List;

public class EmbeddingExample {
    public static void main(String[] args) {
        // 1. 创建 EmbeddingModel（使用环境变量 DASHSCOPE_API_KEY）
        EmbeddingModel embeddingModel = new DashScopeEmbeddingModel("text-embedding-v4");

        // 2. 单条文本向量化
        float[] singleVector = embeddingModel.embed("非链库是一个轻量级 Java LLM 工具库");
        System.out.println("单条 embedding 维度: " + singleVector.length);

        // 3. 批量向量化
        List<float[]> batchVectors = embeddingModel.embedAll(List.of(
                "向量检索用于语义匹配",
                "RRF 可以融合向量检索和关键词检索"
        ));
        System.out.println("批量 embedding 数量: " + batchVectors.size());

        // 4. 获取维度
        System.out.println("模型维度: " + embeddingModel.dimension());
    }
}
```

### 指定维度

```java
import com.non.chain.embedding.DashScopeEmbeddingModel;
import com.non.chain.embedding.EmbeddingModel;

public class EmbeddingDimensionExample {
    public static void main(String[] args) {
        // 指定输出维度为 512
        EmbeddingModel embeddingModel = new DashScopeEmbeddingModel(
                "text-embedding-v4", 512
        );

        float[] vector = embeddingModel.embed("测试文本");
        System.out.println("向量维度: " + vector.length);  // 输出: 512
        System.out.println("模型维度: " + embeddingModel.dimension());  // 输出: 512
    }
}
```

### 显式传入 API Key

```java
import com.non.chain.embedding.DashScopeEmbeddingModel;
import com.non.chain.embedding.EmbeddingModel;

public class EmbeddingApiKeyExample {
    public static void main(String[] args) {
        // 显式传入 API Key（适用于无法设置环境变量的场景）
        EmbeddingModel embeddingModel = new DashScopeEmbeddingModel(
                "sk-your-api-key-here",
                "text-embedding-v4",
                1024
        );

        float[] vector = embeddingModel.embed("使用显式 API Key");
        System.out.println("维度: " + vector.length);  // 输出: 1024
    }
}
```

## 与 KnowledgeStore 集成

Embedding 模型通常与 KnowledgeStore 配合使用，实现文档的向量化存储和语义检索。

```java
import com.non.chain.embedding.DashScopeEmbeddingModel;
import com.non.chain.embedding.EmbeddingModel;
import com.non.chain.knowledge.DocumentChunk;
import com.non.chain.knowledge.SearchRequest;
import com.non.chain.knowledge.SearchResult;
import com.non.chain.knowledge.pgvector.PgvectorKnowledgeStore;

import java.util.List;

public class EmbeddingWithKnowledgeStore {
    public static void main(String[] args) {
        // 1. 初始化 EmbeddingModel
        EmbeddingModel embeddingModel = new DashScopeEmbeddingModel("text-embedding-v4");

        // 2. 初始化 KnowledgeStore（维度需要与 Embedding 模型匹配）
        PgvectorKnowledgeStore store = PgvectorKnowledgeStore.builder(
                        "jdbc:postgresql://localhost:5432/nonchain", 1024)
                .username("postgres")
                .password("postgres")
                .build();

        // 3. 文档内容
        String content = "pgvector 是 PostgreSQL 的向量扩展，支持高效的相似度搜索";
        String kbId = "kb-demo";
        String docId = "doc-001";

        // 4. 向量化并存储
        float[] embedding = embeddingModel.embed(content);
        DocumentChunk chunk = DocumentChunk.builder(docId, kbId, content)
                .embedding(embedding)
                .chunkIndex(0)
                .putMetadata("source", "docs")
                .build();

        String chunkId = store.add(chunk);
        System.out.println("已存储 chunkId: " + chunkId);

        // 5. 查询向量化并检索
        float[] queryVec = embeddingModel.embed("PostgreSQL 向量搜索");
        SearchRequest request = SearchRequest.builder(queryVec)
                .topK(3)
                .minScore(0.5)
                .addKnowledgeBaseId(kbId)
                .build();

        List<SearchResult> results = store.search(request);
        results.forEach(r -> System.out.printf(
                "[%.4f] %s%n", r.score(), r.content()
        ));
    }
}
```

## 环境变量配置

DashScopeEmbeddingModel 支持通过环境变量配置 API Key：

```bash
export DASHSCOPE_API_KEY=sk-your-api-key-here
```

如果未设置环境变量且未显式传入 API Key，构造时会抛出 `IllegalArgumentException`。

## 异常说明

| 异常场景 | 抛出异常 |
|----------|----------|
| 模型名称为空或空白 | `IllegalArgumentException` |
| API Key 未提供且环境变量也未设置 | `IllegalArgumentException` |
| `embed` 传入空文本 | `IllegalArgumentException` |
| `embedAll` 传入空列表 | `IllegalArgumentException` |
| `embedAll` 列表中存在空文本 | `IllegalArgumentException` |
| 返回向量数量与输入不一致 | `IllegalStateException` |

## 依赖

DashScopeEmbeddingModel 基于 OpenAI Java SDK 实现，需要以下 Maven 依赖：

```xml
<dependency>
    <groupId>com.openai</groupId>
    <artifactId>openai-java</artifactId>
</dependency>
```
