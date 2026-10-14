package com.neobank.transfer.dto;

import java.time.Instant;

public record ExecuteScheduledTransferResponse(
        String transferId,
        String referenceNumber,
        String status,
        String statusReasonCode,
        String failureReason,
        String nextAction,
        boolean retryable,
        Instant timestamp
) {
    public static ExecuteScheduledTransferResponse committed(String transferId, String referenceNumber, Instant timestamp) {
        return new ExecuteScheduledTransferResponse(
                transferId,
                referenceNumber,
                "COMMITTED",
                null,
                null,
                "NONE",
                false,
                timestamp != null ? timestamp : Instant.now()
        );
    }

    public static ExecuteScheduledTransferResponse rejected(String transferId, String referenceNumber, String reasonCode) {
        return new ExecuteScheduledTransferResponse(
                transferId,
                referenceNumber,
                "REJECTED",
                reasonCode != null ? reasonCode : "REJECTED",
                reasonCode != null ? reasonCode : "REJECTED",
                "NONE",
                false,
                Instant.now()
        );
    }

    public static ExecuteScheduledTransferResponse processing(String transferId, String referenceNumber) {
        return new ExecuteScheduledTransferResponse(
                transferId,
                referenceNumber,
                "PROCESSING",
                null,
                null,
                "WAIT",
                true,
                Instant.now()
        );
    }

    public static ExecuteScheduledTransferResponse outcomeUnknown(String transferId, String referenceNumber, String reasonCode) {
        return new ExecuteScheduledTransferResponse(
                transferId,
                referenceNumber,
                "OUTCOME_UNKNOWN",
                reasonCode != null ? reasonCode : "FINANCIAL_OUTCOME_PENDING_RECONCILIATION",
                reasonCode != null ? reasonCode : "FINANCIAL_OUTCOME_PENDING_RECONCILIATION",
                "CHECK_STATUS",
                true,
                Instant.now()
        );
    }

    public static ExecuteScheduledTransferResponse failedPreLedger(String transferId, String reasonCode) {
        return new ExecuteScheduledTransferResponse(
                transferId,
                null,
                "FAILED_PRE_LEDGER",
                reasonCode != null ? reasonCode : "LEDGER_UNAVAILABLE",
                reasonCode != null ? reasonCode : "LEDGER_UNAVAILABLE",
                "RETRY",
                true,
                Instant.now()
        );
    }
}
