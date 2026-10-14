package com.neobank.transfer.client;

import com.neobank.transfer.domain.TransferWorkflow;

import java.time.Instant;
import java.util.Optional;

/**
 * Communicates with Ledger Service for authoritative financial mutations under ADR-008 and ADR-009.
 */
public interface LedgerClient {

    enum LedgerOutcomeStatus {
        COMMITTED,
        REJECTED,
        UNCERTAIN
    }

    record LedgerExecutionOutcome(
            LedgerOutcomeStatus status,
            String transactionId,
            String reasonCode,
            Instant timestamp
    ) {
        public static LedgerExecutionOutcome committed(String transactionId, Instant timestamp) {
            return new LedgerExecutionOutcome(LedgerOutcomeStatus.COMMITTED, transactionId, null, timestamp);
        }

        public static LedgerExecutionOutcome rejected(String reasonCode) {
            return new LedgerExecutionOutcome(LedgerOutcomeStatus.REJECTED, null, reasonCode, Instant.now());
        }

        public static LedgerExecutionOutcome uncertain(String reasonCode) {
            return new LedgerExecutionOutcome(LedgerOutcomeStatus.UNCERTAIN, null, reasonCode, Instant.now());
        }
    }

    LedgerExecutionOutcome executeTransfer(TransferWorkflow transfer);

    Optional<LedgerExecutionOutcome> lookupOutcome(String transferId);
}
