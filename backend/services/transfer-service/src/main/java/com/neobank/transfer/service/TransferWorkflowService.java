package com.neobank.transfer.service;

import com.neobank.transfer.client.AccountValidationClient;
import com.neobank.transfer.client.BeneficiaryClient;
import com.neobank.transfer.client.LedgerClient;
import com.neobank.transfer.client.OtpClient;
import com.neobank.transfer.domain.*;
import com.neobank.transfer.dto.*;
import com.neobank.transfer.exception.InvalidTransferStateException;
import com.neobank.transfer.exception.OtpLockoutException;
import com.neobank.transfer.exception.TransferNotFoundException;
import com.neobank.transfer.exception.ValidationException;
import com.neobank.transfer.repository.TransferRepository;
import com.neobank.transfer.exception.PreLedgerException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

@Service
public class TransferWorkflowService {

    public record CreateTransferResult(TransferWorkflow transfer, boolean isNew) {}
    public record ScheduledExecutionOutcome(HttpStatus httpStatus, ExecuteScheduledTransferResponse response) {}
    public record ScheduledReconciliationOutcome(HttpStatus httpStatus, ScheduledTransferReconcileResponse response) {}

    private final TransferRepository transferRepository;
    private final TransferIdempotencyService idempotencyService;
    private final TransferFingerprintService fingerprintService;
    private final AccountValidationClient accountValidationClient;
    private final BeneficiaryClient beneficiaryClient;
    private final OtpClient otpClient;
    private final LedgerClient ledgerClient;
    private final TransferRejectionService rejectionService;
    private final boolean requireOtpForAll;

    @Autowired
    public TransferWorkflowService(TransferRepository transferRepository,
                                   TransferIdempotencyService idempotencyService,
                                   TransferFingerprintService fingerprintService,
                                   AccountValidationClient accountValidationClient,
                                   BeneficiaryClient beneficiaryClient,
                                   OtpClient otpClient,
                                   LedgerClient ledgerClient,
                                   TransferRejectionService rejectionService,
                                   @Value("${neobank.otp.require-for-all:true}") boolean requireOtpForAll) {
        this.transferRepository = transferRepository;
        this.idempotencyService = idempotencyService;
        this.fingerprintService = fingerprintService;
        this.accountValidationClient = accountValidationClient;
        this.beneficiaryClient = beneficiaryClient;
        this.otpClient = otpClient;
        this.ledgerClient = ledgerClient;
        this.rejectionService = rejectionService != null ? rejectionService : new TransferRejectionService(transferRepository);
        this.requireOtpForAll = requireOtpForAll;
    }

    public TransferWorkflowService(TransferRepository transferRepository,
                                   TransferIdempotencyService idempotencyService,
                                   TransferFingerprintService fingerprintService,
                                   AccountValidationClient accountValidationClient,
                                   BeneficiaryClient beneficiaryClient,
                                   OtpClient otpClient,
                                   LedgerClient ledgerClient,
                                   boolean requireOtpForAll) {
        this(transferRepository, idempotencyService, fingerprintService,
             accountValidationClient, beneficiaryClient, otpClient, ledgerClient,
             new TransferRejectionService(transferRepository), requireOtpForAll);
    }

