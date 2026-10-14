package com.neobank.account.dto;

import com.neobank.account.domain.AccountStatus;
import com.neobank.account.domain.AccountType;

public record AccountOwnershipResult(
        boolean valid,
        String accountId,
        String customerId,
        AccountStatus status,
        AccountType accountType,
        String currency,
        String reason
) {
    public static AccountOwnershipResult valid(String accountId, String customerId, AccountStatus status,
                                               AccountType accountType, String currency) {
        return new AccountOwnershipResult(true, accountId, customerId, status, accountType, currency, null);
    }

    public static AccountOwnershipResult invalid(String accountId, String customerId, String reason) {
        return new AccountOwnershipResult(false, accountId, customerId, null, null, null, reason);
    }
}
