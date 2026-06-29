package com.non.chain.flow;

import com.non.chain.callback.ChainCallback;
import com.non.chain.callback.ChainCallbackUtil;
import com.non.chain.callback.ChainContext;
import com.non.chain.trace.SpanAttributes;
import com.non.chain.trace.TraceRuntimeIds;
import com.non.chain.trace.TraceStore;
import com.non.chain.trace.Tracer;

import java.util.*;
import java.util.function.Consumer;

public class Graph {

    public static final String END = "__END__";

    private final String name;
    private final String startNode;
    private final Map<String, Node> nodes;
    private final Map<String, Edge> edges;
    private final Consumer<GraphEvent> onEvent;
    private final ChainCallback callback;
    /** 执行链路遥测（trace 录制，可空；null = 未启用 = 零开销零行为变化）。 */
    private final Tracer tracer;

    private Graph(String name, String startNode, Map<String, Node> nodes, Map<String, Edge> edges,
                  Consumer<GraphEvent> onEvent, ChainCallback callback, Tracer tracer) {
        this.name = name;
        this.startNode = startNode;
        this.nodes = nodes;
        this.edges = edges;
        this.onEvent = onEvent;
        this.callback = callback;
        this.tracer = tracer;
    }

    public String name() {
        return name;
    }

    public GraphResult run(State initialState) {
        if (tracer == null) {
            return runWithoutTrace(initialState);
        }
        // 启用录制：建 graph_run 根 span 包住整个执行。
        // current 为空 → 新根；非空 → startChild（支持子图嵌套 / Flow 节点体内的 graph.run()）。
        Tracer.ScopedSpan rootSpan = tracer.startSpan(SpanAttributes.SpanType.GRAPH_RUN, name);
        rootSpan.putAttribute(SpanAttributes.GRAPH_NAME, name);
        rootSpan.putAttribute(SpanAttributes.START_NODE, startNode);
        try {
            return runWithoutTrace(initialState).withRuntimeId(rootSpan.runtimeId());
        } catch (RuntimeException | Error e) {
            // 失败路径：原异常类型/语义不变，仅附加 suppressed trace marker
            TraceRuntimeIds.attach(e, rootSpan.runtimeId());
            throw e;
        } catch (Exception e) {
            TraceRuntimeIds.attach(e, rootSpan.runtimeId());
            throw e;
        } finally {
            rootSpan.close();
        }
    }

    private GraphResult runWithoutTrace(State initialState) {
        List<State> history = new ArrayList<>();
        List<String> executedNodes = new ArrayList<>();
        State current = initialState;

        history.add(new State(current));

        emit(GraphEvent.of(GraphEvent.Type.GRAPH_START, null, new State(current)));

        String nextNode = startNode;
        // trace 启用时：节点体（node.apply）执行前 push current（graph_node span），
        // 节点体内的 agent.run()/llm.chat()/子图 run() 靠 ThreadLocal 自然读到 node span 当 parent。
        // state_in/state_out 在此本地采集（GraphEvent 只带单份 state，无法表达前后）。
        boolean trace = tracer != null;

        while (nextNode != null && !END.equals(nextNode)) {
            Node node = nodes.get(nextNode);
            if (node == null) {
                String errorMsg = "未找到节点: " + nextNode;
                emit(GraphEvent.nodeError(nextNode, new State(current), errorMsg));
                emit(GraphEvent.graphError(new State(current), errorMsg));
                List<String> finalExecutedNodes = Collections.unmodifiableList(executedNodes);
                emit(GraphEvent.graphEnd(current, finalExecutedNodes));
                throw new IllegalStateException(errorMsg);
            }

            String currentNodeName = nextNode;
            Tracer.ScopedSpan nodeSpan = trace
                    ? tracer.startSpan(SpanAttributes.SpanType.GRAPH_NODE, currentNodeName) : null;
            if (nodeSpan != null) {
                nodeSpan.putAttribute(SpanAttributes.NODE_NAME, currentNodeName);
                nodeSpan.putAttribute(SpanAttributes.STATE_IN, current.data());
            }

            try {
                emit(GraphEvent.of(GraphEvent.Type.NODE_START, currentNodeName, new State(current)));

                current = node.apply(current);
                executedNodes.add(node.name());
                history.add(new State(current));

                if (nodeSpan != null) {
                    nodeSpan.putAttribute(SpanAttributes.STATE_OUT, current.data());
                }
                emit(GraphEvent.of(GraphEvent.Type.NODE_END, node.name(), new State(current)));

                Edge edge = edges.get(currentNodeName);
                if (edge != null) {
                    nextNode = edge.route(current);
                } else {
                    nextNode = null;
                }
                if (nodeSpan != null) {
                    nodeSpan.close();
                    nodeSpan = null;
                }
            } catch (Exception e) {
                String errorMsg = e.getMessage();
                if (nodeSpan != null) {
                    nodeSpan.putAttribute(SpanAttributes.ERROR, errorMsg);
                    nodeSpan.markError(e);
                    nodeSpan.close();
                    nodeSpan = null;
                }
                emit(GraphEvent.nodeError(currentNodeName, new State(current), errorMsg));
                emit(GraphEvent.graphError(new State(current), errorMsg));
                List<String> finalExecutedNodes = Collections.unmodifiableList(executedNodes);
                emit(GraphEvent.graphEnd(current, finalExecutedNodes));
                throw e;
            }
        }

        List<String> finalExecutedNodes = Collections.unmodifiableList(executedNodes);
        emit(GraphEvent.graphEnd(current, finalExecutedNodes));

        return new GraphResult(current, Collections.unmodifiableList(history), finalExecutedNodes);
    }

