# Message 消息模型

`Message` 类是 nonchain 框架中表示聊天消息的核心数据类。它封装了对话中各角色的消息内容，支持纯文本消息、多模态消息（文本 + 图片）、工具调用消息、工具结果消息和仅应用层可见的 note 消息。

## 消息角色

`Message` 支持以下角色（role）：

| 角色 | 说明 |
|------|------|
| `system` | 系统消息，用于设定模型的行为和角色 |
| `user` | 用户消息，表示用户的输入 |
| `assistant` | 助手消息，表示模型的回复 |
| `tool` | 工具结果消息，表示工具执行的返回结果 |
| `note` | 应用层状态消息，默认 `llmVisible=false`，保留在 transcript 但不发送给模型 |

## 静态工厂方法

`Message` 通过静态工厂方法创建不同类型的消息实例：

| 方法 | 说明 | 参数 |
|------|------|------|
| `system(String content)` | 创建系统消息 | `content` - 系统提示内容 |
| `user(String content)` | 创建文本用户消息 | `content` - 用户输入的文本内容 |
| `user(List<ContentPart> contentParts)` | 创建多模态用户消息 | `contentParts` - 内容部件列表（文本 + 图片） |
| `assistant(String content)` | 创建助手消息 | `content` - 助手回复的文本内容 |
| `assistantWithToolCalls(String content, List<ToolCall> toolCalls)` | 创建带工具调用的助手消息 | `content` - 助手回复内容，`toolCalls` - 工具调用列表 |
| `toolResult(String toolCallId, String content)` | 创建工具结果消息 | `toolCallId` - 工具调用 ID，`content` - 工具执行结果 |
| `note(String kind, String content)` | 创建应用层 note 消息 | `kind` - 业务语义标签，`content` - 展示内容 |

## 字段说明

| 字段 | 类型 | 说明 |
|------|------|------|
| `role` | `String` | 消息角色（system / user / assistant / tool） |
| `content` | `String` | 文本内容 |
| `contentParts` | `List<ContentPart>` | 多模态内容部件（仅多模态用户消息使用） |
| `toolCallId` | `String` | 工具调用 ID（仅工具结果消息使用） |
| `toolCalls` | `List<ToolCall>` | 工具调用列表（仅带工具调用的助手消息使用） |
| `llmVisible` | `boolean` | 是否进入 LLM 请求；普通消息默认 `true`，note 默认 `false` |
| `kind` | `String` | 可选应用层语义标签，如 `status` / `ui` |

## 访问器方法

```java
String role()                    // 获取消息角色
String content()                 // 获取文本内容
List<ContentPart> contentParts() // 获取多模态内容部件
String toolCallId()              // 获取工具调用 ID
List<ToolCall> toolCalls()       // 获取工具调用列表
boolean llmVisible()             // 是否进入 LLM 请求
String kind()                    // 获取应用层语义标签
```

## ContentPart 接口

`ContentPart` 是多模态消息内容部件的标记接口，用于构建包含文本和图片的用户消息。目前有两个实现类：

### TextPart - 文本内容部件

```java
// 创建文本部件
TextPart textPart = TextPart.of("请描述这张图片的内容");

// 获取文本
String text = textPart.text();
```

| 方法 | 说明 |
|------|------|
| `TextPart.of(String text)` | 创建文本内容部件 |
| `text()` | 获取文本内容 |

### ImageUrlPart - 图片 URL 内容部件

```java
// 创建图片 URL 部件
ImageUrlPart imagePart = ImageUrlPart.of("https://example.com/image.jpg");

// 获取 URL
String url = imagePart.url();
```

| 方法 | 说明 |
|------|------|
| `ImageUrlPart.of(String url)` | 创建图片 URL 内容部件 |
| `url()` | 获取图片 URL |

## ChatResult 类

`ChatResult` 是 LLM 调用的返回结果，包含模型的回复内容、思考过程和工具调用信息。

### 字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `content` | `String` | 模型的回复内容 |
| `thinkingContent` | `String` | 模型的思考过程（启用思考模式时可用） |
| `toolCalls` | `List<ToolCall>` | 模型请求的工具调用列表 |

### 方法

| 方法 | 返回类型 | 说明 |
|------|----------|------|
| `content()` | `String` | 获取回复内容 |
| `thinkingContent()` | `String` | 获取思考内容 |
| `toolCalls()` | `List<ToolCall>` | 获取工具调用列表 |
| `hasThinking()` | `boolean` | 是否包含思考内容 |
| `hasToolCalls()` | `boolean` | 是否包含工具调用 |
| `toMessage()` | `Message` | 自动转换为 Message 对象 |

### toMessage() 转换逻辑

`toMessage()` 方法会根据 `ChatResult` 的内容自动选择合适的 Message 类型：

- 如果包含工具调用（`hasToolCalls() == true`），返回 `Message.assistantWithToolCalls(content, toolCalls)`
- 否则返回普通的 `Message.assistant(content)`

### toString() 输出格式

