package com.non.chain.callback;

import com.non.chain.callback.event.*;
import com.non.chain.flow.GraphEvent;

/**
 * 统一回调接口，用于订阅 LLM、工具、检索、Graph 等组件的生命周期事件。
 *
 * <p>所有方法均有默认空实现，用户可以只关注感兴趣的事件。</p>
 *
 * <pre>{@code
 * ChainCallback callback = new ChainCallback() {
 *     @Override
 *     public void onLlmComplete(LlmCompleteEvent event) {
 *         System.out.println("LLM 耗时: " + event.latencyMs() + "ms");
 *     }
 * };
 *
 * Agent agent = Agent.builder(llm, registry)
 *     .callback(callback)
 *     .build();
 * }</pre>
 */
public interface ChainCallback {

    // ---- LLM 生命周期 ----

    /**
     * LLM 调用前触发
     */
    default void onLlmStart(LlmStartEvent event) {}

    /**
     * LLM 调用成功后触发（含 token 用量、延迟）
     */
    default void onLlmComplete(LlmCompleteEvent event) {}

    /**
     * LLM 调用失败时触发
     */
    default void onLlmError(LlmErrorEvent event) {}

    // ---- Tool 生命周期 ----

    /**
     * 工具执行前触发
     */
    default void onToolStart(ToolStartEvent event) {}

    /**
     * 工具执行成功后触发（含耗时）
     */
    default void onToolComplete(ToolCompleteEvent event) {}

    /**
     * 工具执行失败时触发
     */
    default void onToolError(ToolErrorEvent event) {}

    // ---- Retrieval 生命周期 ----

    /**
     * 检索开始前触发
     */
    default void onRetrievalStart(RetrievalStartEvent event) {}

    /**
     * 检索完成后触发（含命中数、耗时）
     */
    default void onRetrievalComplete(RetrievalCompleteEvent event) {}

    /**
     * 检索失败时触发
     */
    default void onRetrievalError(RetrievalErrorEvent event) {}

    // ---- Graph 事件 ----

    /**
     * Graph 工作流事件（复用现有 GraphEvent）
     */
    default void onGraphEvent(GraphEvent event) {}
}
