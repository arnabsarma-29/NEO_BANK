package com.neobank.account.exception;

public class AccountNotFoundException extends RuntimeException {

    private final String accountId;

    public AccountNotFoundException(String message, String accountId) {
        super(message);
        this.accountId = accountId;
    }

    public AccountNotFoundException(String message) {
        super(message);
        this.accountId = null;
    }

    public String getAccountId() {
        return accountId;
    }
}
