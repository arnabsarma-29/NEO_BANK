package com.neobank.schedule;

import com.neobank.schedule.domain.*;
import com.neobank.schedule.exception.StaleClaimException;
import com.neobank.schedule.repository.ScheduledOccurrenceRepository;
import com.neobank.schedule.repository.ScheduledTransferRepository;
import com.neobank.schedule.service.ClaimResult;
import com.neobank.schedule.service.OccurrenceClaimService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class OccurrenceClaimSafeguardsIntegrationTest {

    @Autowired
    private ScheduledOccurrenceRepository occurrenceRepository;

    @Autowired
    private ScheduledTransferRepository transferRepository;

    @Autowired
    private OccurrenceClaimService claimService;

    private ScheduledTransfer testSchedule;
    private ScheduledOccurrence testOccurrence;
    private final Instant baseTime = Instant.parse("2026-10-15T10:00:00Z");

    @BeforeEach
    void setup() {
        occurrenceRepository.deleteAll();
        transferRepository.deleteAll();

        testSchedule = new ScheduledTransfer(
                "SCH-TEST-001",
                "C1001",
                "ACC-SRC-1",
                "ACC-DST-2",
                null,
                "INTERNAL",
                new BigDecimal("150.00"),
                "USD",
                "Safeguard test schedule",
                ScheduleType.ONE_TIME,
                null,
                baseTime,
                null,
                baseTime,
                ScheduleStatus.ACTIVE,
                1,
                baseTime,
                baseTime
        );
        transferRepository.save(testSchedule);

        testOccurrence = new ScheduledOccurrence(
                "OCC-TEST-001",
                testSchedule.getScheduleId(),
                1,
                "TRF-SCH-001",
                baseTime,
                baseTime,
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
                baseTime,
                baseTime
        );
        occurrenceRepository.save(testOccurrence);
    }

    @Test
    @DisplayName("Safeguard 1: Atomic claiming assigns initial 5-minute lease and bumps claimVersion to 1")
    void testAtomicClaim_SuccessfulInitialClaim() {
        ClaimResult result = claimService.claimOccurrence("OCC-TEST-001", "worker-A", 0L, baseTime);

        assertTrue(result.claimed(), "Worker A must successfully claim the occurrence");
        assertEquals(1L, result.claimVersion(), "First successful claim must have claimVersion = 1");

        ScheduledOccurrence updated = occurrenceRepository.findById("OCC-TEST-001").orElseThrow();
        assertEquals("worker-A", updated.getClaimOwner());
        assertEquals(OccurrenceStatus.PROCESSING, updated.getStatus());
        assertEquals(1L, updated.getClaimVersion());
        assertEquals(baseTime.plus(5, ChronoUnit.MINUTES), updated.getLeaseExpiresAt(),
                "Lease expiration must be exactly 5 minutes after claim time");
    }

    @Test
    @DisplayName("Safeguard 2: Competing workers concurrency fencing - Worker 2 rejected when Worker 1 acquires claim")
    void testCompetingWorkers_ConcurrencyFencing() {
        ClaimResult worker1Result = claimService.claimOccurrence("OCC-TEST-001", "worker-1", 0L, baseTime);
        assertTrue(worker1Result.claimed());

        // Worker 2 attempts to claim with stale version 0L
        ClaimResult worker2Result = claimService.claimOccurrence("OCC-TEST-001", "worker-2", 0L, baseTime);
        assertFalse(worker2Result.claimed(), "Worker 2 must be rejected due to version mismatch");

        // Worker 2 attempts to claim with version 1L but Worker 1 lease is still active
        ClaimResult worker2AttemptWithNewVersion = claimService.claimOccurrence("OCC-TEST-001", "worker-2", 1L, baseTime.plus(1, ChronoUnit.MINUTES));
        assertFalse(worker2AttemptWithNewVersion.claimed(), "Worker 2 cannot take occurrence while Worker 1 active lease is valid");
    }

    @Test
    @DisplayName("Safeguard 3: Active lease renewal extends 5-minute lease from current renewal time")
    void testLeaseRenewal_SuccessWhileActive() {
        claimService.claimOccurrence("OCC-TEST-001", "worker-A", 0L, baseTime);

        Instant renewalTime = baseTime.plus(1, ChronoUnit.MINUTES);
        boolean renewed = claimService.renewLease("OCC-TEST-001", "worker-A", 1L, renewalTime);
        assertTrue(renewed);

        ScheduledOccurrence refreshed = occurrenceRepository.findById("OCC-TEST-001").orElseThrow();
        assertEquals(renewalTime.plus(5, ChronoUnit.MINUTES), refreshed.getLeaseExpiresAt(),
                "Renewed lease must extend 5 minutes from the renewal timestamp");
    }

    @Test
    @DisplayName("Safeguard 4: Lease renewal rejected for stale worker / mismatched claim version")
    void testLeaseRenewal_RejectedForStaleClaimVersion() {
        claimService.claimOccurrence("OCC-TEST-001", "worker-A", 0L, baseTime);

        // Attempt renewal with stale version 0L
        assertThrows(StaleClaimException.class, () ->
                claimService.renewLease("OCC-TEST-001", "worker-A", 0L, baseTime.plus(1, ChronoUnit.MINUTES)));

        // Attempt renewal by unauthorized worker-B
        assertThrows(StaleClaimException.class, () ->
                claimService.renewLease("OCC-TEST-001", "worker-B", 1L, baseTime.plus(1, ChronoUnit.MINUTES)));
    }

    @Test
    @DisplayName("Safeguard 5: Expired lease renewal rejected - worker cannot renew once lease has elapsed")
    void testLeaseRenewal_RejectedAfterLeaseExpiry() {
        claimService.claimOccurrence("OCC-TEST-001", "worker-A", 0L, baseTime);

        // Advance time past 5-minute lease (e.g. 6 minutes later)
        Instant pastExpiry = baseTime.plus(6, ChronoUnit.MINUTES);

        assertThrows(StaleClaimException.class, () ->
                claimService.renewLease("OCC-TEST-001", "worker-A", 1L, pastExpiry));
    }

    @Test
    @DisplayName("Safeguard 6: Expired lease recovery - Worker B recovers occurrence with monotonically higher version 2")
    void testRecovery_ExpiredLeaseClaimableByNewWorker() {
        claimService.claimOccurrence("OCC-TEST-001", "worker-A", 0L, baseTime);

        // At baseTime + 6 minutes, Worker A's lease is expired
        Instant recoveryTime = baseTime.plus(6, ChronoUnit.MINUTES);

        ClaimResult recoveryResult = claimService.claimOccurrence("OCC-TEST-001", "worker-B", 1L, recoveryTime);
        assertTrue(recoveryResult.claimed(), "Worker B must successfully claim expired occurrence");
        assertEquals(2L, recoveryResult.claimVersion(), "Recovered occurrence receives monotonically bumped claimVersion = 2");

        ScheduledOccurrence updated = occurrenceRepository.findById("OCC-TEST-001").orElseThrow();
        assertEquals("worker-B", updated.getClaimOwner());
        assertEquals(2L, updated.getClaimVersion());
        assertEquals(recoveryTime.plus(5, ChronoUnit.MINUTES), updated.getLeaseExpiresAt());
    }

    @Test
    @DisplayName("Safeguard 7: Fencing token rejects stale worker outcome after recovery by newer worker")
    void testStaleWorkerOutcomeRejection_HigherVersionClaimed() {
        claimService.claimOccurrence("OCC-TEST-001", "worker-A", 0L, baseTime);

        // Worker B recovers at T+6m
        Instant recoveryTime = baseTime.plus(6, ChronoUnit.MINUTES);
        claimService.claimOccurrence("OCC-TEST-001", "worker-B", 1L, recoveryTime);

        // Worker A (resuming with stale version 1L) attempts to record outcome
        assertThrows(StaleClaimException.class, () ->
                claimService.recordOutcome(
                        "OCC-TEST-001",
                        "worker-A",
                        1L,
                        OccurrenceStatus.COMPLETED,
                        null,
                        "SUCCESS",
                        null,
                        baseTime,
                        1,
                        recoveryTime,
                        recoveryTime
                ));
    }

    @Test
    @DisplayName("Safeguard 8: Expired lease outcome rejection - worker cannot record outcome once lease elapsed even if unclaimed")
    void testExpiredLeaseOutcomeRejection_EvenWithoutNewClaimant() {
        claimService.claimOccurrence("OCC-TEST-001", "worker-A", 0L, baseTime);

        // At T+6m, no other worker has claimed yet (version in DB is still 1L)
        Instant pastExpiry = baseTime.plus(6, ChronoUnit.MINUTES);

        assertThrows(StaleClaimException.class, () ->
                claimService.recordOutcome(
                        "OCC-TEST-001",
                        "worker-A",
                        1L,
                        OccurrenceStatus.COMPLETED,
                        null,
                        "SUCCESS",
                        null,
                        baseTime,
                        1,
                        pastExpiry,
                        pastExpiry
                ));
    }

    @Test
    @DisplayName("Safeguard 9: Future retryAfter occurrences cannot be claimed before due time")
    void testCannotClaimFutureRetryAfter() {
        // Occurrence retryAfter is baseTime + 10 minutes
        testOccurrence.setRetryAfter(baseTime.plus(10, ChronoUnit.MINUTES));
        occurrenceRepository.save(testOccurrence);

        ClaimResult result = claimService.claimOccurrence("OCC-TEST-001", "worker-A", 0L, baseTime);
        assertFalse(result.claimed(), "Occurrence with future retryAfter must not be claimed");
    }

    @Test
    @DisplayName("Safeguard 10: Database time query executes successfully")
    void testDatabaseTime_ReturnsValidTimestamp() {
        Instant dbTime = occurrenceRepository.getCurrentDatabaseTime();
        assertNotNull(dbTime, "Database time must not be null");
    }
}
