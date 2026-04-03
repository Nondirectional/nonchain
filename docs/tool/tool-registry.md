# ToolRegistry 工具注册中心

`ToolRegistry` 是 nonchain 框架中工具管理的核心组件，负责工具的注册、定义导出和执行。它支持两种注册方式 -- 注解扫描和流式 API，统一管理所有工具的生命周期。

## 概述

`ToolRegistry` 的核心职责：

1. **注册工具** -- 通过注解扫描或流式 API 将工具定义注册到注册中心
2. **导出工具定义** -- 将注册的工具转换为 `Tool` 列表，传递给 LLM
3. **执行工具调用** -- 根据工具名称和 JSON 参数执行对应的工具逻辑
4. **查询工具** -- 检查某个工具是否已注册

## 两种注册模式

### 模式一：注解扫描

通过 `scan(Object target)` 方法扫描对象中所有带 `@ToolDef` 注解的方法：

```java
static class WeatherService {
    @ToolDef(name = "get_weather", description = "查询天气")
    public String getWeather(
        @ToolParam(name = "city", description = "城市名称") String city) {
        return city + "晴天";
    }
}

ToolRegistry registry = new ToolRegistry().scan(new WeatherService());
```

### 模式二：流式 API

通过 `register()` 方法链式注册工具：

```java
ToolRegistry registry = new ToolRegistry();

registry.register("get_weather", "查询天气")
    .param("city", "string", "城市名称", true)
    .handle(args -> args.getString("city") + "晴天");
```

两种方式可以在同一个 `ToolRegistry` 实例中混合使用。

## API 参考

| 方法 | 返回类型 | 说明 |
|------|----------|------|
| `scan(Object target)` | `ToolRegistry` | 扫描目标对象中所有 `@ToolDef` 注解方法并注册 |
| `register(String name, String description)` | `Registration` | 流式注册入口，返回 `Registration` 对象 |
| `getTools()` | `List<Tool>` | 获取所有已注册工具的定义列表（用于传给 LLM） |
| `execute(String name, String arguments)` | `String` | 按名称执行工具，`arguments` 为 JSON 格式参数字符串 |
| `hasTool(String name)` | `boolean` | 检查指定名称的工具是否已注册 |

### Registration 内部类

`Registration` 是 `ToolRegistry` 的内部类，提供链式工具注册能力：

| 方法 | 返回类型 | 说明 |
|------|----------|------|
| `param(String name, String type, String description, boolean required)` | `Registration` | 添加参数定义 |
| `handle(ToolHandler handler)` | `ToolRegistry` | 设置执行处理器，完成注册 |

## 工具调用流程

```
用户提问
    |
    v
LLM.chat(messages, tools)  -- 传入工具定义
    |
    v
ChatResult.hasToolCalls()  -- 检查是否需要调用工具
    |
    +-- 否 --> 直接使用 result.content() 作为回复
    |
    +-- 是 --> 遍历 result.toolCalls()
                  |
                  v
              ToolRegistry.execute(name, arguments)  -- 执行工具
                  |
                  v
              Message.toolResult(id, result)  -- 构建工具结果消息
                  |
                  v
              将结果加入 messages，再次调用 LLM
                  |
                  v
              循环直到 ChatResult.hasToolCalls() == false
```

## 完整示例

### 工具调用循环

以下是一个完整的工具调用示例，展示了从注册工具到处理多轮工具调用的完整流程：

```java
import com.non.chain.*;
import com.non.chain.provider.DashscopeLLM;
import com.non.chain.provider.LLM;
import com.non.chain.tool.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class ToolCallLoopExample {

    public static void main(String[] args) {
        // ===== 1. 创建 LLM =====
        LLM llm = new DashscopeLLM("qwen-plus", 512);

        // ===== 2. 注册工具 =====
        ToolRegistry registry = new ToolRegistry();

        registry.register("get_weather", "查询指定城市的天气信息")
            .param("city", "string", "城市名称，如北京市", true)
            .param("unit", "string", "温度单位，celsius 或 fahrenheit", false)
            .handle(args -> {
                String city = args.getString("city");
                String unit = args.getString("unit");
                String unitLabel = "fahrenheit".equals(unit) ? "F" : "C";
                int temp = new Random().nextInt(35);
                return city + "今天" + temp + unitLabel + "，天气多云。";
            });

        registry.register("get_time", "查询指定时区的当前时间")
            .param("timezone", "string", "时区名称，如 Asia/Shanghai", true)
            .handle(args -> args.getString("timezone") + " 当前时间: 14:30:00");

        // ===== 3. 获取工具定义 =====
        List<Tool> tools = registry.getTools();

        // ===== 4. 构建对话 =====
        String question = "北京天气怎么样？顺便帮我查一下上海的时间";
        System.out.println("用户: " + question);

        List<Message> messages = new ArrayList<>();
        messages.add(Message.user(question));

        // ===== 5. 发起首次请求 =====
        ChatResult response = llm.chat(messages, tools);
        messages.add(response.toMessage());

        // ===== 6. 工具调用循环 =====
        while (response.hasToolCalls()) {
            for (ToolCall toolCall : response.toolCalls()) {
                System.out.println("  调用工具: " + toolCall.name());
                System.out.println("  参数: " + toolCall.arguments());

                // 执行工具
                String toolResult = registry.execute(toolCall.name(), toolCall.arguments());
                System.out.println("  结果: " + toolResult);

                // 将工具结果加入对话
                messages.add(Message.toolResult(toolCall.id(), toolResult));
            }

            // 带着工具结果继续对话
            response = llm.chat(messages, tools);
            messages.add(response.toMessage());
        }

        // ===== 7. 输出最终回复 =====
        System.out.println("助手: " + response.content());
    }
}
```