`ChatResult` 的 `toString()` 方法会格式化输出：

```
[思考]
<思考内容>

[工具调用]
  ToolCall{id='xxx', name='get_weather', arguments='{"city":"北京"}'}

[回复]
<回复内容>
```

## OutputFormat 枚举

`OutputFormat` 枚举用于指定 LLM 的输出格式：

| 枚举值 | 说明 |
|--------|------|
| `TEXT` | 普通文本输出（默认） |
| `JSON_OBJECT` | JSON 对象结构化输出 |

## 使用示例

### 创建各类型消息

```java
import com.non.chain.Message;
import com.non.chain.ChatResult;
import com.non.chain.TextPart;
import com.non.chain.ImageUrlPart;

// 1. 系统消息
Message systemMsg = Message.system("你是一个有用的助手。");

// 2. 文本用户消息
Message userMsg = Message.user("你好，请介绍一下自己。");

// 3. 多模态用户消息（文本 + 图片）
Message multimodalMsg = Message.user(Arrays.asList(
    ImageUrlPart.of("https://example.com/photo.jpg"),
    TextPart.of("这张图片里有什么？")
));

// 4. 助手消息
Message assistantMsg = Message.assistant("你好！我是一个 AI 助手。");

// 5. 带工具调用的助手消息
Message assistantToolMsg = Message.assistantWithToolCalls(
    "",
    Arrays.asList(
        new ToolCall("call_001", "get_weather", "{\"city\":\"北京\"}")
    )
);

// 6. 工具结果消息
Message toolResultMsg = Message.toolResult("call_001", "北京今天晴天，25°C。");
```

### 处理 ChatResult

```java
import com.non.chain.ChatResult;
import com.non.chain.Message;
import com.non.chain.provider.DashscopeLLM;
import com.non.chain.provider.LLM;

LLM llm = new DashscopeLLM("qwen-plus");
ChatResult result = llm.chat("你是一个助手", "你好");

// 获取回复内容
String content = result.content();

// 检查是否有思考过程（需要启用思考模式）
if (result.hasThinking()) {
    System.out.println("思考过程: " + result.thinkingContent());
}

// 检查是否有工具调用
if (result.hasToolCalls()) {
    System.out.println("模型请求调用工具");
    for (ToolCall tc : result.toolCalls()) {
        System.out.println("工具: " + tc.name() + ", 参数: " + tc.arguments());
    }
}

// 转换为 Message（用于多轮对话）
Message assistantMessage = result.toMessage();
```

### 多轮对话消息构建

```java
import com.non.chain.Message;
import com.non.chain.provider.DashscopeLLM;
import com.non.chain.provider.LLM;
import java.util.ArrayList;
import java.util.List;

LLM llm = new DashscopeLLM("qwen-plus");

List<Message> messages = new ArrayList<>();

// 第一轮对话
messages.add(Message.user("我叫小明"));
ChatResult result1 = llm.chat(messages);
messages.add(result1.toMessage());

// 第二轮对话（模型能记住之前的上下文）
messages.add(Message.user("我叫什么名字？"));
ChatResult result2 = llm.chat(messages);
System.out.println(result2.content());  // 输出: 你叫小明
```

## 多 system 消息兼容

不同模型的 Chat Template 对 `system` 消息数量和位置要求不同。`LLM` 默认声明支持多条 system；对实际不支持的模型实例，应显式关闭该能力：

```java
LLM llm = new OpenAICompatibleLLM("http://localhost:8000/v1", "model-name")
        .supportsMultipleSystemMessages(false);
```

关闭后，`prepareMessages(...)` 在每次请求前创建并归一化消息副本：

- 第一条可见消息是 system 时保留该消息；后续 system 转为带 `[Framework System Instruction]` 边界的 user 消息
- 第一条可见消息不是 system 时，所有 system 都转换为上述 user 消息
- 转换不会插入 assistant(toolCalls) 与其连续 tool result 之间，避免破坏工具调用协议
- 原始 Agent transcript、ChatMemory、callback 事件和 trace 载荷保持原角色与顺序
- `llmVisible=false` 消息不会进入模型请求

`DashscopeLLM`、`OpenAICompatibleLLM` 和 `VLLM` 提供可链式 setter。自定义 `LLM` 默认仍返回 `supportsMultipleSystemMessages() == true`；若需要运行时切换，应覆写 setter 或 `prepareMessages(...)`，默认 setter 会抛出 `UnsupportedOperationException`，避免静默忽略配置。

## 依赖

```xml
<dependency>
    <groupId>io.github.nondirectional</groupId>
    <artifactId>chain</artifactId>
    <version>0.11.0</version>
</dependency>
```

## 相关文档

- [DashscopeLLM](./dashscope-llm.md) - 阿里云 DashScope LLM 实现
- [多模态输入](./multimodal.md) - 文本 + 图片的多模态消息支持
- [结构化输出](./structured-output.md) - JSON 对象结构化输出
- [ToolRegistry 工具注册中心](../tool/tool-registry.md) - 工具调用与执行