    @Transactional
    public CreateTransferResult createTransfer(CreateTransferRequest request, String idempotencyKey, String customerId) {
        idempotencyService.validateKey(idempotencyKey);

        // 1. Validate destination rules (Section 6.1.1)
        validateDestinationRules(request, customerId);

        // 2. Validate source account ownership (ADR-014)
        if (!accountValidationClient.validateAccountOwnership(request.getSourceAccountId(), customerId)) {
            throw new TransferNotFoundException("Source account not found or not owned by customer");
        }

        // 3. Compute immutable request fingerprint (ADR-008)
        String fingerprint = fingerprintService.computeFingerprint(request);

        // 4. Check existing idempotency record
        Optional<TransferIdempotencyRecord> existing = idempotencyService.findExisting(
                customerId, TransferIdempotencyService.OP_CREATE_TRANSFER, idempotencyKey);

        if (existing.isPresent()) {
            idempotencyService.checkConflict(existing.get(), fingerprint);
            TransferWorkflow existingWorkflow = transferRepository.findByTransferIdAndCustomerId(
                            existing.get().getTransferId(), customerId)
                    .orElseThrow(() -> new TransferNotFoundException("Transfer not found"));
            return new CreateTransferResult(existingWorkflow, false);
        }

        // Enforce customer-level transfer initiation restriction under ADR-020 at authentication boundary
        otpClient.validateTransferInitiation(customerId);

        // 5. Resolve server-side destination display details
        String destName = null;
        String destRef = null;
        if (request.getTransferType() == TransferType.OWN_ACCOUNT) {
            destName = "Own Account";
            destRef = request.getDestinationAccountId();
        } else {
            Optional<BeneficiaryClient.BeneficiaryDetails> bDetails =
                    beneficiaryClient.resolveBeneficiary(request.getBeneficiaryId(), customerId);
            if (bDetails.isEmpty()) {
                throw new TransferNotFoundException("Beneficiary not found or unauthorized");
            }
            destName = bDetails.get().destinationName();
            destRef = bDetails.get().destinationReference();
        }

        // 6. Generate stable transfer identifiers
        String transferId = "TRF-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String refNumber = "TXN-SIM-" + Instant.now().toEpochMilli() + "-" + (1000 + new Random().nextInt(9000));

        Instant now = Instant.now();
        TransferWorkflow workflow = new TransferWorkflow(
                transferId,
                refNumber,
                customerId,
                request.getSourceAccountId(),
                request.getDestinationAccountId(),
                request.getBeneficiaryId(),
                request.getTransferType(),
                request.getAmount(),
                request.getCurrency().trim().toUpperCase(),
                request.getRemarks(),
                destName,
                destRef,
                TransferStatus.PENDING_CONFIRMATION,
                NextAction.CONFIRM_TRANSFER,
                now
        );

        workflow = transferRepository.save(workflow);

        // 7. Register request idempotency record atomically
        idempotencyService.register(
                customerId,
                TransferIdempotencyService.OP_CREATE_TRANSFER,
                idempotencyKey,
                fingerprint,
                transferId,
                TransferStatus.PENDING_CONFIRMATION.name()
        );

        return new CreateTransferResult(workflow, true);
    }

    private void validateDestinationRules(CreateTransferRequest request, String customerId) {
        if (request.getTransferType() == TransferType.OWN_ACCOUNT) {
            if (request.getDestinationAccountId() == null || request.getDestinationAccountId().isBlank()) {
                throw new ValidationException("destinationAccountId is required for OWN_ACCOUNT transfer");
            }
            if (request.getBeneficiaryId() != null && !request.getBeneficiaryId().isBlank()) {
                throw new ValidationException("beneficiaryId must be null or omitted for OWN_ACCOUNT transfer");
            }
            if (request.getSourceAccountId().equals(request.getDestinationAccountId())) {
                throw new ValidationException("Source and destination accounts cannot be identical");
            }
            if (!accountValidationClient.validateAccountOwnership(request.getDestinationAccountId(), customerId)) {
                throw new TransferNotFoundException("Destination account not found or not owned by customer");
            }
        } else {
            if (request.getBeneficiaryId() == null || request.getBeneficiaryId().isBlank()) {
                throw new ValidationException("beneficiaryId is required for external transfer");
            }
            if (request.getDestinationAccountId() != null && !request.getDestinationAccountId().isBlank()) {
                throw new ValidationException("destinationAccountId must be null or omitted for external transfer");
            }
        }
    }

    @Transactional(readOnly = true)
    public TransferWorkflow getTransfer(String transferId, String customerId) {
        return transferRepository.findByTransferIdAndCustomerId(transferId, customerId)
                .orElseThrow(() -> new TransferNotFoundException("Transfer not found"));
    }

