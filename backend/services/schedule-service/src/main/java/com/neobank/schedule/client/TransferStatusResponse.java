package com.neobank.schedule.client;

public record TransferStatusResponse(
    String transferId,
    String referenceNumber,
    String status,
    String failureReason
) {
}
