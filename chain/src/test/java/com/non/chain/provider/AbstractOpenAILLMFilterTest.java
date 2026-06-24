package com.non.chain.provider;

import com.non.chain.Message;
import org.junit.Test;

import java.util.Arrays;
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
}
