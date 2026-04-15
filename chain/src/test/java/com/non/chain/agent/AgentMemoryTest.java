package com.non.chain.agent;

import com.non.chain.ChatResult;
import com.non.chain.Message;
import com.non.chain.memory.ChatMemory;
import com.non.chain.memory.InMemoryChatMemoryStore;
import com.non.chain.memory.MessageWindowChatMemory;
import com.non.chain.provider.LLM;
import com.non.chain.tool.ToolCall;
import com.non.chain.tool.ToolRegistry;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class AgentMemoryTest {

    /**
     * 可编程的 mock LLM，按预设顺序返回结果
     */
    static class MockLLM implements LLM {

        private final List<ChatResult> responses;
        private int callIndex = 0;
        private List<List<Message>> capturedMessages = new ArrayList<>();

        MockLLM(List<ChatResult> responses) {
            this.responses = responses;
        }

        @Override
        public ChatResult chat(String systemMessage, String userMessage, com.non.chain.OutputFormat outputFormat) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChatResult chat(List<Message> messages, com.non.chain.OutputFormat outputFormat) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChatResult chat(String systemMessage, String userMessage, List<com.non.chain.tool.Tool> tools, com.non.chain.OutputFormat outputFormat) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChatResult chat(List<Message> messages, List<com.non.chain.tool.Tool> tools, com.non.chain.OutputFormat outputFormat) {
            capturedMessages.add(new ArrayList<>(messages));
            return responses.get(callIndex++);
        }

        @Override
        public ChatResult streamChat(String systemMessage, String userMessage, com.non.chain.OutputFormat outputFormat, java.util.function.Consumer<com.non.chain.ChatChunk> callback) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChatResult streamChat(List<Message> messages, com.non.chain.OutputFormat outputFormat, java.util.function.Consumer<com.non.chain.ChatChunk> callback) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChatResult streamChat(String systemMessage, String userMessage, List<com.non.chain.tool.Tool> tools, com.non.chain.OutputFormat outputFormat, java.util.function.Consumer<com.non.chain.ChatChunk> callback) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChatResult streamChat(List<Message> messages, List<com.non.chain.tool.Tool> tools, com.non.chain.OutputFormat outputFormat, java.util.function.Consumer<com.non.chain.ChatChunk> callback) {
            throw new UnsupportedOperationException();
        }

        List<List<Message>> getCapturedMessages() {
            return capturedMessages;
        }
    }

    @Test
    public void testMemoryStoresConversationHistory() {
        MockLLM llm = new MockLLM(Collections.singletonList(
                new ChatResult("你好小明！", null)
        ));

        ChatMemory memory = MessageWindowChatMemory.builder()
                .maxMessages(10)
                .conversationId("test-user")
                .build();

        ToolRegistry registry = new ToolRegistry();
        Agent agent = Agent.builder(llm, registry)
                .memory(memory)
                .build();

        agent.run("我叫小明");
        assertEquals(2, memory.messages().size());
        assertEquals("user", memory.messages().get(0).role());
        assertEquals("我叫小明", memory.messages().get(0).content());
        assertEquals("assistant", memory.messages().get(1).role());
        assertEquals("你好小明！", memory.messages().get(1).content());
    }

    @Test
    public void testMultiTurnConversation() {
        List<ChatResult> responses = new ArrayList<>();
        responses.add(new ChatResult("你好小明！", null));
        responses.add(new ChatResult("你叫小明。", null));

        MockLLM llm = new MockLLM(responses);

        ChatMemory memory = MessageWindowChatMemory.builder()
                .maxMessages(10)
                .conversationId("test-user")
                .build();

        ToolRegistry registry = new ToolRegistry();
        Agent agent = Agent.builder(llm, registry)
                .memory(memory)
                .build();

        agent.run("我叫小明");
        agent.run("我叫什么名字？");

        // Memory 中应有 4 条消息
        assertEquals(4, memory.messages().size());

        // 第二次调用 LLM 时，消息应包含历史
        List<Message> secondCall = llm.getCapturedMessages().get(1);
        assertTrue(secondCall.size() >= 2); // 至少有 user + 历史消息
        // 找到第一条用户消息（历史中的）
        boolean hasHistory = secondCall.stream()
                .anyMatch(m -> "user".equals(m.role()) && "我叫小明".equals(m.content()));
        assertTrue("Second call should contain history", hasHistory);
    }

    @Test
    public void testRunWithMessageListDoesNotUseMemory() {
        MockLLM llm = new MockLLM(Collections.singletonList(
                new ChatResult("回复", null)
        ));

        ChatMemory memory = MessageWindowChatMemory.builder()
                .maxMessages(10)
                .conversationId("test-user")
                .build();

        ToolRegistry registry = new ToolRegistry();
        Agent agent = Agent.builder(llm, registry)
                .memory(memory)
                .build();

        // run(List<Message>) 不使用 Memory
        List<Message> inputMessages = new ArrayList<>();
        inputMessages.add(Message.user("外部管理的消息"));
        agent.run(inputMessages);

        // Memory 应该为空
        assertTrue(memory.messages().isEmpty());
    }

    @Test
    public void testMemoryWithSystemPrompt() {
        MockLLM llm = new MockLLM(Collections.singletonList(
                new ChatResult("好的", null)
        ));

        ChatMemory memory = MessageWindowChatMemory.builder()
                .maxMessages(10)
                .conversationId("test-user")
                .build();

        ToolRegistry registry = new ToolRegistry();
        Agent agent = Agent.builder(llm, registry)
                .systemPrompt("你是助手")
                .memory(memory)
                .build();

        agent.run("你好");

        // Memory 中不应包含 system 消息（system 由 Agent 管理）
        for (Message msg : memory.messages()) {
            assertNotEquals("system", msg.role());
        }

        // LLM 调用时应包含 system 消息
        List<Message> llmMessages = llm.getCapturedMessages().get(0);
        assertEquals("system", llmMessages.get(0).role());
        assertEquals("你是助手", llmMessages.get(0).content());
    }

    @Test
    public void testMemoryWithToolCalls() {
        ToolCall toolCall = new ToolCall("call-1", "greet", "{\"name\":\"小明\"}");
        List<ChatResult> responses = new ArrayList<>();
        responses.add(new ChatResult("", null, Collections.singletonList(toolCall)));
        responses.add(new ChatResult("你好小明！欢迎！", null));

        MockLLM llm = new MockLLM(responses);

        ChatMemory memory = MessageWindowChatMemory.builder()
                .maxMessages(10)
                .conversationId("test-user")
                .build();

        ToolRegistry registry = new ToolRegistry();
        registry.register("greet", "打招呼").handle(args -> "Hello, " + args.getString("name"));

        Agent agent = Agent.builder(llm, registry)
                .memory(memory)
                .build();

        agent.run("跟小明打招呼");

        // Memory 中应有：user + assistant(toolCalls) + tool_result + assistant(final)
        assertEquals(4, memory.messages().size());
        assertEquals("user", memory.messages().get(0).role());
        assertEquals("assistant", memory.messages().get(1).role());
        assertNotNull(memory.messages().get(1).toolCalls());
        assertEquals("tool", memory.messages().get(2).role());
        assertEquals("assistant", memory.messages().get(3).role());
    }

    @Test
    public void testNoMemoryBehaviorUnchanged() {
        MockLLM llm = new MockLLM(Collections.singletonList(
                new ChatResult("回复", null)
        ));

        ToolRegistry registry = new ToolRegistry();
        Agent agent = Agent.builder(llm, registry).build();

        ChatResult result = agent.run("你好");
        assertEquals("回复", result.content());

        // 消息列表应只包含 user + assistant
        List<Message> captured = llm.getCapturedMessages().get(0);
        assertEquals(1, captured.size()); // only user message
    }
}
