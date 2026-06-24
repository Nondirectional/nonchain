package com.non.chain.agent;

import com.non.chain.ChatResult;
import com.non.chain.Message;
import com.non.chain.callback.event.ToolCompleteEvent;
import com.non.chain.callback.event.ToolErrorEvent;
import com.non.chain.callback.event.ToolStartEvent;
import com.non.chain.callback.ChainCallback;
import com.non.chain.tool.ToolCall;
import com.non.chain.tool.ToolRegistry;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * 工具拦截器（beforeToolCall/afterToolCall）测试。
 *
 * <p>覆盖：block、放行、after 改写 content、after 标 error、多拦截器短路/链式、
 * 并行模式下拦截器、拦截器异常、callback 单次触发。</p>
 */
public class ToolInterceptorTest {

    // ---- 回调计数器（验证 R5：callback 单次触发） ----

    static class CountingCallback implements ChainCallback {
        int toolStart = 0;
        int toolComplete = 0;
        int toolError = 0;

        @Override
        public void onToolStart(ToolStartEvent e) { toolStart++; }
        @Override
        public void onToolComplete(ToolCompleteEvent e) { toolComplete++; }
        @Override
        public void onToolError(ToolErrorEvent e) { toolError++; }
    }

    private static List<ChatResult> twoRoundResponses(ToolCall tc, String finalReply) {
        List<ChatResult> r = new ArrayList<>();
        r.add(new ChatResult("", null, Collections.singletonList(tc)));
        r.add(new ChatResult(finalReply, null));
        return r;
    }

    // ---- before ----

    @Test
    public void beforeBlockSkipsExecutionAndFeedsReasonToLlm() {
        ToolCall tc = new ToolCall("c1", "danger", "{\"cmd\":\"rm -rf /\"}");
        AgentTest.MockLLM llm = new AgentTest.MockLLM(twoRoundResponses(tc, "已处理"));
        ToolRegistry registry = new ToolRegistry();
        List<Boolean> handlerCalled = new ArrayList<>();
        registry.register("danger", "危险工具").handle(args -> {
            handlerCalled.add(true);
            return "不应执行";
        });

        Agent agent = Agent.builder(llm, registry)
                .addBeforeToolCall(ctx -> BeforeResult.block("危险命令禁止"))
                .build();
        agent.run("测试");

        // 工具 handler 未被调用
        assertTrue("block 后工具不应执行", handlerCalled.isEmpty());

        // reason 回灌给 LLM（第二轮消息含 tool result = reason 文本）
        List<Message> secondCall = llm.getCapturedMessages().get(1);
        Message toolResult = secondCall.get(2);
        assertEquals("tool", toolResult.role());
        assertEquals("危险命令禁止", toolResult.content());
    }

    @Test
    public void beforePassExecutesNormally() {
        ToolCall tc = new ToolCall("c1", "safe", "{}");
        AgentTest.MockLLM llm = new AgentTest.MockLLM(twoRoundResponses(tc, "完成"));
        ToolRegistry registry = new ToolRegistry();
        registry.register("safe", "安全工具").handle(args -> "安全结果");

        Agent agent = Agent.builder(llm, registry)
                .addBeforeToolCall(ctx -> BeforeResult.pass())
                .build();
        agent.run("测试");

        List<Message> secondCall = llm.getCapturedMessages().get(1);
        assertEquals("安全结果", secondCall.get(2).content());
    }

    @Test
    public void multipleBeforeShortCircuitOnFirstBlock() {
        ToolCall tc = new ToolCall("c1", "t", "{}");
        AgentTest.MockLLM llm = new AgentTest.MockLLM(twoRoundResponses(tc, "完成"));
        ToolRegistry registry = new ToolRegistry();
        registry.register("t", "工具").handle(args -> "结果");

        List<String> visited = new ArrayList<>();
        Agent agent = Agent.builder(llm, registry)
                .addBeforeToolCall(ctx -> { visited.add("first"); return BeforeResult.block("被拦截"); })
                .addBeforeToolCall(ctx -> { visited.add("second"); return BeforeResult.pass(); })
                .build();
        agent.run("测试");

        // 第二个 before 不应被调用（短路）
        assertEquals(List.of("first"), visited);
    }

    // ---- after ----

    @Test
    public void afterModifiesContent() {
        ToolCall tc = new ToolCall("c1", "fetch", "{}");
        AgentTest.MockLLM llm = new AgentTest.MockLLM(twoRoundResponses(tc, "完成"));
        ToolRegistry registry = new ToolRegistry();
        registry.register("fetch", "取数").handle(args -> "secret=12345");

        Agent agent = Agent.builder(llm, registry)
                .addAfterToolCall(ctx -> AfterResult.content(ctx.result().replace("12345", "***")))
                .build();
        agent.run("测试");

        // LLM 收到脱敏后的 content
        List<Message> secondCall = llm.getCapturedMessages().get(1);
        assertEquals("secret=***", secondCall.get(2).content());
    }

