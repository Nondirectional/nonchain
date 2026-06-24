package com.non.chain.agent;

import com.non.chain.Message;

/**
 * 工具拦截器的入参上下文，不可变值对象。
 *
 * <p>before 拦截器收到时 {@link #result()} 为 null、{@link #isError()} 为 false
 * （尚未执行）；after 拦截器收到时携带实际执行结果与错误状态。</p>
 */
public final class ToolCallContext {

    private final String toolCallId;
    private final String toolName;
    private final String arguments;
    private final Message assistantMessage;
    private final String result;
    private final boolean isError;

    /** before 拦截器用：构造时尚未执行，result=null, isError=false。 */
    public ToolCallContext(String toolCallId, String toolName, String arguments, Message assistantMessage) {
        this(toolCallId, toolName, arguments, assistantMessage, null, false);
    }

    /** after 拦截器用：携带执行结果与错误状态。 */
    public ToolCallContext(String toolCallId, String toolName, String arguments,
                           Message assistantMessage, String result, boolean isError) {
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.arguments = arguments;
        this.assistantMessage = assistantMessage;
        this.result = result;
        this.isError = isError;
    }

    public String toolCallId() {
        return toolCallId;
    }

    public String toolName() {
        return toolName;
    }

    /** 原始 JSON 参数字符串。 */
    public String arguments() {
        return arguments;
    }

    /** 触发该工具调用的 assistant 消息（含完整 content/thinking/toolCalls）。 */
    public Message assistantMessage() {
        return assistantMessage;
    }

    /** 执行结果（after 时非 null；before 时为 null）。 */
    public String result() {
        return result;
    }

    /** 执行是否被标记为错误（after 时有意义；before 时为 false）。 */
    public boolean isError() {
        return isError;
    }
}
