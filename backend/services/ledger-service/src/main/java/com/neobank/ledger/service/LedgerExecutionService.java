package com.neobank.ledger.service;

import com.neobank.ledger.domain.ExecutionStatus;
import com.neobank.ledger.domain.LedgerExecution;
import com.neobank.ledger.domain.LedgerTransaction;
import com.neobank.ledger.domain.TransferType;
import com.neobank.ledger.dto.LedgerTransactionOutcomeResponse;
import com.neobank.ledger.dto.LedgerTransferExecutionRequest;
import com.neobank.ledger.exception.AccountNotFoundException;
import com.neobank.ledger.exception.InsufficientFundsException;
import com.neobank.ledger.exception.InvalidRequestException;
import com.neobank.ledger.exception.TransactionNotFoundException;
import com.neobank.ledger.exception.TransferIdConflictException;
import com.neobank.ledger.exception.TransferNotExecutableException;
import com.neobank.ledger.repository.LedgerExecutionRepository;
import com.neobank.ledger.repository.LedgerTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.Optional;

@Service
public class LedgerExecutionService {

    private static final Logger log = LoggerFactory.getLogger(LedgerExecutionService.class);

    private final LedgerExecutionRepository executionRepository;
    private final LedgerTransactionRepository transactionRepository;
    private final LedgerTransactionExecutor transactionExecutor;

    public LedgerExecutionService(LedgerExecutionRepository executionRepository,
                                  LedgerTransactionRepository transactionRepository,
                                  LedgerTransactionExecutor transactionExecutor) {
        this.executionRepository = executionRepository;
        this.transactionRepository = transactionRepository;
        this.transactionExecutor = transactionExecutor;
    }

    public record ExecutionResult(LedgerTransactionOutcomeResponse response, boolean isNew) {}

    /**
     * Executes the transfer idempotently. If an existing matching transferId is found,
     * returns the existing outcome (COMMITTED replay or stored REJECTED exception).
     * If financial parameters conflict with an existing execution, rejects with 409 Conflict.
     * In case of a concurrent race where two duplicate requests execute simultaneously,
     * the losing transaction rolls back completely before the existing outcome is resolved.
     */
    public ExecutionResult processTransfer(LedgerTransferExecutionRequest request) {
        validateRequest(request);
        String fingerprint = calculateFingerprint(request);

        // Pre-check for existing execution outcome
        Optional<LedgerExecution> existingExecOpt = executionRepository.findByTransferId(request.getTransferId());
        if (existingExecOpt.isPresent()) {
            LedgerExecution existing = existingExecOpt.get();
            verifyFingerprintMatch(existing.getFinancialFingerprint(), fingerprint, request.getTransferId());

            if (existing.getStatus() == ExecutionStatus.COMMITTED) {
                return new ExecutionResult(mapToResponse(existing), false);
            } else if (existing.getStatus() == ExecutionStatus.REJECTED) {
                throwStoredRejection(existing);
            } else {
                // PROCESSING
                return new ExecutionResult(mapToResponse(existing), false);
            }
        }

        // Backward compatibility pre-check for legacy transactions
        Optional<LedgerTransaction> legacyTxOpt = transactionRepository.findByTransferId(request.getTransferId());
        if (legacyTxOpt.isPresent()) {
            LedgerTransaction legacyTx = legacyTxOpt.get();
            verifyImmutableDetailsMatch(legacyTx, request);
            return new ExecutionResult(mapToResponse(legacyTx), false);
        }

        try {
            ExecutionOutcome outcome = transactionExecutor.execute(request, fingerprint);
            if (outcome instanceof ExecutionOutcome.Committed committed) {
                return new ExecutionResult(mapToResponse(committed.execution(), committed.transaction()), true);
            } else if (outcome instanceof ExecutionOutcome.Rejected rejected) {
                throw rejected.exception();
            } else {
                throw new IllegalStateException("Unknown execution outcome: " + outcome);
            }
        } catch (DataIntegrityViolationException ex) {
            log.warn("Data integrity violation during transfer execution. Losing transaction rolled back. Resolving existing outcome for transferId: {}",
                    request.getTransferId());
            return resolveConcurrentOutcome(request, fingerprint, ex);
        }
    }