    @Transactional
    public ConfirmTransferResponse confirmTransfer(String transferId, String customerId) {
        TransferWorkflow workflow = getTransfer(transferId, customerId);

        if (workflow.getStatus() == TransferStatus.AWAITING_OTP) {
            // Idempotent retry of confirmation while awaiting OTP
            return new ConfirmTransferResponse(
                    workflow.getTransferId(),
                    workflow.getReferenceNumber(),
                    workflow.getStatus(),
                    workflow.getNextAction(),
                    workflow.getChallengeId(),
                    Instant.now().plusSeconds(300)
            );
        }

        if (workflow.getStatus() != TransferStatus.PENDING_CONFIRMATION) {
            throw new InvalidTransferStateException("Transfer state does not permit confirmation: " + workflow.getStatus());
        }

        boolean otpRequired = isOtpRequired(workflow);
        Instant now = Instant.now();

        if (otpRequired) {
            OtpClient.OtpChallengeResult challenge = otpClient.createChallenge(customerId, transferId);
            workflow.setStatus(TransferStatus.AWAITING_OTP);
            workflow.setNextAction(NextAction.COMPLETE_OTP_VERIFICATION);
            workflow.setChallengeId(challenge.challengeId());
            workflow.setUpdatedAt(now);
            transferRepository.save(workflow);

            return new ConfirmTransferResponse(
                    workflow.getTransferId(),
                    workflow.getReferenceNumber(),
                    workflow.getStatus(),
                    workflow.getNextAction(),
                    challenge.challengeId(),
                    challenge.expiresAt()
            );
        } else {
            workflow.setStatus(TransferStatus.READY_FOR_EXECUTION);
            workflow.setNextAction(NextAction.WAIT);
            workflow.setUpdatedAt(now);
            workflow = transferRepository.save(workflow);

            // Execute financial transaction to Ledger Service
            executeToLedger(workflow);

            return new ConfirmTransferResponse(
                    workflow.getTransferId(),
                    workflow.getReferenceNumber(),
                    workflow.getStatus(),
                    workflow.getNextAction(),
                    null,
                    null
            );
        }
    }

    @Transactional
    public TransferWorkflow verifyOtp(String transferId, String customerId, VerifyTransferOtpRequest request) {
        TransferWorkflow workflow = getTransfer(transferId, customerId);

        if (workflow.getStatus() != TransferStatus.AWAITING_OTP) {
            throw new InvalidTransferStateException("Transfer is not in AWAITING_OTP state: " + workflow.getStatus());
        }

        if (workflow.getChallengeId() == null || !workflow.getChallengeId().equals(request.getChallengeId())) {
            throw new ValidationException("Challenge does not match transfer");
        }

        // Authoritative verification via Authentication Service
        try {
            otpClient.verifyOtp(request.getChallengeId(), request.getOtpCode());
        } catch (OtpLockoutException ex) {
            // ADR-019: Transition transfer to REJECTED reliably in a separate transaction
            rejectionService.rejectTransfer(transferId, customerId, OtpLockoutException.DEFAULT_LOCKOUT_REASON);
            throw ex;
        }

        Instant now = Instant.now();
        workflow.setStatus(TransferStatus.READY_FOR_EXECUTION);
        workflow.setNextAction(NextAction.WAIT);
        workflow.setUpdatedAt(now);
        workflow = transferRepository.save(workflow);

        // Execute financial transaction to Ledger Service
        executeToLedger(workflow);
        return workflow;
    }

    @Transactional
    public ConfirmTransferResponse resendOtp(String transferId, String customerId, ResendTransferOtpRequest request) {
        TransferWorkflow workflow = getTransfer(transferId, customerId);

        if (workflow.getStatus() != TransferStatus.AWAITING_OTP) {
            throw new InvalidTransferStateException("Transfer is not awaiting OTP: " + workflow.getStatus());
        }

        if (workflow.getChallengeId() == null || !workflow.getChallengeId().equals(request.getChallengeId())) {
            throw new ValidationException("Challenge does not match transfer");
        }

        OtpClient.OtpChallengeResult resend = otpClient.resendOtp(request.getChallengeId());
        workflow.setUpdatedAt(Instant.now());
        transferRepository.save(workflow);

        return new ConfirmTransferResponse(
                workflow.getTransferId(),
                workflow.getReferenceNumber(),
                workflow.getStatus(),
                workflow.getNextAction(),
                workflow.getChallengeId(),
                resend.expiresAt()
        );
    }

