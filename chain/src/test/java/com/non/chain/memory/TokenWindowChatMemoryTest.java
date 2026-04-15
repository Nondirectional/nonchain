package com.non.chain.memory;

import com.non.chain.Message;
import com.non.chain.tool.ToolCall;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class TokenWindowChatMemoryTest {

    @Test
    public void testBasicAddAndGet() {
        ChatMemory memory = TokenWindowChatMemory.builder()
                .maxTokens(4096)
                .conversationId("test-1")
                .build();

        memory.add(Message.user("你好"));
        memory.add(Message.assistant("你好！"));

        assertEquals(2, memory.messages().size());
    }

    @Test
    public void testTokenPruning() {
        // 设置很小的 token 限制
        ChatMemory memory = TokenWindowChatMemory.builder()
                .tokenizer(JtokkitTokenizer.defaults())
                .maxTokens(30)
                .conversationId("test-2")
                .build();

        // 添加多条消息直到超出限制
        for (int i = 1; i <= 10; i++) {
            memory.add(Message.user("这是第" + i + "条消息用于测试token限制裁剪功能"));
        }

        List<Message> messages = memory.messages();
        // 消息应该被裁剪，总 token 数 <= maxTokens
        Tokenizer tokenizer = JtokkitTokenizer.defaults();
        int totalTokens = tokenizer.estimateTokenCount(messages);
        assertTrue("Total tokens should be <= maxTokens, but was " + totalTokens,
                totalTokens <= 30);
        // 应该保留最近的消息
        assertTrue(messages.get(messages.size() - 1).content().contains("第10条"));
    }

    @Test
    public void testSystemMessageProtection() {
        ChatMemory memory = TokenWindowChatMemory.builder()
                .tokenizer(JtokkitTokenizer.defaults())
                .maxTokens(30)
                .conversationId("test-3")
                .build();

        memory.add(Message.system("你是一个AI助手"));
        for (int i = 1; i <= 10; i++) {
            memory.add(Message.user("这是第" + i + "条测试消息"));
        }

        List<Message> messages = memory.messages();
        // SystemMessage 应该始终在首位
        assertEquals("system", messages.get(0).role());
        assertEquals("你是一个AI助手", messages.get(0).content());
    }

    @Test
    public void testToolMessagePairProtection() {
        ChatMemory memory = TokenWindowChatMemory.builder()
                .tokenizer(JtokkitTokenizer.defaults())
                .maxTokens(100)
                .conversationId("test-4")
                .build();

        ToolCall toolCall = new ToolCall("call-1", "get_weather", "{\"city\":\"北京\"}");

        // 添加足够多的消息触发裁剪
        memory.add(Message.user("第一条问题消息用于触发裁剪"));
        memory.add(Message.assistantWithToolCalls(null, Arrays.asList(toolCall)));
        memory.add(Message.toolResult("call-1", "晴天二十五度"));
        memory.add(Message.assistant("北京今天晴天，25度"));
        memory.add(Message.user("最新的问题"));

        List<Message> messages = memory.messages();
        // 验证没有孤立的 tool 消息
        for (int i = 0; i < messages.size(); i++) {
            if ("tool".equals(messages.get(i).role())) {
                // tool 消息前面必须是带 toolCalls 的 assistant 消息
                assertTrue("Tool message must be preceded by assistant with toolCalls",
                        i > 0 && "assistant".equals(messages.get(i - 1).role())
                                && messages.get(i - 1).toolCalls() != null);
            }
        }
    }

    @Test
    public void testClear() {
        ChatMemory memory = TokenWindowChatMemory.builder()
                .maxTokens(4096)
                .conversationId("test-5")
                .build();

        memory.add(Message.user("你好"));
        assertFalse(memory.messages().isEmpty());

        memory.clear();
        assertTrue(memory.messages().isEmpty());
    }

    @Test
    public void testAddAll() {
        ChatMemory memory = TokenWindowChatMemory.builder()
                .maxTokens(4096)
                .conversationId("test-6")
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
    public void testConversationIsolation() {
        ChatMemoryStore store = new InMemoryChatMemoryStore();

        ChatMemory memory1 = TokenWindowChatMemory.builder()
                .store(store)
                .maxTokens(4096)
                .conversationId("user-A")
                .build();

        ChatMemory memory2 = TokenWindowChatMemory.builder()
                .store(store)
                .maxTokens(4096)
                .conversationId("user-B")
                .build();

        memory1.add(Message.user("用户A的消息"));
        memory2.add(Message.user("用户B的消息"));

        assertEquals("用户A的消息", memory1.messages().get(0).content());
        assertEquals("用户B的消息", memory2.messages().get(0).content());
    }

    @Test
    public void testDefaultTokenizer() {
        // 不提供 tokenizer，应该使用默认的 JtokkitTokenizer
        ChatMemory memory = TokenWindowChatMemory.builder()
                .maxTokens(4096)
                .conversationId("test")
                .build();

        memory.add(Message.user("测试默认 tokenizer"));
        assertEquals(1, memory.messages().size());
    }

    @Test
    public void testWithinLimitNoTrim() {
        // maxTokens 足够大，不应裁剪
        ChatMemory memory = TokenWindowChatMemory.builder()
                .maxTokens(10000)
                .conversationId("test")
                .build();

        memory.add(Message.user("你好"));
        memory.add(Message.assistant("你好！"));

        assertEquals(2, memory.messages().size());
    }
}
