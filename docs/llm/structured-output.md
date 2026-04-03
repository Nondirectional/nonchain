# 结构化输出

nonchain 通过 `OutputFormat` 枚举支持 JSON Object 结构化输出模式。启用后，模型将返回一个合法的 JSON 对象，便于程序化地解析和使用模型输出。

## OutputFormat 枚举

| 枚举值 | 说明 |
|--------|------|
| `OutputFormat.TEXT` | 普通文本输出（默认值） |
| `OutputFormat.JSON_OBJECT` | JSON 对象结构化输出 |

## 工作原理

当使用 `OutputFormat.JSON_OBJECT` 时，`DashscopeLLM` 会在请求中设置 `response_format` 为 `json_object`，引导模型生成合法的 JSON 对象输出。

> **重要限制**：`JSON_OBJECT` 模式与工具调用（tools）互斥。如果同时传入 tools 和 `OutputFormat.JSON_OBJECT`，将抛出 `IllegalArgumentException`。这是因为 `json_object` 响应格式与 function calling 的工具调用机制在 API 层面不兼容。

## 启用方式

### 方式一：在 chat 调用中指定

最直接的方式是在 `chat()` 方法中传入 `OutputFormat.JSON_OBJECT` 参数：

```java
import com.non.chain.OutputFormat;
import com.non.chain.ChatResult;
import com.non.chain.provider.DashscopeLLM;
import com.non.chain.provider.LLM;

LLM llm = new DashscopeLLM("qwen-plus");

ChatResult result = llm.chat(
    "你是一个只输出 JSON 对象的助手。",
    "请生成用户画像，字段包含 name、age、tags(数组)。只返回 JSON 对象。",
    OutputFormat.JSON_OBJECT
);

System.out.println(result.content());
// 输出: {"name":"张三","age":25,"tags":["程序员","Java"]}
```

### 方式二：全局启用 JSON Object 模式

使用 `enableJsonObjectMode(true)` 在 LLM 实例上全局启用，之后所有请求默认使用 JSON Object 格式：

```java
import com.non.chain.ChatResult;
import com.non.chain.provider.DashscopeLLM;
import com.non.chain.provider.LLM;

LLM llm = new DashscopeLLM("qwen-plus")
    .enableJsonObjectMode(true);

// 不传 OutputFormat，自动使用 JSON_OBJECT
ChatResult result = llm.chat(
    "你是一个只输出 JSON 的助手。",
    "生成一个包含 title 和 content 的 JSON 对象。"
);

System.out.println(result.content());
```

### 方式三：多轮对话中指定

```java
import com.non.chain.*;
import com.non.chain.provider.DashscopeLLM;
import com.non.chain.provider.LLM;
import java.util.ArrayList;
import java.util.List;

LLM llm = new DashscopeLLM("qwen-plus");

List<Message> messages = new ArrayList<>();
messages.add(Message.system("你是一个 JSON 数据生成器，只返回 JSON 对象。"));
messages.add(Message.user("生成一个包含3个城市的 JSON 数组，每个城市有 name 和 population 字段。"));

ChatResult result = llm.chat(messages, OutputFormat.JSON_OBJECT);
System.out.println(result.content());
```

## 使用场景

### 1. 数据提取

从非结构化文本中提取结构化数据：

```java
import com.non.chain.OutputFormat;
import com.non.chain.ChatResult;
import com.non.chain.provider.DashscopeLLM;
import com.non.chain.provider.LLM;

LLM llm = new DashscopeLLM("qwen-plus");

String text = "张三，28岁，住在北京市朝阳区，手机号13800138000，邮箱zhangsan@example.com";
String prompt = "从以下文本中提取个人信息，返回 JSON 对象，包含 name、age、address、phone、email 字段：\n" + text;

ChatResult result = llm.chat(null, prompt, OutputFormat.JSON_OBJECT);
System.out.println(result.content());
// 输出: {"name":"张三","age":28,"address":"北京市朝阳区","phone":"13800138000","email":"zhangsan@example.com"}
```

