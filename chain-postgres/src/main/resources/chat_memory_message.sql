CREATE TABLE IF NOT EXISTS chat_memory_message (
    id BIGSERIAL PRIMARY KEY,
    conversation_id VARCHAR(255) NOT NULL,
    message_order INT NOT NULL,
    role VARCHAR(20) NOT NULL,
    content_json TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_conv_order ON chat_memory_message (conversation_id, message_order);
