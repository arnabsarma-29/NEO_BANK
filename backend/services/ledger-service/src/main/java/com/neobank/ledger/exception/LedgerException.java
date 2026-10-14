package com.neobank.ledger.exception;

public abstract class LedgerException extends RuntimeException {

    private final int status;
    private final String code;
    private final String transferId;

    public LedgerException(int status, String code, String message, String transferId) {
        super(message);
        this.status = status;
        this.code = code;
        this.transferId = transferId;
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public String getTransferId() {
        return transferId;
    }
}
