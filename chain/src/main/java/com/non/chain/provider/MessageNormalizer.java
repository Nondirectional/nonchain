package com.non.chain.provider;

import com.non.chain.Message;
import com.non.chain.tool.ToolCall;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * LLM 请求边界的消息归一化工具。
 *
 * <p>只在模型声明不支持多 system 消息时转换后续 system；输入消息永不原地修改，
 * 便于 Agent 保留原始 transcript、记忆和事件语义。</p>
 */
final class MessageNormalizer {

    static final String SYSTEM_INSTRUCTION_PREFIX = "[Framework System Instruction]";

    private MessageNormalizer() {
    }

    static List<Message> normalizeForRequest(List<Message> messages, boolean supportsMultipleSystemMessages) {
        if (messages == null || messages.isEmpty()) {
            return new ArrayList<>();
        }
        if (supportsMultipleSystemMessages) {
            return new ArrayList<>(messages);
        }

        List<Message> visible = new ArrayList<>(messages.size());
        for (Message message : messages) {
            if (message != null && message.llmVisible()) {
                visible.add(message);
            }
        }
        if (visible.isEmpty()) {
            return visible;
        }

        boolean keepFirstSystem = "system".equals(visible.get(0).role());
        List<Message> normalized = new ArrayList<>(visible.size());
        Deque<Message> deferredSystemUsers = new ArrayDeque<>();
        Set<String> pendingToolCallIds = new LinkedHashSet<>();

        for (Message message : visible) {
            if ("system".equals(message.role())) {
                Message normalizedSystem = keepFirstSystem && normalized.isEmpty()
                        ? message
                        : Message.user(frameworkInstruction(message.content()));
                if (pendingToolCallIds.isEmpty()) {
                    normalized.add(normalizedSystem);
                } else {
                    deferredSystemUsers.addLast(normalizedSystem);
                }
                continue;
            }

            if (isAssistantWithToolCalls(message)) {
                normalized.add(message);
                pendingToolCallIds.clear();
                for (ToolCall toolCall : message.toolCalls()) {
                    if (toolCall != null && toolCall.id() != null && !toolCall.id().isBlank()) {
                        pendingToolCallIds.add(toolCall.id());
                    }
                }
                if (pendingToolCallIds.isEmpty()) {
                    flushDeferred(normalized, deferredSystemUsers);
                }
                continue;
            }

            normalized.add(message);
            if ("tool".equals(message.role()) && !pendingToolCallIds.isEmpty()) {
                pendingToolCallIds.remove(message.toolCallId());
                if (pendingToolCallIds.isEmpty()) {
                    flushDeferred(normalized, deferredSystemUsers);
                }
            } else if (!"tool".equals(message.role()) && !pendingToolCallIds.isEmpty()) {
                // 非 tool 消息打断了不完整调用组；不要把延迟消息丢失。
                flushDeferred(normalized, deferredSystemUsers);
                pendingToolCallIds.clear();
            }
        }
        flushDeferred(normalized, deferredSystemUsers);
        return normalized;
    }

    private static String frameworkInstruction(String content) {
        return SYSTEM_INSTRUCTION_PREFIX + "\n" + (content == null ? "" : content);
    }

    private static void flushDeferred(List<Message> normalized, Deque<Message> deferred) {
        while (!deferred.isEmpty()) {
            normalized.add(deferred.removeFirst());
        }
    }

    private static boolean isAssistantWithToolCalls(Message message) {
        return "assistant".equals(message.role())
                && message.toolCalls() != null
                && !message.toolCalls().isEmpty();
    }
}
