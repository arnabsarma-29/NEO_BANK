-- V1__init_account_schema.sql
-- Account Service Initial Schema
-- PostgreSQL 17 & H2 (PostgreSQL compatibility mode) compatible

CREATE TABLE IF NOT EXISTS accounts (
    id VARCHAR(64) PRIMARY KEY,
    account_number VARCHAR(32) NOT NULL UNIQUE,
    customer_id VARCHAR(64) NOT NULL,
    account_type VARCHAR(20) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    balance NUMERIC(19, 4) NOT NULL DEFAULT 0.0000,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_account_balance CHECK (balance >= 0)
);

CREATE INDEX IF NOT EXISTS idx_accounts_customer_id ON accounts(customer_id);
CREATE INDEX IF NOT EXISTS idx_accounts_status ON accounts(status);

CREATE TABLE IF NOT EXISTS account_transactions (
    id VARCHAR(64) PRIMARY KEY,
    account_id VARCHAR(64) NOT NULL REFERENCES accounts(id),
    transaction_id VARCHAR(64) NOT NULL,
    transfer_id VARCHAR(64) NOT NULL,
    entry_type VARCHAR(10) NOT NULL,
    amount NUMERIC(19, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    transfer_type VARCHAR(20) NOT NULL,
    reference_number VARCHAR(64) NOT NULL,
    remarks VARCHAR(255),
    destination_name VARCHAR(128),
    destination_reference VARCHAR(128),
    transaction_timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_account_tx_account_time ON account_transactions(account_id, transaction_timestamp DESC);
CREATE INDEX IF NOT EXISTS idx_account_tx_transfer_id ON account_transactions(transfer_id);
CREATE INDEX IF NOT EXISTS idx_account_tx_tx_id ON account_transactions(transaction_id);

CREATE TABLE IF NOT EXISTS processed_events (
    event_id VARCHAR(64) PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Explicit runtime permissions for least-privilege runtime role (neobank_account_app)
GRANT SELECT, INSERT, UPDATE ON accounts TO neobank_account_app;
GRANT SELECT, INSERT ON account_transactions TO neobank_account_app;
GRANT SELECT, INSERT, UPDATE ON processed_events TO neobank_account_app;
