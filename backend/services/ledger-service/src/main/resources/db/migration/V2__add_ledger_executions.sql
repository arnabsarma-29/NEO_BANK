-- V2__add_ledger_executions.sql
-- Ledger Service Durable Execution Outcomes Store (Phase 2 Stage 1)
-- PostgreSQL 17 & H2 (PostgreSQL compatibility mode) compatible

CREATE TABLE IF NOT EXISTS ledger_executions (
    transfer_id VARCHAR(64) PRIMARY KEY,
    status VARCHAR(20) NOT NULL, -- 'PROCESSING', 'COMMITTED', 'REJECTED'
    source_account_id VARCHAR(64) NOT NULL,
    destination_account_id VARCHAR(64),
    transfer_type VARCHAR(20) NOT NULL,
    amount NUMERIC(19, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    reference_number VARCHAR(64),
    ledger_transaction_id VARCHAR(64),
    status_reason_code VARCHAR(64),
    failure_message VARCHAR(255),
    financial_fingerprint VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 1
);

CREATE INDEX IF NOT EXISTS idx_ledger_exec_source ON ledger_executions(source_account_id);
CREATE INDEX IF NOT EXISTS idx_ledger_exec_status ON ledger_executions(status);

-- Runtime application role least-privilege permissions
GRANT SELECT, INSERT, UPDATE ON ledger_executions TO neobank_ledger_app;
