package com.neobank.account.security;

import java.util.Optional;

/**
 * Manages the current thread's trusted customer authentication context.
 */
public final class CustomerContextHolder {

    private static final ThreadLocal<CustomerPrincipal> CONTEXT = new ThreadLocal<>();

    private CustomerContextHolder() {
    }

    public static void setCustomer(CustomerPrincipal principal) {
        CONTEXT.set(principal);
    }

    public static void setCustomer(String customerId, String userId) {
        CONTEXT.set(new CustomerPrincipal(userId, customerId));
    }

    public static Optional<CustomerPrincipal> getCustomer() {
        return Optional.ofNullable(CONTEXT.get());
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
