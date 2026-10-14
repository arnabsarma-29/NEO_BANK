package com.neobank.schedule.exception;

public class OccurrenceNotFoundException extends RuntimeException {
    public OccurrenceNotFoundException(String message) {
        super(message);
    }
}
