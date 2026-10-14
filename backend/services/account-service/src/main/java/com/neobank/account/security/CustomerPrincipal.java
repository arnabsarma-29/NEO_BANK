package com.neobank.account.security;

/**
 * ADR-014: sub (userId) and customerId are distinct identity claims.
 */
public record CustomerPrincipal(
        String userId,
        String customerId
) {
    public CustomerPrincipal {
        if (customerId == null || customerId.isBlank()) {
            throw new IllegalArgumentException("customerId must not be null or blank");
        }
    }
}
