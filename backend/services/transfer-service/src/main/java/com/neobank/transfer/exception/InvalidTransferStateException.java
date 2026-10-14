package com.neobank.transfer.exception;

public class InvalidTransferStateException extends RuntimeException {
    public InvalidTransferStateException(String message) {
        super(message);
    }
}
