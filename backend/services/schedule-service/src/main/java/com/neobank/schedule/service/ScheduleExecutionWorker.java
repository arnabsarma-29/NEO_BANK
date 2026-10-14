package com.neobank.schedule.service;

import com.neobank.schedule.client.*;
import com.neobank.schedule.domain.*;
import com.neobank.schedule.exception.StaleClaimException;
import com.neobank.schedule.repository.ScheduledOccurrenceRepository;
import com.neobank.schedule.repository.ScheduledTransferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ScheduleExecutionWorker {

    private static final Logger log = LoggerFactory.getLogger(ScheduleExecutionWorker.class);

    private final ScheduledTransferRepository transferRepository;
    private final ScheduledOccurrenceRepository occurrenceRepository;
    private final OccurrenceClaimService claimService;
    private final TransferServiceClient transferServiceClient;
    private final TimeProvider timeProvider;

    public ScheduleExecutionWorker(ScheduledTransferRepository transferRepository,
                                   ScheduledOccurrenceRepository occurrenceRepository,
                                   OccurrenceClaimService claimService,
                                   TransferServiceClient transferServiceClient,
                                   TimeProvider timeProvider) {
        this.transferRepository = transferRepository;
        this.occurrenceRepository = occurrenceRepository;
        this.claimService = claimService;
        this.transferServiceClient = transferServiceClient;
        this.timeProvider = timeProvider;
    }

    /**
     * Polls and processes all due occurrences up to the limit using database time.
     */
    public List<ExecutionReport> processDueOccurrences(String workerId, int limit) {
        return processDueOccurrences(workerId, limit, timeProvider.getCurrentTime());
    }

    /**
     * Polls and processes all due occurrences up to the limit with explicit timestamp.
     */
    public List<ExecutionReport> processDueOccurrences(String workerId, int limit, Instant now) {
        List<ScheduledOccurrence> due = occurrenceRepository.findDueOccurrences(now, PageRequest.of(0, limit));
        List<ExecutionReport> reports = new ArrayList<>();

        for (ScheduledOccurrence candidate : due) {
            try {
                ExecutionReport report = processOccurrence(candidate, workerId, now);
                reports.add(report);
            } catch (StaleClaimException e) {
                log.warn("Fencing rejected stale worker execution for occurrence [{}]: {}",
                        candidate.getOccurrenceId(), e.getMessage());
                reports.add(new ExecutionReport(candidate.getOccurrenceId(), candidate.getTransferId(),
                        false, candidate.getStatus(), "Fencing violation: " + e.getMessage()));
            } catch (Exception e) {
                log.error("Unexpected error processing occurrence [{}]: {}",
                        candidate.getOccurrenceId(), e.getMessage(), e);
                reports.add(new ExecutionReport(candidate.getOccurrenceId(), candidate.getTransferId(),
                        false, candidate.getStatus(), "Error: " + e.getMessage()));
            }
        }

        return reports;
    }

    /**
     * Core execution pipeline for a single scheduled occurrence under ADR-009 & ADR-010.
     */
    public ExecutionReport processOccurrence(ScheduledOccurrence candidate, String workerId) {
        return processOccurrence(candidate, workerId, timeProvider.getCurrentTime());
    }

    /**
     * Core execution pipeline for a single scheduled occurrence under ADR-009 & ADR-010 with explicit timestamp.
     */
    public ExecutionReport processOccurrence(ScheduledOccurrence candidate, String workerId, Instant now) {
        // 1. Atomic claim with lease and monotonic fencing token
        ClaimResult claim = claimService.claimOccurrence(
                candidate.getOccurrenceId(),
                workerId,
                candidate.getClaimVersion(),
                now
        );

        if (!claim.claimed()) {
            return new ExecutionReport(candidate.getOccurrenceId(), candidate.getTransferId(),
                    false, candidate.getStatus(), "Claim skipped: " + claim.failureReason());
        }

        long fencingToken = claim.claimVersion();
        ScheduledOccurrence occurrence = claim.occurrence();

        // 2. Validate schedule state
        ScheduledTransfer transfer = transferRepository.findById(occurrence.getScheduleId()).orElse(null);
        if (transfer == null || transfer.getStatus() != ScheduleStatus.ACTIVE) {
            claimService.recordOutcome(
                    occurrence.getOccurrenceId(),
                    workerId,
                    fencingToken,
                    OccurrenceStatus.FAILED,
                    "SCHEDULE_INACTIVE",
                    "FAILED",
                    "Associated schedule is missing or not ACTIVE",
                    occurrence.getRetryAfter(),
                    occurrence.getAttemptCount() + 1,
                    null,
                    now
            );
            return new ExecutionReport(occurrence.getOccurrenceId(), occurrence.getTransferId(),
                    true, OccurrenceStatus.FAILED, "Schedule inactive or missing");
        }

        // 3. Recovery and reconciliation under ADR-010 Section 3.6
        // If this is a recovery, retry, or uncertain state, reconcile with Transfer Service first
        if (occurrence.getAttemptCount() > 0 ||
                candidate.getStatus() == OccurrenceStatus.PROCESSING ||
                candidate.getStatus() == OccurrenceStatus.UNCERTAIN) {
            TransferStatusResponse recon = transferServiceClient.reconcileTransfer(occurrence.getTransferId());
            if ("SUCCESS".equalsIgnoreCase(recon.status())) {
                log.info("Occurrence [{}] already committed downstream. Reconciling to COMPLETED.", occurrence.getOccurrenceId());
                claimService.recordOutcome(
                        occurrence.getOccurrenceId(),
                        workerId,
                        fencingToken,
                        OccurrenceStatus.COMPLETED,
                        "RECONCILED_SUCCESS",
                        "SUCCESS",
                        null,
                        occurrence.getRetryAfter(),
                        Math.max(1, occurrence.getAttemptCount()),
                        now,
                        now
                );
                advanceSchedule(transfer, occurrence, now);
                return new ExecutionReport(occurrence.getOccurrenceId(), occurrence.getTransferId(),
                        true, OccurrenceStatus.COMPLETED, "Reconciled existing success");
            } else if ("FAILED".equalsIgnoreCase(recon.status())) {
                log.info("Occurrence [{}] confirmed permanently failed downstream.", occurrence.getOccurrenceId());
                claimService.recordOutcome(
                        occurrence.getOccurrenceId(),
                        workerId,
                        fencingToken,
                        OccurrenceStatus.FAILED,
                        "RECONCILED_FAILED",
                        "FAILED",
                        recon.failureReason(),
                        occurrence.getRetryAfter(),
                        Math.max(1, occurrence.getAttemptCount()),
                        null,
                        now
                );
                return new ExecutionReport(occurrence.getOccurrenceId(), occurrence.getTransferId(),
                        true, OccurrenceStatus.FAILED, "Reconciled existing failure: " + recon.failureReason());
            }
        }

        // 4. Verify lease ownership before initiating execution (ADR-010 safeguard)
        // A worker with an expired lease cannot initiate execution as if it still owned the occurrence
        claimService.validateActiveClaim(occurrence.getOccurrenceId(), workerId, fencingToken, now);

        // 5. Trigger execution through Transfer Service boundary (Schedule -> Transfer -> Ledger)
        // Uses STABLE transferId and STABLE idempotencyKey across retries/recovery of the same occurrence
        TransferExecutionRequest request = new TransferExecutionRequest(
                occurrence.getTransferId(),
                "IDEMP-" + occurrence.getTransferId(),
                transfer.getCustomerId(),
                transfer.getSourceAccountId(),
                transfer.getDestinationAccountId(),
                transfer.getBeneficiaryId(),
                transfer.getTransferType(),
                transfer.getAmount(),
                transfer.getCurrency(),
                transfer.getRemarks()
        );

        TransferExecutionResponse response;
        try {
            response = transferServiceClient.executeTransfer(request);
        } catch (Exception ex) {
            log.warn("Transfer Service call failed with exception: {}", ex.getMessage());
            response = TransferExecutionResponse.outcomeUnknown(occurrence.getTransferId(), ex.getMessage());
        }

        // 6. Apply outcome under ADR-009 Policy E
        int currentAttempt = occurrence.getAttemptCount() + 1;

        if ("SUCCESS".equalsIgnoreCase(response.status())) {
            claimService.recordOutcome(
                    occurrence.getOccurrenceId(),
                    workerId,
                    fencingToken,
                    OccurrenceStatus.COMPLETED,
                    null,
                    "SUCCESS",
                    null,
                    occurrence.getRetryAfter(),
                    currentAttempt,
                    now,
                    now
            );
            advanceSchedule(transfer, occurrence, now);
            return new ExecutionReport(occurrence.getOccurrenceId(), occurrence.getTransferId(),
                    true, OccurrenceStatus.COMPLETED, "Execution succeeded");
        }

        if ("OUTCOME_UNKNOWN".equalsIgnoreCase(response.status())) {
            claimService.recordOutcome(
                    occurrence.getOccurrenceId(),
                    workerId,
                    fencingToken,
                    OccurrenceStatus.UNCERTAIN,
                    "OUTCOME_UNKNOWN",
                    "UNCERTAIN",
                    response.failureReason(),
                    occurrence.getRetryAfter(),
                    currentAttempt,
                    null,
                    now
            );
            return new ExecutionReport(occurrence.getOccurrenceId(), occurrence.getTransferId(),
                    true, OccurrenceStatus.UNCERTAIN, "Outcome unknown, queued for reconciliation");
        }

        // Failure handling: permanent vs transient
        if (!response.retryable()) {
            claimService.recordOutcome(
                    occurrence.getOccurrenceId(),
                    workerId,
                    fencingToken,
                    OccurrenceStatus.FAILED,
                    "BUSINESS_REJECTION",
                    "FAILED",
                    response.failureReason(),
                    occurrence.getRetryAfter(),
                    currentAttempt,
                    null,
                    now
            );
            return new ExecutionReport(occurrence.getOccurrenceId(), occurrence.getTransferId(),
                    true, OccurrenceStatus.FAILED, "Permanent failure: " + response.failureReason());
        }

        // Transient failure: check retry budget under ADR-009 Policy E
        if (currentAttempt >= occurrence.getMaxAttempts()) {
            claimService.recordOutcome(
                    occurrence.getOccurrenceId(),
                    workerId,
                    fencingToken,
                    OccurrenceStatus.FAILED,
                    "MAX_RETRIES_EXCEEDED",
                    "FAILED",
                    response.failureReason(),
                    occurrence.getRetryAfter(),
                    currentAttempt,
                    null,
                    now
            );
            return new ExecutionReport(occurrence.getOccurrenceId(), occurrence.getTransferId(),
                    true, OccurrenceStatus.FAILED, "Max retry attempts (3) exceeded");
        }

        // Compute next retry timing under Policy E:
        // Attempt 1 failed (currentAttempt == 1) -> +1 minute
        // Attempt 2 failed (currentAttempt == 2) -> +5 minutes
        Instant retryAfter;
        if (currentAttempt == 1) {
            retryAfter = now.plus(1, ChronoUnit.MINUTES);
        } else {
            retryAfter = now.plus(5, ChronoUnit.MINUTES);
        }

        claimService.recordOutcome(
                occurrence.getOccurrenceId(),
                workerId,
                fencingToken,
                OccurrenceStatus.RETRY_PENDING,
                "TRANSIENT_RETRYABLE_ERROR",
                "RETRY_PENDING",
                response.failureReason(),
                retryAfter,
                currentAttempt,
                null,
                now
        );

        return new ExecutionReport(occurrence.getOccurrenceId(), occurrence.getTransferId(),
                true, OccurrenceStatus.RETRY_PENDING, "Retry scheduled for " + retryAfter);
    }

    /**
     * Advances the schedule after an occurrence completes.
     * Generates a genuinely NEW occurrence with a brand NEW transferId for recurring schedules (ADR-010).
     */
    @Transactional
    public void advanceSchedule(ScheduledTransfer transfer, ScheduledOccurrence occurrence, Instant now) {
        if (transfer.getScheduleType() == ScheduleType.ONE_TIME) {
            transfer.setStatus(ScheduleStatus.COMPLETED);
            transfer.setNextRunAt(null);
            transfer.setUpdatedAt(now);
            transferRepository.save(transfer);
            return;
        }

        // Recurring schedule: calculate next run time
        Instant nextRun = calculateNextRun(occurrence.getScheduledTime(), transfer.getFrequency());

        if (transfer.getEndDate() != null && nextRun.isAfter(transfer.getEndDate())) {
            transfer.setStatus(ScheduleStatus.COMPLETED);
            transfer.setNextRunAt(null);
            transfer.setUpdatedAt(now);
            transferRepository.save(transfer);
            return;
        }

        transfer.setNextRunAt(nextRun);
        transfer.setTotalOccurrences(transfer.getTotalOccurrences() + 1);
        transfer.setUpdatedAt(now);
        transferRepository.save(transfer);

        // ADR-010: Genuinely new occurrence receives brand new occurrenceId and brand new transferId
        int nextSequence = occurrence.getSequenceNumber() + 1;
        String newOccurrenceId = "OCC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String newTransferId = "TRF-SCH-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        ScheduledOccurrence nextOccurrence = new ScheduledOccurrence(
                newOccurrenceId,
                transfer.getScheduleId(),
                nextSequence,
                newTransferId,
                nextRun,
                nextRun,
                OccurrenceStatus.PENDING,
                null,
                0,
                3,
                null,
                null,
                0L,
                null,
                null,
                null,
                null,
                now,
                now
        );

        occurrenceRepository.save(nextOccurrence);
        log.info("Generated new recurrence occurrence [{}] with new transferId [{}] for schedule [{}]. Next run: {}",
                newOccurrenceId, newTransferId, transfer.getScheduleId(), nextRun);
    }

    public static Instant calculateNextRun(Instant currentRun, RecurrenceFrequency frequency) {
        if (frequency == null) {
            return currentRun.plus(1, ChronoUnit.DAYS);
        }
        return switch (frequency) {
            case DAILY -> currentRun.plus(1, ChronoUnit.DAYS);
            case WEEKLY -> currentRun.plus(7, ChronoUnit.DAYS);
            case MONTHLY -> currentRun.plus(30, ChronoUnit.DAYS);
        };
    }
}
