CREATE TABLE idempotency_keys (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    response_body MEDIUMTEXT NOT NULL,
    http_status INT NOT NULL,
    expired_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NULL,
    CONSTRAINT uk_idempotency_key UNIQUE (idempotency_key)
);

CREATE INDEX idx_idempotency_key ON idempotency_keys (idempotency_key);
CREATE INDEX idx_expired_at ON idempotency_keys (expired_at);

