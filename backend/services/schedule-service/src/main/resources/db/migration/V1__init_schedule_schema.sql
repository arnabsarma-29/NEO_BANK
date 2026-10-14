-- V1__init_schedule_schema.sql
-- NeoBank Schedule Service Initial Schema Migration
-- Database: neobank_schedule
-- Migrator Role: neobank_schedule_migrator
-- Runtime App Role: neobank_schedule_app

-- 1. Create scheduled_transfers table
CREATE TABLE scheduled_transfers (
    schedule_id VARCHAR(64) PRIMARY KEY,
    customer_id VARCHAR(64) NOT NULL,
    source_account_id VARCHAR(64) NOT NULL,
    destination_account_id VARCHAR(64),
    beneficiary_id VARCHAR(64),
    transfer_type VARCHAR(32) NOT NULL,
    amount NUMERIC(19, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    remarks VARCHAR(255),
    schedule_type VARCHAR(32) NOT NULL,
    frequency VARCHAR(32),
    start_date TIMESTAMP WITH TIME ZONE NOT NULL,
    end_date TIMESTAMP WITH TIME ZONE,
    next_run_at TIMESTAMP WITH TIME ZONE,
    status VARCHAR(32) NOT NULL,
    total_occurrences INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT chk_scheduled_transfers_amount CHECK (amount > 0)
);

CREATE INDEX idx_scheduled_transfers_customer_id ON scheduled_transfers(customer_id);
CREATE INDEX idx_scheduled_transfers_status ON scheduled_transfers(status);
CREATE INDEX idx_scheduled_transfers_next_run_at ON scheduled_transfers(next_run_at);

-- 2. Create scheduled_occurrences table (ADR-010)
CREATE TABLE scheduled_occurrences (
    occurrence_id VARCHAR(64) PRIMARY KEY,
    schedule_id VARCHAR(64) NOT NULL REFERENCES scheduled_transfers(schedule_id),
    sequence_number INT NOT NULL,
    transfer_id VARCHAR(64) NOT NULL UNIQUE,
    scheduled_time TIMESTAMP WITH TIME ZONE NOT NULL,
    retry_after TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(32) NOT NULL,
    status_reason_code VARCHAR(64),
    attempt_count INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 3,
    claim_owner VARCHAR(128),
    lease_expires_at TIMESTAMP WITH TIME ZONE,
    claim_version BIGINT NOT NULL DEFAULT 0,
    execution_outcome VARCHAR(64),
    failure_reason VARCHAR(255),
    last_attempt_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT uk_occurrence_schedule_seq UNIQUE (schedule_id, sequence_number)
);

CREATE INDEX idx_scheduled_occurrences_schedule_id ON scheduled_occurrences(schedule_id);
CREATE INDEX idx_scheduled_occurrences_transfer_id ON scheduled_occurrences(transfer_id);
CREATE INDEX idx_scheduled_occurrences_due_claim ON scheduled_occurrences(status, retry_after, lease_expires_at);

-- 3. Explicit least-privilege grants to runtime role (neobank_schedule_app)
GRANT SELECT, INSERT, UPDATE ON scheduled_transfers TO neobank_schedule_app;
GRANT SELECT, INSERT, UPDATE ON scheduled_occurrences TO neobank_schedule_app;
