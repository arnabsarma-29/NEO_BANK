package com.neobank.schedule;

import com.neobank.schedule.client.*;
import com.neobank.schedule.domain.*;
import com.neobank.schedule.exception.StaleClaimException;
import com.neobank.schedule.repository.ScheduledOccurrenceRepository;
import com.neobank.schedule.repository.ScheduledTransferRepository;
import com.neobank.schedule.service.ClaimResult;
import com.neobank.schedule.service.ExecutionReport;
import com.neobank.schedule.service.OccurrenceClaimService;
import com.neobank.schedule.service.ScheduleExecutionWorker;
import com.neobank.schedule.service.TimeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class ScheduleExecutionWorkerSafeguardsTest {

    private ScheduledTransferRepository transferRepository;
    private ScheduledOccurrenceRepository occurrenceRepository;
    private OccurrenceClaimService claimService;
    private TransferServiceClient transferServiceClient;
    private TimeProvider timeProvider;
    private ScheduleExecutionWorker worker;

    private final Instant baseTime = Instant.parse("2026-10-15T10:00:00Z");
    private ScheduledTransfer oneTimeTransfer;
    private ScheduledTransfer recurringTransfer;
    private ScheduledOccurrence candidateOccurrence;

    @BeforeEach
    void setUp() {
        transferRepository = mock(ScheduledTransferRepository.class);
        occurrenceRepository = mock(ScheduledOccurrenceRepository.class);
        claimService = mock(OccurrenceClaimService.class);
        transferServiceClient = mock(TransferServiceClient.class);
        timeProvider = mock(TimeProvider.class);

        when(timeProvider.getCurrentTime()).thenReturn(baseTime);

        worker = new ScheduleExecutionWorker(
                transferRepository,
                occurrenceRepository,
                claimService,
                transferServiceClient,
                timeProvider
        );

        oneTimeTransfer = new ScheduledTransfer(
                "SCH-100", "C1001", "ACC-SRC", "ACC-DST", null, "INTERNAL",
                new BigDecimal("100.00"), "USD", "One time", ScheduleType.ONE_TIME, null,
                baseTime, null, baseTime, ScheduleStatus.ACTIVE, 1, baseTime, baseTime
        );

        recurringTransfer = new ScheduledTransfer(
                "SCH-200", "C1001", "ACC-SRC", "ACC-DST", null, "INTERNAL",
                new BigDecimal("200.00"), "USD", "Recurring", ScheduleType.RECURRING, RecurrenceFrequency.DAILY,
                baseTime, null, baseTime, ScheduleStatus.ACTIVE, 1, baseTime, baseTime
        );

        candidateOccurrence = new ScheduledOccurrence(
                "OCC-001", "SCH-100", 1, "TRF-SCH-001", baseTime, baseTime,
                OccurrenceStatus.PENDING, null, 0, 3, null, null, 0L,
                null, null, null, null, baseTime, baseTime
        );
    }

    @Test
    @DisplayName("Execution success on one-time schedule: completes schedule with stable identifiers")
    void testExecutionSuccess_OneTimeScheduleCompletes() {
        when(claimService.claimOccurrence(eq("OCC-001"), eq("worker-1"), eq(0L), eq(baseTime)))
                .thenReturn(ClaimResult.success(candidateOccurrence, 1L));
        when(transferRepository.findById("SCH-100")).thenReturn(Optional.of(oneTimeTransfer));
        when(transferServiceClient.executeTransfer(any()))
                .thenReturn(TransferExecutionResponse.success("TRF-SCH-001", "REF-001"));

        ExecutionReport report = worker.processOccurrence(candidateOccurrence, "worker-1", baseTime);

        assertTrue(report.executed());
        assertEquals(OccurrenceStatus.COMPLETED, report.outcomeStatus());
        assertEquals("OCC-001", report.occurrenceId());
        assertEquals("TRF-SCH-001", report.transferId());

        verify(claimService).recordOutcome(
                eq("OCC-001"), eq("worker-1"), eq(1L), eq(OccurrenceStatus.COMPLETED),
                isNull(), eq("SUCCESS"), isNull(), eq(baseTime), eq(1), eq(baseTime), eq(baseTime)
        );

        assertEquals(ScheduleStatus.COMPLETED, oneTimeTransfer.getStatus());
        assertNull(oneTimeTransfer.getNextRunAt());
    }

    @Test
    @DisplayName("Execution success on recurring schedule: creates new occurrence with brand new occurrenceId and transferId")
    void testExecutionSuccess_RecurringAdvancesWithNewIdentities() {
        candidateOccurrence.setScheduleId("SCH-200");
        when(claimService.claimOccurrence(eq("OCC-001"), eq("worker-1"), eq(0L), eq(baseTime)))
                .thenReturn(ClaimResult.success(candidateOccurrence, 1L));
        when(transferRepository.findById("SCH-200")).thenReturn(Optional.of(recurringTransfer));
        when(transferServiceClient.executeTransfer(any()))
                .thenReturn(TransferExecutionResponse.success("TRF-SCH-001", "REF-001"));

        ExecutionReport report = worker.processOccurrence(candidateOccurrence, "worker-1", baseTime);

        assertTrue(report.executed());
        assertEquals(OccurrenceStatus.COMPLETED, report.outcomeStatus());

        // Capture newly generated recurrence occurrence
        ArgumentCaptor<ScheduledOccurrence> nextOccCaptor = ArgumentCaptor.forClass(ScheduledOccurrence.class);
        verify(occurrenceRepository).save(nextOccCaptor.capture());
        ScheduledOccurrence nextOcc = nextOccCaptor.getValue();

        assertNotEquals("OCC-001", nextOcc.getOccurrenceId(), "Next recurrence must have a brand NEW occurrenceId");
        assertNotEquals("TRF-SCH-001", nextOcc.getTransferId(), "Next recurrence must have a brand NEW transferId");
        assertEquals(2, nextOcc.getSequenceNumber(), "Sequence number must be 2");
        assertEquals(OccurrenceStatus.PENDING, nextOcc.getStatus());
        assertEquals(0, nextOcc.getAttemptCount());
        assertEquals(baseTime.plus(1, ChronoUnit.DAYS), nextOcc.getScheduledTime());
    }

    @Test
    @DisplayName("Retry timing under ADR-009 Policy E: Attempt 1 (+1m), Attempt 2 (+5m), Attempt 3 (Terminal FAILED)")
    void testRetrySchedule_Adr009PolicyE_ThreeAttempts() {
        when(transferRepository.findById("SCH-100")).thenReturn(Optional.of(oneTimeTransfer));

        // Attempt 1: attemptCount 0 -> fails -> currentAttempt 1 -> +1 minute delay
        when(claimService.claimOccurrence(eq("OCC-001"), eq("worker-1"), anyLong(), eq(baseTime)))
                .thenReturn(ClaimResult.success(candidateOccurrence, 1L));
        when(transferServiceClient.executeTransfer(any()))
                .thenReturn(TransferExecutionResponse.transientFailure("TRF-SCH-001", "Gateway timeout"));
        when(transferServiceClient.reconcileTransfer("TRF-SCH-001"))
                .thenReturn(new TransferStatusResponse("TRF-SCH-001", null, "UNKNOWN", "Not processed"));

        ExecutionReport report1 = worker.processOccurrence(candidateOccurrence, "worker-1", baseTime);
        assertEquals(OccurrenceStatus.RETRY_PENDING, report1.outcomeStatus());

        verify(claimService).recordOutcome(
                eq("OCC-001"), eq("worker-1"), eq(1L), eq(OccurrenceStatus.RETRY_PENDING),
                eq("TRANSIENT_RETRYABLE_ERROR"), eq("RETRY_PENDING"), eq("Gateway timeout"),
                eq(baseTime.plus(1, ChronoUnit.MINUTES)), eq(1), isNull(), eq(baseTime)
        );

        // Attempt 2: at baseTime + 1m, attemptCount 1 -> fails -> currentAttempt 2 -> +5 minutes delay
        Instant attempt2Time = baseTime.plus(1, ChronoUnit.MINUTES);
        candidateOccurrence.setAttemptCount(1);
        candidateOccurrence.setStatus(OccurrenceStatus.RETRY_PENDING);

        when(claimService.claimOccurrence(eq("OCC-001"), eq("worker-1"), anyLong(), eq(attempt2Time)))
                .thenReturn(ClaimResult.success(candidateOccurrence, 2L));
        when(transferServiceClient.executeTransfer(any()))
                .thenReturn(TransferExecutionResponse.transientFailure("TRF-SCH-001", "Connection reset"));

        ExecutionReport report2 = worker.processOccurrence(candidateOccurrence, "worker-1", attempt2Time);
        assertEquals(OccurrenceStatus.RETRY_PENDING, report2.outcomeStatus());

        verify(claimService).recordOutcome(
                eq("OCC-001"), eq("worker-1"), eq(2L), eq(OccurrenceStatus.RETRY_PENDING),
                eq("TRANSIENT_RETRYABLE_ERROR"), eq("RETRY_PENDING"), eq("Connection reset"),
                eq(attempt2Time.plus(5, ChronoUnit.MINUTES)), eq(2), isNull(), eq(attempt2Time)
        );

        // Attempt 3: at attempt2Time + 5m (baseTime + 6m), attemptCount 2 -> fails -> currentAttempt 3 -> FAILED (maxAttempts exceeded)
        Instant attempt3Time = attempt2Time.plus(5, ChronoUnit.MINUTES);
        candidateOccurrence.setAttemptCount(2);

        when(claimService.claimOccurrence(eq("OCC-001"), eq("worker-1"), anyLong(), eq(attempt3Time)))
                .thenReturn(ClaimResult.success(candidateOccurrence, 3L));
        when(transferServiceClient.executeTransfer(any()))
                .thenReturn(TransferExecutionResponse.transientFailure("TRF-SCH-001", "Service unavailable"));

        ExecutionReport report3 = worker.processOccurrence(candidateOccurrence, "worker-1", attempt3Time);
        assertEquals(OccurrenceStatus.FAILED, report3.outcomeStatus());

        verify(claimService).recordOutcome(
                eq("OCC-001"), eq("worker-1"), eq(3L), eq(OccurrenceStatus.FAILED),
                eq("MAX_RETRIES_EXCEEDED"), eq("FAILED"), eq("Service unavailable"),
                any(), eq(3), isNull(), eq(attempt3Time)
        );
    }

    @Test
    @DisplayName("Permanent business rejection terminates immediately without scheduling retries")
    void testPermanentFailure_NoRetry() {
        when(claimService.claimOccurrence(eq("OCC-001"), eq("worker-1"), eq(0L), eq(baseTime)))
                .thenReturn(ClaimResult.success(candidateOccurrence, 1L));
        when(transferRepository.findById("SCH-100")).thenReturn(Optional.of(oneTimeTransfer));
        when(transferServiceClient.executeTransfer(any()))
                .thenReturn(TransferExecutionResponse.permanentFailure("TRF-SCH-001", "Account closed or invalid"));

        ExecutionReport report = worker.processOccurrence(candidateOccurrence, "worker-1", baseTime);

        assertEquals(OccurrenceStatus.FAILED, report.outcomeStatus());
        verify(claimService).recordOutcome(
                eq("OCC-001"), eq("worker-1"), eq(1L), eq(OccurrenceStatus.FAILED),
                eq("BUSINESS_REJECTION"), eq("FAILED"), eq("Account closed or invalid"),
                eq(baseTime), eq(1), isNull(), eq(baseTime)
        );
    }

    @Test
    @DisplayName("Outcome unknown downstream records UNCERTAIN status for reconciliation")
    void testOutcomeUnknown_QueuedForReconciliation() {
        when(claimService.claimOccurrence(eq("OCC-001"), eq("worker-1"), eq(0L), eq(baseTime)))
                .thenReturn(ClaimResult.success(candidateOccurrence, 1L));
        when(transferRepository.findById("SCH-100")).thenReturn(Optional.of(oneTimeTransfer));
        when(transferServiceClient.executeTransfer(any()))
                .thenThrow(new RuntimeException("Socket read timeout"));

        ExecutionReport report = worker.processOccurrence(candidateOccurrence, "worker-1", baseTime);

        assertEquals(OccurrenceStatus.UNCERTAIN, report.outcomeStatus());
        verify(claimService).recordOutcome(
                eq("OCC-001"), eq("worker-1"), eq(1L), eq(OccurrenceStatus.UNCERTAIN),
                eq("OUTCOME_UNKNOWN"), eq("UNCERTAIN"), contains("Socket read timeout"),
                eq(baseTime), eq(1), isNull(), eq(baseTime)
        );
    }

    @Test
    @DisplayName("Recovery and reconciliation: downstream committed transfer is reconciled to COMPLETED without duplicate execution")
    void testRecoveryAndReconciliation_DownstreamAlreadySucceeded() {
        candidateOccurrence.setStatus(OccurrenceStatus.PROCESSING);
        candidateOccurrence.setAttemptCount(1);

        when(claimService.claimOccurrence(eq("OCC-001"), eq("worker-2"), eq(0L), eq(baseTime)))
                .thenReturn(ClaimResult.success(candidateOccurrence, 2L));
        when(transferRepository.findById("SCH-100")).thenReturn(Optional.of(oneTimeTransfer));
        when(transferServiceClient.reconcileTransfer("TRF-SCH-001"))
                .thenReturn(new TransferStatusResponse("TRF-SCH-001", "REF-RECON-1", "SUCCESS", null));

        ExecutionReport report = worker.processOccurrence(candidateOccurrence, "worker-2", baseTime);

        assertEquals(OccurrenceStatus.COMPLETED, report.outcomeStatus());
        verify(claimService).recordOutcome(
                eq("OCC-001"), eq("worker-2"), eq(2L), eq(OccurrenceStatus.COMPLETED),
                eq("RECONCILED_SUCCESS"), eq("SUCCESS"), isNull(), eq(baseTime), eq(1), eq(baseTime), eq(baseTime)
        );
        // CRITICAL: Transfer execution must NOT be called again
        verify(transferServiceClient, never()).executeTransfer(any());
    }

    @Test
    @DisplayName("Safeguard: worker with expired lease cannot initiate execution")
    void testWorkerWithExpiredLeaseCannotInitiateExecution() {
        when(claimService.claimOccurrence(eq("OCC-001"), eq("worker-1"), eq(0L), eq(baseTime)))
                .thenReturn(ClaimResult.success(candidateOccurrence, 1L));
        when(transferRepository.findById("SCH-100")).thenReturn(Optional.of(oneTimeTransfer));

        // Simulate lease expiration check failing before execution
        doThrow(new StaleClaimException("Worker lease expired"))
                .when(claimService).validateActiveClaim(eq("OCC-001"), eq("worker-1"), eq(1L), eq(baseTime));

        assertThrows(StaleClaimException.class, () ->
                worker.processOccurrence(candidateOccurrence, "worker-1", baseTime));

        // Verify Transfer Service is NEVER invoked
        verify(transferServiceClient, never()).executeTransfer(any());
    }

    @Test
    @DisplayName("Safeguard: No direct Schedule Service call to Ledger Service")
    void testNoDirectCallToLedgerService() {
        // ScheduleExecutionWorker only holds references to transferRepository, occurrenceRepository, claimService, transferServiceClient
        assertTrue(worker.getClass().getDeclaredFields().length >= 4);
        for (var field : worker.getClass().getDeclaredFields()) {
            assertFalse(field.getType().getName().toLowerCase().contains("ledger"),
                    "ScheduleExecutionWorker must not contain any Ledger service reference: " + field.getName());
        }
    }
}
