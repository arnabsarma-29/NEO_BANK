package com.neobank.transfer.dto;

import java.time.Instant;

public record ScheduledTransferReconcileResponse(
        String transferId,
        String referenceNumber,
        String status,
        String statusReasonCode,
        String failureReason,
        String nextAction,
        String ledgerTransactionId,
        boolean retryable,
        Instant completedAt,
        Instant reconciledAt
) {
    public static ScheduledTransferReconcileResponse committed(String transferId, String referenceNumber, String ledgerTxId, Instant completedAt) {
        return new ScheduledTransferReconcileResponse(
                transferId,
                referenceNumber,
                "COMMITTED",
                null,
                null,
                "NONE",
                ledgerTxId,
                false,
                completedAt,
                Instant.now()
        );
    }

    public static ScheduledTransferReconcileResponse rejected(String transferId, String referenceNumber, String reasonCode, Instant completedAt) {
        return new ScheduledTransferReconcileResponse(
                transferId,
                referenceNumber,
                "REJECTED",
                reasonCode != null ? reasonCode : "REJECTED_BY_LEDGER",
                reasonCode != null ? reasonCode : "REJECTED_BY_LEDGER",
                "NONE",
                null,
                false,
                completedAt,
                Instant.now()
        );
    }

    public static ScheduledTransferReconcileResponse processing(String transferId, String referenceNumber) {
        return new ScheduledTransferReconcileResponse(
                transferId,
                referenceNumber,
                "PROCESSING",
                null,
                null,
                "WAIT",
                null,
                true,
                null,
                Instant.now()
        );
    }

    public static ScheduledTransferReconcileResponse uncertain(String transferId, String referenceNumber, String reasonCode) {
        return new ScheduledTransferReconcileResponse(
                transferId,
                referenceNumber,
                "OUTCOME_UNKNOWN",
                reasonCode != null ? reasonCode : "FINANCIAL_OUTCOME_PENDING_RECONCILIATION",
                reasonCode != null ? reasonCode : "FINANCIAL_OUTCOME_PENDING_RECONCILIATION",
                "CHECK_STATUS",
                null,
                true,
                null,
                Instant.now()
        );
    }
}
