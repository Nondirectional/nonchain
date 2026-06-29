package com.non.chain.trace;

import com.non.chain.callback.ChainCallback;
import com.non.chain.callback.event.LlmCompleteEvent;
import com.non.chain.callback.event.LlmStartEvent;
import com.non.chain.callback.event.ToolCompleteEvent;
import com.non.chain.callback.event.ToolErrorEvent;
import com.non.chain.callback.event.ToolStartEvent;
import com.non.chain.flow.GraphEvent;

/**
 * 采集桥：把 {@link ChainCallback} 事件<b>载荷</b>转写进当前 span 的 attributes。
 *
 * <p><b>职责边界（design.md §2.1 / §4.2 修订）</b>：本类<b>不负责</b> span 的创建/关闭——
 * span 生命周期由 {@code Agent} / {@code Graph} 侧显式管理（在 {@code startSpan}/{@code startChild}
 * 后触发 callback 填载荷，工具/LLM 执行完后再关）。这样 span 树结构（尤其并行工具 parent 挂载）
 * 完全可控：llm span 覆盖「LLM 调用 + 其所有工具执行」，tool span 自然挂到对应 llm 下。</p>
 *
 * <p>分工：</p>
 * <ul>
 *   <li><b>本类</b>：onLlmStart → 把 messages/tools 填进当前 llm span；onLlmComplete → 填
 *       result/token/latency；onToolStart → 填 tool_call_id/name/arguments 进当前 tool span；
 *       onToolComplete/Error → 填 result/is_error/latency。</li>
 *   <li><b>Agent.doRunWithLoop</b>：每轮 startSpan("llm") 包住 LLM 调用 + 工具执行；
 *       串行工具 startSpan("tool") 包住 safeExecute；并行工具在 worker 里 startChild 捕获的 llm ctx。</li>
 *   <li><b>SubAgent 下钻（边界1）</b>：子代理用自己的 RecordingCallback 实例 + 注入 parent ctx。</li>
 *   <li><b>Flow 节点（边界3）</b>：state_in/state_out 由 Graph.run 本地采集，本类不承担。</li>
 * </ul>
 *
 * <p><b>异常静默隔离</b>：录制失败不能污染主流程（所有方法 try-catch 吞掉）。</p>
 */
public final class RecordingCallback implements ChainCallback {

    public RecordingCallback() {
        // 无状态：载荷填进 Tracer.currentSpan()，由 Agent/Graph 侧 push 的 span 承载
    }

    private static com.non.chain.trace.Tracer.ScopedSpan currentToolSpan() {
        com.non.chain.trace.Tracer.ScopedSpan s = Tracer.currentSpan();
        return (s != null && SpanAttributes.SpanType.TOOL.equals(s.span().type())) ? s : null;
    }

    private static com.non.chain.trace.Tracer.ScopedSpan currentLlmSpan() {
        com.non.chain.trace.Tracer.ScopedSpan s = Tracer.currentSpan();
        return (s != null && SpanAttributes.SpanType.LLM.equals(s.span().type())) ? s : null;
    }

    // ---------------- LLM ----------------

    @Override
    public void onLlmStart(LlmStartEvent event) {
        try {
            com.non.chain.trace.Tracer.ScopedSpan span = currentLlmSpan();
            if (span != null) {
                span.putAttribute(SpanAttributes.MESSAGES, event.messages());
                span.putAttribute(SpanAttributes.TOOLS, event.tools());
            }
        } catch (Exception ignored) {
            // 录制失败不影响主流程
        }
    }

    @Override
    public void onLlmComplete(LlmCompleteEvent event) {
        try {
            com.non.chain.trace.Tracer.ScopedSpan span = currentLlmSpan();
            if (span == null) {
                return;
            }
            if (event.result() != null) {
                span.putAttribute(SpanAttributes.RESULT_CONTENT, event.result().content());
                if (event.result().hasThinking()) {
                    span.putAttribute(SpanAttributes.RESULT_THINKING, event.result().thinkingContent());
                }
                if (event.result().hasToolCalls()) {
                    span.putAttribute(SpanAttributes.RESULT_TOOL_CALLS, event.result().toolCalls());
                }
            }
            com.non.chain.callback.event.TokenUsage usage = event.tokenUsage() != null
                    ? event.tokenUsage()
                    : (event.result() != null ? event.result().tokenUsage() : null);
            if (usage != null) {
                span.putAttribute(SpanAttributes.PROMPT_TOKENS, usage.promptTokens());
                span.putAttribute(SpanAttributes.COMPLETION_TOKENS, usage.completionTokens());
                span.putAttribute(SpanAttributes.TOTAL_TOKENS, usage.totalTokens());
            }
            span.putAttribute(SpanAttributes.LATENCY_MS, event.latencyMs());
        } catch (Exception ignored) {
            // 录制失败不影响主流程
        }
    }

    // ---------------- Tool ----------------

    @Override
    public void onToolStart(ToolStartEvent event) {
        try {
            com.non.chain.trace.Tracer.ScopedSpan span = currentToolSpan();
            if (span != null) {
                span.putAttribute(SpanAttributes.TOOL_CALL_ID, event.toolCall().id());
                span.putAttribute(SpanAttributes.TOOL_NAME, event.toolCall().name());
                span.putAttribute(SpanAttributes.ARGUMENTS, event.toolCall().arguments());
            }
        } catch (Exception ignored) {
            // 录制失败不影响主流程
        }
    }

    @Override
    public void onToolComplete(ToolCompleteEvent event) {
        try {
            com.non.chain.trace.Tracer.ScopedSpan span = currentToolSpan();
            if (span == null) {
                return;
            }
            span.putAttribute(SpanAttributes.RESULT, event.result());
            span.putAttribute(SpanAttributes.IS_ERROR, false);
            span.putAttribute(SpanAttributes.LATENCY_MS, event.latencyMs());
        } catch (Exception ignored) {
            // 录制失败不影响主流程
        }
    }

    @Override
    public void onToolError(ToolErrorEvent event) {
        try {
            com.non.chain.trace.Tracer.ScopedSpan span = currentToolSpan();
            if (span == null) {
                return;
            }
            span.putAttribute(SpanAttributes.RESULT,
                    event.error() != null ? event.error().getMessage() : "tool error");
            span.putAttribute(SpanAttributes.IS_ERROR, true);
            span.putAttribute(SpanAttributes.LATENCY_MS, event.latencyMs());
            span.markError(event.error());
        } catch (Exception ignored) {
            // 录制失败不影响主流程
        }
    }

    // ---------------- Graph（仅观察桥，不建模 state） ----------------

    @Override
    public void onGraphEvent(GraphEvent event) {
        // node span 的 state_in/state_out 由 Graph.run 本地采集
    }
}
