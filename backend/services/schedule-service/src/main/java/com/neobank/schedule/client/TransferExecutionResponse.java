package com.neobank.schedule.client;

public record TransferExecutionResponse(
    String transferId,
    String referenceNumber,
    String status,
    String failureReason,
    boolean retryable
) {
    public static TransferExecutionResponse success(String transferId, String referenceNumber) {
        return new TransferExecutionResponse(transferId, referenceNumber, "SUCCESS", null, false);
    }

    public static TransferExecutionResponse transientFailure(String transferId, String failureReason) {
        return new TransferExecutionResponse(transferId, null, "FAILED", failureReason, true);
    }

    public static TransferExecutionResponse permanentFailure(String transferId, String failureReason) {
        return new TransferExecutionResponse(transferId, null, "FAILED", failureReason, false);
    }

    public static TransferExecutionResponse outcomeUnknown(String transferId, String failureReason) {
        return new TransferExecutionResponse(transferId, null, "OUTCOME_UNKNOWN", failureReason, true);
    }
}
