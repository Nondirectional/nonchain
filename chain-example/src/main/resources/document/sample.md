# NonChain 快速入门

NonChain 是一个轻量级的 Java LLM 应用开发库。

## 核心模块

### LLM Provider

支持 DashScope 等提供商，提供统一的 Chat 接口。

### Embedding

提供文本向量化能力，支持单条和批量 embedding。

### Knowledge Store

支持 Pgvector 和 Elasticsearch 两种向量存储后端。

## 示例代码

```java
EmbeddingModel model = new DashScopeEmbeddingModel("text-embedding-v4");
float[] vector = model.embed("Hello World");
System.out.println("维度: " + vector.length);
```

## 工作流引擎

Graph 引擎支持条件路由、状态管理，可构建复杂的 Agent 工作流。
