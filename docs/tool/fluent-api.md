# 流式 API 定义工具

nonchain 提供了流式 API（Fluent API）方式来定义工具，通过 `ToolRegistry.register()` 返回的 `Registration` 对象进行链式调用。相比注解方式，流式 API 将工具的 Schema 定义和执行逻辑集中在一处，更适合动态注册和快速原型开发。

## 流式 API vs 注解方式

| 特性 | 流式 API | 注解方式 |
|------|----------|----------|
| 定义方式 | 链式调用 `register().param().handle()` | `@ToolDef` + `@ToolParam` 注解 |
| Schema 与逻辑 | 集中在一处 | Schema 在注解中，逻辑在方法体中 |
| 动态注册 | 支持，运行时动态添加 | 不支持，编译时确定 |
| 适用场景 | 快速原型、动态工具、Lambda 表达式 | 稳定的业务工具、大型项目 |
| 类型安全 | 通过 `ToolArgs` 访问器 | 通过方法参数自动映射 |
| 多工具组织 | 每个工具独立注册 | 同一类中的方法自动扫描 |

## Tool.builder() 模式

`Tool` 类本身也提供了 Builder 模式，用于手动构建工具定义（不含执行逻辑）：

```java
import com.non.chain.tool.Tool;

Tool tool = Tool.builder("tool_name")
    .description("工具描述")
    .addProperty("param1", "string", "参数描述", true)
    .addProperty("param2", "number", "数字参数", false)
    .build();
```

`Tool` 主要用于向 LLM 传递工具的 Schema 定义（通过 `ToolRegistry.getTools()` 获取）。

## ToolRegistry.register() 链式调用

`ToolRegistry.register()` 返回一个 `Registration` 对象，支持链式调用：

```java
ToolRegistry registry = new ToolRegistry();

registry.register("tool_name", "工具描述")
    .param("param1", "string", "参数1描述", true)   // 添加必填参数
    .param("param2", "number", "参数2描述", false)   // 添加可选参数
    .handle(args -> {
        // 工具执行逻辑
        String value = args.getString("param1");
        return "处理结果: " + value;
    });
```

### Registration API

| 方法 | 返回类型 | 说明 |
|------|----------|------|
| `param(String name, String type, String description, boolean required)` | `Registration` | 添加参数定义，返回 this 支持链式调用 |
| `handle(ToolHandler handler)` | `ToolRegistry` | 设置执行处理器，完成注册并返回 ToolRegistry |

### param() 参数

| 参数 | 类型 | 说明 |
|------|------|------|
| `name` | `String` | 参数名称 |
| `type` | `String` | JSON Schema 类型：`"string"`、`"number"`、`"boolean"`、`"array"`、`"object"` |
| `description` | `String` | 参数描述 |
| `required` | `boolean` | 是否必填 |

## ToolHandler 函数式接口

`ToolHandler` 是工具执行的函数式接口，接收 `ToolArgs` 参数，返回 `String` 类型的执行结果：

```java
@FunctionalInterface
public interface ToolHandler {
    String execute(ToolArgs args);
}
```

由于是函数式接口，可以使用 Lambda 表达式或方法引用：

```java
// Lambda 表达式
.handle(args -> {
    String city = args.getString("city");
    return city + "今天晴天";
})

// 方法引用（需要签名匹配）
.handle(MyService::processWeather)
```

## ToolArgs 类型安全访问器

`ToolArgs` 封装了工具调用的参数 Map，提供了类型安全的访问方法：

| 方法 | 返回类型 | 说明 |
|------|----------|------|
| `getString(String name)` | `String` | 获取字符串参数，不存在时返回 `null` |
| `getInt(String name)` | `int` | 获取整数参数，不存在时返回 `0` |
| `getLong(String name)` | `long` | 获取长整数参数，不存在时返回 `0L` |
| `getDouble(String name)` | `double` | 获取浮点数参数，不存在时返回 `0.0` |
| `getBoolean(String name)` | `boolean` | 获取布尔参数，不存在时返回 `false` |
| `has(String name)` | `boolean` | 检查参数是否存在且不为 null |
| `get(String name)` | `<T> T` | 获取原始值（泛型，需自行转型） |

### 类型转换规则

- `getString()`：直接调用 `toString()`
- `getInt()`：如果是 `Number` 类型直接取值，否则通过 `Integer.parseInt()` 转换
- `getLong()`：如果是 `Number` 类型直接取值，否则通过 `Long.parseLong()` 转换
- `getDouble()`：如果是 `Number` 类型直接取值，否则通过 `Double.parseDouble()` 转换
- `getBoolean()`：如果是 `Boolean` 类型直接取值，否则通过 `Boolean.parseBoolean()` 转换

## 完整示例

### 基本用法