    private void executeToLedger(TransferWorkflow workflow) {
        workflow.setStatus(TransferStatus.PROCESSING);
        workflow.setNextAction(NextAction.WAIT);
        workflow.setUpdatedAt(Instant.now());
        transferRepository.save(workflow);

        LedgerClient.LedgerExecutionOutcome outcome = ledgerClient.executeTransfer(workflow);

        Instant updateTime = Instant.now();
        workflow.setUpdatedAt(updateTime);

        switch (outcome.status()) {
            case COMMITTED -> {
                workflow.setStatus(TransferStatus.SUCCESS);
                workflow.setNextAction(NextAction.NONE);
                workflow.setCompletedAt(outcome.timestamp() != null ? outcome.timestamp() : updateTime);
                workflow.setStatusReasonCode(null);
            }
            case REJECTED -> {
                workflow.setStatus(TransferStatus.REJECTED);
                workflow.setNextAction(NextAction.NONE);
                workflow.setCompletedAt(null);
                workflow.setStatusReasonCode(outcome.reasonCode() != null ? outcome.reasonCode() : "REJECTED_BY_LEDGER");
            }
            case UNCERTAIN -> {
                workflow.setStatus(TransferStatus.OUTCOME_UNKNOWN);
                workflow.setNextAction(NextAction.CHECK_STATUS);
                workflow.setCompletedAt(null);
                workflow.setStatusReasonCode(outcome.reasonCode() != null ? outcome.reasonCode() : "FINANCIAL_OUTCOME_PENDING_RECONCILIATION");
            }
        }
        transferRepository.save(workflow);
    }

    private boolean isOtpRequired(TransferWorkflow workflow) {
        if (requireOtpForAll) {
            return true;
        }
        return workflow.getTransferType() != TransferType.OWN_ACCOUNT;
    }

    public CreateTransferResponse mapToCreateResponse(TransferWorkflow t) {
        return new CreateTransferResponse(
                t.getTransferId(),
                t.getReferenceNumber(),
                t.getStatus(),
                t.getTransferType(),
                t.getSourceAccountId(),
                t.getDestinationAccountId(),
                t.getBeneficiaryId(),
                t.getDestinationName(),
                t.getDestinationReference(),
                t.getAmount(),
                t.getCurrency(),
                t.getRemarks(),
                t.getCreatedAt(),
                t.getNextAction()
        );
    }

    public TransferStatusResponse mapToStatusResponse(TransferWorkflow t) {
        return new TransferStatusResponse(
                t.getTransferId(),
                t.getReferenceNumber(),
                t.getStatus(),
                t.getTransferType(),
                t.getSourceAccountId(),
                t.getDestinationAccountId(),
                t.getBeneficiaryId(),
                t.getDestinationName(),
                t.getDestinationReference(),
                t.getAmount(),
                t.getCurrency(),
                t.getRemarks(),
                t.getCreatedAt(),
                t.getUpdatedAt(),
                t.getCompletedAt(),
                t.getNextAction(),
                t.getStatusReasonCode()
        );
    }

    @Transactional
    public ScheduledExecutionOutcome executeScheduledTransfer(ExecuteScheduledTransferRequest request) {
        // 1. Validate required fields
        if (request.getTransferId() == null || request.getTransferId().isBlank()) {
            throw new ValidationException("transferId is required");
        }
        if (request.getIdempotencyKey() == null || request.getIdempotencyKey().isBlank()) {
            throw new ValidationException("idempotencyKey is required");
        }
        if (request.getCustomerId() == null || request.getCustomerId().isBlank()) {
            throw new ValidationException("customerId is required");
        }
        if (request.getSourceAccountId() == null || request.getSourceAccountId().isBlank()) {
            throw new ValidationException("sourceAccountId is required");
        }
        if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ValidationException("amount must be positive");
        }
        if (request.getCurrency() == null || request.getCurrency().isBlank()) {
            throw new ValidationException("currency is required");
        }

        idempotencyService.validateKey(request.getIdempotencyKey());

        TransferType resolvedType = request.resolveTransferType();

        // 2. Validate transfer-type destination rules & account ownership
        String destName = null;
        String destRef = null;

