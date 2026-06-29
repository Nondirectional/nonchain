CREATE TABLE IF NOT EXISTS trace_span (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    runtime_id VARCHAR(64) NOT NULL,
    span_id VARCHAR(64) NOT NULL,
    parent_span_id VARCHAR(64),
    type VARCHAR(32) NOT NULL,
    name VARCHAR(255) NOT NULL,
    start_time_ms BIGINT NOT NULL,
    end_time_ms BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    error TEXT,
    attributes_json MEDIUMTEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_trace_runtime (runtime_id),
    UNIQUE KEY uk_trace_span_id (span_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
