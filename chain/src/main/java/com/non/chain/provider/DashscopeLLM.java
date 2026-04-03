package com.non.chain.provider;

import com.non.chain.*;
import com.non.chain.tool.Tool;
import com.non.chain.tool.ToolCall;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.models.ResponseFormatJsonObject;
import com.openai.models.chat.completions.*;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.stream.Collectors;

public class DashscopeLLM implements LLM {

    private static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final String API_KEY_ENV = "DASHSCOPE_API_KEY";
    private static final String RESPONSE_FORMAT_JSON_OBJECT = "json_object";

    private final OpenAIClient client;
    private final String model;
    private final Integer maxCompletionTokens;
    private boolean enableThinking;
    private Integer thinkingBudget;
    private OutputFormat defaultOutputFormat = OutputFormat.TEXT;

    public DashscopeLLM(String model) {
        this(null, model,null);
    }

    public DashscopeLLM(String model, Integer maxCompletionTokens) {
        this(null, model, maxCompletionTokens);
    }

    public DashscopeLLM(String apiKey, String model, Integer maxCompletionTokens) {
        this.client = OpenAIOkHttpClient.builder()
                .apiKey(resolveApiKey(apiKey))
                .baseUrl(DEFAULT_BASE_URL)
                .build();
        this.model = model;
        this.maxCompletionTokens = maxCompletionTokens;
    }

    private static String resolveApiKey(String explicitApiKey) {
        if (explicitApiKey != null && !explicitApiKey.isBlank()) {
            return explicitApiKey;
        }
        String envApiKey = System.getenv(API_KEY_ENV);
        if (envApiKey != null && !envApiKey.isBlank()) {
            return envApiKey;
        }
        throw new IllegalArgumentException(
                "Dashscope API Key 不能为空：请显式传入，或设置环境变量 " + API_KEY_ENV
        );
    }

    public DashscopeLLM enableThinking(boolean enable) {
        this.enableThinking = enable;
        return this;
    }

    public DashscopeLLM thinkingBudget(Integer budget) {
        this.thinkingBudget = budget;
        return this;
    }

    public DashscopeLLM enableJsonObjectMode(boolean enable) {
        this.defaultOutputFormat = enable ? OutputFormat.JSON_OBJECT : OutputFormat.TEXT;
        return this;
    }

    @Override
    public ChatResult chat(String systemMessage, String userMessage, OutputFormat outputFormat) {
        return chat(systemMessage, userMessage, null, outputFormat);
    }

    @Override
    public ChatResult chat(List<Message> messages, OutputFormat outputFormat) {
        return chat(messages, null, outputFormat);
    }

    @Override
    public ChatResult chat(String systemMessage, String userMessage, List<Tool> tools, OutputFormat outputFormat) {
        ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
                .model(model)
                .addUserMessage(userMessage);
        if(maxCompletionTokens != null) {
            builder.maxCompletionTokens(maxCompletionTokens);
        }
        if (systemMessage != null && !systemMessage.isBlank()) {
            builder.addSystemMessage(systemMessage);
        }

        OutputFormat resolvedOutputFormat = resolveOutputFormat(outputFormat);
        validateResponseFormatAndTools(tools, resolvedOutputFormat);
        addTools(builder, tools);
        return doChat(builder, resolvedOutputFormat);
    }

    @Override
    public ChatResult chat(List<Message> messages, List<Tool> tools, OutputFormat outputFormat) {
        ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
                .model(model);
        if(maxCompletionTokens != null) {
            builder.maxCompletionTokens(maxCompletionTokens);
        }

        for (Message msg : messages) {
            switch (msg.role()) {
                case "system":
                    builder.addSystemMessage(msg.content());
                    break;
                case "user":
                    if (msg.contentParts() != null && !msg.contentParts().isEmpty()) {
                        builder.addUserMessageOfArrayOfContentParts(
                                msg.contentParts().stream()
                                        .map(this::toSdkContentPart)
                                        .collect(Collectors.toList())
                        );
                    } else {
                        builder.addUserMessage(msg.content());
                    }
                    break;
                case "assistant":
                    if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                        ChatCompletionAssistantMessageParam.Builder assistantBuilder =
                                ChatCompletionAssistantMessageParam.builder()
                                        .content(msg.content() != null ? msg.content() : "");
                        for (ToolCall tc : msg.toolCalls()) {
                            assistantBuilder.addToolCall(
                                    ChatCompletionMessageFunctionToolCall.builder()
                                            .id(tc.id())
                                            .function(ChatCompletionMessageFunctionToolCall.Function.builder()
                                                    .name(tc.name())
                                                    .arguments(tc.arguments())
                                                    .build())
                                            .build()
                            );
                        }
                        builder.addMessage(assistantBuilder.build());
                    } else {
                        builder.addAssistantMessage(msg.content());
                    }
                    break;
                case "tool":
                    builder.addMessage(
                            ChatCompletionToolMessageParam.builder()
                                    .toolCallId(msg.toolCallId())
                                    .content(msg.content())
                                    .build()
                    );
                    break;
                default:
                    throw new IllegalArgumentException("不支持的消息角色: " + msg.role());
            }
        }

