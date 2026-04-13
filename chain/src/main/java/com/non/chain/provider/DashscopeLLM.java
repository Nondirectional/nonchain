package com.non.chain.provider;

import com.non.chain.*;
import com.non.chain.tool.Tool;
import com.non.chain.tool.ToolCall;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.models.ResponseFormatJsonObject;
import com.openai.models.chat.completions.*;
import com.openai.core.http.StreamResponse;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
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
    private Double temperature;
    private Double topP;
    private Integer topK;
    private OutputFormat defaultOutputFormat = OutputFormat.TEXT;

    public DashscopeLLM(String model) {
        this(null, model, null);
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

    public DashscopeLLM temperature(Double temperature) {
        this.temperature = temperature;
        return this;
    }

    public DashscopeLLM topP(Double topP) {
        this.topP = topP;
        return this;
    }

    public DashscopeLLM topK(Integer topK) {
        this.topK = topK;
        return this;
    }

    // ---- 同步调用 ----

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
        ChatCompletionCreateParams.Builder builder = buildSimpleParams(systemMessage, userMessage);
        OutputFormat resolvedOutputFormat = resolveOutputFormat(outputFormat);
        validateResponseFormatAndTools(tools, resolvedOutputFormat);
        addTools(builder, tools);
        return doChat(builder, resolvedOutputFormat);
    }

    @Override
    public ChatResult chat(List<Message> messages, List<Tool> tools, OutputFormat outputFormat) {
        ChatCompletionCreateParams.Builder builder = buildMessageListParams(messages);
        OutputFormat resolvedOutputFormat = resolveOutputFormat(outputFormat);
        validateResponseFormatAndTools(tools, resolvedOutputFormat);
        addTools(builder, tools);
        return doChat(builder, resolvedOutputFormat);
    }

    // ---- 流式调用 ----

    @Override
    public ChatResult streamChat(String systemMessage, String userMessage, OutputFormat outputFormat, Consumer<ChatChunk> callback) {
        return streamChat(systemMessage, userMessage, null, outputFormat, callback);
    }

    @Override
    public ChatResult streamChat(List<Message> messages, OutputFormat outputFormat, Consumer<ChatChunk> callback) {
        return streamChat(messages, null, outputFormat, callback);
    }

    @Override
    public ChatResult streamChat(String systemMessage, String userMessage, List<Tool> tools, OutputFormat outputFormat, Consumer<ChatChunk> callback) {
        ChatCompletionCreateParams.Builder builder = buildSimpleParams(systemMessage, userMessage);
        OutputFormat resolvedOutputFormat = resolveOutputFormat(outputFormat);
        validateResponseFormatAndTools(tools, resolvedOutputFormat);
        addTools(builder, tools);
        return doStreamChat(builder, resolvedOutputFormat, callback);
    }

    @Override
    public ChatResult streamChat(List<Message> messages, List<Tool> tools, OutputFormat outputFormat, Consumer<ChatChunk> callback) {
        ChatCompletionCreateParams.Builder builder = buildMessageListParams(messages);
        OutputFormat resolvedOutputFormat = resolveOutputFormat(outputFormat);
        validateResponseFormatAndTools(tools, resolvedOutputFormat);
        addTools(builder, tools);
        return doStreamChat(builder, resolvedOutputFormat, callback);
    }

    // ---- 内部方法 ----

    private ChatCompletionCreateParams.Builder buildSimpleParams(String systemMessage, String userMessage) {
        ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
                .model(model)
                .addUserMessage(userMessage);
        if (maxCompletionTokens != null) {
            builder.maxCompletionTokens(maxCompletionTokens);
        }
        if (systemMessage != null && !systemMessage.isBlank()) {
            builder.addSystemMessage(systemMessage);
        }
        return builder;
    }

    private ChatCompletionCreateParams.Builder buildMessageListParams(List<Message> messages) {
        ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
                .model(model);
        if (maxCompletionTokens != null) {
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
        return builder;
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

    private void applyAdditionalParams(ChatCompletionCreateParams.Builder builder, OutputFormat outputFormat) {
        if (enableThinking) {
            builder.putAdditionalBodyProperty("enable_thinking", JsonValue.from(true));
            if (thinkingBudget != null) {
                builder.putAdditionalBodyProperty("thinking_budget", JsonValue.from(thinkingBudget));
            }
        }
        if (temperature != null) {
            builder.temperature(temperature);
        }
        if (topP != null) {
            builder.topP(topP);
        }
        if (topK != null) {
            builder.putAdditionalBodyProperty("top_k", JsonValue.from(topK));
        }
        if (outputFormat == OutputFormat.JSON_OBJECT) {
            builder.responseFormat(
                    ResponseFormatJsonObject.builder()
                            .type(JsonValue.from(RESPONSE_FORMAT_JSON_OBJECT))
                            .build()
            );
        }
    }

    private ChatResult doChat(ChatCompletionCreateParams.Builder builder, OutputFormat outputFormat) {
        applyAdditionalParams(builder, outputFormat);

        ChatCompletion completion = client.chat().completions().create(builder.build());

        return completion.choices().stream()
                .findFirst()
                .map(choice -> {
                    String content = choice.message().content().orElse("无响应");
                    String thinking = extractThinking(choice.message());
                    List<ToolCall> toolCalls = extractToolCalls(choice.message());
                    return new ChatResult(content, thinking, toolCalls);
                })
                .orElse(new ChatResult("无响应", null));
    }

    private ChatResult doStreamChat(ChatCompletionCreateParams.Builder builder, OutputFormat outputFormat, Consumer<ChatChunk> callback) {
        applyAdditionalParams(builder, outputFormat);

        StringBuilder contentBuilder = new StringBuilder();
        StringBuilder thinkingBuilder = new StringBuilder();
        Map<Integer, ToolCallAccumulator> toolCallAccumulators = new TreeMap<>();

        try (StreamResponse<ChatCompletionChunk> streamResponse =
                     client.chat().completions().createStreaming(builder.build())) {
            streamResponse.stream().forEach(chunk -> {
                chunk.choices().stream().findFirst().ifPresent(choice -> {
                    ChatCompletionChunk.Choice.Delta delta = choice.delta();
                    String deltaContent = delta.content().orElse(null);
                    String deltaThinking = extractDeltaThinking(delta);
                    List<ChatChunk.DeltaToolCall> deltaToolCalls = extractDeltaToolCalls(delta);

                    String finishReason = choice.finishReason()
                            .map(fr -> fr.asString())
                            .orElse(null);

                    // 累积内容
                    if (deltaContent != null) {
                        contentBuilder.append(deltaContent);
                    }
                    if (deltaThinking != null) {
                        thinkingBuilder.append(deltaThinking);
                    }
                    // 累积工具调用
                    for (ChatChunk.DeltaToolCall dtc : deltaToolCalls) {
                        toolCallAccumulators.computeIfAbsent(dtc.index(),
                                idx -> new ToolCallAccumulator()).accumulate(dtc);
                    }

                    ChatChunk chatChunk = new ChatChunk(
                            deltaContent, deltaThinking, deltaToolCalls, finishReason
                    );
                    callback.accept(chatChunk);
                });
            });
        }

        // 组装最终结果
        List<ToolCall> toolCalls = new ArrayList<>();
        for (ToolCallAccumulator acc : toolCallAccumulators.values()) {
            toolCalls.add(acc.toToolCall());
        }

        return new ChatResult(contentBuilder.toString(), thinkingBuilder.toString(), toolCalls);
    }

    private String extractThinking(ChatCompletionMessage message) {
        JsonValue thinkingValue = message._additionalProperties().get("reasoning_content");
        return thinkingValue != null
                ? thinkingValue.accept(new JsonValue.Visitor<String>() {
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
    }

    private String extractDeltaThinking(ChatCompletionChunk.Choice.Delta delta) {
        JsonValue thinkingValue = delta._additionalProperties().get("reasoning_content");
        return thinkingValue != null
                ? thinkingValue.accept(new JsonValue.Visitor<String>() {
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
    }

    private List<ToolCall> extractToolCalls(ChatCompletionMessage message) {
        return message.toolCalls()
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
    }

    private List<ChatChunk.DeltaToolCall> extractDeltaToolCalls(ChatCompletionChunk.Choice.Delta delta) {
        return delta.toolCalls()
                .map(calls -> calls.stream()
                        .map(tc -> {
                            String id = tc.id().orElse(null);
                            String name = tc.function().flatMap(fn -> fn.name()).orElse(null);
                            String arguments = tc.function().flatMap(fn -> fn.arguments()).orElse(null);
                            return new ChatChunk.DeltaToolCall(
                                    (int) tc.index(),
                                    id,
                                    name,
                                    arguments
                            );
                        })
                        .collect(Collectors.toList()))
                .orElse(List.of());
    }

    /**
     * 流式工具调用累积器，按 index 拼接工具调用的 id、name 和 arguments
     */
    private static class ToolCallAccumulator {
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();

        void accumulate(ChatChunk.DeltaToolCall delta) {
            if (delta.id() != null) {
                this.id = delta.id();
            }
            if (delta.name() != null) {
                this.name = delta.name();
            }
            if (delta.argumentsDelta() != null) {
                this.arguments.append(delta.argumentsDelta());
            }
        }

        ToolCall toToolCall() {
            return new ToolCall(id, name, arguments.toString());
        }
    }
}
