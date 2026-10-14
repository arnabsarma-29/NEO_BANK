package com.neobank.account.dto;

import com.neobank.account.domain.AccountStatus;
import com.neobank.account.domain.AccountType;
import java.math.BigDecimal;
import java.time.Instant;

public record AccountSummaryResponse(
        String accountId,
        String accountNumber,
        String maskedAccountNumber,
        AccountType accountType,
        String currency,
        BigDecimal balance,
        AccountStatus status,
        Instant createdAt
) {
}
