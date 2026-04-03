# 多模态输入

nonchain 支持多模态消息输入，允许在用户消息中同时包含文本和图片内容。通过 `ContentPart` 接口体系，可以灵活组合不同类型的内容部件，配合视觉理解模型（如 qwen-vl-plus）实现图片理解、OCR、视觉问答等能力。

## ContentPart 接口体系

`ContentPart` 是多模态消息内容部件的标记接口。目前提供两个实现类：

```
ContentPart (接口)
  ├── TextPart       -- 文本内容部件
  └── ImageUrlPart   -- 图片 URL 内容部件
```

### TextPart - 文本部件

用于携带文本内容。

```java
// 创建方式
TextPart text = TextPart.of("请描述这张图片的内容");

// 获取文本
String content = text.text();
```

### ImageUrlPart - 图片部件

用于携带图片的 URL 地址。模型将通过 URL 下载并分析图片。

```java
// 创建方式
ImageUrlPart image = ImageUrlPart.of("https://example.com/photo.jpg");

// 获取 URL
String url = image.url();
```

## 消息构建

多模态用户消息通过 `Message.user(List<ContentPart>)` 静态工厂方法创建，传入一个 `ContentPart` 列表：

```java
import com.non.chain.Message;
import com.non.chain.TextPart;
import com.non.chain.ImageUrlPart;
import java.util.Arrays;

Message userMessage = Message.user(Arrays.asList(
    ImageUrlPart.of("https://example.com/image.jpg"),
    TextPart.of("这张图片里有什么？")
));
```

与纯文本消息 `Message.user(String content)` 不同，多模态消息的 `content` 字段为 `null`，内容存储在 `contentParts` 字段中。

## 支持的模型

多模态输入需要使用支持视觉理解的模型，推荐以下 DashScope 模型：

| 模型名称 | 说明 |
|----------|------|
| `qwen-vl-plus` | 通义千问视觉理解 Plus 版本 |
| `qwen-vl-max` | 通义千问视觉理解 Max 版本 |

## 完整示例

### 图片理解

```java
import com.non.chain.*;
import com.non.chain.provider.DashscopeLLM;
import com.non.chain.provider.LLM;
import java.util.Arrays;

public class ImageUnderstandingExample {
    public static void main(String[] args) {
        // 使用视觉理解模型
        LLM llm = new DashscopeLLM("qwen-vl-plus");

        // 构建多模态消息：图片 + 文本提问
        Message userMessage = Message.user(Arrays.asList(
            ImageUrlPart.of("https://dashscope.oss-cn-beijing.aliyuncs.com/images/dog_and_girl.jpeg"),
            TextPart.of("女孩的衬衫是什么颜色的？")
        ));

        // 发送消息
        ChatResult result = llm.chat(Arrays.asList(userMessage));

        // 输出结果
        System.out.println(result.content());
    }
}
```

### 多图片理解

```java
import com.non.chain.*;
import com.non.chain.provider.DashscopeLLM;
import com.non.chain.provider.LLM;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

public class MultiImageExample {
    public static void main(String[] args) {
        LLM llm = new DashscopeLLM("qwen-vl-plus");

        // 同时传入多张图片
        List<ContentPart> contentParts = new ArrayList<>();
        contentParts.add(ImageUrlPart.of("https://example.com/image1.jpg"));
        contentParts.add(ImageUrlPart.of("https://example.com/image2.jpg"));
        contentParts.add(TextPart.of("比较这两张图片的异同点"));

        Message userMessage = Message.user(contentParts);

        ChatResult result = llm.chat(Arrays.asList(userMessage));
        System.out.println(result.content());
    }
}
```

### 多轮对话中的多模态

```java
import com.non.chain.*;
import com.non.chain.provider.DashscopeLLM;
import com.non.chain.provider.LLM;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

public class MultimodalMultiTurnExample {
    public static void main(String[] args) {
        LLM llm = new DashscopeLLM("qwen-vl-plus");

        List<Message> messages = new ArrayList<>();

        // 第一轮：发送图片并提问
        messages.add(Message.user(Arrays.asList(
            ImageUrlPart.of("https://example.com/room.jpg"),
            TextPart.of("描述一下这个房间")
        )));
        ChatResult result1 = llm.chat(messages);
        messages.add(result1.toMessage());
        System.out.println("第一轮回复: " + result1.content());

        // 第二轮：继续追问（纯文本）
        messages.add(Message.user("房间里有什么颜色的家具？"));
        ChatResult result2 = llm.chat(messages);
        messages.add(result2.toMessage());
        System.out.println("第二轮回复: " + result2.content());
    }
}
```

### 带系统提示的多模态

```java
import com.non.chain.*;
import com.non.chain.provider.DashscopeLLM;
import com.non.chain.provider.LLM;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

public class MultimodalWithSystemExample {
    public static void main(String[] args) {
        LLM llm = new DashscopeLLM("qwen-vl-plus");

        List<Message> messages = new ArrayList<>();

        // 添加系统提示
        messages.add(Message.system("你是一个专业的图片分析助手，请用中文回答。"));

        // 多模态用户消息
        messages.add(Message.user(Arrays.asList(
            ImageUrlPart.of("https://example.com/chart.png"),
            TextPart.of("请总结这个图表的关键数据")
        )));

        ChatResult result = llm.chat(messages);
        System.out.println(result.content());
    }
}
```

## 图片 URL 要求

- 图片必须通过公网 URL 访问，模型将自动下载
- 支持常见图片格式：JPG、PNG、GIF、WEBP 等
- 建议图片大小不超过 4MB
- URL 必须以 `http://` 或 `https://` 开头

## 注意事项

1. **模型选择**：多模态输入必须使用视觉理解模型（如 `qwen-vl-plus`），普通文本模型无法处理图片内容
2. **ContentPart 顺序**：图片部件和文本部件可以任意顺序排列
3. **混合消息**：在多轮对话中，可以混合使用多模态消息和纯文本消息
4. **工具调用兼容**：多模态消息可以与工具调用结合使用，但需确保模型同时支持视觉理解和 function call

## 相关文档

- [Message 消息模型](./message.md) - Message 类和 ContentPart 接口详解
- [DashscopeLLM](./dashscope-llm.md) - LLM 实现与配置
