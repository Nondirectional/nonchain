package com.non.chain.flow;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class GraphTest {

    @Test
    public void shouldEmitAllEventTypes() {
        List<GraphEvent> events = new ArrayList<>();

        Graph graph = Graph.builder("test")
                .start("a")
                .addNode(new Node("a", state -> state.put("a", true)))
                .addNode(new Node("b", state -> state.put("b", true)))
                .addEdge(Edge.of("a", "b"))
                .addEdge(Edge.of("b", Graph.END))
                .onEvent(events::add)
                .build();

        GraphResult result = graph.run(new State());

        assertEquals(6, events.size());
        assertEquals(GraphEvent.Type.GRAPH_START, events.get(0).type());
        assertNull(events.get(0).node());

        assertEquals(GraphEvent.Type.NODE_START, events.get(1).type());
        assertEquals("a", events.get(1).node());

        assertEquals(GraphEvent.Type.NODE_END, events.get(2).type());
        assertEquals("a", events.get(2).node());

        assertEquals(GraphEvent.Type.NODE_START, events.get(3).type());
        assertEquals("b", events.get(3).node());

        assertEquals(GraphEvent.Type.NODE_END, events.get(4).type());
        assertEquals("b", events.get(4).node());

        assertEquals(GraphEvent.Type.GRAPH_END, events.get(5).type());
        assertNull(events.get(5).node());
        assertEquals(List.of("a", "b"), events.get(5).executedNodes());

        assertEquals(List.of("a", "b"), result.executedNodes());
    }

    @Test
    public void shouldWorkWithoutEventCallback() {
        Graph graph = Graph.builder("silent")
                .start("a")
                .addNode(new Node("a", state -> state.put("x", 42)))
                .addEdge(Edge.of("a", Graph.END))
                .build();

        GraphResult result = graph.run(new State());

        assertEquals(List.of("a"), result.executedNodes());
        assertEquals(Integer.valueOf(42), result.finalState().<Integer>get("x").orElseThrow());
    }

    @Test
    public void shouldProvideCorrectStateAtEachEvent() {
        List<GraphEvent> events = new ArrayList<>();

        Graph graph = Graph.builder("state-check")
                .start("a")
                .addNode(new Node("a", state -> state.put("step", 1)))
                .addNode(new Node("b", state -> state.put("step", 2)))
                .addEdge(Edge.of("a", "b"))
                .addEdge(Edge.of("b", Graph.END))
                .onEvent(events::add)
                .build();

        graph.run(new State());

        // GRAPH_START: initial state, no "step"
        assertFalse(events.get(0).state().has("step"));

        // NODE_START a: state unchanged
        assertFalse(events.get(1).state().has("step"));

        // NODE_END a: step=1
        assertEquals(Integer.valueOf(1), events.get(2).state().<Integer>get("step").orElseThrow());

        // NODE_START b: step=1 (state from previous node)
        assertEquals(Integer.valueOf(1), events.get(3).state().<Integer>get("step").orElseThrow());

        // NODE_END b: step=2
        assertEquals(Integer.valueOf(2), events.get(4).state().<Integer>get("step").orElseThrow());
    }

    @Test
    public void shouldHandleConditionalRouting() {
        List<GraphEvent> events = new ArrayList<>();

        Graph graph = Graph.builder("conditional")
                .start("start")
                .addNode(new Node("start", state -> state.put("flag", "right")))
                .addNode(new Node("left", state -> state.put("path", "L")))
                .addNode(new Node("right", state -> state.put("path", "R")))
                .addEdge(Edge.conditional("start", state ->
                        "right".equals(state.getOrDefault("flag", "")) ? "right" : "left"))
                .addEdge(Edge.of("left", Graph.END))
                .addEdge(Edge.of("right", Graph.END))
                .onEvent(events::add)
                .build();

        GraphResult result = graph.run(new State());

        // GRAPH_START + NODE_START/END start + NODE_START/END right + GRAPH_END
        assertEquals(6, events.size());
        assertEquals("right", events.get(4).node());
        assertEquals(List.of("start", "right"), events.get(5).executedNodes());
        assertEquals("R", result.finalState().<String>get("path").orElseThrow());
    }
}
