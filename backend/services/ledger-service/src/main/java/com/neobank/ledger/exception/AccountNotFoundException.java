package com.neobank.ledger.exception;

public class AccountNotFoundException extends LedgerException {

    public AccountNotFoundException(String message, String transferId) {
        super(404, "ACCOUNT_NOT_FOUND", message, transferId);
    }
}
