package com.non.chain.trace;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/**
 * span 构建器 + current-span ThreadLocal 栈（design.md §4）。
 *
 * <p><b>传播机制（混合方案）</b>：</p>
 * <ul>
 *   <li>{@link SpanContext} 作真相源——跨线程/跨 Agent 传递「当前 span」时捕获/注入它。</li>
 *   <li>{@link #CURRENT} ThreadLocal current-span 栈做易用镜像——同线程子代码（如 Flow 节点体内的
 *       {@code agent.run()}）免改签名即可读到 parent。</li>
 *   <li>三处硬边界（SubAgent 构建点、并行工具 worker、Flow 节点）用 {@link #startChild} 显式传播，
 *       绕开 worker 线程 ThreadLocal 丢失 / SubAgent noop 隔离。</li>
 * </ul>
 *
 * <p><b>正交于用户面 callback</b>：本类用自己的 ThreadLocal 栈，不共用 {@code ChainTrace.TRACE_ID}，
 * 不寄生 {@code ChainCallback}。未配置 trace（{@code store == null}）时 Agent/Graph 不会构造 Tracer，
 * 所有建 span 的代码走 {@code if (tracer != null)} 短路。</p>
 *
 * <p>用法（try-with-resources 自动 pop + end + record）：</p>
 * <pre>{@code
 * try (Tracer.ScopedSpan span = tracer.startSpan("llm", "llm")) {
 *     span.putAttribute(SpanAttributes.RESULT_CONTENT, "...");
 * } // close 时 pop 栈 + end + store.record
 * }</pre>
 */
public final class Tracer {

    private final TraceStore store;

    /** current-span 栈（每线程一个 Deque，栈顶 = 当前 span 的 context）。 */
    private static final ThreadLocal<Deque<SpanContext>> CURRENT = ThreadLocal.withInitial(ArrayDeque::new);

    /** current ScopedSpan 实例栈（与 {@link #CURRENT} 平行，供补载荷用）。 */
    private static final ThreadLocal<Deque<ScopedSpan>> CURRENT_SPAN = ThreadLocal.withInitial(ArrayDeque::new);

    public Tracer(TraceStore store) {
        this.store = store;
    }

    public TraceStore store() {
        return store;
    }

    /**
     * 读当前线程的 current span context（栈顶）。可能为 null：顶层无录制 / worker 线程未注入。
     */
    public static SpanContext current() {
        Deque<SpanContext> stack = CURRENT.get();
        return stack.isEmpty() ? null : stack.peek();
    }

    /**
     * 建一个 span 并 push 为 current：
     * <ul>
     *   <li>current 为空 → 新根（runtimeId = 自身 spanId，parentSpanId=null）。
     *       支持顶层 {@code agent_run} / {@code graph_run}。</li>
     *   <li>current 非空 → 作为 current 的子 span（继承 runtimeId，parent=current.spanId）。
     *       支持同线程嵌套（如 Flow 节点体内的 agent.run() 自然读到 node span 当 parent）。</li>
     * </ul>
     */
    public ScopedSpan startSpan(String type, String name) {
        SpanContext parent = current();
        if (parent == null) {
            // 新根
            String spanId = newSpanId();
            SpanContext ctx = new SpanContext(spanId, spanId, null);
            return new ScopedSpan(this, new Span(spanId, null, spanId, type, name, System.currentTimeMillis()), ctx);
        }
        return startChild(parent, type, name);
    }

    /**
     * 用显式 parent 建子 span（绕开 worker 线程 ThreadLocal 丢失 / SubAgent noop 隔离）。
     * runtimeId 继承 parent.runtimeId。三处边界专用。
     */
    public ScopedSpan startChild(SpanContext parent, String type, String name) {
        String spanId = newSpanId();
        SpanContext ctx = new SpanContext(parent.runtimeId(), spanId, parent.spanId());
        return new ScopedSpan(this, new Span(spanId, parent.spanId(), parent.runtimeId(), type, name,
                System.currentTimeMillis()), ctx);
    }

    private static String newSpanId() {
        return UUID.randomUUID().toString();
    }

    private void push(SpanContext ctx) {
        CURRENT.get().push(ctx);
    }

    private void pop() {
        Deque<SpanContext> stack = CURRENT.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }
        if (stack.isEmpty()) {
            CURRENT.remove();
        }
    }

    /**
     * 当前线程「正在打开」的 ScopedSpan（栈顶对应的实例）。
     * RecordingCallback 在串行路径用它补全当前 llm/tool span 的载荷。
     * 当前线程无打开 span 时返回 null。
     */
    public static ScopedSpan currentSpan() {
        Deque<ScopedSpan> stack = CURRENT_SPAN.get();
        return stack.isEmpty() ? null : stack.peek();
    }

    /**
     * 一次 span 的作用域：创建时 push current，{@link #close()} 时 pop + end + record。
     * 还提供补载荷 / 标错的入口（complete/error 时调用）。
     */
    public static final class ScopedSpan implements AutoCloseable {

        private final Tracer tracer;
        private final Span span;
        private final SpanContext ctx;
        private volatile boolean closed = false;

        ScopedSpan(Tracer tracer, Span span, SpanContext ctx) {
            this.tracer = tracer;
            this.span = span;
            this.ctx = ctx;
            tracer.push(ctx);
            CURRENT_SPAN.get().push(this);
        }

        public Span span() {
            return span;
        }

        public SpanContext context() {
            return ctx;
        }

        public String spanId() {
            return span.spanId();
        }

        public String runtimeId() {
            return span.runtimeId();
        }

        public ScopedSpan putAttribute(String key, Object value) {
            span.putAttribute(key, value);
            return this;
        }

        public ScopedSpan putAllAttributes(java.util.Map<String, Object> map) {
            span.putAllAttributes(map);
            return this;
        }

        /** 标记失败（写 status=error + error 消息）。close 时据此 endWithError。 */
        public ScopedSpan markError(Throwable t) {
            return markErrorMessage(t != null ? t.getMessage() : "unknown error");
        }

        public ScopedSpan markErrorMessage(String message) {
            span.endWithError(span.endTimeMs() == 0 ? System.currentTimeMillis() : span.endTimeMs(), message);
            return this;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            tracer.pop();
            Deque<ScopedSpan> spanStack = CURRENT_SPAN.get();
            if (!spanStack.isEmpty()) {
                spanStack.pop();
            }
            if (spanStack.isEmpty()) {
                CURRENT_SPAN.remove();
            }
            long end = System.currentTimeMillis();
            if (span.status() == null) {
                span.end(end);
            } else {
                // 已被 markError 写过 status，补 endTime（若尚未写）
                if (span.endTimeMs() == 0) {
                    span.end(end);
                }
            }
            // 录制失败不能污染主流程（异常静默隔离，参考 Graph.java:95-99）
            try {
                tracer.store.record(span);
            } catch (Exception ignored) {
                // store 异常不应中断业务执行
            }
        }
    }
}
