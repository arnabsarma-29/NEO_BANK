package com.neobank.ledger.exception;

public class InvalidRequestException extends LedgerException {

    public InvalidRequestException(String message, String transferId) {
        super(400, "INVALID_REQUEST", message, transferId);
    }
}
