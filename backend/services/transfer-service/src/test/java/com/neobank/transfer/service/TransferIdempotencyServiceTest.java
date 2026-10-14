package com.neobank.transfer.service;

import com.neobank.transfer.domain.TransferIdempotencyRecord;
import com.neobank.transfer.exception.IdempotencyConflictException;
import com.neobank.transfer.exception.InvalidIdempotencyKeyException;
import com.neobank.transfer.repository.TransferIdempotencyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class TransferIdempotencyServiceTest {

    @Mock
    private TransferIdempotencyRepository repository;

    private TransferIdempotencyService idempotencyService;

    @BeforeEach
    void setUp() {
        idempotencyService = new TransferIdempotencyService(repository);
    }

    @Test
    void shouldThrowExceptionWhenIdempotencyKeyIsNull() {
        assertThrows(InvalidIdempotencyKeyException.class, () ->
                idempotencyService.validateKey(null));
    }

    @Test
    void shouldThrowExceptionWhenIdempotencyKeyIsBlank() {
        assertThrows(InvalidIdempotencyKeyException.class, () ->
                idempotencyService.validateKey("   "));
    }

    @Test
    void shouldThrowExceptionWhenIdempotencyKeyExceedsMaxLength() {
        String longKey = "a".repeat(129);
        assertThrows(InvalidIdempotencyKeyException.class, () ->
                idempotencyService.validateKey(longKey));
    }

    @Test
    void shouldThrowConflictWhenFingerprintDiffers() {
        TransferIdempotencyRecord record = new TransferIdempotencyRecord(
                "C1001", "CREATE_TRANSFER", "KEY-1", "FINGERPRINT-A", "TRF-1", "PENDING_CONFIRMATION", Instant.now());

        assertThrows(IdempotencyConflictException.class, () ->
                idempotencyService.checkConflict(record, "FINGERPRINT-B"));
    }

    @Test
    void shouldPassWhenFingerprintMatches() {
        TransferIdempotencyRecord record = new TransferIdempotencyRecord(
                "C1001", "CREATE_TRANSFER", "KEY-1", "FINGERPRINT-A", "TRF-1", "PENDING_CONFIRMATION", Instant.now());

        assertDoesNotThrow(() ->
                idempotencyService.checkConflict(record, "FINGERPRINT-A"));
    }
}
