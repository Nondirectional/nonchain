package com.non.chain.provider;

import com.non.chain.ChatResult;
import com.non.chain.Message;
import com.non.chain.tool.Tool;
import com.non.chain.tool.ToolCall;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.models.chat.completions.*;

import java.util.List;
import java.util.stream.Collectors;

public class DashscopeLLM implements ChatResult.LLM {

    private static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final String API_KEY_ENV = "DASHSCOPE_API_KEY";

    private final OpenAIClient client;
    private final String model;
    private final int maxCompletionTokens;
    private boolean enableThinking;
    private Integer thinkingBudget;

    public DashscopeLLM(String model, int maxCompletionTokens) {
        this(null, model, maxCompletionTokens);
    }

    public DashscopeLLM(String apiKey, String model, int maxCompletionTokens) {
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

    @Override
    public ChatResult chat(String systemMessage, String userMessage) {
        return chat(systemMessage, userMessage, null);
    }

    @Override
    public ChatResult chat(List<Message> messages) {
        return chat(messages, null);
    }

    @Override
    public ChatResult chat(String systemMessage, String userMessage, List<Tool> tools) {
        ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
                .model(model)
                .maxCompletionTokens(maxCompletionTokens)
                .addUserMessage(userMessage);

        if (systemMessage != null && !systemMessage.isBlank()) {
            builder.addSystemMessage(systemMessage);
        }

        addTools(builder, tools);
        return doChat(builder);
    }

    @Override
    public ChatResult chat(List<Message> messages, List<Tool> tools) {
        ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
                .model(model)
                .maxCompletionTokens(maxCompletionTokens);

        for (Message msg : messages) {
            switch (msg.role()) {
                case "system":
                    builder.addSystemMessage(msg.content());
                    break;
                case "user":
                    builder.addUserMessage(msg.content());
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

        addTools(builder, tools);
        return doChat(builder);
    }

    private void addTools(ChatCompletionCreateParams.Builder builder, List<Tool> tools) {
        if (tools != null && !tools.isEmpty()) {
            for (Tool tool : tools) {
                builder.addFunctionTool(tool.toFunctionDefinition());
            }
        }
    }

    private ChatResult doChat(ChatCompletionCreateParams.Builder builder) {
        if (enableThinking) {
            builder.putAdditionalBodyProperty("enable_thinking", JsonValue.from(true));
            if (thinkingBudget != null) {
                builder.putAdditionalBodyProperty("thinking_budget", JsonValue.from(thinkingBudget));
            }
        }

        ChatCompletion completion = client.chat().completions().create(builder.build());

        return completion.choices().stream()
                .findFirst()
                .map(choice -> {
                    String content = choice.message().content().orElse("无响应");
                    JsonValue thinkingValue = choice.message()._additionalProperties().get("reasoning_content");
                    String thinking = thinkingValue != null
                            ? thinkingValue.accept(new JsonValue.Visitor<String>() {
                        @Override
                        public String visitString(String value) { return value; }
                        @Override
                        public String visitNull() { return null; }
                        @Override
                        public String visitDefault() { return null; }
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
