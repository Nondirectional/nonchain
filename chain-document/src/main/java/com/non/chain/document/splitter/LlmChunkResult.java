package com.non.chain.document.splitter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * LLM 切分结果的单个条目，对应 LLM 输出的 JSON 数组中的一个对象。
 * <p>
 * 不可变值对象，使用 Jackson 注解支持 JSON 反序列化。
 */
public class LlmChunkResult {

    private final String content;
    private final String title;

    @JsonCreator
    public LlmChunkResult(
            @JsonProperty("content") String content,
            @JsonProperty("title") String title) {
        this.content = Objects.requireNonNull(content, "content 不能为 null");
        this.title = title;
    }

    @JsonProperty("content")
    public String content() {
        return content;
    }

    @JsonProperty("title")
    public String title() {
        return title;
    }
}
