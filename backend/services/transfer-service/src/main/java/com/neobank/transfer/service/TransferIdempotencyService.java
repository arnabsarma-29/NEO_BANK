package com.neobank.transfer.service;

import com.neobank.transfer.domain.TransferIdempotencyRecord;
import com.neobank.transfer.exception.IdempotencyConflictException;
import com.neobank.transfer.exception.InvalidIdempotencyKeyException;
import com.neobank.transfer.repository.TransferIdempotencyRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
public class TransferIdempotencyService {

    public static final String OP_CREATE_TRANSFER = "CREATE_TRANSFER";

    private final TransferIdempotencyRepository repository;

    public TransferIdempotencyService(TransferIdempotencyRepository repository) {
        this.repository = repository;
    }

    public void validateKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new InvalidIdempotencyKeyException("Idempotency-Key header is required");
        }
        if (idempotencyKey.trim().length() > 128) {
            throw new InvalidIdempotencyKeyException("Idempotency-Key must not exceed 128 characters");
        }
    }

    public Optional<TransferIdempotencyRecord> findExisting(String customerId, String operation, String idempotencyKey) {
        return repository.findByCustomerIdAndOperationAndIdempotencyKey(customerId, operation, idempotencyKey);
    }

    public void checkConflict(TransferIdempotencyRecord existing, String currentFingerprint) {
        if (!existing.getRequestFingerprint().equals(currentFingerprint)) {
            throw new IdempotencyConflictException("IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST");
        }
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public TransferIdempotencyRecord register(String customerId, String operation, String idempotencyKey,
                                              String fingerprint, String transferId, String status) {
        try {
            TransferIdempotencyRecord record = new TransferIdempotencyRecord(
                    customerId, operation, idempotencyKey, fingerprint, transferId, status, Instant.now());
            return repository.save(record);
        } catch (DataIntegrityViolationException ex) {
            // Concurrent submission
            Optional<TransferIdempotencyRecord> existing = findExisting(customerId, operation, idempotencyKey);
            if (existing.isPresent()) {
                checkConflict(existing.get(), fingerprint);
                return existing.get();
            }
            throw ex;
        }
    }
}
