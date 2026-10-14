package com.neobank.schedule.exception;

public class StaleClaimException extends RuntimeException {
    public StaleClaimException(String message) {
        super(message);
    }
}
