package com.neobank.ledger.exception;

public class TransactionNotFoundException extends LedgerException {

    public TransactionNotFoundException(String message, String transferId) {
        super(404, "TRANSACTION_NOT_FOUND", message, transferId);
    }
}
