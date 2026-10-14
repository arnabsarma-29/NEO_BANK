package com.neobank.schedule.it;

import com.neobank.schedule.domain.*;
import com.neobank.schedule.exception.StaleClaimException;
import com.neobank.schedule.repository.ScheduledOccurrenceRepository;
import com.neobank.schedule.repository.ScheduledTransferRepository;
import com.neobank.schedule.service.ClaimResult;
import com.neobank.schedule.service.DatabaseTimeService;
import com.neobank.schedule.service.OccurrenceClaimService;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * PostgreSQL 17.11 Concurrency & Fencing Verification Test.
 *
 * Verifies real multi-connection concurrency against PostgreSQL 17.11:
 *  - Competing workers contending for due occurrences on separate transactions
 *  - Monotonic fencing-token progression (ADR-010)
 *  - Lease renewal and expiry recovery by secondary workers
 *  - Strict rejection of stale workers
 *  - Database-time synchronization via SELECT CURRENT_TIMESTAMP
 *
 * Isolation guarantees:
 *  - Uses dedicated test prefix: SCH-CONC- / OCC-CONC-
 *  - Preserves all existing business records in neobank_schedule
 *  - Runtime app role (neobank_schedule_app) has NO DELETE privilege
 *  - Controlled test cleanup is performed exclusively via neobank_schedule_migrator
 */
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "SPRING_DATASOURCE_PASSWORD", matches = ".+")
@SpringBootTest
@ActiveProfiles("postgres-it")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class PostgresConcurrencyVerificationTest {

    private static final Logger log = LoggerFactory.getLogger(PostgresConcurrencyVerificationTest.class);
    private static final String TEST_ID_PREFIX = "CONC-LIVE-";

    @Autowired
    private ScheduledTransferRepository transferRepository;

    @Autowired
    private ScheduledOccurrenceRepository occurrenceRepository;

    @Autowired
    private OccurrenceClaimService claimService;

    @Autowired
    private DatabaseTimeService databaseTimeService;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    private static boolean postgresAvailable = false;

    @BeforeAll
    static void checkPostgresAvailability() {
        String appPassword = System.getenv("SPRING_DATASOURCE_PASSWORD");
        if (appPassword == null || appPassword.isBlank()) {
            log.info("Live PostgreSQL password not present in environment. Skipping live PostgreSQL test.");
            postgresAvailable = false;
            return;
        }

        String jdbcUrl = System.getenv("SPRING_DATASOURCE_URL");
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            jdbcUrl = "jdbc:postgresql://localhost:5432/neobank_schedule";
        }
        String appUser = System.getenv("SPRING_DATASOURCE_USERNAME");
        if (appUser == null || appUser.isBlank()) {
            appUser = "neobank_schedule_app";
        }

        try (Connection conn = DriverManager.getConnection(jdbcUrl, appUser, appPassword);
             PreparedStatement ps = conn.prepareStatement("SELECT 1;");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next() && rs.getInt(1) == 1) {
                postgresAvailable = true;
                log.info("Successfully connected to live PostgreSQL at {} as {}", jdbcUrl, appUser);
            }
        } catch (Exception ex) {
            log.warn("PostgreSQL connectivity check failed: {}", ex.getMessage());
            postgresAvailable = false;
        }
    }

    @BeforeEach
    void assumePostgres() {
        assumeTrue(postgresAvailable, "PostgreSQL 17.11 connection unavailable or password not supplied.");
    }

    @AfterAll
    static void controlledCleanupViaMigrator() {
        String migratorPassword = System.getenv("FLYWAY_PASSWORD");
        if (migratorPassword == null || migratorPassword.isBlank()) {
            log.info("Migrator password not in environment; cleanup of test rows (SCH-CONC-%) will be handled by verification script.");
            return;
        }

        String jdbcUrl = System.getenv("SPRING_DATASOURCE_URL");
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            jdbcUrl = "jdbc:postgresql://localhost:5432/neobank_schedule";
        }
        String migratorUser = System.getenv("FLYWAY_USER");
        if (migratorUser == null || migratorUser.isBlank()) {
            migratorUser = "neobank_schedule_migrator";
        }

        try (Connection conn = DriverManager.getConnection(jdbcUrl, migratorUser, migratorPassword)) {
            conn.setAutoCommit(false);
            try (PreparedStatement psOcc = conn.prepareStatement(
                    "DELETE FROM scheduled_occurrences WHERE schedule_id LIKE 'SCH-" + TEST_ID_PREFIX + "%'");
                 PreparedStatement psTrf = conn.prepareStatement(
                    "DELETE FROM scheduled_transfers WHERE schedule_id LIKE 'SCH-" + TEST_ID_PREFIX + "%'")) {
                int deletedOcc = psOcc.executeUpdate();
                int deletedTrf = psTrf.executeUpdate();
                conn.commit();
                log.info("Controlled cleanup completed as {}: purged {} test occurrences, {} test schedules.",
                        migratorUser, deletedOcc, deletedTrf);
            } catch (Exception e) {
                conn.rollback();
                log.error("Failed during controlled migrator cleanup: {}", e.getMessage());
            }
        } catch (Exception ex) {
            log.warn("Could not connect as migrator for cleanup: {}", ex.getMessage());
        }
    }

    private ScheduledTransfer createTestSchedule(String tag) {
        Instant now = databaseTimeService.getCurrentTime();
        String scheduleId = "SCH-" + TEST_ID_PREFIX + tag + "-" + UUID.randomUUID().toString().substring(0, 8);
        ScheduledTransfer st = new ScheduledTransfer(
                scheduleId,
                "CUST-CONC-TEST",
                "ACC-SRC-9001",
                "ACC-DST-9002",
                null,
                "INTERNAL",
                new BigDecimal("250.00"),
                "USD",
                "Live PG Concurrency Test: " + tag,
                ScheduleType.ONE_TIME,
                null,
                now.minus(10, ChronoUnit.MINUTES),
                null,
                now.minus(5, ChronoUnit.MINUTES),
                ScheduleStatus.ACTIVE,
                1,
                now,
                now
        );
        return transferRepository.save(st);
    }

    private ScheduledOccurrence createTestOccurrence(ScheduledTransfer schedule, int seq, Instant scheduledTime) {
        Instant now = databaseTimeService.getCurrentTime();
        String occurrenceId = "OCC-" + TEST_ID_PREFIX + schedule.getScheduleId().substring(schedule.getScheduleId().lastIndexOf('-') + 1) + "-" + seq;
        String transferId = "TRF-" + occurrenceId;

        ScheduledOccurrence occ = new ScheduledOccurrence(
                occurrenceId,
                schedule.getScheduleId(),
                seq,
                transferId,
                scheduledTime,
                scheduledTime, // retry_after == scheduledTime (due immediately)
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
        return occurrenceRepository.save(occ);
    }

    /**
     * Requirement 3.A: Competing workers
     * Multiple workers attempt to claim the exact same due occurrence concurrently through
     * the actual repository and claiming service on separate database connections.
     * Run across 10 distinct trials to expose race conditions.
     */
    @Test
    @Order(1)
    @DisplayName("3.A: Competing workers - separate DB connections and atomic claiming across 10 trials")
    void testCompetingWorkers_ConcurrentClaimTrials() throws Exception {
        int totalTrials = 10;
        int workersPerTrial = 4;
        AtomicInteger successfulWins = new AtomicInteger(0);
        AtomicInteger totalConflicts = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(workersPerTrial);

        try {
            for (int trial = 1; trial <= totalTrials; trial++) {
                Instant dbNow = databaseTimeService.getCurrentTime();
                ScheduledTransfer schedule = createTestSchedule("RACE-T" + trial);
                ScheduledOccurrence occurrence = createTestOccurrence(schedule, 1, dbNow.minus(5, ChronoUnit.MINUTES));

                String occId = occurrence.getOccurrenceId();
                CountDownLatch startLatch = new CountDownLatch(1);

                List<Future<ClaimResult>> futures = new ArrayList<>();
                List<String> workerNames = new ArrayList<>();

                for (int w = 1; w <= workersPerTrial; w++) {
                    String workerName = "worker-" + trial + "-" + w;
                    workerNames.add(workerName);
                    futures.add(executor.submit(() -> {
                        startLatch.await(); // ensure simultaneous dispatch
                        return claimService.claimOccurrence(occId, workerName, 0L, dbNow);
                    }));
                }

                startLatch.countDown(); // release all workers simultaneously

                String winningWorker = null;
                int trialWins = 0;
                int trialLosses = 0;

                for (int i = 0; i < workersPerTrial; i++) {
                    ClaimResult res = futures.get(i).get();
                    if (res.claimed()) {
                        trialWins++;
                        winningWorker = workerNames.get(i);
                        assertEquals(1L, res.claimVersion(), "Winning worker must receive claimVersion = 1");
                    } else {
                        trialLosses++;
                        assertTrue(res.failureReason().contains("concurrency conflict") ||
                                   res.failureReason().contains("active lease held"),
                                "Losing worker must report concurrency conflict or active lease");
                    }
                }

                // Verify exactly ONE worker won this trial
                assertEquals(1, trialWins, "Trial " + trial + ": Exactly one worker must claim the occurrence");
                assertEquals(workersPerTrial - 1, trialLosses, "Trial " + trial + ": All other workers must lose the claim");
                successfulWins.incrementAndGet();
                totalConflicts.addAndGet(trialLosses);

                // Verify PostgreSQL persistent state
                ScheduledOccurrence inDb = occurrenceRepository.findById(occId).orElseThrow();
                assertEquals(winningWorker, inDb.getClaimOwner(), "Database claimOwner must match winner");
                assertEquals(1L, inDb.getClaimVersion(), "Database claimVersion must be 1");
                assertEquals(OccurrenceStatus.PROCESSING, inDb.getStatus(), "Status must be PROCESSING");
                assertNotNull(inDb.getLeaseExpiresAt(), "Lease expiration must be populated");
                assertTrue(inDb.getLeaseExpiresAt().isAfter(dbNow), "Lease must expire in the future");

                final String finalWinner = winningWorker;

                // Verify winning worker can validate its active claim
                assertDoesNotThrow(() -> claimService.validateActiveClaim(occId, finalWinner, 1L, dbNow));

                // Verify losing worker CANNOT process occurrence using stale/fabricated claims
                for (String loser : workerNames) {
                    if (!loser.equals(finalWinner)) {
                        // Attempt validateActiveClaim with loser name
                        assertThrows(StaleClaimException.class, () ->
                                claimService.validateActiveClaim(occId, loser, 1L, dbNow));
                        // Attempt renewLease with loser name
                        assertThrows(StaleClaimException.class, () ->
                                claimService.renewLease(occId, loser, 1L, dbNow));
                        // Attempt recordOutcome with loser name
                        assertThrows(StaleClaimException.class, () ->
                                claimService.recordOutcome(occId, loser, 1L, OccurrenceStatus.COMPLETED,
                                        "SUCCESS", "SUCCESS", null, null, 1, dbNow, dbNow));
                    }
                }
            }
        } finally {
            executor.shutdown();
        }

        log.info("Live PostgreSQL 3.A trials passed: {} successful trials, {} conflict rejections.",
                successfulWins.get(), totalConflicts.get());
        assertEquals(totalTrials, successfulWins.get());
    }

    /**
     * Requirement 3.B: Lease expiry and recovery
     * 1. Worker A claims a due occurrence (initial 5m lease, version = 1).
     * 2. Renewal at permitted cadence extends the lease per ADR-010.
     * 3. After lease expiry, Worker B recovers the occurrence.
     * 4. Worker B receives strictly higher fencing token (version = 2).
     * 5. Worker A cannot initiate execution or record outcome using its stale token.
     * 6. Worker B updates outcome with its current valid claim.
     */
    @Test
    @Order(2)
    @DisplayName("3.B: Lease expiry, recovery, and fencing-token rejection")
    void testLeaseExpiryAndRecovery_FencingTokenProgression() {
        Instant t0 = databaseTimeService.getCurrentTime();
        ScheduledTransfer schedule = createTestSchedule("LEASE-EXP");
        ScheduledOccurrence occurrence = createTestOccurrence(schedule, 1, t0.minus(10, ChronoUnit.MINUTES));
        String occId = occurrence.getOccurrenceId();

        // 1. Worker A claims due occurrence at t0
        ClaimResult claimA = claimService.claimOccurrence(occId, "worker-A", 0L, t0);
        assertTrue(claimA.claimed(), "Worker A must successfully claim the due occurrence");
        assertEquals(1L, claimA.claimVersion(), "Initial claim must have claimVersion = 1");

        ScheduledOccurrence afterA = occurrenceRepository.findById(occId).orElseThrow();
        assertEquals("worker-A", afterA.getClaimOwner());
        assertEquals(1L, afterA.getClaimVersion());
        assertEquals(t0.plus(5, ChronoUnit.MINUTES), afterA.getLeaseExpiresAt());

        // 2. Renewal at 1-minute cadence extends lease according to ADR-010
        Instant t1 = t0.plus(1, ChronoUnit.MINUTES);
        boolean renewed = claimService.renewLease(occId, "worker-A", 1L, t1);
        assertTrue(renewed, "Worker A must successfully renew its lease at t1");

        ScheduledOccurrence afterRenew = occurrenceRepository.findById(occId).orElseThrow();
        assertEquals(t1.plus(5, ChronoUnit.MINUTES), afterRenew.getLeaseExpiresAt(),
                "Renewed lease must extend to t1 + 5 minutes");

        // 3. After lease expiry (t2 = t1 + 5 minutes + 10 seconds), Worker B recovers the occurrence
        Instant t2 = t1.plus(5, ChronoUnit.MINUTES).plusSeconds(10);

        // At t2, Worker A's lease is expired. Worker B finds the occurrence as due for recovery.
        ClaimResult claimB = claimService.claimOccurrence(occId, "worker-B", 1L, t2);
        assertTrue(claimB.claimed(), "Worker B must recover occurrence after lease expiry");

        // 4. Worker B receives strictly higher fencing token/claim version (2 > 1)
        assertEquals(2L, claimB.claimVersion(), "Worker B must receive strictly higher claimVersion (2)");

        ScheduledOccurrence afterB = occurrenceRepository.findById(occId).orElseThrow();
        assertEquals("worker-B", afterB.getClaimOwner());
        assertEquals(2L, afterB.getClaimVersion());
        assertEquals(t2.plus(5, ChronoUnit.MINUTES), afterB.getLeaseExpiresAt());

        // 5. Worker A can neither initiate execution nor record outcome using stale token (version 1)
        assertThrows(StaleClaimException.class, () ->
                claimService.validateActiveClaim(occId, "worker-A", 1L, t2),
                "Worker A validateActiveClaim must be rejected by fencing check");

        assertThrows(StaleClaimException.class, () ->
                claimService.renewLease(occId, "worker-A", 1L, t2),
                "Worker A renewLease must be rejected by fencing check");

        assertThrows(StaleClaimException.class, () ->
                claimService.recordOutcome(occId, "worker-A", 1L, OccurrenceStatus.COMPLETED,
                        "SUCCESS", "SUCCESS", null, null, 1, t2, t2),
                "Worker A recordOutcome must be rejected by PostgreSQL conditional predicate");

        // 6. Worker B can update outcome with its current valid claim (version 2)
        assertDoesNotThrow(() ->
                claimService.recordOutcome(occId, "worker-B", 2L, OccurrenceStatus.COMPLETED,
                        "SUCCESS", "EXECUTED_CONFIRMED", null, null, 1, t2, t2));

        ScheduledOccurrence finalOcc = occurrenceRepository.findById(occId).orElseThrow();
        assertEquals(OccurrenceStatus.COMPLETED, finalOcc.getStatus());
        assertEquals("EXECUTED_CONFIRMED", finalOcc.getExecutionOutcome());
        assertEquals("worker-B", finalOcc.getClaimOwner());
        assertEquals(2L, finalOcc.getClaimVersion());
        assertEquals(t2, finalOcc.getCompletedAt());
        assertNull(finalOcc.getRetryAfter(), "Terminal completed occurrence must have null retryAfter");

        log.info("Live PostgreSQL 3.B lease expiry and recovery test passed: version progressed 1 -> 2; Worker A rejected; Worker B completed.");
    }

    /**
     * Requirement 3.C: Database-time and atomicity guarantees
     * Confirm lease decisions use the approved database time source and that
     * SQL update predicates enforce active ownership, claim version, and lease validity.
     */
    @Test
    @Order(3)
    @DisplayName("3.C: Database time and atomic SQL predicate guarantees")
    void testDatabaseTimeAndAtomicityGuarantees() {
        // 1. Verify database time query
        Instant dbTime = occurrenceRepository.getCurrentDatabaseTime();
        assertNotNull(dbTime, "Database time from PostgreSQL must not be null");

        Instant providerTime = databaseTimeService.getCurrentTime();
        assertNotNull(providerTime, "DatabaseTimeService must return non-null Instant");
        assertTrue(Math.abs(ChronoUnit.SECONDS.between(dbTime, providerTime)) < 5,
                "Provider time must align with direct PostgreSQL CURRENT_TIMESTAMP");

        // 2. Verify SQL update predicates reject illegal operations
        ScheduledTransfer schedule = createTestSchedule("ATOMICITY");
        ScheduledOccurrence occ = createTestOccurrence(schedule, 1, dbTime.minus(2, ChronoUnit.MINUTES));
        String occId = occ.getOccurrenceId();

        // Stale expected claim version (expected 5, actual is 0) -> 0 rows updated
        int updateWithWrongVersion = transactionTemplate.execute(status ->
                occurrenceRepository.claimOccurrenceAtomic(
                        occId, "worker-X", 5L, dbTime.plus(5, ChronoUnit.MINUTES), dbTime));
        assertEquals(0, updateWithWrongVersion, "Claim with mismatched expectedClaimVersion must update 0 rows");

        // Not due occurrence (retryAfter in future) -> 0 rows updated
        ScheduledOccurrence futureOcc = createTestOccurrence(schedule, 2, dbTime.plus(10, ChronoUnit.MINUTES));
        int updateNotDue = transactionTemplate.execute(status ->
                occurrenceRepository.claimOccurrenceAtomic(
                        futureOcc.getOccurrenceId(), "worker-Y", 0L, dbTime.plus(5, ChronoUnit.MINUTES), dbTime));
        assertEquals(0, updateNotDue, "Claim on non-due occurrence must update 0 rows");

        // Valid claim succeeds
        int validClaim = transactionTemplate.execute(status ->
                occurrenceRepository.claimOccurrenceAtomic(
                        occId, "worker-VALID", 0L, dbTime.plus(5, ChronoUnit.MINUTES), dbTime));
        assertEquals(1, validClaim, "Valid atomic claim must update exactly 1 row");

        // Outcome update with mismatched workerId -> 0 rows updated
        int outcomeWrongWorker = transactionTemplate.execute(status ->
                occurrenceRepository.recordExecutionOutcome(
                        occId, "worker-IMPOSTER", 1L, OccurrenceStatus.COMPLETED,
                        "SUCCESS", "SUCCESS", null, null, 1, dbTime, dbTime));
        assertEquals(0, outcomeWrongWorker, "Outcome update by non-owner must update 0 rows");

        // Outcome update with expired lease -> 0 rows updated
        Instant expiredTime = dbTime.plus(6, ChronoUnit.MINUTES);
        int outcomeExpiredLease = transactionTemplate.execute(status ->
                occurrenceRepository.recordExecutionOutcome(
                        occId, "worker-VALID", 1L, OccurrenceStatus.COMPLETED,
                        "SUCCESS", "SUCCESS", null, null, 1, expiredTime, expiredTime));
        assertEquals(0, outcomeExpiredLease, "Outcome update with expired lease must update 0 rows");

        log.info("Live PostgreSQL 3.C database time and atomic predicate guarantees verified successfully.");
    }
}
