package com.neobank.schedule.security;

public final class SecurityContextUtil {

    private static final ThreadLocal<CustomerPrincipal> CURRENT_PRINCIPAL = new ThreadLocal<>();

    private SecurityContextUtil() {
    }

    public static void setPrincipal(CustomerPrincipal principal) {
        CURRENT_PRINCIPAL.set(principal);
    }

    public static CustomerPrincipal getPrincipal() {
        return CURRENT_PRINCIPAL.get();
    }

    public static void clear() {
        CURRENT_PRINCIPAL.remove();
    }
}
