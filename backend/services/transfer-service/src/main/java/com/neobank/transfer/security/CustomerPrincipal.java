package com.neobank.transfer.security;

public record CustomerPrincipal(
        String userId,
        String customerId
) {
}