    private ExecutionResult resolveConcurrentOutcome(LedgerTransferExecutionRequest request,
                                                     String currentFingerprint,
                                                     DataIntegrityViolationException ex) {
        // Winning transaction has committed. Read authoritative outcome in a clean read.
        Optional<LedgerExecution> outcomeOpt = executionRepository.findByTransferId(request.getTransferId());
        if (outcomeOpt.isEmpty()) {
            for (int i = 0; i < 10; i++) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                outcomeOpt = executionRepository.findByTransferId(request.getTransferId());
                if (outcomeOpt.isPresent()) {
                    break;
                }
            }
        }

        if (outcomeOpt.isEmpty()) {
            // Check legacy ledger_transactions
            Optional<LedgerTransaction> legacyTx = transactionRepository.findByTransferId(request.getTransferId());
            if (legacyTx.isPresent()) {
                verifyImmutableDetailsMatch(legacyTx.get(), request);
                return new ExecutionResult(mapToResponse(legacyTx.get()), false);
            }
            throw ex;
        }

        LedgerExecution existing = outcomeOpt.get();
        verifyFingerprintMatch(existing.getFinancialFingerprint(), currentFingerprint, request.getTransferId());

        if (existing.getStatus() == ExecutionStatus.COMMITTED) {
            return new ExecutionResult(mapToResponse(existing), false);
        } else if (existing.getStatus() == ExecutionStatus.REJECTED) {
            throwStoredRejection(existing);
        }

