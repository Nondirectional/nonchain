package com.non.chain.mysql;

import com.non.chain.Message;
import com.non.chain.memory.ChatMemoryStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * MySQL 存储实现。
 *
 * <p>使用 JDBC + DataSource 将消息持久化到 MySQL。
 * 表结构参见 {@code chat_memory_message.sql}。</p>
 *
 * <pre>{@code
 * DataSource dataSource = ...; // HikariCP, Druid 等
 * ChatMemoryStore store = new MysqlChatMemoryStore(dataSource);
 *
 * ChatMemory memory = MessageWindowChatMemory.builder()
 *     .store(store)
 *     .maxMessages(20)
 *     .conversationId("user-1")
 *     .build();
 * }</pre>
 */
public class MysqlChatMemoryStore implements ChatMemoryStore {

    private static final String TABLE_NAME = "chat_memory_message";

    private static final String GET_MESSAGES =
            "SELECT role, content_json FROM " + TABLE_NAME +
                    " WHERE conversation_id = ? ORDER BY message_order ASC";

    private static final String DELETE_MESSAGES =
            "DELETE FROM " + TABLE_NAME + " WHERE conversation_id = ?";

    private static final String INSERT_MESSAGE =
            "INSERT INTO " + TABLE_NAME +
                    " (conversation_id, message_order, role, content_json) VALUES (?, ?, ?, ?)";

    private final DataSource dataSource;

    public MysqlChatMemoryStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public List<Message> getMessages(String conversationId) {
        List<Message> messages = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(GET_MESSAGES)) {
            ps.setString(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String contentJson = rs.getString("content_json");
                    if (contentJson != null) {
                        messages.add(MessageSerializer.deserialize(contentJson));
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("获取对话消息失败: " + conversationId, e);
        }
        return messages;
    }

    @Override
    public void updateMessages(String conversationId, List<Message> messages) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 删除旧消息
                try (PreparedStatement ps = conn.prepareStatement(DELETE_MESSAGES)) {
                    ps.setString(1, conversationId);
                    ps.executeUpdate();
                }

                // 批量插入新消息
                try (PreparedStatement ps = conn.prepareStatement(INSERT_MESSAGE)) {
                    for (int i = 0; i < messages.size(); i++) {
                        Message msg = messages.get(i);
                        ps.setString(1, conversationId);
                        ps.setInt(2, i);
                        ps.setString(3, msg.role());
                        ps.setString(4, MessageSerializer.serialize(msg));
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException("更新对话消息失败: " + conversationId, e);
        }
    }

    @Override
    public void deleteMessages(String conversationId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(DELETE_MESSAGES)) {
            ps.setString(1, conversationId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("删除对话消息失败: " + conversationId, e);
        }
    }
}
