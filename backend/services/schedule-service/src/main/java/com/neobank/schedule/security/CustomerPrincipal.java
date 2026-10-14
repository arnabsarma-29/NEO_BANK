package com.neobank.schedule.security;

public class CustomerPrincipal {
    private final String userId;
    private final String customerId;
    private final String email;
    private final String role;

    public CustomerPrincipal(String userId, String customerId, String email, String role) {
        this.userId = userId;
        this.customerId = customerId;
        this.email = email;
        this.role = role;
    }

    public String getUserId() {
        return userId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getEmail() {
        return email;
    }

    public String getRole() {
        return role;
    }
}
