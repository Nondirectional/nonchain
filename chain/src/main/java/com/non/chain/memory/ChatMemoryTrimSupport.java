package com.non.chain.memory;

import com.non.chain.Message;

import java.util.ArrayList;
import java.util.List;

final class ChatMemoryTrimSupport {

    private ChatMemoryTrimSupport() {
    }

    static int findFirstDeletableIndex(List<Message> messages) {
        int start = isSystemMessage(messages, 0) ? 1 : 0;
        if (start >= messages.size()) {
            return -1;
        }
        return start;
    }

    static int countMessageGroup(List<Message> messages, int index) {
        Message message = messages.get(index);
        if (isAssistantWithToolCalls(message)) {
            return countForwardToolGroup(messages, index);
        }
        if (isToolMessage(message)) {
            return countReversedToolGroup(messages, index);
        }
        return 1;
    }

    private static int countForwardToolGroup(List<Message> messages, int index) {
        int count = 1;
        for (int i = index + 1; i < messages.size(); i++) {
            if (isToolMessage(messages.get(i))) {
                count++;
            } else {
                break;
            }
        }
        return count;
    }

    private static int countReversedToolGroup(List<Message> messages, int index) {
        List<String> toolCallIds = new ArrayList<>();
        for (int i = index; i < messages.size(); i++) {
            Message current = messages.get(i);
            if (isToolMessage(current)) {
                if (current.toolCallId() == null || current.toolCallId().isEmpty()) {
                    return 1;
                }
                toolCallIds.add(current.toolCallId());
                continue;
            }
            if (isAssistantWithToolCalls(current) && containsAllToolCallIds(current, toolCallIds)) {
                return i - index + 1;
            }
            break;
        }
        return 1;
    }

    private static boolean containsAllToolCallIds(Message assistantMessage, List<String> toolCallIds) {
        for (String toolCallId : toolCallIds) {
            boolean matched = false;
            for (int i = 0; i < assistantMessage.toolCalls().size(); i++) {
                if (toolCallId.equals(assistantMessage.toolCalls().get(i).id())) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAssistantWithToolCalls(Message message) {
        return "assistant".equals(message.role())
                && message.toolCalls() != null
                && !message.toolCalls().isEmpty();
    }

    private static boolean isToolMessage(Message message) {
        return "tool".equals(message.role());
    }

    private static boolean isSystemMessage(List<Message> messages, int index) {
        return index == 0 && !messages.isEmpty() && "system".equals(messages.get(0).role());
    }
}
