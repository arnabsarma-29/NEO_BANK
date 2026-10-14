package com.neobank.ledger.controller;

import com.neobank.ledger.dto.LedgerApiErrorResponse;
import com.neobank.ledger.exception.LedgerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.stream.Collectors;

@RestControllerAdvice
public class LedgerGlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(LedgerGlobalExceptionHandler.class);

    @ExceptionHandler(LedgerException.class)
    public ResponseEntity<LedgerApiErrorResponse> handleLedgerException(LedgerException ex) {
        log.warn("Ledger domain exception: code={}, status={}, message={}", ex.getCode(), ex.getStatus(), ex.getMessage());
        LedgerApiErrorResponse error = new LedgerApiErrorResponse(
                Instant.now(),
                ex.getStatus(),
                ex.getCode(),
                ex.getMessage(),
                ex.getTransferId()
        );
        return ResponseEntity.status(ex.getStatus()).body(error);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<LedgerApiErrorResponse> handleValidationException(MethodArgumentNotValidException ex) {
        String details = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .collect(Collectors.joining(", "));

        log.warn("Validation error on request: {}", details);
        LedgerApiErrorResponse error = new LedgerApiErrorResponse(
                Instant.now(),
                HttpStatus.BAD_REQUEST.value(),
                "INVALID_REQUEST",
                "Validation failed: " + details,
                null
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<LedgerApiErrorResponse> handleNotReadableException(HttpMessageNotReadableException ex) {
        log.warn("Malformed HTTP request body: {}", ex.getMessage());
        LedgerApiErrorResponse error = new LedgerApiErrorResponse(
                Instant.now(),
                HttpStatus.BAD_REQUEST.value(),
                "INVALID_REQUEST",
                "Malformed request payload",
                null
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<LedgerApiErrorResponse> handleGenericException(Exception ex) {
        log.error("Unhandled internal server error in Ledger Service", ex);
        LedgerApiErrorResponse error = new LedgerApiErrorResponse(
                Instant.now(),
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "INTERNAL_SERVER_ERROR",
                "An unexpected internal error occurred",
                null
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }
}
