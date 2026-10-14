package com.neobank.ledger.exception;

public class TransferIdConflictException extends LedgerException {

    public TransferIdConflictException(String message, String transferId) {
        super(409, "TRANSFER_ID_REUSED_WITH_DIFFERENT_DETAILS", message, transferId);
    }
}
