package com.neobank.transfer.service;

import com.neobank.transfer.client.AccountValidationClient;
import com.neobank.transfer.client.BeneficiaryClient;
import com.neobank.transfer.client.LedgerClient;
import com.neobank.transfer.client.OtpClient;
import com.neobank.transfer.domain.*;
import com.neobank.transfer.dto.*;
import com.neobank.transfer.exception.IdempotencyConflictException;
import com.neobank.transfer.exception.PreLedgerException;
import com.neobank.transfer.exception.ValidationException;
import com.neobank.transfer.repository.TransferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScheduledTransferWorkflowServiceTest {

    @Mock
    private TransferRepository transferRepository;
    @Mock
    private TransferIdempotencyService idempotencyService;
    @Mock
    private TransferFingerprintService fingerprintService;
    @Mock
    private AccountValidationClient accountValidationClient;
    @Mock
    private BeneficiaryClient beneficiaryClient;
    @Mock
    private OtpClient otpClient;
    @Mock
    private LedgerClient ledgerClient;
    @Mock
    private TransferRejectionService rejectionService;

    private TransferWorkflowService workflowService;

    @BeforeEach
    void setUp() {
        workflowService = new TransferWorkflowService(
                transferRepository,
                idempotencyService,
                fingerprintService,
                accountValidationClient,
                beneficiaryClient,
                otpClient,
                ledgerClient,
                rejectionService,
                true
        );
    }

    private ExecuteScheduledTransferRequest validRequest() {
        return new ExecuteScheduledTransferRequest(
                "TRF-SCH-001",
                "key-sch-001",
                "SCH-100",
                "OCC-100",
                "CUST-001",
                "ACC-1001",
                "ACC-1002",
                null,
                "OWN_ACCOUNT",
                new BigDecimal("250.00"),
                "INR",
                "Scheduled recurring transfer",
                Instant.now(),
                null
        );
    }

    @Test
    @DisplayName("Fails validation when required fields are missing")
    void shouldFailValidationWhenRequiredFieldsMissing() {
        ExecuteScheduledTransferRequest request = validRequest();
        request.setTransferId("");
        assertThrows(ValidationException.class, () -> workflowService.executeScheduledTransfer(request));

        request.setTransferId("TRF-SCH-001");
        request.setAmount(new BigDecimal("-10.00"));
        assertThrows(ValidationException.class, () -> workflowService.executeScheduledTransfer(request));
    }

    @Test
    @DisplayName("Rejects with 422 when source account is not owned by customer")
    void shouldRejectWhenSourceAccountNotOwned() {
        ExecuteScheduledTransferRequest request = validRequest();
        when(accountValidationClient.validateAccountOwnership("ACC-1002", "CUST-001")).thenReturn(true);
        when(accountValidationClient.validateAccountOwnership("ACC-1001", "CUST-001")).thenReturn(false);

        TransferWorkflowService.ScheduledExecutionOutcome outcome = workflowService.executeScheduledTransfer(request);

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, outcome.httpStatus());
        assertEquals("REJECTED", outcome.response().status());
        assertEquals("SOURCE_ACCOUNT_NOT_OWNED", outcome.response().statusReasonCode());
        verifyNoInteractions(ledgerClient);
    }

    @Test
    @DisplayName("Rejects with 422 when destination account is not owned for OWN_ACCOUNT transfer")
    void shouldRejectWhenDestinationAccountNotOwnedForOwnAccount() {
        ExecuteScheduledTransferRequest request = validRequest();
        when(accountValidationClient.validateAccountOwnership("ACC-1002", "CUST-001")).thenReturn(false);

        TransferWorkflowService.ScheduledExecutionOutcome outcome = workflowService.executeScheduledTransfer(request);

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, outcome.httpStatus());
        assertEquals("REJECTED", outcome.response().status());
        assertEquals("DESTINATION_ACCOUNT_NOT_OWNED", outcome.response().statusReasonCode());
        verifyNoInteractions(ledgerClient);
    }

    @Test
    @DisplayName("Rejects with 422 when source and destination accounts are identical")
    void shouldRejectWhenSourceAndDestinationAreIdentical() {
        ExecuteScheduledTransferRequest request = validRequest();
        request.setDestinationAccountId("ACC-1001");

        TransferWorkflowService.ScheduledExecutionOutcome outcome = workflowService.executeScheduledTransfer(request);

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, outcome.httpStatus());
        assertEquals("REJECTED", outcome.response().status());
        assertEquals("IDENTICAL_SOURCE_AND_DESTINATION_ACCOUNTS", outcome.response().statusReasonCode());
        verifyNoInteractions(ledgerClient);
    }

    @Test
    @DisplayName("Rejects with 422 when external transfer beneficiary cannot be resolved")
    void shouldRejectWhenBeneficiaryCannotBeResolved() {
        ExecuteScheduledTransferRequest request = new ExecuteScheduledTransferRequest(
                "TRF-SCH-002",
                "key-sch-002",
                "SCH-100",
                "OCC-100",
                "CUST-001",
                "ACC-1001",
                null,
                "BEN-INVALID",
                "OTHER_BANK",
                new BigDecimal("500.00"),
                "INR",
                "Rent",
                Instant.now(),
                null
        );

        when(beneficiaryClient.resolveBeneficiary("BEN-INVALID", "CUST-001")).thenReturn(Optional.empty());

        TransferWorkflowService.ScheduledExecutionOutcome outcome = workflowService.executeScheduledTransfer(request);

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, outcome.httpStatus());
        assertEquals("REJECTED", outcome.response().status());
        assertEquals("BENEFICIARY_NOT_FOUND", outcome.response().statusReasonCode());
        verifyNoInteractions(ledgerClient);
    }

    @Test
    @DisplayName("New scheduled execution commits to Ledger and returns 201 Created")
    void shouldExecuteSuccessfullyToLedgerAndReturn201() {
        ExecuteScheduledTransferRequest request = validRequest();

        when(accountValidationClient.validateAccountOwnership("ACC-1001", "CUST-001")).thenReturn(true);
        when(accountValidationClient.validateAccountOwnership("ACC-1002", "CUST-001")).thenReturn(true);
        when(transferRepository.findByTransferId("TRF-SCH-001")).thenReturn(Optional.empty());
        when(fingerprintService.computeScheduledFingerprint(request)).thenReturn("hash-scheduled");
        when(idempotencyService.findExisting("CUST-001", "SCHEDULED_EXECUTION", "key-sch-001"))
                .thenReturn(Optional.empty());
        when(transferRepository.save(any(TransferWorkflow.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Instant committedAt = Instant.now();
        when(ledgerClient.executeTransfer(any(TransferWorkflow.class)))
                .thenReturn(LedgerClient.LedgerExecutionOutcome.committed("TXN-LEDGER-99", committedAt));

        TransferWorkflowService.ScheduledExecutionOutcome outcome = workflowService.executeScheduledTransfer(request);

        assertEquals(HttpStatus.CREATED, outcome.httpStatus());
        assertEquals("COMMITTED", outcome.response().status());
        assertEquals("TRF-SCH-001", outcome.response().transferId());
        assertFalse(outcome.response().retryable());

        // Verify OTP challenge was bypassed
        verifyNoInteractions(otpClient);
        verify(ledgerClient, times(1)).executeTransfer(any());
    }

    @Test
    @DisplayName("Identical committed replay returns stored outcome 200 OK without second ledger execution")
    void shouldReturn200ForIdenticalCommittedReplay() {
        ExecuteScheduledTransferRequest request = validRequest();
        Instant completedAt = Instant.now().minusSeconds(120);

        TransferWorkflow existing = new TransferWorkflow(
                "TRF-SCH-001",
                "TXN-SCH-PREV",
                "CUST-001",
                "ACC-1001",
                "ACC-1002",
                null,
                TransferType.OWN_ACCOUNT,
                new BigDecimal("250.00"),
                "INR",
                "Scheduled recurring transfer",
                "Own Account",
                "ACC-1002",
                TransferStatus.SUCCESS,
                NextAction.NONE,
                completedAt
        );
        existing.setCompletedAt(completedAt);

        when(accountValidationClient.validateAccountOwnership("ACC-1001", "CUST-001")).thenReturn(true);
        when(accountValidationClient.validateAccountOwnership("ACC-1002", "CUST-001")).thenReturn(true);
        when(transferRepository.findByTransferId("TRF-SCH-001")).thenReturn(Optional.of(existing));

        TransferWorkflowService.ScheduledExecutionOutcome outcome = workflowService.executeScheduledTransfer(request);

        assertEquals(HttpStatus.OK, outcome.httpStatus());
        assertEquals("COMMITTED", outcome.response().status());
        assertEquals("TRF-SCH-001", outcome.response().transferId());
        assertEquals("TXN-SCH-PREV", outcome.response().referenceNumber());
        assertFalse(outcome.response().retryable());

        // Crucial: No second financial execution to Ledger
        verify(ledgerClient, never()).executeTransfer(any());
    }

    @Test
    @DisplayName("Conflicting reuse of transferId returns 409 Conflict")
    void shouldReturn409ForConflictingTransferIdReuse() {
        ExecuteScheduledTransferRequest request = validRequest(); // amount = 250.00

        TransferWorkflow existingWithDifferentAmount = new TransferWorkflow(
                "TRF-SCH-001",
                "TXN-SCH-PREV",
                "CUST-001",
                "ACC-1001",
                "ACC-1002",
                null,
                TransferType.OWN_ACCOUNT,
                new BigDecimal("999.00"), // Different amount!
                "INR",
                "Scheduled recurring transfer",
                "Own Account",
                "ACC-1002",
                TransferStatus.SUCCESS,
                NextAction.NONE,
                Instant.now()
        );

        when(accountValidationClient.validateAccountOwnership("ACC-1001", "CUST-001")).thenReturn(true);
        when(accountValidationClient.validateAccountOwnership("ACC-1002", "CUST-001")).thenReturn(true);
        when(transferRepository.findByTransferId("TRF-SCH-001")).thenReturn(Optional.of(existingWithDifferentAmount));

        TransferWorkflowService.ScheduledExecutionOutcome outcome = workflowService.executeScheduledTransfer(request);

        assertEquals(HttpStatus.CONFLICT, outcome.httpStatus());
        assertEquals("REJECTED", outcome.response().status());
        assertEquals("TRANSFER_ID_REUSED_WITH_DIFFERENT_DETAILS", outcome.response().statusReasonCode());

        // No ledger call
        verify(ledgerClient, never()).executeTransfer(any());
    }

    @Test
    @DisplayName("Conflicting request fingerprint for same idempotency key throws IdempotencyConflictException")
    void shouldThrowConflictWhenIdempotencyKeyHasDifferentFingerprint() {
        ExecuteScheduledTransferRequest request = validRequest();

        when(accountValidationClient.validateAccountOwnership("ACC-1001", "CUST-001")).thenReturn(true);
        when(accountValidationClient.validateAccountOwnership("ACC-1002", "CUST-001")).thenReturn(true);
        when(transferRepository.findByTransferId("TRF-SCH-001")).thenReturn(Optional.empty());
        when(fingerprintService.computeScheduledFingerprint(request)).thenReturn("hash-current");

        TransferIdempotencyRecord existingRecord = new TransferIdempotencyRecord(
                "CUST-001", "SCHEDULED_EXECUTION", "key-sch-001", "hash-different", "TRF-SCH-PREV", "PROCESSING", Instant.now());
        when(idempotencyService.findExisting("CUST-001", "SCHEDULED_EXECUTION", "key-sch-001"))
                .thenReturn(Optional.of(existingRecord));
        doThrow(new IdempotencyConflictException("IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST"))
                .when(idempotencyService).checkConflict(existingRecord, "hash-current");

        assertThrows(IdempotencyConflictException.class, () -> workflowService.executeScheduledTransfer(request));
        verify(ledgerClient, never()).executeTransfer(any());
    }

    @Test
    @DisplayName("Ledger definitive rejection returns 422 Unprocessable Entity")
    void shouldReturn422WhenLedgerRejectsTransaction() {
        ExecuteScheduledTransferRequest request = validRequest();

        when(accountValidationClient.validateAccountOwnership("ACC-1001", "CUST-001")).thenReturn(true);
        when(accountValidationClient.validateAccountOwnership("ACC-1002", "CUST-001")).thenReturn(true);
        when(transferRepository.findByTransferId("TRF-SCH-001")).thenReturn(Optional.empty());
        when(fingerprintService.computeScheduledFingerprint(request)).thenReturn("hash-scheduled");
        when(idempotencyService.findExisting("CUST-001", "SCHEDULED_EXECUTION", "key-sch-001"))
                .thenReturn(Optional.empty());
        when(transferRepository.save(any(TransferWorkflow.class))).thenAnswer(invocation -> invocation.getArgument(0));

        when(ledgerClient.executeTransfer(any(TransferWorkflow.class)))
                .thenReturn(LedgerClient.LedgerExecutionOutcome.rejected("INSUFFICIENT_FUNDS"));

        TransferWorkflowService.ScheduledExecutionOutcome outcome = workflowService.executeScheduledTransfer(request);

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, outcome.httpStatus());
        assertEquals("REJECTED", outcome.response().status());
        assertEquals("INSUFFICIENT_FUNDS", outcome.response().statusReasonCode());
    }

    @Test
    @DisplayName("Ledger uncertain outcome returns 202 Accepted")
    void shouldReturn202WhenLedgerOutcomeIsUncertain() {
        ExecuteScheduledTransferRequest request = validRequest();

        when(accountValidationClient.validateAccountOwnership("ACC-1001", "CUST-001")).thenReturn(true);
        when(accountValidationClient.validateAccountOwnership("ACC-1002", "CUST-001")).thenReturn(true);
        when(transferRepository.findByTransferId("TRF-SCH-001")).thenReturn(Optional.empty());
        when(fingerprintService.computeScheduledFingerprint(request)).thenReturn("hash-scheduled");
        when(idempotencyService.findExisting("CUST-001", "SCHEDULED_EXECUTION", "key-sch-001"))
                .thenReturn(Optional.empty());
        when(transferRepository.save(any(TransferWorkflow.class))).thenAnswer(invocation -> invocation.getArgument(0));

        when(ledgerClient.executeTransfer(any(TransferWorkflow.class)))
                .thenReturn(LedgerClient.LedgerExecutionOutcome.uncertain("FINANCIAL_OUTCOME_PENDING_RECONCILIATION"));

        TransferWorkflowService.ScheduledExecutionOutcome outcome = workflowService.executeScheduledTransfer(request);

        assertEquals(HttpStatus.ACCEPTED, outcome.httpStatus());
        assertEquals("OUTCOME_UNKNOWN", outcome.response().status());
        assertTrue(outcome.response().retryable());
    }

    @Test
    @DisplayName("Pre-Ledger infrastructure failure returns 503 Service Unavailable")
    void shouldReturn503WhenPreLedgerFailureOccurs() {
        ExecuteScheduledTransferRequest request = validRequest();

        when(accountValidationClient.validateAccountOwnership("ACC-1001", "CUST-001")).thenReturn(true);
        when(accountValidationClient.validateAccountOwnership("ACC-1002", "CUST-001")).thenReturn(true);
        when(transferRepository.findByTransferId("TRF-SCH-001")).thenReturn(Optional.empty());
        when(fingerprintService.computeScheduledFingerprint(request)).thenReturn("hash-scheduled");
        when(idempotencyService.findExisting("CUST-001", "SCHEDULED_EXECUTION", "key-sch-001"))
                .thenReturn(Optional.empty());
        when(transferRepository.save(any(TransferWorkflow.class))).thenAnswer(invocation -> invocation.getArgument(0));

        when(ledgerClient.executeTransfer(any(TransferWorkflow.class)))
                .thenThrow(new PreLedgerException("Ledger connection pool exhausted before dispatch"));

        TransferWorkflowService.ScheduledExecutionOutcome outcome = workflowService.executeScheduledTransfer(request);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, outcome.httpStatus());
        assertEquals("FAILED_PRE_LEDGER", outcome.response().status());
        assertTrue(outcome.response().retryable());
    }

    @Test
    @DisplayName("Reconcile returns 404 when workflow record is not found")
    void shouldReturn404WhenReconcileRecordNotFound() {
        when(transferRepository.findByTransferId("TRF-NONE")).thenReturn(Optional.empty());

        TransferWorkflowService.ScheduledReconciliationOutcome outcome =
                workflowService.reconcileScheduledTransfer("TRF-NONE");

        assertEquals(HttpStatus.NOT_FOUND, outcome.httpStatus());
        assertNull(outcome.response());
    }

    @Test
    @DisplayName("Reconcile returns 200 for durable SUCCESS transfer")
    void shouldReturn200ForCommittedReconciliation() {
        Instant completedAt = Instant.now();
        TransferWorkflow workflow = new TransferWorkflow(
                "TRF-SCH-001", "TXN-1", "CUST-001", "ACC-1", "ACC-2", null,
                TransferType.OWN_ACCOUNT, new BigDecimal("100.00"), "INR", null,
                "Own", "ACC-2", TransferStatus.SUCCESS, NextAction.NONE, completedAt
        );
        workflow.setCompletedAt(completedAt);

        when(transferRepository.findByTransferId("TRF-SCH-001")).thenReturn(Optional.of(workflow));

        TransferWorkflowService.ScheduledReconciliationOutcome outcome =
                workflowService.reconcileScheduledTransfer("TRF-SCH-001");

        assertEquals(HttpStatus.OK, outcome.httpStatus());
        assertEquals("COMMITTED", outcome.response().status());
    }

    @Test
    @DisplayName("Reconcile queries Ledger and updates uncertain transfer when committed")
    void shouldUpdateUncertainTransferToCommittedViaReconciliation() {
        TransferWorkflow workflow = new TransferWorkflow(
                "TRF-SCH-001", "TXN-1", "CUST-001", "ACC-1", "ACC-2", null,
                TransferType.OWN_ACCOUNT, new BigDecimal("100.00"), "INR", null,
                "Own", "ACC-2", TransferStatus.OUTCOME_UNKNOWN, NextAction.CHECK_STATUS, Instant.now()
        );

        when(transferRepository.findByTransferId("TRF-SCH-001")).thenReturn(Optional.of(workflow));
        Instant ledgerTimestamp = Instant.now();
        when(ledgerClient.lookupOutcome("TRF-SCH-001"))
                .thenReturn(Optional.of(LedgerClient.LedgerExecutionOutcome.committed("TXN-LEDGER-1", ledgerTimestamp)));
        when(transferRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        TransferWorkflowService.ScheduledReconciliationOutcome outcome =
                workflowService.reconcileScheduledTransfer("TRF-SCH-001");

        assertEquals(HttpStatus.OK, outcome.httpStatus());
        assertEquals("COMMITTED", outcome.response().status());
        assertEquals("TXN-LEDGER-1", outcome.response().ledgerTransactionId());
        assertEquals(TransferStatus.SUCCESS, workflow.getStatus());
    }

    @Test
    @DisplayName("Reconcile retains uncertain status and returns 202 when Ledger lookup returns 404 (empty)")
    void shouldRetainUncertainStatusWhenLedgerLookupReturnsEmpty() {
        TransferWorkflow workflow = new TransferWorkflow(
                "TRF-SCH-001", "TXN-1", "CUST-001", "ACC-1", "ACC-2", null,
                TransferType.OWN_ACCOUNT, new BigDecimal("100.00"), "INR", null,
                "Own", "ACC-2", TransferStatus.OUTCOME_UNKNOWN, NextAction.CHECK_STATUS, Instant.now()
        );

        when(transferRepository.findByTransferId("TRF-SCH-001")).thenReturn(Optional.of(workflow));
        // Ledger 404 means no record found at this moment
        when(ledgerClient.lookupOutcome("TRF-SCH-001")).thenReturn(Optional.empty());

        TransferWorkflowService.ScheduledReconciliationOutcome outcome =
                workflowService.reconcileScheduledTransfer("TRF-SCH-001");

        // Must NOT mark as failed! Must return 202 Accepted.
        assertEquals(HttpStatus.ACCEPTED, outcome.httpStatus());
        assertEquals("OUTCOME_UNKNOWN", outcome.response().status());
        assertTrue(outcome.response().retryable());
        assertEquals(TransferStatus.OUTCOME_UNKNOWN, workflow.getStatus());
    }
}
