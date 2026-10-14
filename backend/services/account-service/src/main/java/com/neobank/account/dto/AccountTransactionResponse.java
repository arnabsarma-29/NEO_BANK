package com.neobank.account.dto;

import com.neobank.account.domain.EntryType;
import com.neobank.account.domain.TransferType;
import java.math.BigDecimal;
import java.time.Instant;

public record AccountTransactionResponse(
        String id,
        String accountId,
        String transactionId,
        String transferId,
        EntryType entryType,
        BigDecimal amount,
        String currency,
        TransferType transferType,
        String referenceNumber,
        String remarks,
        String destinationName,
        String destinationReference,
        Instant timestamp
) {
}
