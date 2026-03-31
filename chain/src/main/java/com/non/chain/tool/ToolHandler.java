package com.non.chain.tool;

/**
 * 工具执行处理器
 */
@FunctionalInterface
public interface ToolHandler {
    String execute(ToolArgs args);
}
