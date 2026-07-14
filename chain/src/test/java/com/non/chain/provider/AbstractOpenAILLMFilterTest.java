package com.non.chain.provider;

import com.non.chain.Message;
import com.non.chain.tool.ToolCall;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * 应用层消息与 LLM 消息分层 — LLM 边界过滤（R2）。
 *
 * <p>直接测试 package-private 的 {@link AbstractOpenAILLM#filterLlmVisible(List)}，
 * 它是 buildMessageListParams 在送入 provider 之前的唯一过滤入口。</p>
 */
public class AbstractOpenAILLMFilterTest {

    @Test
    public void filtersOutNonLlmVisibleMessages() {
        List<Message> input = Arrays.asList(
                Message.system("系统提示"),
                Message.user("问题"),
                Message.note("status", "正在思考"),
                Message.assistant("回答"),
                Message.note("ui", "工具审核中")
        );

        List<Message> filtered = AbstractOpenAILLM.filterLlmVisible(input);

        assertEquals(3, filtered.size());
        assertEquals("system", filtered.get(0).role());
        assertEquals("user", filtered.get(1).role());
        assertEquals("assistant", filtered.get(2).role());
        // 不应包含任何 note
        for (Message m : filtered) {
            assertTrue("过滤后不应含 llmVisible=false 的消息", m.llmVisible());
        }
    }

    @Test
    public void preservesAllLlmVisibleMessages() {
        List<Message> input = Arrays.asList(
                Message.system("s"),
                Message.user("u"),
                Message.assistant("a")
        );
        assertEquals(3, AbstractOpenAILLM.filterLlmVisible(input).size());
    }

    @Test
    public void emptyInputReturnsEmpty() {
        assertTrue(AbstractOpenAILLM.filterLlmVisible(Arrays.asList()).isEmpty());
    }

    @Test
    public void allNonLlmVisibleReturnsEmpty() {
        List<Message> input = Arrays.asList(
                Message.note("status", "a"),
                Message.note("ui", "b")
        );
        assertTrue(AbstractOpenAILLM.filterLlmVisible(input).isEmpty());
    }

    @Test
    public void preservesOrder() {
        List<Message> input = Arrays.asList(
                Message.user("first"),
                Message.note("x", "hidden"),
                Message.assistant("second")
        );
        List<Message> filtered = AbstractOpenAILLM.filterLlmVisible(input);
        assertEquals("first", filtered.get(0).content());
        assertEquals("second", filtered.get(1).content());
    }

    @Test
    public void unsupportedModelKeepsOnlyFirstSystemAndConvertsLaterSystem() {
        List<Message> normalized = MessageNormalizer.normalizeForRequest(Arrays.asList(
                Message.system("角色"),
                Message.user("问题"),
                Message.system("技能指令")
        ), false);

        assertEquals(3, normalized.size());
        assertEquals("system", normalized.get(0).role());
        assertEquals("角色", normalized.get(0).content());
        assertEquals("user", normalized.get(2).role());
        assertEquals("[Framework System Instruction]\n技能指令", normalized.get(2).content());
    }

    @Test
    public void unsupportedModelConvertsAllSystemsWhenFirstVisibleIsNotSystem() {
        List<Message> normalized = MessageNormalizer.normalizeForRequest(Arrays.asList(
                Message.note("ui", "隐藏"),
                Message.user("问题"),
                Message.system("技能一"),
                Message.system("技能二")
        ), false);

        assertEquals(3, normalized.size());
        assertEquals("user", normalized.get(0).role());
        assertEquals("user", normalized.get(1).role());
        assertEquals("user", normalized.get(2).role());
        assertEquals("[Framework System Instruction]\n技能一", normalized.get(1).content());
        assertEquals("[Framework System Instruction]\n技能二", normalized.get(2).content());
    }

    @Test
    public void convertedSystemBetweenToolCallAndResultIsDeferred() {
        ToolCall call = new ToolCall("call-1", "lookup", "{}");
        List<Message> normalized = MessageNormalizer.normalizeForRequest(Arrays.asList(
                Message.system("角色"),
                Message.assistantWithToolCalls("", Collections.singletonList(call)),
                Message.system("技能指令"),
                Message.toolResult("call-1", "结果")
        ), false);

        assertEquals(4, normalized.size());
        assertEquals("assistant", normalized.get(1).role());
        assertEquals("tool", normalized.get(2).role());
        assertEquals("user", normalized.get(3).role());
        assertEquals("[Framework System Instruction]\n技能指令", normalized.get(3).content());
    }

    @Test
    public void systemNormalizationIsIdempotent() {
        List<Message> input = Arrays.asList(
                Message.system("角色"),
                Message.system("技能指令")
        );
        List<Message> once = MessageNormalizer.normalizeForRequest(input, false);
        List<Message> twice = MessageNormalizer.normalizeForRequest(once, false);

        assertEquals(once.size(), twice.size());
        for (int i = 0; i < once.size(); i++) {
            assertEquals(once.get(i).role(), twice.get(i).role());
            assertEquals(once.get(i).content(), twice.get(i).content());
        }
        assertEquals("system", input.get(1).role());
        assertEquals("技能指令", input.get(1).content());
    }

    @Test
    public void builtInLlmCapabilityCanBeConfiguredThroughInterface() {
        LLM llm = new OpenAICompatibleLLM("http://localhost:1/v1", "model");
        llm.supportsMultipleSystemMessages(false);

        assertFalse(llm.supportsMultipleSystemMessages());
    }
}
