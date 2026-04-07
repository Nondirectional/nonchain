package com.non.chain;

import java.util.Collections;
import java.util.List;

/**
 * 流式输出的增量块，表示 LLM 每次返回的 token 片段
 */
public class ChatChunk {

    private final String deltaContent;
    private final String deltaThinking;
    private final List<DeltaToolCall> deltaToolCalls;
    private final String finishReason;

    public ChatChunk(String deltaContent, String deltaThinking, List<DeltaToolCall> deltaToolCalls, String finishReason) {
        this.deltaContent = deltaContent;
        this.deltaThinking = deltaThinking;
        this.deltaToolCalls = deltaToolCalls != null ? deltaToolCalls : Collections.emptyList();
        this.finishReason = finishReason;
    }

    public String deltaContent() {
        return deltaContent;
    }

    public String deltaThinking() {
        return deltaThinking;
    }

    public List<DeltaToolCall> deltaToolCalls() {
        return deltaToolCalls;
    }

    public String finishReason() {
        return finishReason;
    }

    public boolean hasContent() {
        return deltaContent != null && !deltaContent.isEmpty();
    }

    public boolean hasThinking() {
        return deltaThinking != null && !deltaThinking.isEmpty();
    }

    public boolean hasToolCalls() {
        return deltaToolCalls != null && !deltaToolCalls.isEmpty();
    }

    public boolean isFinished() {
        return finishReason != null;
    }

    /**
     * 流式工具调用的增量片段
     */
    public static class DeltaToolCall {

        private final int index;
        private final String id;
        private final String name;
        private final String argumentsDelta;

        public DeltaToolCall(int index, String id, String name, String argumentsDelta) {
            this.index = index;
            this.id = id;
            this.name = name;
            this.argumentsDelta = argumentsDelta;
        }

        public int index() {
            return index;
        }

        public String id() {
            return id;
        }

        public String name() {
            return name;
        }

        public String argumentsDelta() {
            return argumentsDelta;
        }
    }
}