        OutputFormat resolvedOutputFormat = resolveOutputFormat(outputFormat);
        validateResponseFormatAndTools(tools, resolvedOutputFormat);
        addTools(builder, tools);
        return doChat(builder, resolvedOutputFormat);
    }

    private ChatCompletionContentPart toSdkContentPart(ContentPart part) {
        if (part instanceof TextPart) {
            return ChatCompletionContentPart.ofText(
                    ChatCompletionContentPartText.builder()
                            .text(((TextPart) part).text())
                            .type(JsonValue.from("text"))
                            .build()
            );
        } else if (part instanceof ImageUrlPart) {
            return ChatCompletionContentPart.ofImageUrl(
                    ChatCompletionContentPartImage.builder()
                            .imageUrl(ChatCompletionContentPartImage.ImageUrl.builder()
                                    .url(((ImageUrlPart) part).url())
                                    .build())
                            .type(JsonValue.from("image_url"))
                            .build()
            );
        }
        throw new IllegalArgumentException("不支持的内容类型: " + part.getClass().getSimpleName());
    }

    private OutputFormat resolveOutputFormat(OutputFormat outputFormat) {
        return outputFormat != null ? outputFormat : defaultOutputFormat;
    }

    private void validateResponseFormatAndTools(List<Tool> tools, OutputFormat outputFormat) {
        if (outputFormat == OutputFormat.JSON_OBJECT && tools != null && !tools.isEmpty()) {
            throw new IllegalArgumentException("启用 json_object 结构化输出时，不支持同时传入 tools");
        }
    }

    private void addTools(ChatCompletionCreateParams.Builder builder, List<Tool> tools) {
        if (tools != null && !tools.isEmpty()) {
            for (Tool tool : tools) {
                builder.addFunctionTool(tool.toFunctionDefinition());
            }
        }
    }

    private ChatResult doChat(ChatCompletionCreateParams.Builder builder, OutputFormat outputFormat) {
        if (enableThinking) {
            builder.putAdditionalBodyProperty("enable_thinking", JsonValue.from(true));
            if (thinkingBudget != null) {
                builder.putAdditionalBodyProperty("thinking_budget", JsonValue.from(thinkingBudget));
            }
        }
        if (outputFormat == OutputFormat.JSON_OBJECT) {
            builder.responseFormat(
                    ResponseFormatJsonObject.builder()
                            .type(JsonValue.from(RESPONSE_FORMAT_JSON_OBJECT))
                            .build()
            );
        }

        ChatCompletion completion = client.chat().completions().create(builder.build());

        return completion.choices().stream()
                .findFirst()
                .map(choice -> {
                    String content = choice.message().content().orElse("无响应");
                    JsonValue thinkingValue = choice.message()._additionalProperties().get("reasoning_content");
                    String thinking = thinkingValue != null
                            ? thinkingValue.accept(new JsonValue.Visitor<>() {
                        @Override
                        public String visitString(@NotNull String value) {
                            return value;
                        }

                        @Override
                        public String visitNull() {
                            return null;
                        }

                        @Override
                        public String visitDefault() {
                            return null;
                        }
                    })
                            : null;

                    List<ToolCall> toolCalls = choice.message().toolCalls()
                            .map(calls -> calls.stream()
                                    .filter(ChatCompletionMessageToolCall::isFunction)
                                    .map(tc -> {
                                        ChatCompletionMessageFunctionToolCall ftc = tc.asFunction();
                                        return new ToolCall(
                                                ftc.id(),
                                                ftc.function().name(),
                                                ftc.function().arguments()
                                        );
                                    })
                                    .collect(Collectors.toList()))
                            .orElse(List.of());

                    return new ChatResult(content, thinking, toolCalls);
                })
                .orElse(new ChatResult("无响应", null));
    }
}