    private void emit(GraphEvent event) {
        if (onEvent != null) {
            onEvent.accept(event);
        }
        if (callback != null) {
            try {
                callback.onGraphEvent(event);
            } catch (Exception ignored) {
                // 回调异常不应中断主流程
            }
        }
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static class Builder {
        private final String name;
        private String startNode;
        private final Map<String, Node> nodes = new LinkedHashMap<>();
        private final Map<String, Edge> edges = new HashMap<>();
        private Consumer<GraphEvent> onEvent;
        private ChainCallback callback;
        private TraceStore traceStore;

        private Builder(String name) {
            this.name = name;
        }

        public Builder addNode(Node node) {
            nodes.put(node.name(), node);
            return this;
        }

        public Builder start(String nodeName) {
            this.startNode = nodeName;
            return this;
        }

        public Builder addEdge(Edge edge) {
            edges.put(edge.from(), edge);
            return this;
        }

        public Builder onEvent(Consumer<GraphEvent> onEvent) {
            this.onEvent = onEvent;
            return this;
        }

        /**
         * 设置 ChainCallback，Graph 事件将同时通过此回调发出
         */
        public Builder callback(ChainCallback callback) {
            this.callback = callback;
            return this;
        }

        /**
         * 通过 ChainContext 注入回调
         */
        public Builder chainContext(ChainContext chainContext) {
            if (chainContext != null && this.callback == null) {
                this.callback = chainContext.callback();
            }
            return this;
        }

        /**
         * 启用执行链路遥测录制（opt-in）。
         *
         * <p>传入非空 {@link TraceStore} 即开启录制：Graph 每次执行会生成一棵 span 树
         * （graph_run 根 + 每个节点一个 graph_node span），存入 store，调用方可凭
         * {@link GraphResult#runtimeId()} 拉回完整 trace。失败路径下 runtimeId 通过
         * {@code TraceRuntimeIds.find(throwable)} 从异常里提取。</p>
         *
         * <p><b>默认全关</b>：不调用本方法 = 不录制 = 零开销、零行为变化。</p>
         *
         * <p>节点体内部若调用 {@code agent.run()}（且该 Agent 也启用 trace，共享同一 store），
         * 其 span 会通过 ThreadLocal 自然挂在对应 graph_node span 下（节点体执行时 current 即 node span）。</p>
         *
         * <pre>{@code
         * InMemoryTraceStore store = new InMemoryTraceStore();
         * Graph graph = Graph.builder("flow")
         *     .traceStore(store)      // ← 启用录制
         *     .addNode(...).addEdge(...).start("a").build();
         * GraphResult gr = graph.run(initialState);
         * Trace trace = store.getTrace(gr.runtimeId()).orElseThrow();
         * }</pre>
         */
        public Builder traceStore(TraceStore traceStore) {
            this.traceStore = traceStore;
            return this;
        }

        public Graph build() {
            if (nodes.isEmpty()) {
                throw new IllegalStateException("Graph 必须包含至少一个 Node");
            }
            if (startNode == null) {
                throw new IllegalStateException("必须指定起始节点");
            }
            Tracer tracer = traceStore != null ? new Tracer(traceStore) : null;
            return new Graph(name, startNode, nodes, edges, onEvent,
                    callback != null ? callback : ChainCallbackUtil.noop(), tracer);
        }
    }
}

