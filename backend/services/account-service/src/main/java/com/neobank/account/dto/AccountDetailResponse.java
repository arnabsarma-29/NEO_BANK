package com.neobank.account.dto;

import com.neobank.account.domain.AccountStatus;
import com.neobank.account.domain.AccountType;
import java.math.BigDecimal;
import java.time.Instant;

public record AccountDetailResponse(
        String accountId,
        String accountNumber,
        String maskedAccountNumber,
        String customerId,
        AccountType accountType,
        String currency,
        BigDecimal balance,
        AccountStatus status,
        Long version,
        Instant createdAt,
        Instant updatedAt
) {
}
