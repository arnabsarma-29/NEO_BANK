-- V1__init_transfer_schema.sql
-- NeoBank Transfer Service Initial Schema Migration
-- Database: neobank_transfer
-- Migrator Role: neobank_transfer_migrator
-- Runtime App Role: neobank_transfer_app

-- 1. Create transfers table
CREATE TABLE transfers (
    transfer_id VARCHAR(64) PRIMARY KEY,
    reference_number VARCHAR(64) NOT NULL UNIQUE,
    customer_id VARCHAR(64) NOT NULL,
    source_account_id VARCHAR(64) NOT NULL,
    destination_account_id VARCHAR(64),
    beneficiary_id VARCHAR(64),
    transfer_type VARCHAR(32) NOT NULL,
    amount NUMERIC(19, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    remarks VARCHAR(255),
    destination_name VARCHAR(128),
    destination_reference VARCHAR(128),
    status VARCHAR(32) NOT NULL,
    next_action VARCHAR(32) NOT NULL,
    challenge_id VARCHAR(64),
    status_reason_code VARCHAR(64),
    completed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT chk_transfers_amount CHECK (amount > 0)
);

CREATE INDEX idx_transfers_customer_id ON transfers(customer_id);
CREATE INDEX idx_transfers_status ON transfers(status);
CREATE INDEX idx_transfers_created_at ON transfers(created_at);

-- 2. Create transfer_idempotency_records table (ADR-008)
CREATE TABLE transfer_idempotency_records (
    id VARCHAR(64) PRIMARY KEY,
    customer_id VARCHAR(64) NOT NULL,
    operation VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    transfer_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_transfer_idempotency UNIQUE (customer_id, operation, idempotency_key)
);

CREATE INDEX idx_transfer_idempotency_transfer_id ON transfer_idempotency_records(transfer_id);

-- 3. Explicit least-privilege grants to runtime role (neobank_transfer_app)
GRANT SELECT, INSERT, UPDATE ON transfers TO neobank_transfer_app;
GRANT SELECT, INSERT, UPDATE ON transfer_idempotency_records TO neobank_transfer_app;
