package com.non.chain.agent;

/**
 * before 工具拦截器：在工具实际执行前调用，可阻止执行。
 *
 * <p>多个 before 拦截器按注册顺序串行调用，任一返回 {@link BeforeResult#block(String)} 即短路。</p>
 *
 * <pre>{@code
 * Agent.builder(llm, registry)
 *     .addBeforeToolCall(ctx -> {
 *         if (isDangerous(ctx.arguments())) {
 *             return BeforeResult.block("危险命令禁止");
 *         }
 *         return BeforeResult.pass();
 *     })
 *     .build();
 * }</pre>
 */
@FunctionalInterface
public interface BeforeToolCall {
    BeforeResult before(ToolCallContext ctx);
}
