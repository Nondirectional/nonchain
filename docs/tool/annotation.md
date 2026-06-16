# 注解方式定义工具

nonchain 提供了基于注解的工具定义方式，通过 `@ToolDef` 和 `@ToolParam` 注解将普通 Java 方法声明为 LLM 可调用的工具。这种方式代码简洁、与业务逻辑紧密耦合，适合快速定义少量工具。

## 核心注解

### @ToolDef - 工具定义注解

标注在方法上，声明该方法为一个 LLM 可调用的工具。

| 属性 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `name` | `String` | 是 | 工具名称，LLM 通过此名称调用工具 |
| `description` | `String` | 是 | 工具描述，帮助 LLM 理解工具的用途 |

```java
@ToolDef(name = "get_weather", description = "查询指定城市的天气信息")
public String getWeather(...) { ... }
```

### @ToolParam - 参数定义注解

标注在方法参数上，声明参数的元信息。

| 属性 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `name` | `String` | 是 | - | 参数名称 |
| `description` | `String` | 否 | `""` | 参数描述 |
| `required` | `boolean` | 否 | `true` | 是否必填 |

> **为什么需要 `name` 属性**：Java 编译时默认不保留参数名（除非使用 `-parameters` 编译选项），因此必须通过 `@ToolParam(name = "...")` 显式指定参数名称，以确保 LLM 生成的 JSON 参数能正确映射到方法参数。

```java
public String getWeather(
    @ToolParam(name = "city", description = "城市名称，如北京市") String city,
    @ToolParam(name = "unit", description = "温度单位", required = false) String unit
) { ... }
```

## ToolCall 类

`ToolCall` 表示 LLM 返回的一个工具调用请求。

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | `String` | 工具调用的唯一标识符，用于关联工具结果 |
| `name` | `String` | 工具名称 |
| `arguments` | `String` | JSON 格式的参数字符串 |

```java
// LLM 返回的工具调用
ToolCall toolCall = new ToolCall(
    "call_abc123",           // id
    "get_weather",           // name
    "{\"city\":\"北京\"}"    // arguments (JSON 字符串)
);

// 访问字段
String id = toolCall.id();           // "call_abc123"
String name = toolCall.name();       // "get_weather"
String args = toolCall.arguments();  // "{\"city\":\"北京\"}"
```

## 完整示例

### 基本用法

以下示例展示了使用注解方式定义天气查询工具的完整流程：

```java
import com.non.chain.*;
import com.non.chain.provider.DashscopeLLM;
import com.non.chain.provider.LLM;
import com.non.chain.tool.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class AnnotationToolExample {

    // 1. 定义工具服务类
    static class WeatherService {

        private final Random random = new Random();

        @ToolDef(name = "get_current_weather", description = "当你想查询指定城市的天气时非常有用。")
        public String getCurrentWeather(
                @ToolParam(name = "location", description = "城市或县区，比如北京市、杭州市、余杭区等。") String location) {
            String[] conditions = {"晴天", "多云", "雨天"};
            return location + "今天是" + conditions[random.nextInt(conditions.length)] + "。";
        }
    }

    public static void main(String[] args) {
        // 2. 创建 LLM
        LLM llm = new DashscopeLLM("qwen-plus", 512);

        // 3. 扫描注解，注册工具
        ToolRegistry registry = new ToolRegistry().scan(new WeatherService());
        List<Tool> tools = registry.getTools();

        // 4. 发起对话
        String userQuestion = "北京天气咋样";
        List<Message> messages = new ArrayList<>();
        messages.add(Message.user(userQuestion));

        ChatResult response = llm.chat(messages, tools);

        // 5. 如果模型不需要调用工具，直接返回
        if (!response.hasToolCalls()) {
            System.out.println("直接回复: " + response.content());
            return;
        }

        messages.add(response.toMessage());

        // 6. 工具调用循环
        while (response.hasToolCalls()) {
            for (ToolCall toolCall : response.toolCalls()) {
                System.out.println("调用工具: " + toolCall.name() + ", 参数: " + toolCall.arguments());

                // 执行工具
                String toolResult = registry.execute(toolCall.name(), toolCall.arguments());
                System.out.println("工具结果: " + toolResult);

                // 将工具结果添加到对话中
                messages.add(Message.toolResult(toolCall.id(), toolResult));
            }

            // 继续对话
            response = llm.chat(messages, tools);
            messages.add(response.toMessage());
        }

        System.out.println("最终回复: " + response.content());
    }
}
```

### 多参数工具

```java
import com.non.chain.tool.ToolDef;
import com.non.chain.tool.ToolParam;

static class WeatherService {

    @ToolDef(name = "get_weather", description = "查询指定城市的天气信息")
    public String getWeather(
            @ToolParam(name = "city", description = "城市名称，如北京市") String city,
            @ToolParam(name = "unit", description = "温度单位，celsius 或 fahrenheit", required = false) String unit
    ) {
        // unit 是可选参数，可能为 null
        String unitLabel = "celsius".equals(unit) ? "C" : "F";
        return city + "今天25" + unitLabel + "，天气晴朗。";
    }
}
```

