# DashscopeLLM

`DashscopeLLM` 是 nonchain 框架中阿里云 DashScope 大模型服务的实现类。它通过 OpenAI 兼容 API 与 DashScope 服务通信，支持通义千问系列模型的对话、工具调用、思考模式和结构化输出等功能。

## 前置条件

- Java 11+
- DashScope API Key（通过环境变量或构造参数传入）
- Maven 依赖

### 环境变量配置

```bash
export DASHSCOPE_API_KEY=sk-xxxxxxxxxxxxxxxxxxxxxxxx
```

### Maven 依赖

```xml
<dependency>
    <groupId>com.non</groupId>
    <artifactId>chain</artifactId>
    <version>0.4.0</version>
</dependency>
```

## 构造器

| 构造器 | 说明 |
|--------|------|
| `DashscopeLLM(String model)` | 使用 `DASHSCOPE_API_KEY` 环境变量，不限制最大 token 数 |
| `DashscopeLLM(String model, Integer maxCompletionTokens)` | 使用 `DASHSCOPE_API_KEY` 环境变量，指定最大 token 数 |
| `DashscopeLLM(String apiKey, String model, Integer maxCompletionTokens)` | 显式传入 API Key、模型名称和最大 token 数 |

API Key 的解析优先级：构造参数 > 环境变量 `DASHSCOPE_API_KEY`。如果两者都未提供，将抛出 `IllegalArgumentException`。

### 常用模型

| 模型名称 | 说明 |
|----------|------|
| `qwen-plus` | 通义千问 Plus，通用对话模型 |
| `qwen-turbo` | 通义千问 Turbo，快速响应模型 |
| `qwen-max` | 通义千问 Max，高性能模型 |
| `qwen-vl-plus` | 通义千问视觉理解模型，支持图片输入 |

## 配置方法

以下方法支持链式调用：

| 方法 | 返回类型 | 说明 |
|------|----------|------|
| `enableThinking(boolean enable)` | `DashscopeLLM` | 启用或关闭思考模式（Deep Thinking） |
| `thinkingBudget(Integer budget)` | `DashscopeLLM` | 设置思考模式的 token 预算 |
| `enableJsonObjectMode(boolean enable)` | `DashscopeLLM` | 启用或关闭 JSON Object 结构化输出模式 |
| `temperature(Double temperature)` | `DashscopeLLM` | 采样温度，控制生成文本多样性，范围 [0, 2) |
| `topP(Double topP)` | `DashscopeLLM` | 核采样概率阈值，范围 (0, 1.0] |
| `topK(Integer topK)` | `DashscopeLLM` | 候选 Token 数量，非 OpenAI 标准参数 |

### 思考模式

思考模式（Deep Thinking）让模型在回复前进行推理思考。启用后，`ChatResult` 中可以通过 `thinkingContent()` 获取模型的思考过程。

```java
DashscopeLLM llm = new DashscopeLLM("qwen-plus")
    .enableThinking(true)
    .thinkingBudget(1024);  // 可选：设置思考 token 预算
```

### JSON Object 模式

启用后，所有请求默认使用 `json_object` 响应格式，模型输出将是一个合法的 JSON 对象。

```java
DashscopeLLM llm = new DashscopeLLM("qwen-plus")
    .enableJsonObjectMode(true);
```

> **注意**：`json_object` 模式与工具调用（tools）互斥，不能同时使用。如果同时传入 tools 和 JSON Object 模式，将抛出 `IllegalArgumentException`。

## LLM 接口方法

`DashscopeLLM` 实现了 `LLM` 接口，提供以下方法：

| 方法 | 说明 |
|------|------|
| `chat(String systemMessage, String userMessage)` | 单轮对话，默认 TEXT 输出格式 |
| `chat(List<Message> messages)` | 多轮对话，默认 TEXT 输出格式 |
| `chat(String systemMessage, String userMessage, List<Tool> tools)` | 单轮对话 + 工具定义 |
| `chat(List<Message> messages, List<Tool> tools)` | 多轮对话 + 工具定义 |
| `chat(String systemMessage, String userMessage, OutputFormat outputFormat)` | 单轮对话，指定输出格式 |
| `chat(List<Message> messages, OutputFormat outputFormat)` | 多轮对话，指定输出格式 |
| `chat(String systemMessage, String userMessage, List<Tool> tools, OutputFormat outputFormat)` | 单轮对话 + 工具 + 输出格式 |
| `chat(List<Message> messages, List<Tool> tools, OutputFormat outputFormat)` | 多轮对话 + 工具 + 输出格式 |

带 `OutputFormat` 参数的版本是核心实现，其余为便捷默认方法。

## 使用示例

### 简单对话

```java
import com.non.chain.ChatResult;
import com.non.chain.provider.DashscopeLLM;
import com.non.chain.provider.LLM;

LLM llm = new DashscopeLLM("qwen-plus");

// 方式一：传入 system 和 user 字符串
ChatResult result = llm.chat("你是一个有用的助手", "你好，请介绍一下 Java");
System.out.println(result.content());
```

### 多轮对话

