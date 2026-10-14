package com.neobank.account.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * ADR-004: Exactly 10 fields.
 */
public record BalanceUpdatedEvent(
        String eventId,
        String eventType,
        String transferId,
        String accountId,
        String transactionId,
        BigDecimal oldBalance,
        BigDecimal newBalance,
        BigDecimal amount,
        String currency,
        Instant timestamp
) {
    public BalanceUpdatedEvent {
        if (eventType == null) {
            eventType = "BALANCE_UPDATED";
        }
    }
}