        return new ExecutionResult(mapToResponse(existing), false);
    }

    @Transactional(readOnly = true)
    public LedgerTransactionOutcomeResponse getOutcome(String transferId) {
        if (transferId == null || transferId.isBlank()) {
            throw new InvalidRequestException("transferId cannot be blank", null);
        }

        Optional<LedgerExecution> execOpt = executionRepository.findByTransferId(transferId);
        if (execOpt.isPresent()) {
            LedgerExecution exec = execOpt.get();
            Optional<LedgerTransaction> txOpt = transactionRepository.findByTransferId(transferId);
            return txOpt.map(tx -> mapToResponse(exec, tx)).orElseGet(() -> mapToResponse(exec));
        }

        Optional<LedgerTransaction> txOpt = transactionRepository.findByTransferId(transferId);
        if (txOpt.isPresent()) {
            return mapToResponse(txOpt.get());
        }

        throw new TransactionNotFoundException(
                "No execution or transaction found for transferId: " + transferId, transferId);
    }

    public static String calculateFingerprint(LedgerTransferExecutionRequest request) {
        String destAcc = request.getDestinationAccountId() != null ? request.getDestinationAccountId().trim() : "";
        String destRef = request.getDestinationReference() != null ? request.getDestinationReference().trim() : "";
        String refNum = request.getReferenceNumber() != null ? request.getReferenceNumber().trim() : "";
        String amountStr = request.getAmount().setScale(4, RoundingMode.HALF_UP).toPlainString();
        String raw = String.join("|",
                request.getTransferId().trim(),
                request.getSourceAccountId().trim(),
                destAcc,
                request.getTransferType().name(),
                amountStr,
                request.getCurrency().trim().toUpperCase(),
                refNum,
                destRef
        );
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    private void validateRequest(LedgerTransferExecutionRequest request) {
        if (request.getTransferType() == TransferType.OWN_ACCOUNT) {
            if (request.getDestinationAccountId() == null || request.getDestinationAccountId().isBlank()) {
                throw new InvalidRequestException("destinationAccountId is required for OWN_ACCOUNT transfers",
                        request.getTransferId());
            }
        } else {
            // OTHER_BANK or MOCK_UPI
            if (request.getDestinationAccountId() != null) {
                throw new InvalidRequestException(
                        "destinationAccountId must be null for external " + request.getTransferType() + " transfers",
                        request.getTransferId());
            }
            if (request.getDestinationReference() == null || request.getDestinationReference().isBlank()) {
                throw new InvalidRequestException(
                        "destinationReference is required for external " + request.getTransferType() + " transfers",
                        request.getTransferId());
            }
        }
    }

    private void verifyFingerprintMatch(String storedFingerprint, String currentFingerprint, String transferId) {
        if (!Objects.equals(storedFingerprint, currentFingerprint)) {
            throw new TransferIdConflictException(
                    "The transferId is already associated with different immutable financial details.",
                    transferId);
        }
    }

    private void verifyImmutableDetailsMatch(LedgerTransaction tx, LedgerTransferExecutionRequest req) {
        if (!Objects.equals(tx.getSourceAccountId(), req.getSourceAccountId()) ||
                !Objects.equals(tx.getDestinationAccountId(), req.getDestinationAccountId()) ||
                tx.getTransferType() != req.getTransferType() ||
                tx.getAmount().compareTo(req.getAmount()) != 0 ||
                !Objects.equals(tx.getCurrency(), req.getCurrency()) ||
                !Objects.equals(tx.getReferenceNumber(), req.getReferenceNumber())) {
            throw new TransferIdConflictException(
                    "The transferId is already associated with different immutable financial details.",
                    req.getTransferId());
        }
    }

    private void throwStoredRejection(LedgerExecution execution) {
        String code = execution.getStatusReasonCode();
        String msg = execution.getFailureMessage();
        String trfId = execution.getTransferId();
        if ("INSUFFICIENT_FUNDS".equals(code)) {
            throw new InsufficientFundsException(msg, trfId);
        } else if ("ACCOUNT_NOT_FOUND".equals(code)) {
            throw new AccountNotFoundException(msg, trfId);
        } else {
            throw new TransferNotExecutableException(code, msg, trfId);
        }
    }

    public LedgerTransactionOutcomeResponse mapToResponse(LedgerExecution exec) {
        return new LedgerTransactionOutcomeResponse(
                exec.getTransferId(),
                exec.getLedgerTransactionId(),
                exec.getStatus().name(),
                exec.getSourceAccountId(),
                exec.getDestinationAccountId(),
                exec.getTransferType().name(),
                exec.getAmount(),
                exec.getCurrency(),
                exec.getReferenceNumber(),
                null,
                null,
                null,
                exec.getCompletedAt() != null ? exec.getCompletedAt() : exec.getCreatedAt(),
                exec.getStatusReasonCode(),
                exec.getFailureMessage()
        );
    }

    public LedgerTransactionOutcomeResponse mapToResponse(LedgerExecution exec, LedgerTransaction tx) {
        return new LedgerTransactionOutcomeResponse(
                exec.getTransferId(),
                tx.getId(),
                exec.getStatus().name(),
                exec.getSourceAccountId(),
                exec.getDestinationAccountId(),
                exec.getTransferType().name(),
                exec.getAmount(),
                exec.getCurrency(),
                exec.getReferenceNumber(),
                tx.getRemarks(),
                tx.getDestinationName(),
                tx.getDestinationReference(),
                exec.getCompletedAt() != null ? exec.getCompletedAt() : tx.getCreatedAt(),
                exec.getStatusReasonCode(),
                exec.getFailureMessage()
        );
    }

    public LedgerTransactionOutcomeResponse mapToResponse(LedgerTransaction tx) {
        return new LedgerTransactionOutcomeResponse(
                tx.getTransferId(),
                tx.getId(),
                "COMMITTED",
                tx.getSourceAccountId(),
                tx.getDestinationAccountId(),
                tx.getTransferType().name(),
                tx.getAmount(),
                tx.getCurrency(),
                tx.getReferenceNumber(),
                tx.getRemarks(),
                tx.getDestinationName(),
                tx.getDestinationReference(),
                tx.getCreatedAt(),
                null,
                null
        );
    }
}
