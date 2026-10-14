package com.neobank.schedule.dto;

import java.time.Instant;

public record ErrorResponse(
    Instant timestamp,
    int status,
    String error,
    String code,
    String message
) {
    public static ErrorResponse of(int status, String error, String code, String message) {
        return new ErrorResponse(Instant.now(), status, error, code, message);
    }
}
