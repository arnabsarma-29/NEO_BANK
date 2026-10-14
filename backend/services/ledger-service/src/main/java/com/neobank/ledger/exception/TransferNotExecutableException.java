package com.neobank.ledger.exception;

public class TransferNotExecutableException extends LedgerException {

    public TransferNotExecutableException(String message, String transferId) {
        super(422, "TRANSFER_NOT_EXECUTABLE", message, transferId);
    }

    public TransferNotExecutableException(String code, String message, String transferId) {
        super(422, code != null ? code : "TRANSFER_NOT_EXECUTABLE", message, transferId);
    }
}
