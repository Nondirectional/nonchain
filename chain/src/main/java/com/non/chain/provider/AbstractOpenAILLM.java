package com.non.chain.provider;

import com.non.chain.*;
import com.non.chain.callback.ChainCallback;
import com.non.chain.callback.ChainCallbackUtil;
import com.non.chain.callback.ChainContext;
import com.non.chain.callback.ChainTrace;
import com.non.chain.callback.event.LlmCompleteEvent;
import com.non.chain.callback.event.LlmErrorEvent;
import com.non.chain.callback.event.LlmStartEvent;
import com.non.chain.callback.event.TokenUsage;
import com.non.chain.tool.Tool;
import com.non.chain.tool.ToolCall;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.core.Timeout;
import com.openai.models.ResponseFormatJsonObject;
import com.openai.models.chat.completions.*;
import com.openai.core.http.StreamResponse;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * OpenAI 兼容 LLM 抽象基类，封装通用的 OpenAI Chat Completions API 调用逻辑。
 * 子类只需提供 base URL、API key 等配置即可。
 */
public abstract class AbstractOpenAILLM implements LLM {

    private static final String RESPONSE_FORMAT_JSON_OBJECT = "json_object";

    protected final OpenAIClient client;
    protected final String model;
    protected Integer maxCompletionTokens;
    protected ChainCallback callback;

    private boolean enableThinking;
    private Integer thinkingBudget;
    private Double temperature;
    private Double topP;
    private OutputFormat defaultOutputFormat = OutputFormat.TEXT;

    protected AbstractOpenAILLM(String baseUrl, String apiKey, String model) {
        this.client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .timeout(Timeout.builder()
                        .connect(Duration.ofSeconds(30))
                        .read(Duration.ofSeconds(180))
                        .write(Duration.ofSeconds(60))
                        .build())
                .build();
        this.model = model;
        this.callback = ChainCallbackUtil.noop();
    }

    // ---- Fluent setters ----

    public AbstractOpenAILLM enableThinking(boolean enable) {
        this.enableThinking = enable;
        return this;
    }

    public AbstractOpenAILLM thinkingBudget(Integer budget) {
        this.thinkingBudget = budget;
        return this;
    }

    public AbstractOpenAILLM enableJsonObjectMode(boolean enable) {
        this.defaultOutputFormat = enable ? OutputFormat.JSON_OBJECT : OutputFormat.TEXT;
        return this;
    }

    public AbstractOpenAILLM temperature(Double temperature) {
        this.temperature = temperature;
        return this;
    }

    public AbstractOpenAILLM topP(Double topP) {
        this.topP = topP;
        return this;
    }

    public AbstractOpenAILLM maxCompletionTokens(Integer maxCompletionTokens) {
        this.maxCompletionTokens = maxCompletionTokens;
        return this;
    }

    public AbstractOpenAILLM callback(ChainCallback callback) {
        this.callback = callback != null ? callback : ChainCallbackUtil.noop();
        return this;
    }

    // ---- Protected accessors for subclasses ----

    protected boolean isEnableThinking() {
        return enableThinking;
    }

    protected Integer getThinkingBudget() {
        return thinkingBudget;
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
        List<Message> messages = new ArrayList<>();
        if (systemMessage != null && !systemMessage.isBlank()) {
            messages.add(Message.system(systemMessage));
        }
        messages.add(Message.user(userMessage));
        return doChatWithCallback(messages, tools, outputFormat,
                buildSimpleParams(systemMessage, userMessage));
    }

    @Override
    public ChatResult chat(List<Message> messages, List<Tool> tools, OutputFormat outputFormat) {
        return doChatWithCallback(messages, tools, outputFormat,
                buildMessageListParams(messages));
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
        List<Message> messages = new ArrayList<>();
        if (systemMessage != null && !systemMessage.isBlank()) {
            messages.add(Message.system(systemMessage));
        }
        messages.add(Message.user(userMessage));
        return doStreamChatWithCallback(messages, tools, outputFormat, callback,
                buildSimpleParams(systemMessage, userMessage));
    }

    @Override
    public ChatResult streamChat(List<Message> messages, List<Tool> tools, OutputFormat outputFormat, Consumer<ChatChunk> callback) {
        return doStreamChatWithCallback(messages, tools, outputFormat, callback,
                buildMessageListParams(messages));
    }

