-- 베이스 스키마: users, account, transaction, audit_log, refresh_tokens
-- Hibernate ddl-auto=update 시절에 생성된 스키마와 동등하게 정의한다.
-- 컬럼 타입은 Hibernate 6.x 기본 매핑(LocalDateTime → datetime(6), boolean → bit) 기준.

CREATE TABLE users (
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    email       VARCHAR(100) NOT NULL,
    password    VARCHAR(255) NOT NULL,
    name        VARCHAR(50)  NOT NULL,
    created_at  DATETIME(6),
    updated_at  DATETIME(6),
    CONSTRAINT uk_users_email UNIQUE (email)
);

CREATE TABLE account (
    id              BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    account_number  VARCHAR(20)   NOT NULL,
    owner_name      VARCHAR(255)  NOT NULL,
    user_id         BIGINT        NOT NULL,
    balance         DECIMAL(19,2) NOT NULL,
    status          VARCHAR(20)   NOT NULL,
    created_at      DATETIME(6),
    updated_at      DATETIME(6),
    CONSTRAINT uk_account_number UNIQUE (account_number),
    CONSTRAINT fk_account_user   FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE TABLE transaction (
    id                          BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    account_id                  BIGINT        NOT NULL,
    amount                      DECIMAL(19,2) NOT NULL,
    balance_after_transaction   DECIMAL(19,2) NOT NULL,
    type                        VARCHAR(20)   NOT NULL,
    description                 VARCHAR(255),
    created_at                  DATETIME(6),
    updated_at                  DATETIME(6),
    CONSTRAINT fk_transaction_account FOREIGN KEY (account_id) REFERENCES account(id),
    INDEX idx_tx_acc_type_createdat (account_id, type, created_at)
);

CREATE TABLE audit_log (
    id              BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT,
    action          VARCHAR(255)  NOT NULL,
    account_number  VARCHAR(255),
    amount          DECIMAL(19,2),
    ip_address      VARCHAR(255),
    created_at      DATETIME(6),
    updated_at      DATETIME(6)
);

CREATE TABLE refresh_tokens (
    id          BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    token       VARCHAR(36) NOT NULL,
    user_id     BIGINT      NOT NULL,
    expires_at  DATETIME(6) NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    used        BIT(1)      NOT NULL,
    CONSTRAINT uk_refresh_tokens_token UNIQUE (token)
);
