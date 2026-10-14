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
public class ScheduleGlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ScheduleGlobalExceptionHandler.class);

    @ExceptionHandler(InvalidScheduleRequestException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRequest(InvalidScheduleRequestException ex) {
        log.warn("Invalid schedule request: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(HttpStatus.BAD_REQUEST.value(), "Bad Request", "INVALID_SCHEDULE_REQUEST", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .findFirst()
                .orElse("Validation failed");
        log.warn("Validation error: {}", msg);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(HttpStatus.BAD_REQUEST.value(), "Bad Request", "VALIDATION_ERROR", msg));
    }

    @ExceptionHandler(ScheduleNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleScheduleNotFound(ScheduleNotFoundException ex) {
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

    @ExceptionHandler(InvalidScheduleStateException.class)
    public ResponseEntity<ErrorResponse> handleInvalidState(InvalidScheduleStateException ex) {
        log.warn("Invalid schedule state: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(HttpStatus.CONFLICT.value(), "Conflict", "INVALID_SCHEDULE_STATE", ex.getMessage()));
    }

    @ExceptionHandler({StaleClaimException.class, OccurrenceClaimException.class})
    public ResponseEntity<ErrorResponse> handleClaimConflict(RuntimeException ex) {
        log.warn("Claim conflict: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(HttpStatus.CONFLICT.value(), "Conflict", "CLAIM_CONFLICT", ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        log.error("Unhandled error: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Internal Server Error", "INTERNAL_ERROR", "An unexpected error occurred"));
    }
}