        if (resolvedType == TransferType.OWN_ACCOUNT) {
            if (request.getDestinationAccountId() == null || request.getDestinationAccountId().isBlank()) {
                return new ScheduledExecutionOutcome(HttpStatus.UNPROCESSABLE_ENTITY,
                        ExecuteScheduledTransferResponse.rejected(request.getTransferId(), null, "DESTINATION_ACCOUNT_REQUIRED"));
            }
            if (request.getSourceAccountId().equals(request.getDestinationAccountId())) {
                return new ScheduledExecutionOutcome(HttpStatus.UNPROCESSABLE_ENTITY,
                        ExecuteScheduledTransferResponse.rejected(request.getTransferId(), null, "IDENTICAL_SOURCE_AND_DESTINATION_ACCOUNTS"));
            }
            if (!accountValidationClient.validateAccountOwnership(request.getDestinationAccountId(), request.getCustomerId())) {
                return new ScheduledExecutionOutcome(HttpStatus.UNPROCESSABLE_ENTITY,
                        ExecuteScheduledTransferResponse.rejected(request.getTransferId(), null, "DESTINATION_ACCOUNT_NOT_OWNED"));
            }
            destName = "Own Account";
            destRef = request.getDestinationAccountId();
        } else {
            if (request.getBeneficiaryId() != null && !request.getBeneficiaryId().isBlank()) {
                Optional<BeneficiaryClient.BeneficiaryDetails> bDetails =
                        beneficiaryClient.resolveBeneficiary(request.getBeneficiaryId(), request.getCustomerId());
                if (bDetails.isEmpty()) {
                    return new ScheduledExecutionOutcome(HttpStatus.UNPROCESSABLE_ENTITY,
                            ExecuteScheduledTransferResponse.rejected(request.getTransferId(), null, "BENEFICIARY_NOT_FOUND"));
                }
                destName = bDetails.get().destinationName();
                destRef = bDetails.get().destinationReference();
            } else if (request.getDestinationAccountId() != null && !request.getDestinationAccountId().isBlank()) {
                destName = "Account-" + request.getDestinationAccountId();
                destRef = request.getDestinationAccountId();
            }
        }

        // Validate source account ownership
        if (!accountValidationClient.validateAccountOwnership(request.getSourceAccountId(), request.getCustomerId())) {
            return new ScheduledExecutionOutcome(HttpStatus.UNPROCESSABLE_ENTITY,
                    ExecuteScheduledTransferResponse.rejected(request.getTransferId(), null, "SOURCE_ACCOUNT_NOT_OWNED"));
        }

        // 3. Check existing transferId in transferRepository
        Optional<TransferWorkflow> existingWorkflowOpt = transferRepository.findByTransferId(request.getTransferId());
        if (existingWorkflowOpt.isPresent()) {
            TransferWorkflow existing = existingWorkflowOpt.get();
            if (!isMatchingScheduledTransfer(existing, request, resolvedType)) {
                return new ScheduledExecutionOutcome(HttpStatus.CONFLICT,
                        ExecuteScheduledTransferResponse.rejected(
                                existing.getTransferId(),
                                existing.getReferenceNumber(),
                                "TRANSFER_ID_REUSED_WITH_DIFFERENT_DETAILS"
                        ));
            }
            return mapStoredWorkflowToScheduledOutcome(existing);
        }

        // 4. Compute request fingerprint and check idempotency record
        String fingerprint = fingerprintService.computeScheduledFingerprint(request);
        Optional<TransferIdempotencyRecord> existingIdemp = idempotencyService.findExisting(
                request.getCustomerId(), "SCHEDULED_EXECUTION", request.getIdempotencyKey());

        if (existingIdemp.isPresent()) {
            idempotencyService.checkConflict(existingIdemp.get(), fingerprint);
            Optional<TransferWorkflow> workflowByIdemp = transferRepository.findByTransferId(existingIdemp.get().getTransferId());
            if (workflowByIdemp.isPresent()) {
                return mapStoredWorkflowToScheduledOutcome(workflowByIdemp.get());
            }
        }

        // 5. Build durable workflow record in PROCESSING state
        String refNumber = "TXN-SCH-" + Instant.now().toEpochMilli() + "-" + (1000 + new Random().nextInt(9000));
        Instant now = Instant.now();
        TransferWorkflow workflow = new TransferWorkflow(
                request.getTransferId(),
                refNumber,
                request.getCustomerId(),
                request.getSourceAccountId(),
                request.getDestinationAccountId(),
                request.getBeneficiaryId(),
                resolvedType,
                request.getAmount(),
                request.getCurrency().trim().toUpperCase(),
                request.getRemarks(),
                destName,
                destRef,
                TransferStatus.PROCESSING,
                NextAction.WAIT,
                now
        );
        workflow = transferRepository.save(workflow);

