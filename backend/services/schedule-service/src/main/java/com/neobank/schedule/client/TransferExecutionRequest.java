package com.neobank.schedule.client;

import java.math.BigDecimal;

public record TransferExecutionRequest(
    String transferId,
    String idempotencyKey,
    String customerId,
    String sourceAccountId,
    String destinationAccountId,
    String beneficiaryId,
    String transferType,
    BigDecimal amount,
    String currency,
    String remarks
) {
}
