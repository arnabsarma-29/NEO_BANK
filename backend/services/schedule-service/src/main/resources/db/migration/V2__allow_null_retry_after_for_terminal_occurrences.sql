-- V2__allow_null_retry_after_for_terminal_occurrences.sql
-- Allow retry_after to be NULL for terminal occurrences (COMPLETED, FAILED)
-- where no future retry deadline applies.

ALTER TABLE scheduled_occurrences ALTER COLUMN retry_after DROP NOT NULL;