        // Register idempotency record
        idempotencyService.register(
                request.getCustomerId(),
                "SCHEDULED_EXECUTION",
                request.getIdempotencyKey(),
                fingerprint,
                request.getTransferId(),
                TransferStatus.PROCESSING.name()
        );

        // 6. Execute financial transaction with Ledger Service (bypassing customer OTP)
        LedgerClient.LedgerExecutionOutcome outcome;
        try {
            outcome = ledgerClient.executeTransfer(workflow);
        } catch (PreLedgerException ex) {
            workflow.setStatus(TransferStatus.FAILED);
            workflow.setStatusReasonCode(ex.getReasonCode() != null ? ex.getReasonCode() : "LEDGER_UNAVAILABLE");
            workflow.setNextAction(NextAction.CHECK_STATUS);
            workflow.setUpdatedAt(Instant.now());
            transferRepository.save(workflow);
            return new ScheduledExecutionOutcome(HttpStatus.SERVICE_UNAVAILABLE,
                    ExecuteScheduledTransferResponse.failedPreLedger(workflow.getTransferId(), ex.getMessage()));
        }

        Instant updateTime = Instant.now();
        workflow.setUpdatedAt(updateTime);

        switch (outcome.status()) {
            case COMMITTED -> {
                workflow.setStatus(TransferStatus.SUCCESS);
                workflow.setNextAction(NextAction.NONE);
                workflow.setCompletedAt(outcome.timestamp() != null ? outcome.timestamp() : updateTime);
                workflow.setStatusReasonCode(null);
                transferRepository.save(workflow);
                return new ScheduledExecutionOutcome(HttpStatus.CREATED,
                        ExecuteScheduledTransferResponse.committed(workflow.getTransferId(), workflow.getReferenceNumber(), workflow.getCompletedAt()));
            }
            case REJECTED -> {
                workflow.setStatus(TransferStatus.REJECTED);
                workflow.setNextAction(NextAction.NONE);
                workflow.setCompletedAt(null);
                workflow.setStatusReasonCode(outcome.reasonCode() != null ? outcome.reasonCode() : "REJECTED_BY_LEDGER");
                transferRepository.save(workflow);
                HttpStatus status = "TRANSFER_ID_REUSED_WITH_DIFFERENT_DETAILS".equals(outcome.reasonCode())
                        ? HttpStatus.CONFLICT
                        : HttpStatus.UNPROCESSABLE_ENTITY;
                return new ScheduledExecutionOutcome(status,
                        ExecuteScheduledTransferResponse.rejected(workflow.getTransferId(), workflow.getReferenceNumber(), workflow.getStatusReasonCode()));
            }
            case UNCERTAIN -> {
                workflow.setStatus(TransferStatus.OUTCOME_UNKNOWN);
                workflow.setNextAction(NextAction.CHECK_STATUS);
                workflow.setCompletedAt(null);
                workflow.setStatusReasonCode(outcome.reasonCode() != null ? outcome.reasonCode() : "FINANCIAL_OUTCOME_PENDING_RECONCILIATION");
                transferRepository.save(workflow);
                return new ScheduledExecutionOutcome(HttpStatus.ACCEPTED,
                        ExecuteScheduledTransferResponse.outcomeUnknown(workflow.getTransferId(), workflow.getReferenceNumber(), workflow.getStatusReasonCode()));
            }
            default -> {
                return new ScheduledExecutionOutcome(HttpStatus.ACCEPTED,
                        ExecuteScheduledTransferResponse.outcomeUnknown(workflow.getTransferId(), workflow.getReferenceNumber(), "UNKNOWN_STATUS"));
            }
        }
    }

    private boolean isMatchingScheduledTransfer(TransferWorkflow existing, ExecuteScheduledTransferRequest request, TransferType resolvedType) {
        if (!Objects.equals(existing.getCustomerId(), request.getCustomerId())) {
            return false;
        }
        if (!Objects.equals(existing.getSourceAccountId(), request.getSourceAccountId())) {
            return false;
        }
        if (existing.getTransferType() != resolvedType) {
            return false;
        }
        if (existing.getAmount() == null || request.getAmount() == null
                || existing.getAmount().compareTo(request.getAmount()) != 0) {
            return false;
        }
        if (existing.getCurrency() == null || !existing.getCurrency().equalsIgnoreCase(request.getCurrency())) {
            return false;
        }
        if (resolvedType == TransferType.OWN_ACCOUNT) {
            if (!Objects.equals(existing.getDestinationAccountId(), request.getDestinationAccountId())) {
                return false;
            }
        } else {
            if (request.getBeneficiaryId() != null && !request.getBeneficiaryId().isBlank()) {
                if (!Objects.equals(existing.getBeneficiaryId(), request.getBeneficiaryId())) {
                    return false;
                }
            } else if (request.getDestinationAccountId() != null && !request.getDestinationAccountId().isBlank()) {
                if (!Objects.equals(existing.getDestinationAccountId(), request.getDestinationAccountId())) {
                    return false;
                }
            }
        }
        return true;
    }

    private ScheduledExecutionOutcome mapStoredWorkflowToScheduledOutcome(TransferWorkflow existing) {
        if (existing.getStatus() == TransferStatus.SUCCESS) {
            return new ScheduledExecutionOutcome(HttpStatus.OK,
                    ExecuteScheduledTransferResponse.committed(
                            existing.getTransferId(),
                            existing.getReferenceNumber(),
                            existing.getCompletedAt()
                    ));
        } else if (existing.getStatus() == TransferStatus.REJECTED) {
            return new ScheduledExecutionOutcome(HttpStatus.UNPROCESSABLE_ENTITY,
                    ExecuteScheduledTransferResponse.rejected(
                            existing.getTransferId(),
                            existing.getReferenceNumber(),
                            existing.getStatusReasonCode()
                    ));
        } else if (existing.getStatus() == TransferStatus.FAILED) {
            return new ScheduledExecutionOutcome(HttpStatus.SERVICE_UNAVAILABLE,
                    ExecuteScheduledTransferResponse.failedPreLedger(
                            existing.getTransferId(),
                            existing.getStatusReasonCode()
                    ));
        } else {
            // Check if Ledger outcome lookup can resolve PROCESSING or OUTCOME_UNKNOWN
            Optional<LedgerClient.LedgerExecutionOutcome> lookup = ledgerClient.lookupOutcome(existing.getTransferId());
            if (lookup.isPresent()) {
                LedgerClient.LedgerExecutionOutcome outcome = lookup.get();
                if (outcome.status() == LedgerClient.LedgerOutcomeStatus.COMMITTED) {
                    existing.setStatus(TransferStatus.SUCCESS);
                    existing.setNextAction(NextAction.NONE);
                    existing.setCompletedAt(outcome.timestamp() != null ? outcome.timestamp() : Instant.now());
                    existing.setStatusReasonCode(null);
                    existing.setUpdatedAt(Instant.now());
                    transferRepository.save(existing);
                    return new ScheduledExecutionOutcome(HttpStatus.OK,
                            ExecuteScheduledTransferResponse.committed(
                                    existing.getTransferId(),
                                    existing.getReferenceNumber(),
                                    existing.getCompletedAt()
                            ));
                } else if (outcome.status() == LedgerClient.LedgerOutcomeStatus.REJECTED) {
                    existing.setStatus(TransferStatus.REJECTED);
                    existing.setNextAction(NextAction.NONE);
                    existing.setStatusReasonCode(outcome.reasonCode() != null ? outcome.reasonCode() : "REJECTED_BY_LEDGER");
                    existing.setUpdatedAt(Instant.now());
                    transferRepository.save(existing);
                    return new ScheduledExecutionOutcome(HttpStatus.UNPROCESSABLE_ENTITY,
                            ExecuteScheduledTransferResponse.rejected(
                                    existing.getTransferId(),
                                    existing.getReferenceNumber(),
                                    existing.getStatusReasonCode()
                            ));
                }
            }
            if (existing.getStatus() == TransferStatus.PROCESSING) {
                return new ScheduledExecutionOutcome(HttpStatus.ACCEPTED,
                        ExecuteScheduledTransferResponse.processing(
                                existing.getTransferId(),
                                existing.getReferenceNumber()
                        ));
            } else {
                return new ScheduledExecutionOutcome(HttpStatus.ACCEPTED,
                        ExecuteScheduledTransferResponse.outcomeUnknown(
                                existing.getTransferId(),
                                existing.getReferenceNumber(),
                                existing.getStatusReasonCode()
                        ));
            }
        }
    }

    @Transactional
    public ScheduledReconciliationOutcome reconcileScheduledTransfer(String transferId) {
        Optional<TransferWorkflow> workflowOpt = transferRepository.findByTransferId(transferId);
        if (workflowOpt.isEmpty()) {
            return new ScheduledReconciliationOutcome(HttpStatus.NOT_FOUND, null);
        }

        TransferWorkflow workflow = workflowOpt.get();

        if (workflow.getStatus() == TransferStatus.SUCCESS) {
            return new ScheduledReconciliationOutcome(HttpStatus.OK,
                    ScheduledTransferReconcileResponse.committed(
                            workflow.getTransferId(),
                            workflow.getReferenceNumber(),
                            null,
                            workflow.getCompletedAt()
                    ));
        }

        if (workflow.getStatus() == TransferStatus.REJECTED) {
            return new ScheduledReconciliationOutcome(HttpStatus.OK,
                    ScheduledTransferReconcileResponse.rejected(
                            workflow.getTransferId(),
                            workflow.getReferenceNumber(),
                            workflow.getStatusReasonCode(),
                            workflow.getCompletedAt()
                    ));
        }

        // For PROCESSING or OUTCOME_UNKNOWN, query Ledger outcome lookup
        Optional<LedgerClient.LedgerExecutionOutcome> ledgerLookup = ledgerClient.lookupOutcome(transferId);
        if (ledgerLookup.isPresent()) {
            LedgerClient.LedgerExecutionOutcome outcome = ledgerLookup.get();
            if (outcome.status() == LedgerClient.LedgerOutcomeStatus.COMMITTED) {
                workflow.setStatus(TransferStatus.SUCCESS);
                workflow.setNextAction(NextAction.NONE);
                workflow.setCompletedAt(outcome.timestamp() != null ? outcome.timestamp() : Instant.now());
                workflow.setStatusReasonCode(null);
                workflow.setUpdatedAt(Instant.now());
                transferRepository.save(workflow);
                return new ScheduledReconciliationOutcome(HttpStatus.OK,
                        ScheduledTransferReconcileResponse.committed(
                                workflow.getTransferId(),
                                workflow.getReferenceNumber(),
                                outcome.transactionId(),
                                workflow.getCompletedAt()
                        ));
            } else if (outcome.status() == LedgerClient.LedgerOutcomeStatus.REJECTED) {
                workflow.setStatus(TransferStatus.REJECTED);
                workflow.setNextAction(NextAction.NONE);
                workflow.setStatusReasonCode(outcome.reasonCode() != null ? outcome.reasonCode() : "REJECTED_BY_LEDGER");
                workflow.setUpdatedAt(Instant.now());
                transferRepository.save(workflow);
                return new ScheduledReconciliationOutcome(HttpStatus.OK,
                        ScheduledTransferReconcileResponse.rejected(
                                workflow.getTransferId(),
                                workflow.getReferenceNumber(),
                                workflow.getStatusReasonCode(),
                                workflow.getCompletedAt()
                        ));
            }
        }

        // If Ledger returned 404 (empty) or outcome is uncertain:
        // A Ledger 404 means no execution record was found at that moment;
        // it does not prove a delayed original request can never execute.
        // Therefore, return 202 Accepted.
        if (workflow.getStatus() == TransferStatus.PROCESSING) {
            return new ScheduledReconciliationOutcome(HttpStatus.ACCEPTED,
                    ScheduledTransferReconcileResponse.processing(
                            workflow.getTransferId(),
                            workflow.getReferenceNumber()
                    ));
        } else {
            return new ScheduledReconciliationOutcome(HttpStatus.ACCEPTED,
                    ScheduledTransferReconcileResponse.uncertain(
                            workflow.getTransferId(),
                            workflow.getReferenceNumber(),
                            workflow.getStatusReasonCode()
                    ));
        }
    }
}
