package com.neobank.transfer.service;

import com.neobank.transfer.client.AccountValidationClient;
import com.neobank.transfer.client.BeneficiaryClient;
import com.neobank.transfer.client.LedgerClient;
import com.neobank.transfer.client.OtpClient;
import com.neobank.transfer.domain.*;
import com.neobank.transfer.dto.*;
import com.neobank.transfer.exception.InvalidTransferStateException;
import com.neobank.transfer.exception.OtpLockoutException;
import com.neobank.transfer.exception.OtpRateLimitException;
import com.neobank.transfer.exception.OtpVerificationException;
import com.neobank.transfer.exception.TransferNotFoundException;
import com.neobank.transfer.exception.ValidationException;
import com.neobank.transfer.repository.TransferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransferWorkflowServiceTest {

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
                true // requireOtpForAll = true
        );
    }

    @Test
    void shouldCreateOwnAccountTransferSuccessfully() {
        CreateTransferRequest request = new CreateTransferRequest(
                "ACC-1001", TransferType.OWN_ACCOUNT, "ACC-1002", null,
                new BigDecimal("500.00"), "INR", "Savings transfer");

        when(accountValidationClient.validateAccountOwnership("ACC-1001", "C1001")).thenReturn(true);
        when(accountValidationClient.validateAccountOwnership("ACC-1002", "C1001")).thenReturn(true);
        when(fingerprintService.computeFingerprint(request)).thenReturn("hash-123");
        when(idempotencyService.findExisting("C1001", TransferIdempotencyService.OP_CREATE_TRANSFER, "key-1"))
                .thenReturn(Optional.empty());
        when(transferRepository.save(any(TransferWorkflow.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TransferWorkflowService.CreateTransferResult result =
                workflowService.createTransfer(request, "key-1", "C1001");

        assertTrue(result.isNew());
        assertNotNull(result.transfer().getTransferId());
        assertEquals(TransferStatus.PENDING_CONFIRMATION, result.transfer().getStatus());
        assertEquals(NextAction.CONFIRM_TRANSFER, result.transfer().getNextAction());
        verify(idempotencyService).register(eq("C1001"), eq(TransferIdempotencyService.OP_CREATE_TRANSFER),
                eq("key-1"), eq("hash-123"), anyString(), eq("PENDING_CONFIRMATION"));
    }

    @Test
    void shouldRejectOwnAccountTransferWhenDestinationIsMissing() {
        CreateTransferRequest request = new CreateTransferRequest(
                "ACC-1001", TransferType.OWN_ACCOUNT, null, null,
                new BigDecimal("500.00"), "INR", null);

        assertThrows(ValidationException.class, () ->
                workflowService.createTransfer(request, "key-1", "C1001"));
    }

    @Test
    void shouldRejectOwnAccountTransferWhenSourceAndDestinationAreIdentical() {
        CreateTransferRequest request = new CreateTransferRequest(
                "ACC-1001", TransferType.OWN_ACCOUNT, "ACC-1001", null,
                new BigDecimal("500.00"), "INR", null);

        assertThrows(ValidationException.class, () ->
                workflowService.createTransfer(request, "key-1", "C1001"));
    }

    @Test
    void shouldRejectWhenSourceAccountNotOwnedByCustomer() {
        CreateTransferRequest request = new CreateTransferRequest(
                "ACC-1001", TransferType.OWN_ACCOUNT, "ACC-1002", null,
                new BigDecimal("500.00"), "INR", null);

        when(accountValidationClient.validateAccountOwnership("ACC-1002", "C1001")).thenReturn(true);
        when(accountValidationClient.validateAccountOwnership("ACC-1001", "C1001")).thenReturn(false);

        assertThrows(TransferNotFoundException.class, () ->
                workflowService.createTransfer(request, "key-1", "C1001"));
    }

    @Test
    void shouldConfirmTransferAndTransitionToAwaitingOtp() {
        Instant now = Instant.now();
        TransferWorkflow workflow = new TransferWorkflow(
                "TRF-1", "REF-1", "C1001", "ACC-1001", "ACC-1002", null,
                TransferType.OWN_ACCOUNT, new BigDecimal("100"), "INR", null,
                "Own", "ACC-1002", TransferStatus.PENDING_CONFIRMATION, NextAction.CONFIRM_TRANSFER, now);

        when(transferRepository.findByTransferIdAndCustomerId("TRF-1", "C1001"))
                .thenReturn(Optional.of(workflow));
        when(otpClient.createChallenge("C1001", "TRF-1"))
                .thenReturn(new OtpClient.OtpChallengeResult("OTP-999", now.plusSeconds(300)));
        when(transferRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ConfirmTransferResponse response = workflowService.confirmTransfer("TRF-1", "C1001");

        assertEquals(TransferStatus.AWAITING_OTP, response.getStatus());
        assertEquals(NextAction.COMPLETE_OTP_VERIFICATION, response.getNextAction());
        assertEquals("OTP-999", response.getChallengeId());
    }

    @Test
    void shouldRejectConfirmationWhenTransferNotInPendingConfirmation() {
        Instant now = Instant.now();
        TransferWorkflow workflow = new TransferWorkflow(
                "TRF-1", "REF-1", "C1001", "ACC-1001", "ACC-1002", null,
                TransferType.OWN_ACCOUNT, new BigDecimal("100"), "INR", null,
                "Own", "ACC-1002", TransferStatus.SUCCESS, NextAction.NONE, now);

        when(transferRepository.findByTransferIdAndCustomerId("TRF-1", "C1001"))
                .thenReturn(Optional.of(workflow));

        assertThrows(InvalidTransferStateException.class, () ->
                workflowService.confirmTransfer("TRF-1", "C1001"));
    }

    @Test
    void shouldVerifyOtpAndExecuteLedgerSuccessfully() {
        Instant now = Instant.now();
        TransferWorkflow workflow = new TransferWorkflow(
                "TRF-1", "REF-1", "C1001", "ACC-1001", "ACC-1002", null,
                TransferType.OWN_ACCOUNT, new BigDecimal("100"), "INR", null,
                "Own", "ACC-1002", TransferStatus.AWAITING_OTP, NextAction.COMPLETE_OTP_VERIFICATION, now);
        workflow.setChallengeId("OTP-123");

        when(transferRepository.findByTransferIdAndCustomerId("TRF-1", "C1001"))
                .thenReturn(Optional.of(workflow));
        when(otpClient.verifyOtp("OTP-123", "123456")).thenReturn(true);
        when(ledgerClient.executeTransfer(workflow))
                .thenReturn(LedgerClient.LedgerExecutionOutcome.committed("TXN-1", now));
        when(transferRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        TransferWorkflow result = workflowService.verifyOtp("TRF-1", "C1001",
                new VerifyTransferOtpRequest("OTP-123", "123456"));

        assertEquals(TransferStatus.SUCCESS, result.getStatus());
        assertEquals(NextAction.NONE, result.getNextAction());
        assertNotNull(result.getCompletedAt());
    }

    @Test
    void shouldHandleLedgerRejectionGracefully() {
        Instant now = Instant.now();
        TransferWorkflow workflow = new TransferWorkflow(
                "TRF-1", "REF-1", "C1001", "ACC-1001", "ACC-1002", null,
                TransferType.OWN_ACCOUNT, new BigDecimal("100"), "INR", null,
                "Own", "ACC-1002", TransferStatus.AWAITING_OTP, NextAction.COMPLETE_OTP_VERIFICATION, now);
        workflow.setChallengeId("OTP-123");

        when(transferRepository.findByTransferIdAndCustomerId("TRF-1", "C1001"))
                .thenReturn(Optional.of(workflow));
        when(otpClient.verifyOtp("OTP-123", "123456")).thenReturn(true);
        when(ledgerClient.executeTransfer(workflow))
                .thenReturn(LedgerClient.LedgerExecutionOutcome.rejected("INSUFFICIENT_FUNDS"));
        when(transferRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        TransferWorkflow result = workflowService.verifyOtp("TRF-1", "C1001",
                new VerifyTransferOtpRequest("OTP-123", "123456"));

        assertEquals(TransferStatus.REJECTED, result.getStatus());
        assertEquals(NextAction.NONE, result.getNextAction());
        assertEquals("INSUFFICIENT_FUNDS", result.getStatusReasonCode());
        assertNull(result.getCompletedAt());
    }

    @Test
    void shouldHandleUncertainLedgerOutcomeAsOutcomeUnknown() {
        Instant now = Instant.now();
        TransferWorkflow workflow = new TransferWorkflow(
                "TRF-1", "REF-1", "C1001", "ACC-1001", "ACC-1002", null,
                TransferType.OWN_ACCOUNT, new BigDecimal("100"), "INR", null,
                "Own", "ACC-1002", TransferStatus.AWAITING_OTP, NextAction.COMPLETE_OTP_VERIFICATION, now);
        workflow.setChallengeId("OTP-123");

        when(transferRepository.findByTransferIdAndCustomerId("TRF-1", "C1001"))
                .thenReturn(Optional.of(workflow));
        when(otpClient.verifyOtp("OTP-123", "123456")).thenReturn(true);
        when(ledgerClient.executeTransfer(workflow))
                .thenReturn(LedgerClient.LedgerExecutionOutcome.uncertain("FINANCIAL_OUTCOME_PENDING_RECONCILIATION"));
        when(transferRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        TransferWorkflow result = workflowService.verifyOtp("TRF-1", "C1001",
                new VerifyTransferOtpRequest("OTP-123", "123456"));

        assertEquals(TransferStatus.OUTCOME_UNKNOWN, result.getStatus());
        assertEquals(NextAction.CHECK_STATUS, result.getNextAction());
        assertEquals("FINANCIAL_OUTCOME_PENDING_RECONCILIATION", result.getStatusReasonCode());
        assertNull(result.getCompletedAt());
    }

    @Test
    void shouldRejectTransferWhenOtpLockoutOccurs() {
        Instant now = Instant.now();
        TransferWorkflow workflow = new TransferWorkflow(
                "TRF-1", "REF-1", "C1001", "ACC-1001", "ACC-1002", null,
                TransferType.OWN_ACCOUNT, new BigDecimal("100"), "INR", null,
                "Own", "ACC-1002", TransferStatus.AWAITING_OTP, NextAction.COMPLETE_OTP_VERIFICATION, now);
        workflow.setChallengeId("OTP-123");

        when(transferRepository.findByTransferIdAndCustomerId("TRF-1", "C1001"))
                .thenReturn(Optional.of(workflow));
        when(otpClient.verifyOtp("OTP-123", "000000"))
                .thenThrow(new OtpLockoutException("OTP verification locked for 15 minutes after 5 incorrect attempts"));

        assertThrows(OtpLockoutException.class, () -> workflowService.verifyOtp("TRF-1", "C1001",
                new VerifyTransferOtpRequest("OTP-123", "000000")));

        // Verifies the terminal rejection service was called with OTP_ATTEMPTS_EXCEEDED
        verify(rejectionService, times(1)).rejectTransfer("TRF-1", "C1001", "OTP_ATTEMPTS_EXCEEDED");
    }

    @Test
    void shouldNotRejectTransferWhenOrdinaryOtpVerificationFails() {
        Instant now = Instant.now();
        TransferWorkflow workflow = new TransferWorkflow(
                "TRF-1", "REF-1", "C1001", "ACC-1001", "ACC-1002", null,
                TransferType.OWN_ACCOUNT, new BigDecimal("100"), "INR", null,
                "Own", "ACC-1002", TransferStatus.AWAITING_OTP, NextAction.COMPLETE_OTP_VERIFICATION, now);
        workflow.setChallengeId("OTP-123");

        when(transferRepository.findByTransferIdAndCustomerId("TRF-1", "C1001"))
                .thenReturn(Optional.of(workflow));
        when(otpClient.verifyOtp("OTP-123", "000000"))
                .thenThrow(new OtpVerificationException("Invalid OTP code. Attempt 2 of 5"));

        assertThrows(OtpVerificationException.class, () -> workflowService.verifyOtp("TRF-1", "C1001",
                new VerifyTransferOtpRequest("OTP-123", "000000")));

        // Verifies rejectionService is NEVER called for ordinary incorrect attempt
        verify(rejectionService, never()).rejectTransfer(any(), any(), any());
    }

    @Test
    void shouldNotRejectTransferWhenResendCooldownErrorOccurs() {
        Instant now = Instant.now();
        TransferWorkflow workflow = new TransferWorkflow(
                "TRF-1", "REF-1", "C1001", "ACC-1001", "ACC-1002", null,
                TransferType.OWN_ACCOUNT, new BigDecimal("100"), "INR", null,
                "Own", "ACC-1002", TransferStatus.AWAITING_OTP, NextAction.COMPLETE_OTP_VERIFICATION, now);
        workflow.setChallengeId("OTP-123");

        when(transferRepository.findByTransferIdAndCustomerId("TRF-1", "C1001"))
                .thenReturn(Optional.of(workflow));
        when(otpClient.resendOtp("OTP-123"))
                .thenThrow(new OtpRateLimitException("OTP resend cooldown active. Please wait 30 seconds."));

        assertThrows(OtpRateLimitException.class, () -> workflowService.resendOtp("TRF-1", "C1001",
                new ResendTransferOtpRequest("OTP-123")));

        // Cooldown must not reject transfer
        verify(rejectionService, never()).rejectTransfer(any(), any(), any());
        assertEquals(TransferStatus.AWAITING_OTP, workflow.getStatus());
    }

    @Test
    void shouldRejectOperationsOnAlreadyRejectedTransfer() {
        Instant now = Instant.now();
        TransferWorkflow workflow = new TransferWorkflow(
                "TRF-1", "REF-1", "C1001", "ACC-1001", "ACC-1002", null,
                TransferType.OWN_ACCOUNT, new BigDecimal("100"), "INR", null,
                "Own", "ACC-1002", TransferStatus.REJECTED, NextAction.NONE, now);
        workflow.setChallengeId("OTP-123");
        workflow.setStatusReasonCode("OTP_ATTEMPTS_EXCEEDED");

        when(transferRepository.findByTransferIdAndCustomerId("TRF-1", "C1001"))
                .thenReturn(Optional.of(workflow));

        // verifyOtp on rejected transfer throws InvalidTransferStateException
        assertThrows(InvalidTransferStateException.class, () -> workflowService.verifyOtp("TRF-1", "C1001",
                new VerifyTransferOtpRequest("OTP-123", "123456")));

        // confirmTransfer on rejected transfer throws InvalidTransferStateException
        assertThrows(InvalidTransferStateException.class, () -> workflowService.confirmTransfer("TRF-1", "C1001"));

        // resendOtp on rejected transfer throws InvalidTransferStateException
        assertThrows(InvalidTransferStateException.class, () -> workflowService.verifyOtp("TRF-1", "C1001",
                new VerifyTransferOtpRequest("OTP-123", "123456")));

        // confirmTransfer on rejected transfer throws InvalidTransferStateException
        assertThrows(InvalidTransferStateException.class, () -> workflowService.confirmTransfer("TRF-1", "C1001"));

        // resendOtp on rejected transfer throws InvalidTransferStateException
        assertThrows(InvalidTransferStateException.class, () -> workflowService.resendOtp("TRF-1", "C1001",
                new ResendTransferOtpRequest("OTP-123")));
    }

    @Test
    void shouldBlockCreateTransferWhenCustomerRestrictedUnderAdr020() {
        CreateTransferRequest request = new CreateTransferRequest(
                "ACC-1001", TransferType.OWN_ACCOUNT, "ACC-1002", null,
                new BigDecimal("500.00"), "INR", "Savings transfer");

        when(accountValidationClient.validateAccountOwnership("ACC-1001", "C1001")).thenReturn(true);
        when(accountValidationClient.validateAccountOwnership("ACC-1002", "C1001")).thenReturn(true);
        when(fingerprintService.computeFingerprint(request)).thenReturn("hash-123");
        when(idempotencyService.findExisting("C1001", TransferIdempotencyService.OP_CREATE_TRANSFER, "key-1"))
                .thenReturn(Optional.empty());
        doThrow(new OtpRateLimitException("Transfer initiation temporarily restricted for 15 minutes due to exceeding maximum incorrect OTP attempts"))
                .when(otpClient).validateTransferInitiation("C1001");

        assertThrows(OtpRateLimitException.class, () ->
                workflowService.createTransfer(request, "key-1", "C1001"));

        verify(transferRepository, never()).save(any(TransferWorkflow.class));
        verify(idempotencyService, never()).register(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void shouldBlockConfirmTransferWhenCustomerRestrictedUnderAdr020() {
        Instant now = Instant.now();
        TransferWorkflow workflow = new TransferWorkflow(
                "TRF-2", "REF-2", "C1001", "ACC-1001", "ACC-1002", null,
                TransferType.OWN_ACCOUNT, new BigDecimal("100"), "INR", null,
                "Own", "ACC-1002", TransferStatus.PENDING_CONFIRMATION, NextAction.CONFIRM_TRANSFER, now);

        when(transferRepository.findByTransferIdAndCustomerId("TRF-2", "C1001"))
                .thenReturn(Optional.of(workflow));
        doThrow(new OtpRateLimitException("OTP challenge creation temporarily restricted for 15 minutes due to exceeding maximum incorrect attempts"))
                .when(otpClient).createChallenge("C1001", "TRF-2");

        assertThrows(OtpRateLimitException.class, () ->
                workflowService.confirmTransfer("TRF-2", "C1001"));

        // Status remains PENDING_CONFIRMATION, not moved to AWAITING_OTP
        assertEquals(TransferStatus.PENDING_CONFIRMATION, workflow.getStatus());
    }
}
