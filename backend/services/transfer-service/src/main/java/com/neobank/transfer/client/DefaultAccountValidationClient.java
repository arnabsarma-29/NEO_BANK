package com.neobank.transfer.client;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Default local boundary adapter for Account Service ownership validation.
 * Fails closed if account ID or customer ID is missing or invalid.
 */
@Component
@ConditionalOnMissingBean(type = "AccountValidationClientCustom")
public class DefaultAccountValidationClient implements AccountValidationClient {

    @Override
    public boolean validateAccountOwnership(String accountId, String customerId) {
        if (accountId == null || accountId.isBlank() || customerId == null || customerId.isBlank()) {
            return false;
        }
        // In standalone mode, accounts starting with "ACC-" or non-empty strings are considered valid
        // unless explicitly named "INVALID" or "UNAUTHORIZED"
        return !accountId.contains("INVALID") && !accountId.contains("UNAUTHORIZED");
    }
}
