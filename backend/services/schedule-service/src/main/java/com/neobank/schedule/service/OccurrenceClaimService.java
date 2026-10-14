package com.neobank.schedule.service;

import com.neobank.schedule.domain.OccurrenceStatus;
import com.neobank.schedule.domain.ScheduledOccurrence;
import com.neobank.schedule.exception.OccurrenceNotFoundException;
import com.neobank.schedule.exception.StaleClaimException;
import com.neobank.schedule.repository.ScheduledOccurrenceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class OccurrenceClaimService {

    private static final Logger log = LoggerFactory.getLogger(OccurrenceClaimService.class);
    private static final long LEASE_DURATION_MINUTES = 5L;

    private final ScheduledOccurrenceRepository occurrenceRepository;
    private final TimeProvider timeProvider;

    public OccurrenceClaimService(ScheduledOccurrenceRepository occurrenceRepository, TimeProvider timeProvider) {
        this.occurrenceRepository = occurrenceRepository;
        this.timeProvider = timeProvider;
    }

    /**
     * Atomically claims a scheduled occurrence with an initial 5-minute lease and
     * a monotonically increasing fencing token / claim version under ADR-010.
     */
    @Transactional
    public ClaimResult claimOccurrence(String occurrenceId, String workerId, long expectedVersion) {
        return claimOccurrence(occurrenceId, workerId, expectedVersion, timeProvider.getCurrentTime());
    }

    /**
     * Atomically claims a scheduled occurrence with an explicit timestamp (for deterministic test control).
     */
    @Transactional
    public ClaimResult claimOccurrence(String occurrenceId, String workerId, long expectedVersion, Instant now) {
        Instant leaseExpiresAt = now.plus(LEASE_DURATION_MINUTES, ChronoUnit.MINUTES);

        int updated = occurrenceRepository.claimOccurrenceAtomic(
                occurrenceId,
                workerId,
                expectedVersion,
                leaseExpiresAt,
                now
        );

        if (updated == 0) {
            log.warn("Worker [{}] failed to claim occurrence [{}]. Concurrency conflict, active lease held, or not due.",
                    workerId, occurrenceId);
            return ClaimResult.failed("Claim rejected: concurrency conflict or active lease held by another worker");
        }

        ScheduledOccurrence claimed = occurrenceRepository.findById(occurrenceId)
                .orElseThrow(() -> new OccurrenceNotFoundException("Occurrence not found after claim: " + occurrenceId));

        log.info("Worker [{}] successfully claimed occurrence [{}]. New claimVersion: {}, leaseExpiresAt: {}",
                workerId, occurrenceId, claimed.getClaimVersion(), claimed.getLeaseExpiresAt());

        return ClaimResult.success(claimed, claimed.getClaimVersion());
    }

    /**
     * Renews the 5-minute lease every minute while actively processing under ADR-010.
     * Enforces that the renewing worker still matches current claim version, worker ID, and has active lease.
     */
    @Transactional
    public boolean renewLease(String occurrenceId, String workerId, long claimVersion) {
        return renewLease(occurrenceId, workerId, claimVersion, timeProvider.getCurrentTime());
    }

    /**
     * Renews lease with explicit timestamp for deterministic testing.
     */
    @Transactional
    public boolean renewLease(String occurrenceId, String workerId, long claimVersion, Instant now) {
        Instant newLeaseExpiresAt = now.plus(LEASE_DURATION_MINUTES, ChronoUnit.MINUTES);

        int updated = occurrenceRepository.renewLease(
                occurrenceId,
                workerId,
                claimVersion,
                newLeaseExpiresAt,
                now
        );

        if (updated == 0) {
            String msg = String.format("Worker [%s] failed to renew lease on occurrence [%s] with version %d. " +
                    "Claim is stale, expired, or was acquired by another worker.", workerId, occurrenceId, claimVersion);
            log.error(msg);
            throw new StaleClaimException(msg);
        }

        log.debug("Worker [{}] successfully renewed lease on occurrence [{}] (version {}). New leaseExpiresAt: {}",
                workerId, occurrenceId, claimVersion, newLeaseExpiresAt);
        return true;
    }

    /**
     * Validates that the worker currently owns an active, unexpired claim under ADR-010.
     * Must be called before initiating execution to prevent workers with expired leases from running tasks.
     */
    @Transactional(readOnly = true)
    public void validateActiveClaim(String occurrenceId, String workerId, long claimVersion, Instant now) {
        ScheduledOccurrence occ = occurrenceRepository.findById(occurrenceId)
                .orElseThrow(() -> new OccurrenceNotFoundException("Occurrence not found: " + occurrenceId));

        if (!workerId.equals(occ.getClaimOwner()) ||
                occ.getClaimVersion() != claimVersion ||
                occ.getStatus() != OccurrenceStatus.PROCESSING ||
                occ.getLeaseExpiresAt() == null ||
                !occ.getLeaseExpiresAt().isAfter(now)) {
            String msg = String.format("Worker [%s] claim or lease is no longer active for occurrence [%s] (version %d). " +
                    "Status: %s, Owner: %s, LeaseExpiresAt: %s, Now: %s",
                    workerId, occurrenceId, claimVersion, occ.getStatus(), occ.getClaimOwner(), occ.getLeaseExpiresAt(), now);
            log.warn(msg);
            throw new StaleClaimException(msg);
        }
    }

    /**
     * Records execution outcome with strict claim-version, owner, and active lease fencing under ADR-010.
     * If the claim version is stale, worker is not owner, or lease expired, update is rejected.
     */
    @Transactional
    public void recordOutcome(String occurrenceId, String workerId, long claimVersion, OccurrenceStatus newStatus,
                              String statusReasonCode, String executionOutcome, String failureReason,
                              Instant retryAfter, int attemptCount, Instant completedAt, Instant now) {
        int updated = occurrenceRepository.recordExecutionOutcome(
                occurrenceId,
                workerId,
                claimVersion,
                newStatus,
                statusReasonCode,
                executionOutcome,
                failureReason,
                retryAfter,
                attemptCount,
                completedAt,
                now
        );

        if (updated == 0) {
            String msg = String.format("Failed to record outcome on occurrence [%s] by worker [%s] with claimVersion %d. " +
                    "Claim is stale, expired, or owned by another worker. Fencing token rejected the update.",
                    occurrenceId, workerId, claimVersion);
            log.error(msg);
            throw new StaleClaimException(msg);
        }

        log.info("Recorded outcome [{}] on occurrence [{}] (version {} by worker [{}]). Attempt count: {}",
                newStatus, occurrenceId, claimVersion, workerId, attemptCount);
    }
}