    @Test
    public void afterMarksError() {
        ToolCall tc = new ToolCall("c1", "t", "{}");
        AgentTest.MockLLM llm = new AgentTest.MockLLM(twoRoundResponses(tc, "完成"));
        ToolRegistry registry = new ToolRegistry();
        registry.register("t", "工具").handle(args -> "正常结果");

        CountingCallback counter = new CountingCallback();
        Agent agent = Agent.builder(llm, registry)
                .callback(counter)
                .addAfterToolCall(ctx -> AfterResult.error())
                .build();
        agent.run("测试");

        // after 标 error → onToolError 触发，无 onToolComplete
        assertEquals(1, counter.toolStart);
        assertEquals(0, counter.toolComplete);
        assertEquals(1, counter.toolError);
    }

    @Test
    public void multipleAfterChain() {
        ToolCall tc = new ToolCall("c1", "t", "{}");
        AgentTest.MockLLM llm = new AgentTest.MockLLM(twoRoundResponses(tc, "完成"));
        ToolRegistry registry = new ToolRegistry();
        registry.register("t", "工具").handle(args -> "abcdef");

        // 第一个 after 截断为前3字符，第二个在前者基础上再大写
        Agent agent = Agent.builder(llm, registry)
                .addAfterToolCall(ctx -> AfterResult.content(ctx.result().substring(0, 3)))
                .addAfterToolCall(ctx -> AfterResult.content(ctx.result().toUpperCase()))
                .build();
        agent.run("测试");

        List<Message> secondCall = llm.getCapturedMessages().get(1);
        assertEquals("ABC", secondCall.get(2).content());
    }

    @Test
    public void afterSeesExecutionErrorResult() {
        // 工具抛异常 → after 应看到 isError=true + 软失败文本，可改写
        ToolCall tc = new ToolCall("c1", "bad", "{}");
        AgentTest.MockLLM llm = new AgentTest.MockLLM(twoRoundResponses(tc, "完成"));
        ToolRegistry registry = new ToolRegistry();
        registry.register("bad", "坏工具").handle(args -> { throw new RuntimeException("连接超时"); });

        List<Boolean> sawError = new ArrayList<>();
        Agent agent = Agent.builder(llm, registry)
                .addAfterToolCall(ctx -> {
                    sawError.add(ctx.isError());
                    return AfterResult.content("友好错误提示");
                })
                .build();
        agent.run("测试");

        assertEquals(List.of(true), sawError);
        // 改写后的 content 回灌 LLM
        List<Message> secondCall = llm.getCapturedMessages().get(1);
        assertEquals("友好错误提示", secondCall.get(2).content());
    }

    // ---- 并行模式 ----

    @Test
    public void interceptorsWorkInParallelMode() {
        ToolCall tc1 = new ToolCall("c1", "get", "{\"q\":\"a\"}");
        ToolCall tc2 = new ToolCall("c2", "get", "{\"q\":\"b\"}");
        List<ChatResult> resp = new ArrayList<>();
        resp.add(new ChatResult("", null, List.of(tc1, tc2)));
        resp.add(new ChatResult("完成", null));

        AgentTest.MockLLM llm = new AgentTest.MockLLM(resp);
        ToolRegistry registry = new ToolRegistry();
        registry.register("get", "查询").handle(args -> args.getString("q") + "-原始");

        // before 全放行；after 给每个结果加后缀
        Agent agent = Agent.builder(llm, registry)
                .addBeforeToolCall(ctx -> BeforeResult.pass())
                .addAfterToolCall(ctx -> AfterResult.content(ctx.result() + "-已处理"))
                .build();
        agent.run("测试");

        // 按源序，两个结果都被 after 改写
        List<Message> secondCall = llm.getCapturedMessages().get(1);
        assertEquals("a-原始-已处理", secondCall.get(2).content());
        assertEquals("b-原始-已处理", secondCall.get(3).content());
    }

    // ---- 拦截器异常 ----

    @Test
    public void interceptorThrowsBecomesAgentException() {
        ToolCall tc = new ToolCall("c1", "t", "{}");
        AgentTest.MockLLM llm = new AgentTest.MockLLM(twoRoundResponses(tc, "完成"));
        ToolRegistry registry = new ToolRegistry();
        registry.register("t", "工具").handle(args -> "结果");

        Agent agent = Agent.builder(llm, registry)
                .addBeforeToolCall(ctx -> { throw new RuntimeException("拦截器崩了"); })
                .build();

        try {
            agent.run("测试");
            fail("拦截器异常应包装为 AgentException 抛出");
        } catch (AgentException e) {
            assertTrue(e.getMessage().contains("t"));
        }
    }

    // ---- R5: callback 单次触发 ----

    @Test
    public void callbackFiredOncePerToolCall() {
        ToolCall tc = new ToolCall("c1", "t", "{}");
        AgentTest.MockLLM llm = new AgentTest.MockLLM(twoRoundResponses(tc, "完成"));
        ToolRegistry registry = new ToolRegistry();
        registry.register("t", "工具").handle(args -> "结果");

        CountingCallback counter = new CountingCallback();
        Agent agent = Agent.builder(llm, registry).callback(counter).build();
        agent.run("测试");

        // R5 核心：每次工具调用 onToolStart/onToolComplete 各只触发 1 次（修复前 ToolRegistry 会再触发一次）
        assertEquals("onToolStart 应只触发 1 次", 1, counter.toolStart);
        assertEquals("onToolComplete 应只触发 1 次", 1, counter.toolComplete);
        assertEquals(0, counter.toolError);
    }
}
