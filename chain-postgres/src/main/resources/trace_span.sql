CREATE TABLE IF NOT EXISTS trace_span (
    id BIGSERIAL PRIMARY KEY,
    runtime_id VARCHAR(64) NOT NULL,
    span_id VARCHAR(64) NOT NULL UNIQUE,
    parent_span_id VARCHAR(64),
    type VARCHAR(32) NOT NULL,
    name VARCHAR(255) NOT NULL,
    start_time_ms BIGINT NOT NULL,
    end_time_ms BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    error TEXT,
    attributes_json TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_trace_runtime ON trace_span (runtime_id);