### 2. 分类与标注

对输入进行分类并返回结构化结果：

```java
import com.non.chain.OutputFormat;
import com.non.chain.ChatResult;
import com.non.chain.provider.DashscopeLLM;
import com.non.chain.provider.LLM;

LLM llm = new DashscopeLLM("qwen-plus");

String prompt = "对以下文本进行情感分析，返回 JSON 对象包含 sentiment（positive/negative/neutral）和 confidence（0-1）字段：\n" +
    "这个产品用起来非常顺手，功能强大而且界面美观！";

ChatResult result = llm.chat(null, prompt, OutputFormat.JSON_OBJECT);
System.out.println(result.content());
// 输出: {"sentiment":"positive","confidence":0.95}
```

### 3. 配置生成

生成程序可消费的配置数据：

```java
import com.non.chain.OutputFormat;
import com.non.chain.ChatResult;
import com.non.chain.provider.DashscopeLLM;
import com.non.chain.provider.LLM;

LLM llm = new DashscopeLLM("qwen-plus");

String prompt = "为一个 Spring Boot REST API 项目生成 application.yml 配置片段，" +
    "包含 server.port、spring.datasource.url、spring.datasource.username 字段。只返回 JSON 对象。";

ChatResult result = llm.chat(null, prompt, OutputFormat.JSON_OBJECT);
// 输出: {"server":{"port":8080},"spring":{"datasource":{"url":"jdbc:mysql://localhost:3306/mydb","username":"root"}}}
```

### 4. API 响应模拟

快速生成模拟的 API 响应数据：

```java
import com.non.chain.OutputFormat;
import com.non.chain.ChatResult;
import com.non.chain.provider.DashscopeLLM;
import com.non.chain.provider.LLM;

LLM llm = new DashscopeLLM("qwen-plus");

String prompt = "模拟一个电商商品列表 API 的响应，返回 JSON 对象包含 items 数组，" +
    "每个 item 包含 id、name、price、stock 字段，生成3个商品。只返回 JSON。";

ChatResult result = llm.chat(null, prompt, OutputFormat.JSON_OBJECT);
System.out.println(result.content());
```

## 输出验证

由于 `json_object` 格式仅保证模型输出一个 JSON 对象，建议在业务代码中进行验证：

```java
import com.non.chain.OutputFormat;
import com.non.chain.ChatResult;
import com.non.chain.provider.DashscopeLLM;
import com.non.chain.provider.LLM;

LLM llm = new DashscopeLLM("qwen-plus");

ChatResult result = llm.chat(
    "你是一个 JSON 助手。",
    "生成一个包含 name 和 age 的 JSON 对象。",
    OutputFormat.JSON_OBJECT
);

String content = result.content().trim();

// 基本验证
if (!content.startsWith("{") || !content.endsWith("}")) {
    System.out.println("警告：输出不是标准 JSON 对象");
} else {
    System.out.println("JSON 输出验证通过");
    System.out.println(content);
}
```

## 与工具调用的对比

| 特性 | 结构化输出 (JSON_OBJECT) | 工具调用 (Tools) |
|------|------------------------|-----------------|
| 输出格式 | JSON 对象 | 模型自由选择是否调用工具 |
| 适用场景 | 数据生成、信息提取 | 需要执行外部操作 |
| 多步交互 | 单次请求 | 支持多轮工具调用循环 |
| Schema 控制 | 通过 Prompt 描述 | 通过 Tool 参数定义精确 Schema |
| 互斥关系 | 不能与 tools 同时使用 | 不能与 JSON_OBJECT 同时使用 |

## 相关文档

- [Message 消息模型](./message.md) - Message 类和 OutputFormat 枚举
- [DashscopeLLM](./dashscope-llm.md) - LLM 配置与使用
- [ToolRegistry 工具注册中心](../tool/tool-registry.md) - 工具调用方式
