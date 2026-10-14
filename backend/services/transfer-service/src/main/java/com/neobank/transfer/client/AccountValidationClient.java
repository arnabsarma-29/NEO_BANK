package com.neobank.transfer.client;

/**
 * Validates account ownership through Account Service boundary under ADR-014.
 */
public interface AccountValidationClient {

    /**
     * Validates that the specified account exists and belongs to the authenticated customer.
     *
     * @param accountId  the account identifier
     * @param customerId the authenticated customer identifier
     * @return true if valid and owned by customer, false otherwise
     */
    boolean validateAccountOwnership(String accountId, String customerId);
}
