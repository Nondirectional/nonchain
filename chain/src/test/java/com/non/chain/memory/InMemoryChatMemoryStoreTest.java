package com.non.chain.memory;

import com.non.chain.Message;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class InMemoryChatMemoryStoreTest {

    @Test
    public void testGetMessagesEmpty() {
        ChatMemoryStore store = new InMemoryChatMemoryStore();
        List<Message> messages = store.getMessages("nonexistent");
        assertTrue(messages.isEmpty());
    }

    @Test
    public void testUpdateAndGetMessages() {
        ChatMemoryStore store = new InMemoryChatMemoryStore();
        store.updateMessages("conv-1", Arrays.asList(
                Message.user("你好"),
                Message.assistant("你好！")
        ));

        List<Message> messages = store.getMessages("conv-1");
        assertEquals(2, messages.size());
    }

    @Test
    public void testUpdateReplacesMessages() {
        ChatMemoryStore store = new InMemoryChatMemoryStore();
        store.updateMessages("conv-1", Arrays.asList(Message.user("旧消息")));
        store.updateMessages("conv-1", Arrays.asList(Message.user("新消息")));

        List<Message> messages = store.getMessages("conv-1");
        assertEquals(1, messages.size());
        assertEquals("新消息", messages.get(0).content());
    }

    @Test
    public void testDeleteMessages() {
        ChatMemoryStore store = new InMemoryChatMemoryStore();
        store.updateMessages("conv-1", Arrays.asList(Message.user("消息")));
        store.deleteMessages("conv-1");

        assertTrue(store.getMessages("conv-1").isEmpty());
    }

    @Test
    public void testConversationIsolation() {
        ChatMemoryStore store = new InMemoryChatMemoryStore();
        store.updateMessages("conv-A", Arrays.asList(Message.user("A的消息")));
        store.updateMessages("conv-B", Arrays.asList(Message.user("B的消息")));

        assertEquals(1, store.getMessages("conv-A").size());
        assertEquals(1, store.getMessages("conv-B").size());
        assertEquals("A的消息", store.getMessages("conv-A").get(0).content());
        assertEquals("B的消息", store.getMessages("conv-B").get(0).content());
    }

    @Test
    public void testReturnedMessagesAreCopies() {
        ChatMemoryStore store = new InMemoryChatMemoryStore();
        store.updateMessages("conv-1", Arrays.asList(Message.user("原始")));

        List<Message> messages = store.getMessages("conv-1");
        messages.add(Message.assistant("额外的"));

        // store 中的数据不受影响
        assertEquals(1, store.getMessages("conv-1").size());
    }
}
