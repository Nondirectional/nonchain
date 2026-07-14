# VLLM

`VLLM` 是 nonchain 框架中专门为 [vLLM](https://github.com/vllm-project/vllm) 推理服务器设计的 LLM provider。它继承自 `OpenAICompatibleLLM`，并处理 vLLM 特有的 thinking 参数格式。

## 为什么需要 VLLM 而不是 OpenAICompatibleLLM？

vLLM 兼容 OpenAI Chat Completions API，`OpenAICompatibleLLM` 可以完成基本对话。但 vLLM 的 thinking 模式使用**嵌套参数格式**，与 DashScope 等其他提供商的平级格式不同：

| 参数 | DashScope / 通用 | vLLM |
|------|------------------|------|
| thinking 开关 | `enable_thinking: true`（平级） | `chat_template_kwargs: {enable_thinking: true}`（嵌套） |
| 思考预算 | `thinking_budget: 1024` | `thinking_token_budget: 1024`（字段名不同） |

`VLLM` 自动处理这些差异，让你无需关心底层参数格式。

## 前置条件

- Java 11+
- 一个运行中的 vLLM 服务端点
- Maven 依赖

### Maven 依赖

```xml
<dependency>
    <groupId>com.non</groupId>
    <artifactId>chain</artifactId>
    <version>0.11.0</version>
</dependency>
```

## 构造器

| 构造器 | 说明 |
|--------|------|
| `VLLM(String baseUrl, String model)` | 无 API Key，适用于内网无认证部署 |
| `VLLM(String baseUrl, String apiKey, String model)` | 指定 API Key |

API Key 为可选参数。大多数 vLLM 部署不需要认证，可以不传 API Key。

## 配置方法

以下方法支持链式调用：

| 方法 | 返回类型 | 说明 |
|------|----------|------|
| `enableThinking(boolean enable)` | `VLLM` | 启用或关闭思考模式 |
| `thinkingBudget(Integer budget)` | `VLLM` | 设置思考模式的 token 预算 |
| `enableJsonObjectMode(boolean enable)` | `VLLM` | 启用或关闭 JSON Object 结构化输出模式 |
| `temperature(Double temperature)` | `VLLM` | 采样温度，控制生成文本多样性 |
| `topP(Double topP)` | `VLLM` | 核采样概率阈值 |
| `maxCompletionTokens(Integer)` | `VLLM` | 最大生成 token 数 |
| `callback(ChainCallback)` | `VLLM` | 设置回调 |

## 使用示例

### 基础对话

```java
import com.non.chain.ChatResult;
import com.non.chain.provider.VLLM;
import com.non.chain.provider.LLM;

LLM llm = new VLLM("http://localhost:8000/v1", "Qwen/Qwen3-14B");

ChatResult result = llm.chat("你是一个有用的助手", "用一句话介绍 Java");
System.out.println(result.content());
```

### 启用 thinking 模式

```java
LLM llm = new VLLM("http://localhost:8000/v1", "Qwen/Qwen3-14B")
    .enableThinking(true);

ChatResult result = llm.chat(null, "9.9 和 9.11 哪个大？");

if (result.hasThinking()) {
    System.out.println("思考过程: " + result.thinkingContent());
}
System.out.println("回复: " + result.content());
```

### thinking + 思考预算控制

```java
LLM llm = new VLLM("http://localhost:8000/v1", "Qwen/Qwen3-14B")
    .enableThinking(true)
    .thinkingBudget(2048);

ChatResult result = llm.chat(null, "解释一下递归");
```

### 流式 + thinking

```java
LLM llm = new VLLM("http://localhost:8000/v1", "Qwen/Qwen3-14B")
    .enableThinking(true);

ChatResult result = llm.streamChat("你是一个有用的助手", "解释量子纠缠", chunk -> {
    if (chunk.hasThinking()) {
        System.out.print("[思考]" + chunk.deltaThinking());
    }
    if (chunk.hasContent()) {
        System.out.print(chunk.deltaContent());
    }
});
```

### 配合 Agent 使用

```java
import com.non.chain.agent.Agent;
import com.non.chain.provider.VLLM;
import com.non.chain.tool.ToolRegistry;

LLM llm = new VLLM("http://localhost:8000/v1", "Qwen/Qwen3-14B");

ToolRegistry registry = new ToolRegistry();
registry.register("search", "搜索信息")
    .param("query", "string", "搜索关键词", true)
    .handle(args -> "搜索结果...");

Agent agent = Agent.builder(llm, registry)
    .systemPrompt("你是一个有用的助手")
    .build();

String response = agent.run("帮我搜索一下 Java 的新特性");
```

### 多模态图片理解

vLLM 支持部署视觉理解模型（如 Qwen2-VL），可以结合 `ImageDataPart` 传入本地图片文件或 base64 数据：

```java
import com.non.chain.*;
import com.non.chain.provider.LLM;
import com.non.chain.provider.VLLM;
import java.util.Arrays;

LLM llm = new VLLM("http://localhost:8000/v1", "Qwen/Qwen2-VL-7B-Instruct");

// 从本地文件读取图片（自动检测 MIME 类型）
Message userMessage = Message.user(Arrays.asList(
    ImageDataPart.fromFile("/path/to/image.jpg"),
    TextPart.of("描述这张图片的内容")
));

ChatResult result = llm.chat(Arrays.asList(userMessage));
System.out.println(result.content());
```

也可以直接传入 base64 编码的图片数据：

```java
Message userMessage = Message.user(Arrays.asList(
    ImageDataPart.of(base64String, "image/png"),
    TextPart.of("这张图片里有什么？")
));
```

vLLM 多模态同时支持 `ImageUrlPart`（URL 图片）和 `ImageDataPart`（base64/本地文件）两种方式。

## Provider 继承体系

```
LLM (接口)
 └── AbstractOpenAILLM (抽象基类，封装 OpenAI API 通用逻辑)
      ├── OpenAICompatibleLLM    ← 通用，base URL 完全可配置
      │    └── VLLM              ← vLLM 专用，thinking 参数嵌套格式
      └── DashscopeLLM           ← DashScope 默认值 + topK 特有参数
```

## 相关文档

- [OpenAICompatibleLLM](./openai-compatible-llm.md) - 通用 OpenAI 兼容 provider
- [DashscopeLLM](./dashscope-llm.md) - 阿里云 DashScope provider
- [Message 消息模型](./message.md) - 消息类型与 ChatResult
- [结构化输出](./structured-output.md) - JSON 结构化输出详解
