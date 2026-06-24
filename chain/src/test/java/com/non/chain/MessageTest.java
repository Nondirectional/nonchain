package com.non.chain;

import com.non.chain.tool.ToolCall;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

/**
 * 应用层消息与 LLM 消息分层 — Message 模型标记机制（R1）。
 */
public class MessageTest {

    @Test
    public void noteFactoryProducesNonLlmVisibleMessage() {
        Message msg = Message.note("status", "已读取文件 X");
        assertEquals("note", msg.role());
        assertEquals("已读取文件 X", msg.content());
        assertFalse("note 必须 llmVisible=false", msg.llmVisible());
        assertEquals("status", msg.kind());
    }

    @Test
    public void noteKindCanBeNull() {
        Message msg = Message.note(null, "无标签通知");
        assertFalse(msg.llmVisible());
        assertNull(msg.kind());
    }

    @Test
    public void existingFactoriesDefaultToLlmVisible() {
        // R6: 现有工厂产出的消息全部 LLM 可见，kind=null
        assertTrue("system 默认应 LLM 可见", Message.system("s").llmVisible());
        assertNull(Message.system("s").kind());

        assertTrue(Message.user("u").llmVisible());
        assertNull(Message.user("u").kind());

        assertTrue(Message.assistant("a").llmVisible());
        assertNull(Message.assistant("a").kind());

        Message withToolCalls = Message.assistantWithToolCalls("a", Collections.singletonList(
                new ToolCall("call-1", "f", "{}")));
        assertTrue(withToolCalls.llmVisible());
        assertNull(withToolCalls.kind());

        Message toolResult = Message.toolResult("call-1", "r");
        assertTrue(toolResult.llmVisible());
        assertNull(toolResult.kind());

        Message multimodal = Message.user(Arrays.asList(new TextPart("t")));
        assertTrue(multimodal.llmVisible());
        assertNull(multimodal.kind());
    }

    @Test
    public void legacyFiveArgOfRemainsBackwardCompatible() {
        // 旧 5 参 of(...) 仍可用，产出 llmVisible=true, kind=null
        Message msg = Message.of("user", "hi", null, null, null);
        assertTrue(msg.llmVisible());
        assertNull(msg.kind());
        assertEquals("user", msg.role());
    }

    @Test
    public void sevenArgOfPreservesAllFields() {
        Message msg = Message.of("note", "通知", null, null, null, false, "ui");
        assertEquals("note", msg.role());
        assertEquals("通知", msg.content());
        assertFalse(msg.llmVisible());
        assertEquals("ui", msg.kind());
    }
}
