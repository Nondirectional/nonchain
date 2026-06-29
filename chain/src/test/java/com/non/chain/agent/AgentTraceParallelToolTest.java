package com.non.chain.agent;

import com.non.chain.ChatChunk;
import com.non.chain.ChatResult;
import com.non.chain.Message;
import com.non.chain.provider.LLM;
import com.non.chain.tool.ToolCall;
import com.non.chain.tool.ToolRegistry;
import com.non.chain.trace.InMemoryTraceStore;
import com.non.chain.trace.Span;
import com.non.chain.trace.SpanAttributes;
import com.non.chain.trace.Trace;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

/**
 * 边界2 测试：并行工具 span 正确父子——多个并行 tool span 挂在同一 llm 调用下，
 * 跨 worker 线程 parent 不丢，且每个 toolCall 恰好对应一个 tool span（无重复）。
 */
public class AgentTraceParallelToolTest {

    static class MockLLM implements LLM {
        private final List<ChatResult> responses;
        private int callIndex = 0;
        MockLLM(List<ChatResult> responses) { this.responses = responses; }

        @Override public ChatResult chat(String s, String u, com.non.chain.OutputFormat f) { throw new UnsupportedOperationException(); }
        @Override public ChatResult chat(List<Message> m, com.non.chain.OutputFormat f) { throw new UnsupportedOperationException(); }
        @Override public ChatResult chat(String s, String u, List<com.non.chain.tool.Tool> t, com.non.chain.OutputFormat f) { throw new UnsupportedOperationException(); }
        @Override public ChatResult chat(List<Message> messages, List<com.non.chain.tool.Tool> tools, com.non.chain.OutputFormat f) {
            return responses.get(callIndex++);
        }
        @Override public ChatResult streamChat(String s, String u, com.non.chain.OutputFormat f, java.util.function.Consumer<ChatChunk> c) { throw new UnsupportedOperationException(); }
        @Override public ChatResult streamChat(List<Message> m, com.non.chain.OutputFormat f, java.util.function.Consumer<ChatChunk> c) { throw new UnsupportedOperationException(); }
        @Override public ChatResult streamChat(String s, String u, List<com.non.chain.tool.Tool> t, com.non.chain.OutputFormat f, java.util.function.Consumer<ChatChunk> c) { throw new UnsupportedOperationException(); }
        @Override public ChatResult streamChat(List<Message> messages, List<com.non.chain.tool.Tool> tools, com.non.chain.OutputFormat f, java.util.function.Consumer<ChatChunk> callback) {
            ChatResult response = responses.get(callIndex++);
            if (response.content() != null && !response.content().isEmpty()) {
                callback.accept(new ChatChunk(response.content(), null, null, null));
            }
            if (response.hasToolCalls()) {
                List<ChatChunk.DeltaToolCall> deltas = new ArrayList<>();
                for (int i = 0; i < response.toolCalls().size(); i++) {
                    ToolCall tc = response.toolCalls().get(i);
                    deltas.add(new ChatChunk.DeltaToolCall(i, tc.id(), tc.name(), tc.arguments()));
                }
                callback.accept(new ChatChunk(null, null, deltas, null));
            }
            callback.accept(new ChatChunk(null, null, null, "stop"));
            return response;
        }
    }

    private static ChatResult reply(String content) { return new ChatResult(content, null); }
    private static ChatResult toolCalls(ToolCall... calls) {
        return new ChatResult("", null, Arrays.asList(calls));
    }

    @Test
    public void parallelToolsHangUnderSameLlmSpanAcrossWorkerThreads() {
        InMemoryTraceStore store = new InMemoryTraceStore();
        // 一轮同时调用 3 个工具，第二轮给最终答复
        MockLLM llm = new MockLLM(Arrays.asList(
                toolCalls(
                        new ToolCall("c1", "tool_a", "{\"n\":1}"),
                        new ToolCall("c2", "tool_b", "{\"n\":2}"),
                        new ToolCall("c3", "tool_c", "{\"n\":3}")
                ),
                reply("汇总完成")
        ));
        ToolRegistry registry = new ToolRegistry();
        registry.register("tool_a", "工具A").handle(args -> "a-result");
        registry.register("tool_b", "工具B").handle(args -> "b-result");
        registry.register("tool_c", "工具C").handle(args -> "c-result");

        Agent agent = Agent.builder(llm, registry)
                .trace(store)
                .executor(Executors.newFixedThreadPool(4)) // 并行
                .build();

        ChatResult result = agent.run("并行执行");
        Trace trace = store.getTrace(result.runtimeId()).get();

        // 恰好 3 个 tool span（无重复）
        List<Span> toolSpans = trace.spans().stream()
                .filter(s -> "tool".equals(s.type())).collect(Collectors.toList());
        assertEquals("每个 toolCall 恰好一个 tool span，无重复", 3, toolSpans.size());

        // 3 个 tool span 的 parent 都应是第一轮的 llm span
        Span firstLlm = trace.spans().stream().filter(s -> "llm".equals(s.type()))
                .min(java.util.Comparator.comparingLong(Span::startTimeMs)).get();
        for (Span ts : toolSpans) {
            assertEquals("并行 tool span 应挂在同一 llm 调用下（跨 worker 线程 parent 不丢）",
                    firstLlm.spanId(), ts.parentSpanId());
        }

        // tool 名称齐全（跨线程并发 record 不丢失）
        List<String> toolNames = toolSpans.stream()
                .map(s -> (String) s.attributes().get(SpanAttributes.TOOL_NAME))
                .sorted().collect(Collectors.toList());
        assertEquals(Arrays.asList("tool_a", "tool_b", "tool_c"), toolNames);
        // 全部成功
        for (Span ts : toolSpans) {
            assertEquals("ok", ts.status());
        }
    }

    @Test
    public void serialToolsAlsoHangUnderLlmSpanWhenNoExecutor() {
        // 无 executor → 串行：tool span 也应挂在 llm 下，验证串/并行一致
        InMemoryTraceStore store = new InMemoryTraceStore();
        MockLLM llm = new MockLLM(Arrays.asList(
                toolCalls(new ToolCall("c1", "t1", "{}"), new ToolCall("c2", "t2", "{}")),
                reply("done")
        ));
        ToolRegistry registry = new ToolRegistry();
        registry.register("t1", "工具1").handle(args -> "1");
        registry.register("t2", "工具2").handle(args -> "2");

        Agent agent = Agent.builder(llm, registry).trace(store).executor(null).build();
        ChatResult result = agent.run("串行执行");
        Trace trace = store.getTrace(result.runtimeId()).get();

        Span firstLlm = trace.spans().stream().filter(s -> "llm".equals(s.type()))
                .min(java.util.Comparator.comparingLong(Span::startTimeMs)).get();
        List<Span> toolSpans = trace.spans().stream()
                .filter(s -> "tool".equals(s.type())).collect(Collectors.toList());
        assertEquals(2, toolSpans.size());
        for (Span ts : toolSpans) {
            assertEquals(firstLlm.spanId(), ts.parentSpanId());
        }
    }
}
