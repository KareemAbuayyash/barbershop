CREATE TABLE management_accounts (
    id UUID PRIMARY KEY,
    username_normalized VARCHAR(64) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_management_accounts_username UNIQUE (username_normalized),
    CONSTRAINT chk_management_accounts_role CHECK (role = 'ROLE_OWNER'),
    CONSTRAINT uq_management_accounts_role UNIQUE (role)
);
