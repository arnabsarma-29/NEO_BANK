package com.neobank.schedule.exception;

public class InvalidScheduleStateException extends RuntimeException {
    public InvalidScheduleStateException(String message) {
        super(message);
    }
}
