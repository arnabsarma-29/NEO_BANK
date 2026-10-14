package com.neobank.ledger.service;

import com.neobank.ledger.domain.ExecutionStatus;
import com.neobank.ledger.domain.LedgerExecution;
import com.neobank.ledger.domain.LedgerTransaction;
import com.neobank.ledger.domain.TransferType;
import com.neobank.ledger.dto.LedgerTransactionOutcomeResponse;
import com.neobank.ledger.dto.LedgerTransferExecutionRequest;
import com.neobank.ledger.exception.InsufficientFundsException;
import com.neobank.ledger.exception.TransactionNotFoundException;
import com.neobank.ledger.exception.TransferIdConflictException;
import com.neobank.ledger.repository.LedgerExecutionRepository;
import com.neobank.ledger.repository.LedgerTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LedgerExecutionServiceTest {

    @Mock
    private LedgerExecutionRepository executionRepository;

    @Mock
    private LedgerTransactionRepository transactionRepository;

    @Mock
    private LedgerTransactionExecutor transactionExecutor;

    private LedgerExecutionService service;

    @BeforeEach
    void setUp() {
        service = new LedgerExecutionService(executionRepository, transactionRepository, transactionExecutor);
    }

    @Test
    @DisplayName("Should execute new transfer and return isNew = true")
    void shouldExecuteNewTransferSuccessfully() {
        LedgerTransferExecutionRequest req = new LedgerTransferExecutionRequest(
                "TRF-1001",
                "ACC-001",
                "ACC-002",
                TransferType.OWN_ACCOUNT,
                new BigDecimal("200.0000"),
                "INR",
                "REF-001",
                "Self transfer",
                "My Savings",
                null
        );
        String fingerprint = LedgerExecutionService.calculateFingerprint(req);

        LedgerTransaction committedTx = new LedgerTransaction(
                "TX-1001",
                "TRF-1001",
                "ACC-001",
                "ACC-002",
                TransferType.OWN_ACCOUNT,
                new BigDecimal("200.0000"),
                "INR",
                "SUCCESS",
                "REF-001",
                "Self transfer",
                "My Savings",
                null,
                Instant.now()
        );

        LedgerExecution committedExec = new LedgerExecution(
                "TRF-1001",
                ExecutionStatus.COMMITTED,
                "ACC-001",
                "ACC-002",
                TransferType.OWN_ACCOUNT,
                new BigDecimal("200.0000"),
                "INR",
                "REF-001",
                "TX-1001",
                null,
                null,
                fingerprint,
                Instant.now(),
                Instant.now(),
                1L
        );

        when(executionRepository.findByTransferId("TRF-1001")).thenReturn(Optional.empty());
        when(transactionRepository.findByTransferId("TRF-1001")).thenReturn(Optional.empty());
        when(transactionExecutor.execute(eq(req), eq(fingerprint)))
                .thenReturn(new ExecutionOutcome.Committed(committedExec, committedTx));

        LedgerExecutionService.ExecutionResult result = service.processTransfer(req);

        assertThat(result.isNew()).isTrue();
        assertThat(result.response().getTransferId()).isEqualTo("TRF-1001");
        assertThat(result.response().getTransactionId()).isEqualTo("TX-1001");
        assertThat(result.response().getStatus()).isEqualTo("COMMITTED");
        verify(transactionExecutor, times(1)).execute(eq(req), eq(fingerprint));
    }

    @Test
    @DisplayName("Should return existing COMMITTED outcome on pre-check without invoking executor")
    void shouldReturnExistingOutcomeOnPreCheck() {
        LedgerTransferExecutionRequest req = new LedgerTransferExecutionRequest(
                "TRF-1004",
                "ACC-001",
                "ACC-002",
                TransferType.OWN_ACCOUNT,
                new BigDecimal("100.0000"),
                "INR",
                "REF-004",
                "Remarks",
                null,
                null
        );
        String fingerprint = LedgerExecutionService.calculateFingerprint(req);

        LedgerExecution existing = new LedgerExecution(
                "TRF-1004",
                ExecutionStatus.COMMITTED,
                "ACC-001",
                "ACC-002",
                TransferType.OWN_ACCOUNT,
                new BigDecimal("100.0000"),
                "INR",
                "REF-004",
                "TX-999",
                null,
                null,
                fingerprint,
                Instant.now(),
                Instant.now(),
                1L
        );

        when(executionRepository.findByTransferId("TRF-1004")).thenReturn(Optional.of(existing));

        LedgerExecutionService.ExecutionResult result = service.processTransfer(req);

        assertThat(result.isNew()).isFalse();
        assertThat(result.response().getTransactionId()).isEqualTo("TX-999");
        assertThat(result.response().getTransferId()).isEqualTo("TRF-1004");
        assertThat(result.response().getStatus()).isEqualTo("COMMITTED");

        // Executor never called
        verify(transactionExecutor, never()).execute(any(), any());
    }

    @Test
    @DisplayName("Should replay stored REJECTED outcome on pre-check without re-evaluating")
    void shouldReplayStoredRejectionOnPreCheck() {
        LedgerTransferExecutionRequest req = new LedgerTransferExecutionRequest(
                "TRF-REJ-1",
                "ACC-001",
                "ACC-002",
                TransferType.OWN_ACCOUNT,
                new BigDecimal("50000.0000"),
                "INR",
                "REF-REJ-1",
                "Remarks",
                null,
                null
        );
        String fingerprint = LedgerExecutionService.calculateFingerprint(req);

        LedgerExecution existing = new LedgerExecution(
                "TRF-REJ-1",
                ExecutionStatus.REJECTED,
                "ACC-001",
                "ACC-002",
                TransferType.OWN_ACCOUNT,
                new BigDecimal("50000.0000"),
                "INR",
                "REF-REJ-1",
                null,
                "INSUFFICIENT_FUNDS",
                "Insufficient balance in source account: 1000 < 50000",
                fingerprint,
                Instant.now(),
                Instant.now(),
                1L
        );

        when(executionRepository.findByTransferId("TRF-REJ-1")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.processTransfer(req))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessageContaining("Insufficient balance in source account");

        verify(transactionExecutor, never()).execute(any(), any());
    }

    @Test
    @DisplayName("Should reject with 409 Conflict when transferId is reused with different details")
    void shouldRejectWithConflictWhenTransferIdReusedWithDifferentDetails() {
        LedgerTransferExecutionRequest req = new LedgerTransferExecutionRequest(
                "TRF-1005",
                "ACC-001",
                "ACC-002",
                TransferType.OWN_ACCOUNT,
                new BigDecimal("100.0000"),
                "INR",
                "REF-005",
                "Remarks",
                null,
                null
        );

        LedgerExecution existing = new LedgerExecution(
                "TRF-1005",
                ExecutionStatus.COMMITTED,
                "ACC-001",
                "ACC-002",
                TransferType.OWN_ACCOUNT,
                new BigDecimal("100.0000"),
                "INR",
                "REF-005",
                "TX-999",
                null,
                null,
                "different-fingerprint-hash",
                Instant.now(),
                Instant.now(),
                1L
        );

        when(executionRepository.findByTransferId("TRF-1005")).thenReturn(Optional.of(existing));

        // Different amount: 200 instead of 100
        LedgerTransferExecutionRequest conflictingReq = new LedgerTransferExecutionRequest(
                "TRF-1005",
                "ACC-001",
                "ACC-002",
                TransferType.OWN_ACCOUNT,
                new BigDecimal("200.0000"),
                "INR",
                "REF-005",
                "Remarks",
                null,
                null
        );

        assertThatThrownBy(() -> service.processTransfer(conflictingReq))
                .isInstanceOf(TransferIdConflictException.class)
                .hasMessageContaining("different immutable financial details");

        verify(transactionExecutor, never()).execute(any(), any());
    }

    @Test
    @DisplayName("Concurrent duplicate: when DataIntegrityViolationException occurs, recovers committed outcome safely")
    void shouldRecoverCommittedOutcomeOnConcurrentDuplicateRace() {
        LedgerTransferExecutionRequest req = new LedgerTransferExecutionRequest(
                "TRF-RACE-1",
                "ACC-001",
                "ACC-002",
                TransferType.OWN_ACCOUNT,
                new BigDecimal("100.0000"),
                "INR",
                "REF-RACE-1",
                "Remarks",
                null,
                null
        );
        String fingerprint = LedgerExecutionService.calculateFingerprint(req);

        LedgerExecution committedByOtherThread = new LedgerExecution(
                "TRF-RACE-1",
                ExecutionStatus.COMMITTED,
                "ACC-001",
                "ACC-002",
                TransferType.OWN_ACCOUNT,
                new BigDecimal("100.0000"),
                "INR",
                "REF-RACE-1",
                "TX-RACE-WINNER",
                null,
                null,
                fingerprint,
                Instant.now(),
                Instant.now(),
                1L
        );

        // Pre-check returns empty (both threads saw no record initially)
        when(executionRepository.findByTransferId("TRF-RACE-1"))
                .thenReturn(Optional.empty()) // on pre-check
                .thenReturn(Optional.of(committedByOtherThread)); // on recovery after rollback

        when(transactionRepository.findByTransferId("TRF-RACE-1")).thenReturn(Optional.empty());

        when(transactionExecutor.execute(eq(req), eq(fingerprint)))
                .thenThrow(new DataIntegrityViolationException("Unique constraint violation"));

        LedgerExecutionService.ExecutionResult result = service.processTransfer(req);

        assertThat(result.isNew()).isFalse();
        assertThat(result.response().getTransactionId()).isEqualTo("TX-RACE-WINNER");
        assertThat(result.response().getTransferId()).isEqualTo("TRF-RACE-1");
        assertThat(result.response().getStatus()).isEqualTo("COMMITTED");
    }

    @Test
    @DisplayName("Should retrieve existing outcome by transferId for reconciliation")
    void shouldRetrieveExistingOutcome() {
        LedgerExecution existing = new LedgerExecution(
                "TRF-1006",
                ExecutionStatus.COMMITTED,
                "ACC-001",
                null,
                TransferType.MOCK_UPI,
                new BigDecimal("50.0000"),
                "INR",
                "REF-006",
                "TX-888",
                null,
                null,
                "fingerprint-1006",
                Instant.now(),
                Instant.now(),
                1L
        );

        when(executionRepository.findByTransferId("TRF-1006")).thenReturn(Optional.of(existing));

        LedgerTransactionOutcomeResponse resp = service.getOutcome("TRF-1006");

        assertThat(resp.getTransactionId()).isEqualTo("TX-888");
        assertThat(resp.getTransferId()).isEqualTo("TRF-1006");
        assertThat(resp.getTransferType()).isEqualTo("MOCK_UPI");
        assertThat(resp.getStatus()).isEqualTo("COMMITTED");
    }

    @Test
    @DisplayName("Should retrieve rejected outcome by transferId for reconciliation")
    void shouldRetrieveRejectedOutcomeForReconciliation() {
        LedgerExecution rejected = new LedgerExecution(
                "TRF-1007",
                ExecutionStatus.REJECTED,
                "ACC-001",
                null,
                TransferType.MOCK_UPI,
                new BigDecimal("5000.0000"),
                "INR",
                "REF-007",
                null,
                "INSUFFICIENT_FUNDS",
                "Insufficient balance in source account: 100 < 5000",
                "fingerprint-1007",
                Instant.now(),
                Instant.now(),
                1L
        );

        when(executionRepository.findByTransferId("TRF-1007")).thenReturn(Optional.of(rejected));

        LedgerTransactionOutcomeResponse resp = service.getOutcome("TRF-1007");

        assertThat(resp.getTransferId()).isEqualTo("TRF-1007");
        assertThat(resp.getStatus()).isEqualTo("REJECTED");
        assertThat(resp.getStatusReasonCode()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(resp.getFailureMessage()).contains("Insufficient balance");
    }

    @Test
    @DisplayName("Should throw TransactionNotFoundException when transferId does not exist during lookup")
    void shouldThrowTransactionNotFoundException() {
        when(executionRepository.findByTransferId("TRF-UNKNOWN")).thenReturn(Optional.empty());
        when(transactionRepository.findByTransferId("TRF-UNKNOWN")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOutcome("TRF-UNKNOWN"))
                .isInstanceOf(TransactionNotFoundException.class)
                .hasMessageContaining("No execution or transaction found");
    }
}
