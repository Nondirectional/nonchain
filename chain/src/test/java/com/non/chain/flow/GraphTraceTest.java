package com.non.chain.flow;

import com.non.chain.trace.InMemoryTraceStore;
import com.non.chain.trace.Span;
import com.non.chain.trace.SpanAttributes;
import com.non.chain.trace.Trace;
import com.non.chain.trace.TraceRuntimeIds;
import com.non.chain.trace.Tracer;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

/**
 * 边界3 测试：Graph 接入 trace —— graph_run 根 + 每节点一个 graph_node span（含 state_in/state_out），
 * 失败路径保留原异常类型且 runtimeId 可提取，节点体内部子执行可继承 node span。
 */
public class GraphTraceTest {

    private Node node(String name, java.util.function.Function<State, State> fn) {
        return new Node(name, fn);
    }

    @Test
    public void graphRunProducesRootAndNodeSpans() {
        InMemoryTraceStore store = new InMemoryTraceStore();
        Graph graph = Graph.builder("flow")
                .traceStore(store)
                .addNode(node("a", s -> s.put("step", "1")))
                .addEdge(Edge.of("a", "b"))
                .addNode(node("b", s -> s.put("step", "2")))
                .addEdge(Edge.of("b", Graph.END))
                .start("a")
                .build();

        State init = new State();
        GraphResult result = graph.run(init);

        assertNotNull(result.runtimeId());
        Trace trace = store.getTrace(result.runtimeId()).get();

        Span root = trace.spans().stream().filter(s -> "graph_run".equals(s.type())).findFirst().get();
        assertNull(root.parentSpanId());
        assertEquals("flow", root.attributes().get(SpanAttributes.GRAPH_NAME));
        assertEquals("a", root.attributes().get(SpanAttributes.START_NODE));

        List<Span> nodeSpans = trace.spans().stream()
                .filter(s -> "graph_node".equals(s.type())).collect(Collectors.toList());
        assertEquals(2, nodeSpans.size());
        // 两个 node span 都挂在 graph_run 根下
        for (Span ns : nodeSpans) {
            assertEquals(root.spanId(), ns.parentSpanId());
        }

        // state_in / state_out 载荷
        Span nodeB = nodeSpans.stream().filter(s -> "b".equals(s.attributes().get(SpanAttributes.NODE_NAME)))
                .findFirst().get();
        @SuppressWarnings("unchecked")
        Map<String, Object> stateIn = (Map<String, Object>) nodeB.attributes().get(SpanAttributes.STATE_IN);
        assertEquals("1", stateIn.get("step"));
        @SuppressWarnings("unchecked")
        Map<String, Object> stateOut = (Map<String, Object>) nodeB.attributes().get(SpanAttributes.STATE_OUT);
        assertEquals("2", stateOut.get("step"));
    }

    @Test
    public void nodeErrorPreservesExceptionAndExposesRuntimeId() {
        InMemoryTraceStore store = new InMemoryTraceStore();
        Graph graph = Graph.builder("err-flow")
                .traceStore(store)
                .addNode(node("boom", s -> { throw new IllegalStateException("节点炸了"); }))
                .addEdge(Edge.of("boom", Graph.END))
                .start("boom")
                .build();

        IllegalStateException caught = null;
        try {
            graph.run(new State());
        } catch (IllegalStateException e) {
            caught = e;
        }
        assertNotNull(caught);
        // 原异常类型/消息不变
        assertEquals("节点炸了", caught.getMessage());

        // runtimeId 可提取，且能回捞 trace（含 error 状态的 node span + graph_run 根）
        Optional<String> rid = TraceRuntimeIds.find(caught);
        assertTrue(rid.isPresent());
        Trace trace = store.getTrace(rid.get()).get();
        Span errNode = trace.spans().stream()
                .filter(s -> "graph_node".equals(s.type())).findFirst().get();
        assertEquals("error", errNode.status());
        assertEquals("节点炸了", errNode.attributes().get(SpanAttributes.ERROR));
    }

    @Test
    public void noTraceWhenStoreNotConfigured() {
        Graph graph = Graph.builder("plain")
                .addNode(node("a", s -> s.put("v", 1)))
                .addEdge(Edge.of("a", Graph.END))
                .start("a")
                .build();
        GraphResult result = graph.run(new State());
        assertNull(result.runtimeId());
    }

    @Test
    public void nodeBodyAgentInheritsNodeSpanAsParent() {
        // 边界3 关键：节点体内部用 Tracer.current() 读到 node span 当 parent。
        // 这里用 Tracer 直接模拟「节点体内建子 span」验证 ThreadLocal 继承（无需真 Agent）。
        InMemoryTraceStore store = new InMemoryTraceStore();
        TracerHolder holder = new TracerHolder();
        Graph graph = Graph.builder("nest")
                .traceStore(store)
                .addNode(new Node("outer", state -> {
                    // 模拟节点体内的子执行：用「当前 tracer」建一个子 span
                    if (holder.tracer != null) {
                        try (Tracer.ScopedSpan inner = holder.tracer.startSpan(
                                com.non.chain.trace.SpanAttributes.SpanType.LLM, "llm-inside-node")) {
                            // 仅验证 parent 继承
                        }
                    }
                    return state;
                }))
                .addEdge(Edge.of("outer", Graph.END))
                .start("outer")
                .build();

        // 由于 Graph 内部 tracer 是私有的，这里用一个共享 store 间接验证：
        // 节点体内的 span 应出现在同一 runtimeId 下且 parent 为 node span。
        // 为让节点体能访问 tracer，用反射注入 holder（保持测试简洁）。
        injectTracer(graph, holder);
        State init = new State();
        GraphResult result = graph.run(init);

        Trace trace = store.getTrace(result.runtimeId()).get();
        Span nodeSpan = trace.spans().stream().filter(s -> "graph_node".equals(s.type())).findFirst().get();
        Optional<Span> innerLlm = trace.spans().stream()
                .filter(s -> "llm".equals(s.type())).findFirst();
        assertTrue("节点体内的子 span 应被录制", innerLlm.isPresent());
        assertEquals("节点体内子 span 应挂在 node span 下（ThreadLocal 继承）",
                nodeSpan.spanId(), innerLlm.get().parentSpanId());
    }

    /** 通过反射把 Graph 内部 tracer 暴露给测试（节点体用它建子 span）。 */
    private static void injectTracer(Graph graph, TracerHolder holder) {
        try {
            java.lang.reflect.Field f = Graph.class.getDeclaredField("tracer");
            f.setAccessible(true);
            holder.tracer = (Tracer) f.get(graph);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static class TracerHolder {
        Tracer tracer;
    }
}