```java
import com.non.chain.Message;
import com.non.chain.ChatResult;
import com.non.chain.provider.DashscopeLLM;
import com.non.chain.provider.LLM;
import java.util.ArrayList;
import java.util.List;

LLM llm = new DashscopeLLM("qwen-plus");

List<Message> messages = new ArrayList<>();

// 第一轮
messages.add(Message.user("我想学 Java"));
ChatResult result1 = llm.chat(messages);
messages.add(result1.toMessage());

// 第二轮
messages.add(Message.user("推荐几本入门书籍"));
ChatResult result2 = llm.chat(messages);
System.out.println(result2.content());
```

### 带系统提示的多轮对话

```java
import com.non.chain.Message;
import com.non.chain.ChatResult;
import com.non.chain.provider.DashscopeLLM;
import com.non.chain.provider.LLM;
import java.util.ArrayList;
import java.util.List;

LLM llm = new DashscopeLLM("qwen-plus");

List<Message> messages = new ArrayList<>();
messages.add(Message.system("你是一个 Java 编程专家，用简洁的方式回答问题。"));
messages.add(Message.user("什么是多态？"));

ChatResult result = llm.chat(messages);
System.out.println(result.content());
```

### 带工具调用的对话

```java
import com.non.chain.*;
import com.non.chain.provider.DashscopeLLM;
import com.non.chain.provider.LLM;
import com.non.chain.tool.*;
import java.util.ArrayList;
import java.util.List;

// 1. 创建 LLM
LLM llm = new DashscopeLLM("qwen-plus", 512);

// 2. 注册工具（Fluent API 方式）
ToolRegistry registry = new ToolRegistry();
registry.register("get_current_weather", "查询指定城市的天气")
    .param("location", "string", "城市名称，如北京市", true)
    .handle(args -> {
        String location = args.getString("location");
        return location + "今天晴天，25°C。";
    });

List<Tool> tools = registry.getTools();

// 3. 发起对话
List<Message> messages = new ArrayList<>();
messages.add(Message.user("北京天气怎么样？"));

ChatResult response = llm.chat(messages, tools);
messages.add(response.toMessage());

// 4. 处理工具调用
while (response.hasToolCalls()) {
    for (ToolCall toolCall : response.toolCalls()) {
        String toolResult = registry.execute(toolCall.name(), toolCall.arguments());
        messages.add(Message.toolResult(toolCall.id(), toolResult));
    }
    response = llm.chat(messages, tools);
    messages.add(response.toMessage());
}

System.out.println("最终回复: " + response.content());
```

### 结构化输出

```java
import com.non.chain.OutputFormat;
import com.non.chain.ChatResult;
import com.non.chain.provider.DashscopeLLM;
import com.non.chain.provider.LLM;

LLM llm = new DashscopeLLM("qwen-plus");

String system = "你是一个只输出 JSON 对象的助手。";
String user = "请生成用户画像，字段包含 name、age、tags(数组)。只返回 JSON 对象。";

ChatResult result = llm.chat(system, user, OutputFormat.JSON_OBJECT);
System.out.println(result.content());
// 输出类似: {"name":"张三","age":25,"tags":["程序员","Java"]}
```

### 启用思考模式

```java
import com.non.chain.ChatResult;
import com.non.chain.provider.DashscopeLLM;
import com.non.chain.provider.LLM;

LLM llm = new DashscopeLLM("qwen-plus")
    .enableThinking(true);

ChatResult result = llm.chat(null, "9.9 和 9.11 哪个大？");

// 查看思考过程
if (result.hasThinking()) {
    System.out.println("思考过程: " + result.thinkingContent());
}

// 查看最终回复
System.out.println("回复: " + result.content());
```

### 限制最大 Token 数

```java
import com.non.chain.provider.DashscopeLLM;
import com.non.chain.provider.LLM;

// 方式一：构造器指定
LLM llm = new DashscopeLLM("qwen-plus", 256);

// 方式二：显式传入 API Key
LLM llm2 = new DashscopeLLM("sk-xxx", "qwen-plus", 256);
```

## 底层实现

`DashscopeLLM` 基于 OpenAI Java SDK 的 `OpenAIOkHttpClient` 实现，默认使用 DashScope 的 OpenAI 兼容端点：

```
https://dashscope.aliyuncs.com/compatible-mode/v1
```

思考模式通过在请求体中添加 `enable_thinking` 和 `thinking_budget` 扩展字段实现，结构化输出通过 `response_format` 参数设置为 `json_object` 实现。`temperature` 和 `topP` 通过 OpenAI SDK 标准参数传递，`topK` 通过 `additionalBodyProperty` 传递（非 OpenAI 标准参数）。建议 `temperature` 和 `topP` 只设置其中一个。

## 相关文档

- [Message 消息模型](./message.md) - 消息类型与 ChatResult
- [多模态输入](./multimodal.md) - 图片理解功能
- [结构化输出](./structured-output.md) - JSON 结构化输出详解
- [ToolRegistry 工具注册中心](../tool/tool-registry.md) - 工具注册与执行
