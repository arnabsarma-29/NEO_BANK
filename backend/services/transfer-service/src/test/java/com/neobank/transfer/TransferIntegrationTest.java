package com.neobank.transfer;

import com.neobank.transfer.client.DefaultOtpClient;
import com.neobank.transfer.client.LedgerClient;
import com.neobank.transfer.client.OtpClient;
import com.neobank.transfer.domain.NextAction;
import com.neobank.transfer.domain.TransferStatus;
import com.neobank.transfer.domain.TransferType;
import com.neobank.transfer.domain.TransferWorkflow;
import com.neobank.transfer.dto.*;
import com.neobank.transfer.exception.IdempotencyConflictException;
import com.neobank.transfer.exception.InvalidTransferStateException;
import com.neobank.transfer.exception.OtpLockoutException;
import com.neobank.transfer.exception.OtpRateLimitException;
import com.neobank.transfer.exception.OtpVerificationException;
import com.neobank.transfer.exception.TransferNotFoundException;
import com.neobank.transfer.repository.TransferIdempotencyRepository;
import com.neobank.transfer.repository.TransferRepository;
import com.neobank.transfer.service.TransferWorkflowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class TransferIntegrationTest {

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private TransferIdempotencyRepository idempotencyRepository;

    @Autowired
    private TransferWorkflowService workflowService;

    @Autowired
    private OtpClient otpClient;

    @MockitoBean
    private LedgerClient ledgerClient;

    @BeforeEach
    void setUp() {
        transferRepository.deleteAll();
        idempotencyRepository.deleteAll();
        if (otpClient instanceof DefaultOtpClient def) {
            def.clearCustomerRestriction("C1001");
            def.clearCustomerRestriction("C1002");
            def.setClock(Clock.systemUTC());
        }
    }

    @Test
    @DisplayName("End-to-End: Full transfer lifecycle from creation to confirmation, OTP verification, and Ledger commit")
    void shouldCompleteFullTransferLifecycle() {
        // Mock authoritative ledger commit
        Instant now = Instant.now();
        when(ledgerClient.executeTransfer(any(TransferWorkflow.class)))
                .thenReturn(LedgerClient.LedgerExecutionOutcome.committed("TXN-LEDGER-101", now));

        // 1. Create Transfer
        CreateTransferRequest createReq = new CreateTransferRequest(
                "ACC-1001", TransferType.OWN_ACCOUNT, "ACC-1002", null,
                new BigDecimal("2500.00"), "INR", "Rent payment");

        TransferWorkflowService.CreateTransferResult createResult =
                workflowService.createTransfer(createReq, "idemp-key-101", "C1001");

        assertThat(createResult.isNew()).isTrue();
        TransferWorkflow workflow = createResult.transfer();
        assertThat(workflow.getTransferId()).startsWith("TRF-");
        assertThat(workflow.getStatus()).isEqualTo(TransferStatus.PENDING_CONFIRMATION);
        assertThat(workflow.getNextAction()).isEqualTo(NextAction.CONFIRM_TRANSFER);

        // 2. Request Idempotency: Replaying with identical key returns existing transfer
        TransferWorkflowService.CreateTransferResult repeatResult =
                workflowService.createTransfer(createReq, "idemp-key-101", "C1001");
        assertThat(repeatResult.isNew()).isFalse();
        assertThat(repeatResult.transfer().getTransferId()).isEqualTo(workflow.getTransferId());

        // 3. Request Idempotency: Replaying with different payload throws Conflict
        CreateTransferRequest conflictingReq = new CreateTransferRequest(
                "ACC-1001", TransferType.OWN_ACCOUNT, "ACC-1002", null,
                new BigDecimal("9999.00"), "INR", "Different amount");
        assertThatThrownBy(() -> workflowService.createTransfer(conflictingReq, "idemp-key-101", "C1001"))
                .isInstanceOf(IdempotencyConflictException.class);

        // 4. Confirm Transfer (initiates OTP challenge under ADR-011)
        ConfirmTransferResponse confirmResp = workflowService.confirmTransfer(workflow.getTransferId(), "C1001");
        assertThat(confirmResp.getStatus()).isEqualTo(TransferStatus.AWAITING_OTP);
        assertThat(confirmResp.getNextAction()).isEqualTo(NextAction.COMPLETE_OTP_VERIFICATION);
        assertThat(confirmResp.getChallengeId()).isNotNull();

        // 5. Submit OTP Verification
        VerifyTransferOtpRequest verifyReq = new VerifyTransferOtpRequest(confirmResp.getChallengeId(), "123456");
        TransferWorkflow verifiedWorkflow = workflowService.verifyOtp(workflow.getTransferId(), "C1001", verifyReq);

        assertThat(verifiedWorkflow.getStatus()).isEqualTo(TransferStatus.SUCCESS);
        assertThat(verifiedWorkflow.getNextAction()).isEqualTo(NextAction.NONE);
        assertThat(verifiedWorkflow.getCompletedAt()).isNotNull();

        // 6. Query Transfer Status
        TransferWorkflow fetched = workflowService.getTransfer(workflow.getTransferId(), "C1001");
        assertThat(fetched.getStatus()).isEqualTo(TransferStatus.SUCCESS);
        assertThat(fetched.getAmount()).isEqualByComparingTo("2500.0000");

        // 7. Resource Privacy: Another customer querying the transfer gets 404 (TransferNotFoundException)
        assertThatThrownBy(() -> workflowService.getTransfer(workflow.getTransferId(), "C2002"))
                .isInstanceOf(TransferNotFoundException.class);
    }

    @Test
    @DisplayName("ADR-019: Fifth incorrect OTP attempt locks challenge, reliably persists REJECTED status, and blocks reopening")
    void shouldPersistTerminalRejectionOnFifthIncorrectOtpAttemptAndBlockFurtherOperations() {
        // 1. Create and confirm transfer
        CreateTransferRequest createReq = new CreateTransferRequest(
                "ACC-1001", TransferType.OWN_ACCOUNT, "ACC-1002", null,
                new BigDecimal("1500.00"), "INR", "Lockout test");
        TransferWorkflow workflow = workflowService.createTransfer(createReq, "idemp-lockout-1", "C1001").transfer();
        ConfirmTransferResponse confirmResp = workflowService.confirmTransfer(workflow.getTransferId(), "C1001");
        String challengeId = confirmResp.getChallengeId();
        String transferId = workflow.getTransferId();

        // 2. Submit incorrect attempts 1 to 4 -> transfer must remain AWAITING_OTP in DB
        for (int i = 1; i <= 4; i++) {
            final int attempt = i;
            assertThatThrownBy(() -> workflowService.verifyOtp(transferId, "C1001", new VerifyTransferOtpRequest(challengeId, "000000")))
                    .isInstanceOf(OtpVerificationException.class)
                    .hasMessageContaining("Attempt " + attempt + " of 5");

            TransferWorkflow inFlight = transferRepository.findByTransferIdAndCustomerId(transferId, "C1001").orElseThrow();
            assertThat(inFlight.getStatus()).isEqualTo(TransferStatus.AWAITING_OTP);
        }

        // 3. Submit 5th incorrect attempt -> throws OtpLockoutException (mapped to 429)
        assertThatThrownBy(() -> workflowService.verifyOtp(transferId, "C1001", new VerifyTransferOtpRequest(challengeId, "000000")))
                .isInstanceOf(OtpLockoutException.class)
                .hasMessageContaining("locked for 15 minutes after 5 incorrect attempts");

        // 4. Persistence & Transaction Boundary Guarantee:
        // Even though an exception was thrown from verifyOtp, TransferRejectionService committed the REJECTED status in REQUIRES_NEW
        TransferWorkflow rejectedDb = transferRepository.findByTransferIdAndCustomerId(transferId, "C1001").orElseThrow();
        assertThat(rejectedDb.getStatus()).isEqualTo(TransferStatus.REJECTED);
        assertThat(rejectedDb.getStatusReasonCode()).isEqualTo("OTP_ATTEMPTS_EXCEEDED");
        assertThat(rejectedDb.getNextAction()).isEqualTo(NextAction.NONE);

        // 5. Query status returns REJECTED with reason code
        TransferWorkflow fetched = workflowService.getTransfer(transferId, "C1001");
        assertThat(fetched.getStatus()).isEqualTo(TransferStatus.REJECTED);
        assertThat(fetched.getStatusReasonCode()).isEqualTo("OTP_ATTEMPTS_EXCEEDED");

        // 6. Immutability: Verification, confirmation, and resend cannot reopen or modify the rejected transfer
        assertThatThrownBy(() -> workflowService.verifyOtp(transferId, "C1001", new VerifyTransferOtpRequest(challengeId, "123456")))
                .isInstanceOf(InvalidTransferStateException.class)
                .hasMessageContaining("Transfer is not in AWAITING_OTP state: REJECTED");

        assertThatThrownBy(() -> workflowService.confirmTransfer(transferId, "C1001"))
                .isInstanceOf(InvalidTransferStateException.class)
                .hasMessageContaining("Transfer state does not permit confirmation: REJECTED");

        assertThatThrownBy(() -> workflowService.resendOtp(transferId, "C1001", new ResendTransferOtpRequest(challengeId)))
                .isInstanceOf(InvalidTransferStateException.class)
                .hasMessageContaining("Transfer is not awaiting OTP: REJECTED");
    }

    @Test
    @DisplayName("ADR-019: Resend cooldown error does not reject the transfer")
    void shouldNotRejectTransferOnResendCooldownError() {
        CreateTransferRequest createReq = new CreateTransferRequest(
                "ACC-1001", TransferType.OWN_ACCOUNT, "ACC-1002", null,
                new BigDecimal("500.00"), "INR", "Cooldown test");
        TransferWorkflow workflow = workflowService.createTransfer(createReq, "idemp-cooldown-1", "C1001").transfer();
        ConfirmTransferResponse confirmResp = workflowService.confirmTransfer(workflow.getTransferId(), "C1001");
        String transferId = workflow.getTransferId();

        // Resend immediately (within 30 seconds) -> triggers cooldown OtpRateLimitException
        assertThatThrownBy(() -> workflowService.resendOtp(transferId, "C1001", new ResendTransferOtpRequest(confirmResp.getChallengeId())))
                .isInstanceOf(OtpRateLimitException.class)
                .hasMessageContaining("cooldown active");

        // Verify transfer in database remains AWAITING_OTP (cooldown must NOT reject the transfer)
        TransferWorkflow dbWorkflow = transferRepository.findByTransferIdAndCustomerId(transferId, "C1001").orElseThrow();
        assertThat(dbWorkflow.getStatus()).isEqualTo(TransferStatus.AWAITING_OTP);
        assertThat(dbWorkflow.getNextAction()).isEqualTo(NextAction.COMPLETE_OTP_VERIFICATION);
        assertThat(dbWorkflow.getStatusReasonCode()).isNull();
    }

    @Test
    @DisplayName("ADR-019 & ADR-020: 15-minute restriction prevents customer from initiating new transfers until expiry")
    void shouldEnforceCustomerLockoutAcrossTransfersUntilExpiry() {
        when(ledgerClient.executeTransfer(any(TransferWorkflow.class)))
                .thenReturn(LedgerClient.LedgerExecutionOutcome.committed("TXN-RETRY-101", Instant.now()));

        Instant t0 = Instant.parse("2026-10-13T12:00:00Z");
        DefaultOtpClient defOtp = (DefaultOtpClient) otpClient;
        defOtp.setClock(Clock.fixed(t0, ZoneOffset.UTC));

        // 1. Initial transfer gets rejected after 5 failed attempts
        CreateTransferRequest req1 = new CreateTransferRequest(
                "ACC-1001", TransferType.OWN_ACCOUNT, "ACC-1002", null,
                new BigDecimal("300.00"), "INR", "Failed first try");
        TransferWorkflow t1 = workflowService.createTransfer(req1, "idemp-attempt-1", "C1001").transfer();
        ConfirmTransferResponse c1 = workflowService.confirmTransfer(t1.getTransferId(), "C1001");

        for (int i = 0; i < 5; i++) {
            try {
                workflowService.verifyOtp(t1.getTransferId(), "C1001", new VerifyTransferOtpRequest(c1.getChallengeId(), "WRONG"));
            } catch (Exception ignored) {}
        }
        assertThat(transferRepository.findByTransferIdAndCustomerId(t1.getTransferId(), "C1001").orElseThrow().getStatus())
                .isEqualTo(TransferStatus.REJECTED);

        // 2. Immediate retry during active restriction is BLOCKED at authentication boundary
        CreateTransferRequest req2 = new CreateTransferRequest(
                "ACC-1001", TransferType.OWN_ACCOUNT, "ACC-1002", null,
                new BigDecimal("300.00"), "INR", "Retry new transfer");

        assertThatThrownBy(() -> workflowService.createTransfer(req2, "idemp-attempt-2", "C1001"))
                .isInstanceOf(OtpRateLimitException.class)
                .hasMessageContaining("Transfer initiation temporarily restricted for 15 minutes");

        // Verify NO new transfer record was created in the database
        assertThat(transferRepository.findAll()).hasSize(1);
        assertThat(idempotencyRepository.findAll()).hasSize(1);

        // 3. A different customer C1002 is NOT restricted
        CreateTransferRequest reqOther = new CreateTransferRequest(
                "ACC-1001", TransferType.OWN_ACCOUNT, "ACC-1002", null,
                new BigDecimal("150.00"), "INR", "Other customer transfer");
        TransferWorkflow tOther = workflowService.createTransfer(reqOther, "idemp-other-1", "C1002").transfer();
        assertThat(tOther.getStatus()).isEqualTo(TransferStatus.PENDING_CONFIRMATION);

        // 4. Advance clock by 15 minutes (now >= restrictedUntil)
        defOtp.setClock(Clock.fixed(t0.plus(Duration.ofMinutes(15)), ZoneOffset.UTC));

        // Customer C1001 can now initiate a new transfer
        TransferWorkflow t2 = workflowService.createTransfer(req2, "idemp-attempt-2", "C1001").transfer();
        assertThat(t2.getTransferId()).isNotEqualTo(t1.getTransferId());
        assertThat(t2.getStatus()).isEqualTo(TransferStatus.PENDING_CONFIRMATION);

        ConfirmTransferResponse c2 = workflowService.confirmTransfer(t2.getTransferId(), "C1001");
        assertThat(c2.getStatus()).isEqualTo(TransferStatus.AWAITING_OTP);
        assertThat(c2.getChallengeId()).isNotEqualTo(c1.getChallengeId());

        // New transfer can be verified and committed
        TransferWorkflow success = workflowService.verifyOtp(t2.getTransferId(), "C1001",
                new VerifyTransferOtpRequest(c2.getChallengeId(), "123456"));
        assertThat(success.getStatus()).isEqualTo(TransferStatus.SUCCESS);

        // Original transfer t1 remains permanently REJECTED
        TransferWorkflow originalDb = transferRepository.findByTransferIdAndCustomerId(t1.getTransferId(), "C1001").orElseThrow();
        assertThat(originalDb.getStatus()).isEqualTo(TransferStatus.REJECTED);
        assertThat(originalDb.getStatusReasonCode()).isEqualTo("OTP_ATTEMPTS_EXCEEDED");
    }
}
