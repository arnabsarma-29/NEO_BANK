package com.neobank.ledger.exception;

public class InsufficientFundsException extends LedgerException {

    public InsufficientFundsException(String message, String transferId) {
        super(422, "INSUFFICIENT_FUNDS", message, transferId);
    }
}
