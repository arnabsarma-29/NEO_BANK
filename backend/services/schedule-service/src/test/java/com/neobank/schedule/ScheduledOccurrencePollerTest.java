package com.neobank.schedule;

import com.neobank.schedule.client.TransferExecutionRequest;
import com.neobank.schedule.client.TransferExecutionResponse;
import com.neobank.schedule.client.TransferServiceClient;
import com.neobank.schedule.client.TransferStatusResponse;
import com.neobank.schedule.domain.*;
import com.neobank.schedule.repository.ScheduledOccurrenceRepository;
import com.neobank.schedule.repository.ScheduledTransferRepository;
import com.neobank.schedule.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class ScheduledOccurrencePollerTest {

    private ScheduledTransferRepository transferRepository;
    private ScheduledOccurrenceRepository occurrenceRepository;
    private OccurrenceClaimService claimService;
    private TransferServiceClient transferServiceClient;
    private TimeProvider timeProvider;
    private ScheduleExecutionWorker worker;
    private ScheduledOccurrencePoller poller;

    private final Instant baseTime = Instant.parse("2026-10-15T12:00:00Z");
    private ScheduledTransfer activeSchedule;
    private ScheduledOccurrence dueOccurrence;

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

        poller = new ScheduledOccurrencePoller(worker, "test-poller-1", 10);

        activeSchedule = new ScheduledTransfer(
                "SCH-TEST-001", "CUST-001", "ACC-SRC-01", "ACC-DST-01", null, "OWN_ACCOUNT",
                new BigDecimal("150.00"), "INR", "Test remarks", ScheduleType.ONE_TIME, null,
                baseTime, null, baseTime, ScheduleStatus.ACTIVE, 1, baseTime, baseTime
        );

        dueOccurrence = new ScheduledOccurrence(
                "OCC-TEST-001", "SCH-TEST-001", 1, "TRF-TEST-001", baseTime, baseTime,
                OccurrenceStatus.PENDING, null, 0, 3, null, null, 0L,
                null, null, null, null, baseTime, baseTime
        );
    }

    @Test
    @DisplayName("1. No due occurrences results in no financial calls or claims")
    void testPoll_WhenNoDueOccurrences_MakesNoFinancialCalls() {
        when(occurrenceRepository.findDueOccurrences(eq(baseTime), any(Pageable.class)))
                .thenReturn(Collections.emptyList());

        poller.pollAndProcessDueOccurrences();

        verify(occurrenceRepository).findDueOccurrences(eq(baseTime), any(Pageable.class));
        verify(claimService, never()).claimOccurrence(anyString(), anyString(), anyLong(), any());
        verify(transferServiceClient, never()).executeTransfer(any());
        verify(transferServiceClient, never()).reconcileTransfer(any());
    }

    @Test
    @DisplayName("2. Due occurrence is claimed and executes transfer through TransferServiceClient")
    void testPoll_WhenDueOccurrenceExists_ClaimsAndCallsTransferServiceClient() {
        when(occurrenceRepository.findDueOccurrences(eq(baseTime), any(Pageable.class)))
                .thenReturn(List.of(dueOccurrence));
        when(claimService.claimOccurrence(eq("OCC-TEST-001"), eq("test-poller-1"), eq(0L), eq(baseTime)))
                .thenReturn(ClaimResult.success(dueOccurrence, 1L));
        when(transferRepository.findById("SCH-TEST-001")).thenReturn(Optional.of(activeSchedule));
        when(transferServiceClient.executeTransfer(any(TransferExecutionRequest.class)))
                .thenReturn(TransferExecutionResponse.success("TRF-TEST-001", "REF-TEST-001"));

        poller.pollAndProcessDueOccurrences();

        // Verify atomic claim with poller's workerId
        verify(claimService).claimOccurrence(eq("OCC-TEST-001"), eq("test-poller-1"), eq(0L), eq(baseTime));

        // Verify execution call with stable transferId
        ArgumentCaptor<TransferExecutionRequest> requestCaptor = ArgumentCaptor.forClass(TransferExecutionRequest.class);
        verify(transferServiceClient).executeTransfer(requestCaptor.capture());
        TransferExecutionRequest request = requestCaptor.getValue();
        assertEquals("TRF-TEST-001", request.transferId());
        assertEquals("IDEMP-TRF-TEST-001", request.idempotencyKey());
        assertEquals("ACC-SRC-01", request.sourceAccountId());
        assertEquals("ACC-DST-01", request.destinationAccountId());
        assertEquals(new BigDecimal("150.00"), request.amount());

        // Verify outcome recorded as COMPLETED
        verify(claimService).recordOutcome(
                eq("OCC-TEST-001"), eq("test-poller-1"), eq(1L), eq(OccurrenceStatus.COMPLETED),
                isNull(), eq("SUCCESS"), isNull(), eq(baseTime), eq(1), eq(baseTime), eq(baseTime)
        );

        // Verify one-time schedule completed
        assertEquals(ScheduleStatus.COMPLETED, activeSchedule.getStatus());
        assertNull(activeSchedule.getNextRunAt());
    }

    @Test
    @DisplayName("3. Uncertain outcome records UNCERTAIN status and reconciles on subsequent poll")
    void testPoll_WhenOutcomeUnknown_RecordsUncertainAndReconcilesLater() {
        // Poll 1: Transfer call times out
        when(occurrenceRepository.findDueOccurrences(eq(baseTime), any(Pageable.class)))
                .thenReturn(List.of(dueOccurrence));
        when(claimService.claimOccurrence(eq("OCC-TEST-001"), eq("test-poller-1"), eq(0L), eq(baseTime)))
                .thenReturn(ClaimResult.success(dueOccurrence, 1L));
        when(transferRepository.findById("SCH-TEST-001")).thenReturn(Optional.of(activeSchedule));
        when(transferServiceClient.executeTransfer(any(TransferExecutionRequest.class)))
                .thenReturn(TransferExecutionResponse.outcomeUnknown("TRF-TEST-001", "NETWORK_TIMEOUT"));

        poller.pollAndProcessDueOccurrences();

        // Verify recorded as UNCERTAIN
        verify(claimService).recordOutcome(
                eq("OCC-TEST-001"), eq("test-poller-1"), eq(1L), eq(OccurrenceStatus.UNCERTAIN),
                eq("OUTCOME_UNKNOWN"), eq("UNCERTAIN"), eq("NETWORK_TIMEOUT"), eq(baseTime), eq(1), isNull(), eq(baseTime)
        );

        // Poll 2: Re-claimed candidate in UNCERTAIN state triggers reconciliation first
        ScheduledOccurrence uncertainOcc = new ScheduledOccurrence(
                "OCC-TEST-001", "SCH-TEST-001", 1, "TRF-TEST-001", baseTime, baseTime,
                OccurrenceStatus.UNCERTAIN, "OUTCOME_UNKNOWN", 1, 3, "test-poller-1", null, 1L,
                "UNCERTAIN", "NETWORK_TIMEOUT", baseTime, null, baseTime, baseTime
        );

        reset(occurrenceRepository, claimService, transferServiceClient);
        when(occurrenceRepository.findDueOccurrences(eq(baseTime), any(Pageable.class)))
                .thenReturn(List.of(uncertainOcc));
        when(claimService.claimOccurrence(eq("OCC-TEST-001"), eq("test-poller-1"), eq(1L), eq(baseTime)))
                .thenReturn(ClaimResult.success(uncertainOcc, 2L));
        when(transferRepository.findById("SCH-TEST-001")).thenReturn(Optional.of(activeSchedule));
        when(transferServiceClient.reconcileTransfer("TRF-TEST-001"))
                .thenReturn(new TransferStatusResponse("TRF-TEST-001", "REF-TEST-001", "SUCCESS", null));

        poller.pollAndProcessDueOccurrences();

        // Verify reconciliation was invoked instead of blindly executing a second transfer
        verify(transferServiceClient).reconcileTransfer("TRF-TEST-001");
        verify(transferServiceClient, never()).executeTransfer(any());

        // Verify transitioned to COMPLETED
        verify(claimService).recordOutcome(
                eq("OCC-TEST-001"), eq("test-poller-1"), eq(2L), eq(OccurrenceStatus.COMPLETED),
                eq("RECONCILED_SUCCESS"), eq("SUCCESS"), isNull(), eq(baseTime), eq(1), eq(baseTime), eq(baseTime)
        );
    }

    @Test
    @DisplayName("4. Recurring schedule advances and creates new recurrence occurrence on success")
    void testPoll_RecurringScheduleAdvancesOnSuccess() {
        ScheduledTransfer recurringSchedule = new ScheduledTransfer(
                "SCH-REC-001", "CUST-001", "ACC-SRC-01", "ACC-DST-01", null, "OWN_ACCOUNT",
                new BigDecimal("50.00"), "INR", "Daily recurring", ScheduleType.RECURRING, RecurrenceFrequency.DAILY,
                baseTime, null, baseTime, ScheduleStatus.ACTIVE, 1, baseTime, baseTime
        );

        when(occurrenceRepository.findDueOccurrences(eq(baseTime), any(Pageable.class)))
                .thenReturn(List.of(dueOccurrence));
        when(claimService.claimOccurrence(eq("OCC-TEST-001"), eq("test-poller-1"), eq(0L), eq(baseTime)))
                .thenReturn(ClaimResult.success(dueOccurrence, 1L));
        when(transferRepository.findById("SCH-TEST-001")).thenReturn(Optional.of(recurringSchedule));
        when(transferServiceClient.executeTransfer(any()))
                .thenReturn(TransferExecutionResponse.success("TRF-TEST-001", "REF-001"));

        poller.pollAndProcessDueOccurrences();

        // Verify recurring schedule updated with next run date
        assertEquals(ScheduleStatus.ACTIVE, recurringSchedule.getStatus());
        assertEquals(2, recurringSchedule.getTotalOccurrences());
        assertNotNull(recurringSchedule.getNextRunAt());

        // Verify new recurrence occurrence saved with sequence 2
        ArgumentCaptor<ScheduledOccurrence> nextOccCaptor = ArgumentCaptor.forClass(ScheduledOccurrence.class);
        verify(occurrenceRepository).save(nextOccCaptor.capture());
        ScheduledOccurrence nextOcc = nextOccCaptor.getValue();
        assertEquals(2, nextOcc.getSequenceNumber());
        assertNotEquals("TRF-TEST-001", nextOcc.getTransferId(), "Genuinely new recurrence must have new transferId");
        assertEquals(OccurrenceStatus.PENDING, nextOcc.getStatus());
    }

    @Test
    @DisplayName("5. Inactive or cancelled schedule rejects execution without financial dispatch")
    void testPoll_InactiveSchedule_RejectsOccurrenceWithoutFinancialCall() {
        activeSchedule.setStatus(ScheduleStatus.CANCELLED);

        when(occurrenceRepository.findDueOccurrences(eq(baseTime), any(Pageable.class)))
                .thenReturn(List.of(dueOccurrence));
        when(claimService.claimOccurrence(eq("OCC-TEST-001"), eq("test-poller-1"), eq(0L), eq(baseTime)))
                .thenReturn(ClaimResult.success(dueOccurrence, 1L));
        when(transferRepository.findById("SCH-TEST-001")).thenReturn(Optional.of(activeSchedule));

        poller.pollAndProcessDueOccurrences();

        // Transfer client must NOT be called
        verify(transferServiceClient, never()).executeTransfer(any());

        // Occurrence marked FAILED with SCHEDULE_INACTIVE
        verify(claimService).recordOutcome(
                eq("OCC-TEST-001"), eq("test-poller-1"), eq(1L), eq(OccurrenceStatus.FAILED),
                eq("SCHEDULE_INACTIVE"), eq("FAILED"), anyString(), eq(baseTime), eq(1), isNull(), eq(baseTime)
        );
    }
}
