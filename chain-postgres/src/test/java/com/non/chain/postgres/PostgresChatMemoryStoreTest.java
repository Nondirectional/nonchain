package com.non.chain.postgres;

import com.non.chain.Message;
import com.non.chain.memory.ChatMemoryStore;
import com.non.chain.tool.ToolCall;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class PostgresChatMemoryStoreTest {

    private DataSource dataSource;
    private ChatMemoryStore store;

    @Before
    public void setUp() throws Exception {
        dataSource = createTestDataSource();
        createTable(dataSource);
        store = new PostgresChatMemoryStore(dataSource);
    }

    @After
    public void tearDown() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS chat_memory_message");
        }
    }

    @Test
    public void testGetMessagesEmpty() {
        List<Message> messages = store.getMessages("nonexistent");
        assertTrue(messages.isEmpty());
    }

    @Test
    public void testUpdateAndGetMessages() {
        store.updateMessages("conv-1", Arrays.asList(
                Message.user("你好"),
                Message.assistant("你好！")
        ));

        List<Message> messages = store.getMessages("conv-1");
        assertEquals(2, messages.size());
        assertEquals("user", messages.get(0).role());
        assertEquals("你好", messages.get(0).content());
        assertEquals("assistant", messages.get(1).role());
        assertEquals("你好！", messages.get(1).content());
    }

    @Test
    public void testUpdateReplacesMessages() {
        store.updateMessages("conv-1", Arrays.asList(Message.user("旧消息")));
        store.updateMessages("conv-1", Arrays.asList(Message.user("新消息")));

        List<Message> messages = store.getMessages("conv-1");
        assertEquals(1, messages.size());
        assertEquals("新消息", messages.get(0).content());
    }

    @Test
    public void testDeleteMessages() {
        store.updateMessages("conv-1", Arrays.asList(Message.user("消息")));
        store.deleteMessages("conv-1");

        assertTrue(store.getMessages("conv-1").isEmpty());
    }

    @Test
    public void testConversationIsolation() {
        store.updateMessages("conv-A", Arrays.asList(Message.user("A的消息")));
        store.updateMessages("conv-B", Arrays.asList(Message.user("B的消息")));

        assertEquals(1, store.getMessages("conv-A").size());
        assertEquals(1, store.getMessages("conv-B").size());
        assertEquals("A的消息", store.getMessages("conv-A").get(0).content());
        assertEquals("B的消息", store.getMessages("conv-B").get(0).content());
    }

    @Test
    public void testToolCallsRoundTrip() {
        ToolCall toolCall = new ToolCall("call-1", "get_weather", "{\"city\":\"北京\"}");
        store.updateMessages("conv-1", Arrays.asList(
                Message.user("北京天气"),
                Message.assistantWithToolCalls(null, Arrays.asList(toolCall)),
                Message.toolResult("call-1", "晴天"),
                Message.assistant("北京今天晴天")
        ));

        List<Message> messages = store.getMessages("conv-1");
        assertEquals(4, messages.size());

        Message assistantMsg = messages.get(1);
        assertEquals("assistant", assistantMsg.role());
        assertNotNull(assistantMsg.toolCalls());
        assertEquals(1, assistantMsg.toolCalls().size());
        assertEquals("get_weather", assistantMsg.toolCalls().get(0).name());

        assertEquals("tool", messages.get(2).role());
        assertEquals("call-1", messages.get(2).toolCallId());
    }

    @Test
    public void testSystemMessageRoundTrip() {
        store.updateMessages("conv-1", Arrays.asList(
                Message.system("你是助手"),
                Message.user("你好")
        ));

        List<Message> messages = store.getMessages("conv-1");
        assertEquals(2, messages.size());
        assertEquals("system", messages.get(0).role());
        assertEquals("你是助手", messages.get(0).content());
    }

    @Test
    public void testMessageOrderPreserved() {
        store.updateMessages("conv-1", Arrays.asList(
                Message.user("第1条"),
                Message.assistant("第1回复"),
                Message.user("第2条"),
                Message.assistant("第2回复"),
                Message.user("第3条")
        ));

        List<Message> messages = store.getMessages("conv-1");
        assertEquals(5, messages.size());
        assertEquals("第1条", messages.get(0).content());
        assertEquals("第1回复", messages.get(1).content());
        assertEquals("第3条", messages.get(4).content());
    }

    private static DataSource createTestDataSource() {
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }

    private static void createTable(DataSource dataSource) throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS chat_memory_message ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                    + "conversation_id VARCHAR(255) NOT NULL, "
                    + "message_order INT NOT NULL, "
                    + "role VARCHAR(20) NOT NULL, "
                    + "content_json TEXT, "
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                    + ")");
        }
    }
}
