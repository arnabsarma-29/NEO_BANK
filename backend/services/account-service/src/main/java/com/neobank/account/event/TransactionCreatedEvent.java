package com.neobank.account.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * ADR-007: Exactly 15 fields.
 */
public record TransactionCreatedEvent(
        String eventId,
        String eventType,
        String transactionId,
        String transferId,
        String sourceAccountId,
        String destinationAccountId,
        String transferType,
        BigDecimal amount,
        String currency,
        String status,
        String referenceNumber,
        String remarks,
        String destinationName,
        String destinationReference,
        Instant timestamp
) {
    public TransactionCreatedEvent {
        if (eventType == null) {
            eventType = "TRANSACTION_CREATED";
        }
    }
}
