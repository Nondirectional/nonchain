# OpenAICompatibleLLM

`OpenAICompatibleLLM` 是 nonchain 框架中的通用 OpenAI 兼容 LLM provider。它继承自 `AbstractOpenAILLM`，可以连接任何兼容 OpenAI Chat Completions API 的服务端点，包括 vllm-openai、Ollama、LiteLLM 等。

## 前置条件

- Java 11+
- 一个运行中的 OpenAI 兼容服务端点（如 vllm-openai）
- Maven 依赖

### Maven 依赖

```xml
<dependency>
    <groupId>com.non</groupId>
    <artifactId>chain</artifactId>
    <version><!-- use latest --></version>
</dependency>
```

## 构造器

| 构造器 | 说明 |
|--------|------|
| `OpenAICompatibleLLM(String baseUrl, String model)` | 无 API Key，适用于内网无认证部署 |
| `OpenAICompatibleLLM(String baseUrl, String apiKey, String model)` | 指定 API Key |

API Key 为可选参数。对于内网无认证的部署环境，可以不传 API Key，框架会使用占位符。

## 配置方法

以下方法支持链式调用：

| 方法 | 返回类型 | 说明 |
|------|----------|------|
| `enableThinking(boolean enable)` | `OpenAICompatibleLLM` | 启用或关闭思考模式 |
| `thinkingBudget(Integer budget)` | `OpenAICompatibleLLM` | 设置思考模式的 token 预算 |
| `enableJsonObjectMode(boolean enable)` | `OpenAICompatibleLLM` | 启用或关闭 JSON Object 结构化输出模式 |
| `temperature(Double temperature)` | `OpenAICompatibleLLM` | 采样温度，控制生成文本多样性，范围 [0, 2) |
| `topP(Double topP)` | `OpenAICompatibleLLM` | 核采样概率阈值，范围 (0, 1.0] |
| `maxCompletionTokens(Integer)` | `OpenAICompatibleLLM` | 最大生成 token 数 |
| `callback(ChainCallback)` | `OpenAICompatibleLLM` | 设置回调 |

## 使用示例

### 连接 vllm-openai 本地模型

```java
import com.non.chain.ChatResult;
import com.non.chain.provider.OpenAICompatibleLLM;
import com.non.chain.provider.LLM;

LLM llm = new OpenAICompatibleLLM("http://10.100.10.21:40000/v1", "qwen3-14b");

ChatResult result = llm.chat("你是一个有用的助手", "你好，请介绍一下 Java");
System.out.println(result.content());
```

### 带思考模式

```java
LLM llm = new OpenAICompatibleLLM("http://10.100.10.21:40000/v1", "qwen3-14b")
    .enableThinking(true)
    .thinkingBudget(2048);

ChatResult result = llm.chat(null, "9.9 和 9.11 哪个大？");

if (result.hasThinking()) {
    System.out.println("思考过程: " + result.thinkingContent());
}
System.out.println("回复: " + result.content());
```

### 连接 Ollama

```java
LLM llm = new OpenAICompatibleLLM("http://localhost:11434/v1", "qwen3:14b");
```

### 带 API Key 的服务

```java
LLM llm = new OpenAICompatibleLLM(
    "https://api.example.com/v1",
    "sk-your-api-key",
    "model-name"
).maxCompletionTokens(2048);
```

### 配合 Agent 使用

```java
import com.non.chain.agent.Agent;
import com.non.chain.provider.OpenAICompatibleLLM;
import com.non.chain.tool.ToolRegistry;

LLM llm = new OpenAICompatibleLLM("http://10.100.10.21:40000/v1", "qwen3-14b");

ToolRegistry registry = new ToolRegistry();
registry.register("search", "搜索信息")
    .param("query", "string", "搜索关键词", true)
    .handle(args -> "搜索结果...");

Agent agent = Agent.builder(llm, registry)
    .systemPrompt("你是一个有用的助手")
    .build();

String response = agent.run("帮我搜索一下 Java 的新特性");
```

## HTTP 超时配置

`AbstractOpenAILLM` 构造时设置了以下默认超时时间：

| 阶段 | 超时时间 |
|------|----------|
| 连接超时 (connect) | 30 秒 |
| 读取超时 (read) | 180 秒 |
| 写入超时 (write) | 60 秒 |

该配置应用于所有继承自 `AbstractOpenAILLM` 的 provider（DashscopeLLM、OpenAICompatibleLLM、VLLM）。当前不支持通过 API 自定义超时时间。

## Provider 继承体系

```
LLM (接口)
 └── AbstractOpenAILLM (抽象基类，封装 OpenAI API 通用逻辑)
      ├── OpenAICompatibleLLM    ← 通用，base URL 完全可配置
      │    └── VLLM              ← vLLM 专用，thinking 参数嵌套格式
      └── DashscopeLLM           ← DashScope 默认值 + topK 特有参数
```

`AbstractOpenAILLM` 封装了所有通用的 OpenAI Chat Completions API 逻辑，包括消息构建、工具调用、流式响应、思考模式提取等。子类只需提供连接配置和特有参数。

## 相关文档

- [VLLM](./vllm.md) - vLLM 推理服务器专用 provider（thinking 嵌套参数格式）
- [DashscopeLLM](./dashscope-llm.md) - 阿里云 DashScope provider
- [Message 消息模型](./message.md) - 消息类型与 ChatResult
- [多模态输入](./multimodal.md) - 图片理解功能
- [结构化输出](./structured-output.md) - JSON 结构化输出详解
