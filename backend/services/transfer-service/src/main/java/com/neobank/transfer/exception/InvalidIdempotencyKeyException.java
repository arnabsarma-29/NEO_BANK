package com.neobank.transfer.exception;

public class InvalidIdempotencyKeyException extends RuntimeException {
    public InvalidIdempotencyKeyException(String message) {
        super(message);
    }
}
