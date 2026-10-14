-- V1__init_ledger_schema.sql
-- Ledger Service Initial Schema
-- PostgreSQL 17 & H2 (PostgreSQL compatibility mode) compatible

CREATE TABLE IF NOT EXISTS ledger_accounts (
    account_id VARCHAR(64) PRIMARY KEY,
    balance NUMERIC(19, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_ledger_account_balance CHECK (balance >= 0)
);

CREATE TABLE IF NOT EXISTS ledger_transactions (
    id VARCHAR(64) PRIMARY KEY,
    transfer_id VARCHAR(64) NOT NULL,
    source_account_id VARCHAR(64) NOT NULL,
    destination_account_id VARCHAR(64),
    transfer_type VARCHAR(20) NOT NULL,
    amount NUMERIC(19, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'SUCCESS',
    reference_number VARCHAR(64) NOT NULL,
    remarks VARCHAR(255),
    destination_name VARCHAR(128),
    destination_reference VARCHAR(128),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_ledger_tx_transfer_id UNIQUE (transfer_id)
);

CREATE INDEX IF NOT EXISTS idx_ledger_tx_source ON ledger_transactions(source_account_id);
CREATE INDEX IF NOT EXISTS idx_ledger_tx_destination ON ledger_transactions(destination_account_id);
CREATE INDEX IF NOT EXISTS idx_ledger_tx_ref ON ledger_transactions(reference_number);

CREATE TABLE IF NOT EXISTS ledger_entries (
    id VARCHAR(64) PRIMARY KEY,
    transaction_id VARCHAR(64) NOT NULL REFERENCES ledger_transactions(id),
    account_id VARCHAR(64) NOT NULL,
    entry_type VARCHAR(10) NOT NULL,
    amount NUMERIC(19, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ledger_entries_tx ON ledger_entries(transaction_id);
CREATE INDEX IF NOT EXISTS idx_ledger_entries_account ON ledger_entries(account_id);

CREATE TABLE IF NOT EXISTS outbox_events (
    id VARCHAR(64) PRIMARY KEY,
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_outbox_events_status_created ON outbox_events(status, created_at);

-- Explicit runtime permissions for least-privilege runtime role (neobank_ledger_app)
GRANT SELECT, INSERT, UPDATE ON ledger_accounts TO neobank_ledger_app;
GRANT SELECT, INSERT ON ledger_transactions TO neobank_ledger_app;
GRANT SELECT, INSERT ON ledger_entries TO neobank_ledger_app;
GRANT SELECT, INSERT, UPDATE ON outbox_events TO neobank_ledger_app;
