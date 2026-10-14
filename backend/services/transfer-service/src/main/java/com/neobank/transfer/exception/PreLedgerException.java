package com.neobank.transfer.exception;

public class PreLedgerException extends LedgerExecutionException {

    public PreLedgerException(String message) {
        this(message, "LEDGER_UNAVAILABLE");
    }

    public PreLedgerException(String message, String reasonCode) {
        super(message, reasonCode);
    }
}
