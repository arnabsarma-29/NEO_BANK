package com.neobank.schedule.controller;

import com.neobank.schedule.dto.ErrorResponse;
import com.neobank.schedule.exception.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ScheduleNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ScheduleNotFoundException ex) {
        log.warn("Schedule not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(HttpStatus.NOT_FOUND.value(), "Not Found", "SCHEDULE_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(OccurrenceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleOccurrenceNotFound(OccurrenceNotFoundException ex) {
        log.warn("Occurrence not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(HttpStatus.NOT_FOUND.value(), "Not Found", "OCCURRENCE_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(UnauthorizedScheduleAccessException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorized(UnauthorizedScheduleAccessException ex) {
        log.warn("Unauthorized access: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of(HttpStatus.FORBIDDEN.value(), "Forbidden", "UNAUTHORIZED_SCHEDULE_ACCESS", ex.getMessage()));
    }

    @ExceptionHandler(InvalidScheduleRequestException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRequest(InvalidScheduleRequestException ex) {
        log.warn("Invalid schedule request: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(HttpStatus.BAD_REQUEST.value(), "Bad Request", "INVALID_SCHEDULE_REQUEST", ex.getMessage()));
    }

    @ExceptionHandler(InvalidScheduleStateException.class)
    public ResponseEntity<ErrorResponse> handleInvalidState(InvalidScheduleStateException ex) {
        log.warn("Invalid schedule state: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(HttpStatus.CONFLICT.value(), "Conflict", "INVALID_SCHEDULE_STATE", ex.getMessage()));
    }

    @ExceptionHandler(StaleClaimException.class)
    public ResponseEntity<ErrorResponse> handleStaleClaim(StaleClaimException ex) {
        log.warn("Stale claim fencing violation: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(HttpStatus.CONFLICT.value(), "Conflict", "STALE_CLAIM_CONFLICT", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .orElse("Validation failed");
        log.warn("Validation error: {}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(HttpStatus.BAD_REQUEST.value(), "Bad Request", "VALIDATION_ERROR", message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception ex) {
        log.error("Unhandled exception: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Internal Server Error", "INTERNAL_ERROR", ex.getMessage()));
    }
}
