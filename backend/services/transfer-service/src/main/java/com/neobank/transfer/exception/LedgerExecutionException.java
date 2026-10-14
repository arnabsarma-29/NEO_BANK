package com.neobank.transfer.exception;

public class LedgerExecutionException extends RuntimeException {

    private final String reasonCode;

    public LedgerExecutionException(String message, String reasonCode) {
        super(message);
        this.reasonCode = reasonCode;
    }

    public String getReasonCode() {
        return reasonCode;
    }
}