### 多工具服务类

一个服务类中可以定义多个工具方法：

```java
static class TravelService {

    @ToolDef(name = "search_flights", description = "搜索航班信息")
    public String searchFlights(
            @ToolParam(name = "from", description = "出发城市") String from,
            @ToolParam(name = "to", description = "目的城市") String to,
            @ToolParam(name = "date", description = "出发日期，如 2024-01-15") String date
    ) {
        return "找到 3 个航班: " + from + " -> " + to + " (" + date + ")";
    }

    @ToolDef(name = "book_hotel", description = "预订酒店")
    public String bookHotel(
            @ToolParam(name = "city", description = "酒店所在城市") String city,
            @ToolParam(name = "checkin", description = "入住日期") String checkin,
            @ToolParam(name = "checkout", description = "退房日期") String checkout
    ) {
        return "已预订 " + city + " 酒店: " + checkin + " 至 " + checkout;
    }
}

// 注册所有工具
ToolRegistry registry = new ToolRegistry().scan(new TravelService());
List<Tool> tools = registry.getTools();
// tools 包含 search_flights 和 book_hotel 两个工具
```

### 数组与对象参数

工具方法支持数组（`List` / `Set` / Java 数组）和对象（`Map`）参数。框架会从方法签名的泛型自动推断数组元素类型，生成对应的 JSON Schema `items`，无需额外注解：

```java
static class DataTool {

    @ToolDef(name = "sum_points", description = "对一组数值求和")
    public String sumPoints(
            @ToolParam(name = "points", description = "数值列表") List<Integer> points) {
        int sum = points.stream().mapToInt(Integer::intValue).sum();
        return "总和: " + sum;
    }

    @ToolDef(name = "lookup", description = "按对象查找")
    public String lookup(
            @ToolParam(name = "data", description = "查询对象") Map<String, Object> data) {
        return "收到对象: " + data;
    }
}
```

`sumPoints` 生成的参数 Schema 为：

```json
{"type": "array", "items": {"type": "number"}}
```

LLM 据此返回数组 `{"points": [12, 34]}`，框架解析为 `List<Integer>` 后直接传入方法，无需手动转换。raw type `List`（无泛型）会兜底推断元素为 `string`。

## 扫描机制

`ToolRegistry.scan(Object target)` 的工作流程：

1. 遍历目标对象的所有声明方法（`getDeclaredMethods()`）
2. 检查每个方法是否标注了 `@ToolDef` 注解
3. 如果有，则通过反射读取方法参数上的 `@ToolParam` 注解
4. 自动将 Java 类型映射为 JSON Schema 类型：
   - `int` / `Integer` / `long` / `Long` / `double` / `Double` / `float` / `Float` -> `number`
   - `boolean` / `Boolean` -> `boolean`
   - `List` / `Set` / Java 数组（如 `List<Integer>`、`String[]`） -> `array`（自动从泛型推断 `items` 元素类型，如 `{"type":"array","items":{"type":"number"}}`）
   - `Map` -> `object`
   - 其他类型 -> `string`
5. 将工具注册到 `ToolRegistry` 中

> **注意**：`scan()` 使用 `setAccessible(true)` 访问私有方法，因此 `@ToolDef` 可以标注在任意访问级别的方法上。

## 最佳实践

### 1. 工具名称使用蛇形命名

```java
// 推荐
@ToolDef(name = "get_current_weather", description = "...")

// 不推荐
@ToolDef(name = "getCurrentWeather", description = "...")
```

LLM 对蛇形命名（snake_case）的工具名称理解更好。

### 2. 描述要清晰具体

```java
// 推荐：描述清楚工具的用途和触发条件
@ToolDef(name = "get_weather", description = "当你想查询指定城市的天气时非常有用。")

// 不推荐：描述过于简单
@ToolDef(name = "get_weather", description = "天气查询")
```

### 3. 参数描述包含示例

```java
// 推荐：描述中包含示例值
@ToolParam(name = "location", description = "城市或县区，比如北京市、杭州市、余杭区等。")

// 不推荐：描述过于简短
@ToolParam(name = "location", description = "城市")
```

### 4. 合理设置必填参数

```java
public String search(
    @ToolParam(name = "keyword", description = "搜索关键词") String keyword,        // 必填
    @ToolParam(name = "limit", description = "返回数量", required = false) int limit  // 可选
) { ... }
```

### 5. 方法返回 String 类型

工具方法的返回值会被转换为字符串发送给 LLM，因此建议直接返回 `String` 类型。如果返回其他类型，会调用 `toString()` 转换。

## 相关文档

- [流式 API 定义工具](./fluent-api.md) - 另一种工具定义方式
- [ToolRegistry 工具注册中心](./tool-registry.md) - 工具注册与执行管理
- [Message 消息模型](../llm/message.md) - 消息类型与工具调用消息
