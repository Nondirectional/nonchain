package com.non.chain.agent;

/**
 * after 工具拦截器：在工具执行完成后、结果回灌 LLM 前调用，可改写结果。
 *
 * <p>多个 after 拦截器按注册顺序链式调用，前一个的输出作为后一个的输入（可叠加脱敏/截断）。</p>
 *
 * <pre>{@code
 * Agent.builder(llm, registry)
 *     .addAfterToolCall(ctx -> AfterResult.content(maskSecrets(ctx.result())))
 *     .build();
 * }</pre>
 */
@FunctionalInterface
public interface AfterToolCall {
    AfterResult after(ToolCallContext ctx);
}