### 注解方式 + 工具调用循环

```java
import com.non.chain.*;
import com.non.chain.provider.DashscopeLLM;
import com.non.chain.provider.LLM;
import com.non.chain.tool.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class AnnotationToolLoopExample {

    static class TravelService {

        @ToolDef(name = "search_flights", description = "搜索航班信息")
        public String searchFlights(
                @ToolParam(name = "from", description = "出发城市") String from,
                @ToolParam(name = "to", description = "目的城市") String to) {
            return "找到 2 个航班: " + from + " -> " + to + "，价格 800-1200 元。";
        }

        @ToolDef(name = "book_hotel", description = "预订酒店房间")
        public String bookHotel(
                @ToolParam(name = "city", description = "酒店所在城市") String city,
                @ToolParam(name = "nights", description = "入住天数") int nights) {
            return "已预订 " + city + " 酒店 " + nights + " 晚，价格 " + (nights * 300) + " 元。";
        }
    }

    public static void main(String[] args) {
        LLM llm = new DashscopeLLM("qwen-plus", 512);

        // 扫描注解注册工具
        ToolRegistry registry = new ToolRegistry().scan(new TravelService());
        List<Tool> tools = registry.getTools();

        // 检查工具是否注册
        System.out.println("search_flights 已注册: " + registry.hasTool("search_flights"));  // true
        System.out.println("unknown_tool 已注册: " + registry.hasTool("unknown_tool"));      // false

        // 对话
        List<Message> messages = new ArrayList<>();
        messages.add(Message.user("帮我查一下北京到上海的航班，再预订上海酒店2晚"));

        ChatResult response = llm.chat(messages, tools);
        messages.add(response.toMessage());

        while (response.hasToolCalls()) {
            for (ToolCall toolCall : response.toolCalls()) {
                System.out.println("调用: " + toolCall.name() + "(" + toolCall.arguments() + ")");

                String result = registry.execute(toolCall.name(), toolCall.arguments());
                System.out.println("结果: " + result);

                messages.add(Message.toolResult(toolCall.id(), result));
            }

            response = llm.chat(messages, tools);
            messages.add(response.toMessage());
        }

        System.out.println("最终回复: " + response.content());
    }
}
```

### 直接执行工具（不经过 LLM）

`ToolRegistry.execute()` 可以直接调用，无需 LLM 参与：

```java
ToolRegistry registry = new ToolRegistry();

registry.register("calculate", "数学计算")
    .param("expression", "string", "数学表达式", true)
    .handle(args -> "结果: " + args.getString("expression"));

// 直接执行工具
String result = registry.execute("calculate", "{\"expression\":\"2+3*4\"}");
System.out.println(result);  // 输出: 结果: 2+3*4
```

### 混合注册模式

```java
import com.non.chain.tool.*;

// 注解方式定义的工具
static class DatabaseService {
    @ToolDef(name = "query_user", description = "查询用户信息")
    public String queryUser(
            @ToolParam(name = "user_id", description = "用户ID") String userId) {
        return "用户: " + userId + ", 姓名: 张三, 年龄: 28";
    }
}

ToolRegistry registry = new ToolRegistry();

// 注解扫描
registry.scan(new DatabaseService());

// 流式 API 补充注册
registry.register("send_email", "发送邮件")
    .param("to", "string", "收件人邮箱", true)
    .param("subject", "string", "邮件主题", true)
    .param("body", "string", "邮件正文", true)
    .handle(args -> "邮件已发送至 " + args.getString("to"));

registry.register("get_current_time", "获取当前时间")
    .handle(args -> "当前时间: 14:30:00");

// 获取所有工具定义
List<Tool> tools = registry.getTools();
System.out.println("已注册 " + tools.size() + " 个工具");
// 输出: 已注册 3 个工具 (query_user, send_email, get_current_time)
```

## 参数类型映射

`ToolRegistry` 在注解扫描模式下，会自动将 Java 类型映射为 JSON Schema 类型：

| Java 类型 | JSON Schema 类型 |
|-----------|-----------------|
| `int` / `Integer` | `number` |
| `long` / `Long` | `number` |
| `double` / `Double` | `number` |
| `float` / `Float` | `number` |
| `boolean` / `Boolean` | `boolean` |
| `String` / 其他 | `string` |

在流式 API 模式下，通过 `param()` 方法的 `type` 参数手动指定类型。

## JSON 参数解析

`ToolRegistry.execute()` 接收的 `arguments` 是一个 JSON 字符串。框架内置了简易 JSON 解析器，支持以下类型：

- 字符串值：`"hello"` -> `String`
- 数字值：`42`, `3.14` -> `Number`
- 布尔值：`true`, `false` -> `Boolean`
- 空值：`null` -> `null`

```java
// JSON 参数示例
String json = "{\"city\":\"北京\",\"temp\":25,\"rainy\":false}";

// 解析后
// city  -> "北京"  (String)
// temp  -> 25      (Number)
// rainy -> false   (Boolean)
```

## 错误处理

- 如果执行未注册的工具，`execute()` 将抛出 `IllegalArgumentException("未注册的工具: " + name)`
- 如果工具执行过程中抛出异常，异常会被包装为 `RuntimeException("工具执行失败: " + name, cause)`

## 相关文档

- [注解方式定义工具](./annotation.md) - @ToolDef 和 @ToolParam 注解详解
- [流式 API 定义工具](./fluent-api.md) - register().param().handle() 链式 API
- [Message 消息模型](../llm/message.md) - Message 类和工具调用消息
- [DashscopeLLM](../llm/dashscope-llm.md) - LLM 的 chat() 方法与工具集成
