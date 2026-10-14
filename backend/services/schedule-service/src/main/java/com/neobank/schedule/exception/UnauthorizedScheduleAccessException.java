package com.neobank.schedule.exception;

public class UnauthorizedScheduleAccessException extends RuntimeException {
    public UnauthorizedScheduleAccessException(String message) {
        super(message);
    }
}