    // ---- 内部方法 ----

    private ChatCompletionCreateParams.Builder buildSimpleParams(String systemMessage, String userMessage) {
        ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
                .model(model);
        if (systemMessage != null && !systemMessage.isBlank()) {
            builder.addSystemMessage(systemMessage);
        }
        builder.addUserMessage(userMessage);
        if (maxCompletionTokens != null) {
            builder.maxCompletionTokens(maxCompletionTokens);
        }
        return builder;
    }

    private ChatCompletionCreateParams.Builder buildMessageListParams(List<Message> messages) {
        ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
                .model(model);
        if (maxCompletionTokens != null) {
            builder.maxCompletionTokens(maxCompletionTokens);
        }

        // LLM 边界过滤：应用层消息（llmVisible=false，如 UI 状态/通知）不进 provider 请求。
        // 静默剥离，遵循项目无日志框架约定；下方 default:throw 是唯一 fail-safe 信号。
        for (Message msg : filterLlmVisible(messages)) {
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

    /**
     * LLM 边界过滤：剥离 llmVisible=false 的应用层消息，只保留进 LLM 上下文的消息。
     *
     * <p>package-private 以便单测覆盖过滤语义，是本任务 R2 的唯一可测入口。</p>
     */
    static List<Message> filterLlmVisible(List<Message> messages) {
        List<Message> visible = new ArrayList<>(messages.size());
        for (Message msg : messages) {
            if (msg.llmVisible()) {
                visible.add(msg);
            }
        }
        return visible;
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
        } else if (part instanceof ImageDataPart) {
            ImageDataPart imgData = (ImageDataPart) part;
            String dataUri = "data:" + imgData.mimeType() + ";base64," + imgData.base64Data();
            return ChatCompletionContentPart.ofImageUrl(
                    ChatCompletionContentPartImage.builder()
                            .imageUrl(ChatCompletionContentPartImage.ImageUrl.builder()
                                    .url(dataUri)
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

    /**
     * 应用通用参数（temperature, topP, thinking, outputFormat）。
     * 子类可覆写此方法添加额外参数（如 DashScope 的 topK）。
     */
    protected void applyAdditionalParams(ChatCompletionCreateParams.Builder builder, OutputFormat outputFormat) {
        applyThinkingParams(builder);
        applyCommonParams(builder, outputFormat);
    }

    /**
     * 注入 thinking 相关参数。
     * 子类可覆写此方法以支持不同提供商的 thinking 参数格式。
     * 默认实现将 enable_thinking 和 thinking_budget 作为平级属性发送。
     */
    protected void applyThinkingParams(ChatCompletionCreateParams.Builder builder) {
        if (enableThinking) {
            builder.putAdditionalBodyProperty("enable_thinking", JsonValue.from(true));
            if (thinkingBudget != null) {
                builder.putAdditionalBodyProperty("thinking_budget", JsonValue.from(thinkingBudget));
            }
        }
    }

    /**
     * 应用通用参数（temperature, topP, outputFormat）。
     */
    private void applyCommonParams(ChatCompletionCreateParams.Builder builder, OutputFormat outputFormat) {
        if (temperature != null) {
            builder.temperature(temperature);
        }
        if (topP != null) {
            builder.topP(topP);
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

        TokenUsage usage = completion.usage()
                .map(u -> new TokenUsage(u.promptTokens(), u.completionTokens(), u.totalTokens()))
                .orElse(null);

        return completion.choices().stream()
                .findFirst()
                .map(choice -> {
                    String content = choice.message().content().orElse("无响应");
                    String thinking = extractThinking(choice.message());
                    List<ToolCall> toolCalls = extractToolCalls(choice.message());
                    return new ChatResult(content, thinking, toolCalls, usage);
                })
                .orElse(new ChatResult("无响应", null));
    }

    private ChatResult doChatWithCallback(List<Message> messages, List<Tool> tools, OutputFormat outputFormat,
                                          ChatCompletionCreateParams.Builder builder) {
        OutputFormat resolvedOutputFormat = resolveOutputFormat(outputFormat);
        validateResponseFormatAndTools(tools, resolvedOutputFormat);
        addTools(builder, tools);

        String traceId = ChainTrace.get();
        callback.onLlmStart(new LlmStartEvent(traceId, messages, tools));

        long start = System.currentTimeMillis();
        try {
            ChatResult result = doChat(builder, resolvedOutputFormat);
            long latencyMs = System.currentTimeMillis() - start;
            callback.onLlmComplete(new LlmCompleteEvent(traceId, result, result.tokenUsage(), latencyMs));
            return result;
        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - start;
            callback.onLlmError(new LlmErrorEvent(traceId, messages, tools, e, latencyMs));
            throw e;
        }
    }

    private ChatResult doStreamChatWithCallback(List<Message> messages, List<Tool> tools, OutputFormat outputFormat,
                                                Consumer<ChatChunk> streamCallback,
                                                ChatCompletionCreateParams.Builder builder) {
        OutputFormat resolvedOutputFormat = resolveOutputFormat(outputFormat);
        validateResponseFormatAndTools(tools, resolvedOutputFormat);
        addTools(builder, tools);

        String traceId = ChainTrace.get();
        callback.onLlmStart(new LlmStartEvent(traceId, messages, tools));

        long start = System.currentTimeMillis();
        try {
            ChatResult result = doStreamChat(builder, resolvedOutputFormat, streamCallback);
            long latencyMs = System.currentTimeMillis() - start;
            callback.onLlmComplete(new LlmCompleteEvent(traceId, result, result.tokenUsage(), latencyMs));
            return result;
        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - start;
            callback.onLlmError(new LlmErrorEvent(traceId, messages, tools, e, latencyMs));
            throw e;
        }
    }

    private ChatResult doStreamChat(ChatCompletionCreateParams.Builder builder, OutputFormat outputFormat, Consumer<ChatChunk> callback) {
        applyAdditionalParams(builder, outputFormat);

        StringBuilder contentBuilder = new StringBuilder();
        StringBuilder thinkingBuilder = new StringBuilder();
        Map<Integer, ToolCallAccumulator> toolCallAccumulators = new TreeMap<>();
        final com.openai.models.completions.CompletionUsage[] lastUsage = {null};

        try (StreamResponse<ChatCompletionChunk> streamResponse =
                     client.chat().completions().createStreaming(builder.build())) {
            streamResponse.stream().forEach(chunk -> {
                chunk.usage().ifPresent(u -> lastUsage[0] = u);

                chunk.choices().stream().findFirst().ifPresent(choice -> {
                    ChatCompletionChunk.Choice.Delta delta = choice.delta();
                    String deltaContent = delta.content().orElse(null);
                    String deltaThinking = extractDeltaThinking(delta);
                    List<ChatChunk.DeltaToolCall> deltaToolCalls = extractDeltaToolCalls(delta);

                    String finishReason = choice.finishReason()
                            .map(fr -> fr.asString())
                            .orElse(null);

                    if (deltaContent != null) {
                        contentBuilder.append(deltaContent);
                    }
                    if (deltaThinking != null) {
                        thinkingBuilder.append(deltaThinking);
                    }
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

        List<ToolCall> toolCalls = new ArrayList<>();
        for (ToolCallAccumulator acc : toolCallAccumulators.values()) {
            toolCalls.add(acc.toToolCall());
        }

        TokenUsage tokenUsage = lastUsage[0] != null
                ? new TokenUsage(lastUsage[0].promptTokens(), lastUsage[0].completionTokens(), lastUsage[0].totalTokens())
                : null;

        return new ChatResult(contentBuilder.toString(), thinkingBuilder.toString(), toolCalls, tokenUsage);
    }

    /**
     * 返回响应中思考内容的字段名，子类可覆写。
     * 默认为 "reasoning_content"（DashScope 风格），vLLM 使用 "reasoning"。
     */
    protected String getThinkingFieldName() {
        return "reasoning_content";
    }

    protected String extractThinking(ChatCompletionMessage message) {
        JsonValue thinkingValue = message._additionalProperties().get(getThinkingFieldName());
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

    protected String extractDeltaThinking(ChatCompletionChunk.Choice.Delta delta) {
        JsonValue thinkingValue = delta._additionalProperties().get(getThinkingFieldName());
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