```java
import com.non.chain.*;
import com.non.chain.provider.DashscopeLLM;
import com.non.chain.provider.LLM;
import com.non.chain.tool.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class FluentToolExample {

    public static void main(String[] args) {
        // 1. 创建 LLM
        LLM llm = new DashscopeLLM("qwen-plus", 512);

        // 2. 使用 Fluent API 定义工具
        ToolRegistry registry = new ToolRegistry();
        registry.register("get_current_weather", "当你想查询指定城市的天气时非常有用。")
                .param("location", "string", "城市或县区，比如北京市、杭州市、余杭区等。", true)
                .handle(args -> {
                    String location = args.getString("location");
                    String[] conditions = {"晴天", "多云", "雨天"};
                    return location + "今天是" + conditions[new Random().nextInt(conditions.length)] + "。";
                });

        List<Tool> tools = registry.getTools();

        // 3. 发起对话
        String userQuestion = "北京天气咋样";
        List<Message> messages = new ArrayList<>();
        messages.add(Message.user(userQuestion));

        ChatResult response = llm.chat(messages, tools);

        if (!response.hasToolCalls()) {
            System.out.println("直接回复: " + response.content());
            return;
        }

        messages.add(response.toMessage());

        // 4. 工具调用循环
        while (response.hasToolCalls()) {
            for (ToolCall toolCall : response.toolCalls()) {
                System.out.println("调用工具: " + toolCall.name() + ", 参数: " + toolCall.arguments());

                String toolResult = registry.execute(toolCall.name(), toolCall.arguments());
                System.out.println("工具结果: " + toolResult);

                messages.add(Message.toolResult(toolCall.id(), toolResult));
            }

            response = llm.chat(messages, tools);
            messages.add(response.toMessage());
        }

        System.out.println("最终回复: " + response.content());
    }
}
```

### 多参数工具

```java
ToolRegistry registry = new ToolRegistry();

registry.register("get_weather", "查询指定城市的天气信息")
    .param("city", "string", "城市名称，如北京市", true)
    .param("unit", "string", "温度单位，celsius 或 fahrenheit", false)
    .handle(args -> {
        String city = args.getString("city");
        String unit = args.getString("unit");  // 可选参数，可能为 null

        // 使用 has() 检查参数是否存在
        String unitLabel = args.has("unit") && "fahrenheit".equals(unit) ? "F" : "C";
        int temp = new Random().nextInt(35);
        return city + "今天" + temp + unitLabel + "，天气多云。";
    });
```

### 注册多个工具

```java
ToolRegistry registry = new ToolRegistry();

// 工具1：天气查询
registry.register("get_weather", "查询天气")
    .param("city", "string", "城市名称", true)
    .handle(args -> args.getString("city") + "今天晴天，25C。");

// 工具2：时间查询
registry.register("get_time", "查询当前时间")
    .param("timezone", "string", "时区", false)
    .handle(args -> "现在是 2024-01-15 14:30:00。");

// 工具3：计算器
registry.register("calculate", "数学计算")
    .param("expression", "string", "数学表达式，如 2+3*4", true)
    .handle(args -> {
        String expr = args.getString("expression");
        // 简化示例，实际应使用安全的表达式解析
        return "计算结果: " + eval(expr);
    });

// 获取所有工具定义
List<Tool> tools = registry.getTools();
// tools.size() == 3
```

### 使用不同类型参数

```java
registry.register("search_products", "搜索商品")
    .param("keyword", "string", "搜索关键词", true)
    .param("min_price", "number", "最低价格", false)
    .param("max_price", "number", "最高价格", false)
    .param("in_stock", "boolean", "是否只看有货", false)
    .handle(args -> {
        String keyword = args.getString("keyword");

        // 类型安全访问
        if (args.has("min_price")) {
            double minPrice = args.getDouble("min_price");
            // ...
        }
        if (args.has("in_stock")) {
            boolean inStock = args.getBoolean("in_stock");
            // ...
        }

        return "找到 5 个相关商品: " + keyword;
    });
```

### 混合使用注解和流式 API

两种方式可以在同一个 `ToolRegistry` 中混合使用：

```java
// 注解方式
static class WeatherService {
    @ToolDef(name = "get_weather", description = "查询天气")
    public String getWeather(
        @ToolParam(name = "city", description = "城市名称") String city) {
        return city + "晴天";
    }
}

// 创建注册中心
ToolRegistry registry = new ToolRegistry();

// 注解扫描
registry.scan(new WeatherService());

// 流式 API 注册额外工具
registry.register("get_time", "查询时间")
    .param("timezone", "string", "时区", false)
    .handle(args -> "现在是 14:30");

// 两种方式注册的工具都会被包含
List<Tool> tools = registry.getTools();
// tools 包含 get_weather 和 get_time
```

## 相关文档

- [注解方式定义工具](./annotation.md) - 基于注解的工具定义方式
- [ToolRegistry 工具注册中心](./tool-registry.md) - 工具注册与执行管理
- [Message 消息模型](../llm/message.md) - 消息类型与工具调用消息
