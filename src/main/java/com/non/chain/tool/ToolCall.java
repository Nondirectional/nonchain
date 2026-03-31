package com.non.chain.tool;

/**
 * 表示 LLM 返回的一个工具调用
 */
public class ToolCall {

    private final String id;
    private final String name;
    private final String arguments;

    public ToolCall(String id, String name, String arguments) {
        this.id = id;
        this.name = name;
        this.arguments = arguments;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String arguments() {
        return arguments;
    }

    @Override
    public String toString() {
        return "ToolCall{id='" + id + "', name='" + name + "', arguments='" + arguments + "'}";
    }
}
