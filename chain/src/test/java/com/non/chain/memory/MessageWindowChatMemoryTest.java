package com.non.chain.memory;

import com.non.chain.Message;
import com.non.chain.tool.ToolCall;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class MessageWindowChatMemoryTest {

    @Test
    public void testBasicAddAndGet() {
        ChatMemory memory = MessageWindowChatMemory.builder()
                .maxMessages(10)
                .conversationId("test-1")
                .build();

        memory.add(Message.user("你好"));
        memory.add(Message.assistant("你好！"));

        List<Message> messages = memory.messages();
        assertEquals(2, messages.size());
        assertEquals("user", messages.get(0).role());
        assertEquals("assistant", messages.get(1).role());
    }

    @Test
    public void testAddAll() {
        ChatMemory memory = MessageWindowChatMemory.builder()
                .maxMessages(10)
                .conversationId("test-2")
                .build();

        memory.addAll(Arrays.asList(
                Message.user("问题1"),
                Message.assistant("回答1"),
                Message.user("问题2"),
                Message.assistant("回答2")
        ));

        assertEquals(4, memory.messages().size());
    }

    @Test
    public void testClear() {
        ChatMemory memory = MessageWindowChatMemory.builder()
                .maxMessages(10)
                .conversationId("test-3")
                .build();

        memory.add(Message.user("你好"));
        assertFalse(memory.messages().isEmpty());

        memory.clear();
        assertTrue(memory.messages().isEmpty());
    }

    @Test
    public void testSlidingWindow() {
        ChatMemory memory = MessageWindowChatMemory.builder()
                .maxMessages(4)
                .conversationId("test-4")
                .build();

        // 添加 6 条消息，应只保留最后 4 条
        for (int i = 1; i <= 6; i++) {
            memory.add(Message.user("问题" + i));
        }

        List<Message> messages = memory.messages();
        assertEquals(4, messages.size());
        assertEquals("问题3", messages.get(0).content());
        assertEquals("问题6", messages.get(3).content());
    }

    @Test
    public void testSystemMessageProtection() {
        ChatMemory memory = MessageWindowChatMemory.builder()
                .maxMessages(3)
                .conversationId("test-5")
                .build();

        memory.add(Message.system("你是一个助手"));
        memory.add(Message.user("问题1"));
        memory.add(Message.assistant("回答1"));
        memory.add(Message.user("问题2"));
        memory.add(Message.assistant("回答2"));

        List<Message> messages = memory.messages();
        // maxMessages=3, system 不算在内的话应该保留 system + 最近 2 条
        assertEquals(3, messages.size());
        assertEquals("system", messages.get(0).role());
        assertEquals("你是一个助手", messages.get(0).content());
    }

    @Test
    public void testToolMessagePairProtection() {
        ChatMemory memory = MessageWindowChatMemory.builder()
                .maxMessages(4)
                .conversationId("test-6")
                .build();

        ToolCall toolCall = new ToolCall("call-1", "get_weather", "{\"city\":\"北京\"}");
        memory.add(Message.user("北京天气怎么样？"));
        memory.add(Message.assistantWithToolCalls(null, Arrays.asList(toolCall)));
        memory.add(Message.toolResult("call-1", "晴天，25度"));
        memory.add(Message.assistant("北京今天晴天，25度"));
        memory.add(Message.user("上海呢？"));

        List<Message> messages = memory.messages();
        assertEquals(4, messages.size());

        // 应该从最老的开始裁剪，但 assistant + tool pair 要一起删除
        // user(北京天气) + assistantWithToolCalls + toolResult + assistant(北京今天) + user(上海)
        // 裁剪后: assistantWithToolCalls + toolResult + assistant(北京今天) + user(上海)
        // 或: 如果裁剪 assistantWithToolCalls，连带 toolResult 一起
        // -> toolResult + assistant(北京今天) + user(上海)... 不对
        // -> 裁剪 user(北京天气), 剩余 4 条刚好

        assertEquals("assistant", messages.get(0).role()); // assistantWithToolCalls
        assertTrue(messages.get(0).toolCalls() != null && !messages.get(0).toolCalls().isEmpty());
        assertEquals("tool", messages.get(1).role());
        assertEquals("assistant", messages.get(2).role());
        assertEquals("user", messages.get(3).role());
    }

    @Test
    public void testConversationIdIsolation() {
        ChatMemoryStore store = new InMemoryChatMemoryStore();

        ChatMemory memory1 = MessageWindowChatMemory.builder()
                .store(store)
                .conversationId("user-A")
                .maxMessages(10)
                .build();

        ChatMemory memory2 = MessageWindowChatMemory.builder()
                .store(store)
                .conversationId("user-B")
                .maxMessages(10)
                .build();

        memory1.add(Message.user("用户A的消息"));
        memory2.add(Message.user("用户B的消息"));

        assertEquals(1, memory1.messages().size());
        assertEquals(1, memory2.messages().size());
        assertEquals("用户A的消息", memory1.messages().get(0).content());
        assertEquals("用户B的消息", memory2.messages().get(0).content());
    }

    @Test
    public void testDefaultConversationId() {
        ChatMemory memory = MessageWindowChatMemory.builder()
                .maxMessages(10)
                .build();

        assertEquals("default", memory.conversationId());
    }

    @Test
    public void testDefaultStore() {
        // 不提供 store，应该自动创建 InMemoryChatMemoryStore
        ChatMemory memory = MessageWindowChatMemory.builder()
                .maxMessages(10)
                .conversationId("test")
                .build();

        memory.add(Message.user("test"));
        assertEquals(1, memory.messages().size());
    }

    @Test
    public void testMessagesReturnedIsCopy() {
        ChatMemory memory = MessageWindowChatMemory.builder()
                .maxMessages(10)
                .conversationId("test")
                .build();

        memory.add(Message.user("原始消息"));
        List<Message> messages = memory.messages();
        messages.add(Message.assistant("不应出现的消息"));

        // 再次获取应该不受影响
        assertEquals(1, memory.messages().size());
    }

    @Test
    public void testReversedToolAssistantPairTrimmedTogether() {
        // maxMessages=3，5 条消息必定触发裁剪
        // 异常顺序 [tool, assistant(toolCalls)] 应被识别为一组一起删除
        ChatMemory memory = MessageWindowChatMemory.builder()
                .maxMessages(3)
                .conversationId("test-reversed-pair")
                .build();

        ToolCall toolCall = new ToolCall("call-1", "get_weather", "{\"city\":\"北京\"}");
        // 异常顺序：tool 在 assistant(toolCalls) 之前
        memory.add(Message.toolResult("call-1", "晴天，25度"));
        memory.add(Message.assistantWithToolCalls(null, Arrays.asList(toolCall)));
        memory.add(Message.user("上海呢？"));
        memory.add(Message.assistant("上海多云"));
        memory.add(Message.user("广州呢？"));

        List<Message> messages = memory.messages();
        assertEquals(3, messages.size());

        // 核心断言：不会留下孤立的 assistant(toolCalls)
        for (int i = 0; i < messages.size(); i++) {
            if ("assistant".equals(messages.get(i).role())
                    && messages.get(i).toolCalls() != null
                    && !messages.get(i).toolCalls().isEmpty()) {
                boolean hasToolResult = false;
                for (int j = 0; j < messages.size(); j++) {
                    if ("tool".equals(messages.get(j).role())) {
                        hasToolResult = true;
                        break;
                    }
                }
                assertTrue("assistant(toolCalls) must not exist without matching tool",
                        hasToolResult);
            }
        }
    }

    @Test
    public void testTrulyOrphanToolMessageCanBeDeleted() {
        ChatMemory memory = MessageWindowChatMemory.builder()
                .maxMessages(2)
                .conversationId("test-orphan-tool")
                .build();

        // 真正孤立的 tool（没有匹配的 assistant(toolCalls)）
        memory.add(Message.toolResult("call-unknown", "过期结果"));
        memory.add(Message.user("问题1"));
        memory.add(Message.assistant("回答1"));
        memory.add(Message.user("问题2"));
        memory.add(Message.assistant("回答2"));

        List<Message> messages = memory.messages();
        assertEquals(2, messages.size());
        // 孤立的 tool 应该被最先删除
        for (Message msg : messages) {
            assertFalse("Truly orphan tool should be trimmed",
                    "tool".equals(msg.role()) && "call-unknown".equals(msg.toolCallId()));
        }
    }

    // ---- 应用层消息分层（R4 裁剪交互）----

    @Test
    public void testNoteDoesNotConsumeWindowBudget() {
        // maxMessages=2：3 条 LLM 可见消息触发裁剪，但 note 不占预算
        ChatMemory memory = MessageWindowChatMemory.builder()
                .maxMessages(2)
                .conversationId("test-note-budget")
                .build();

        memory.add(Message.user("问题1"));
        memory.add(Message.note("status", "正在思考"));   // 不占预算
        memory.add(Message.assistant("回答1"));
        memory.add(Message.user("问题2"));

        List<Message> messages = memory.messages();
        // LLM 可见消息 3 条 → 裁剪到 2 条（删 user 问题1）；note 原位保留
        long llmVisible = messages.stream().filter(Message::llmVisible).count();
        assertEquals("LLM 可见消息应裁剪到 maxMessages", 2, llmVisible);
        assertTrue("note 必须原位保留", messages.stream().anyMatch(m -> "note".equals(m.role())));
    }

    @Test
    public void testNotePreservedInPlaceAfterTrim() {
        // 验证 note 不被删除、不前移
        ChatMemory memory = MessageWindowChatMemory.builder()
                .maxMessages(2)
                .conversationId("test-note-inplace")
                .build();

        memory.add(Message.user("u1"));
        memory.add(Message.note("ui", "标记1"));
        memory.add(Message.assistant("a1"));
        memory.add(Message.user("u2"));
        memory.add(Message.assistant("a2"));

        List<Message> messages = memory.messages();
        // note 必须仍在列表中
        Message note = messages.stream()
                .filter(m -> "note".equals(m.role())).findFirst().orElse(null);
        assertNotNull("note 必须保留", note);
        assertEquals("标记1", note.content());
    }

    @Test
    public void testNoteDoesNotBreakToolPairProtection() {
        // 危险场景：note 插在 assistant(toolCalls) 与 tool result 之间
        // 裁剪绝不能破坏 tool 配对保护
        ChatMemory memory = MessageWindowChatMemory.builder()
                .maxMessages(3)
                .conversationId("test-note-tool-pair")
                .build();

        ToolCall toolCall = new ToolCall("call-1", "get_weather", "{\"city\":\"北京\"}");
        memory.add(Message.user("北京天气？"));
        memory.add(Message.assistantWithToolCalls(null, Arrays.asList(toolCall)));
        memory.add(Message.note("status", "正在查询天气"));  // 插在中间
        memory.add(Message.toolResult("call-1", "晴天"));
        memory.add(Message.assistant("北京晴天"));
        memory.add(Message.user("上海呢？"));

        List<Message> messages = memory.messages();
        // 核心断言：若 assistant(toolCalls) 留下，必有匹配 tool result；反之亦然
        boolean hasAssistantWithToolCalls = messages.stream()
                .anyMatch(m -> "assistant".equals(m.role())
                        && m.toolCalls() != null && !m.toolCalls().isEmpty());
        boolean hasToolResult = messages.stream()
                .anyMatch(m -> "tool".equals(m.role()));
        if (hasAssistantWithToolCalls) {
            assertTrue("assistant(toolCalls) 不应孤立留下", hasToolResult);
        }
        // note 必须保留
        assertTrue("note 必须原位保留", messages.stream().anyMatch(m -> "note".equals(m.role())));
    }
}
