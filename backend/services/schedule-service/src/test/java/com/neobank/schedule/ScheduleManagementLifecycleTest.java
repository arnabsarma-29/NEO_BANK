package com.neobank.schedule;

import com.neobank.schedule.domain.*;
import com.neobank.schedule.dto.CreateScheduleRequest;
import com.neobank.schedule.dto.OccurrenceResponse;
import com.neobank.schedule.dto.ScheduleResponse;
import com.neobank.schedule.exception.InvalidScheduleRequestException;
import com.neobank.schedule.exception.InvalidScheduleStateException;
import com.neobank.schedule.exception.UnauthorizedScheduleAccessException;
import com.neobank.schedule.repository.ScheduledOccurrenceRepository;
import com.neobank.schedule.repository.ScheduledTransferRepository;
import com.neobank.schedule.service.ScheduleManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class ScheduleManagementLifecycleTest {

    @Autowired
    private ScheduleManagementService managementService;

    @Autowired
    private ScheduledTransferRepository transferRepository;

    @Autowired
    private ScheduledOccurrenceRepository occurrenceRepository;

    private final Instant baseTime = Instant.parse("2026-10-15T12:00:00Z");

    @BeforeEach
    void setUp() {
        occurrenceRepository.deleteAll();
        transferRepository.deleteAll();
    }

    @Test
    @DisplayName("Create one-time schedule: validates and creates schedule with initial occurrence")
    void testCreateOneTimeSchedule_Success() {
        CreateScheduleRequest req = new CreateScheduleRequest(
                "ACC-SRC-1",
                "ACC-DST-2",
                null,
                "INTERNAL",
                new BigDecimal("500.00"),
                "USD",
                "One time transfer",
                ScheduleType.ONE_TIME,
                null,
                baseTime.plus(1, ChronoUnit.DAYS),
                null
        );

        ScheduleResponse res = managementService.createSchedule(req, "C1001", baseTime);

        assertNotNull(res.scheduleId());
        assertEquals("C1001", res.customerId());
        assertEquals(ScheduleStatus.ACTIVE, res.status());
        assertEquals(1, res.totalOccurrences());

        List<OccurrenceResponse> occurrences = managementService.listScheduleOccurrences(res.scheduleId(), "C1001");
        assertEquals(1, occurrences.size());
        OccurrenceResponse occ = occurrences.get(0);
        assertEquals(1, occ.sequenceNumber());
        assertEquals(OccurrenceStatus.PENDING, occ.status());
        assertEquals(req.startDate(), occ.scheduledTime());
    }

    @Test
    @DisplayName("Create recurring schedule: requires frequency and valid end date")
    void testCreateRecurringSchedule_Success() {
        CreateScheduleRequest req = new CreateScheduleRequest(
                "ACC-SRC-1",
                "ACC-DST-2",
                null,
                "INTERNAL",
                new BigDecimal("250.00"),
                "USD",
                "Monthly rent",
                ScheduleType.RECURRING,
                RecurrenceFrequency.MONTHLY,
                baseTime.plus(1, ChronoUnit.DAYS),
                baseTime.plus(180, ChronoUnit.DAYS)
        );

        ScheduleResponse res = managementService.createSchedule(req, "C1001", baseTime);

        assertEquals(ScheduleType.RECURRING, res.scheduleType());
        assertEquals(RecurrenceFrequency.MONTHLY, res.frequency());
        assertEquals(ScheduleStatus.ACTIVE, res.status());
    }

    @Test
    @DisplayName("Create schedule validations: rejected for past start date, missing frequency, or missing destination")
    void testCreateSchedule_ValidationFailures() {
        // 1. Past start date
        CreateScheduleRequest pastDateReq = new CreateScheduleRequest(
                "ACC-SRC-1", "ACC-DST-2", null, "INTERNAL",
                new BigDecimal("100.00"), "USD", "Past", ScheduleType.ONE_TIME, null,
                baseTime.minus(1, ChronoUnit.DAYS), null
        );
        assertThrows(InvalidScheduleRequestException.class, () ->
                managementService.createSchedule(pastDateReq, "C1001", baseTime));

        // 2. Missing frequency for recurring
        CreateScheduleRequest missingFreqReq = new CreateScheduleRequest(
                "ACC-SRC-1", "ACC-DST-2", null, "INTERNAL",
                new BigDecimal("100.00"), "USD", "Recurring without freq", ScheduleType.RECURRING, null,
                baseTime.plus(1, ChronoUnit.DAYS), null
        );
        assertThrows(InvalidScheduleRequestException.class, () ->
                managementService.createSchedule(missingFreqReq, "C1001", baseTime));

        // 3. Missing both destination account and beneficiary
        CreateScheduleRequest missingDestReq = new CreateScheduleRequest(
                "ACC-SRC-1", null, null, "INTERNAL",
                new BigDecimal("100.00"), "USD", "No destination", ScheduleType.ONE_TIME, null,
                baseTime.plus(1, ChronoUnit.DAYS), null
        );
        assertThrows(InvalidScheduleRequestException.class, () ->
                managementService.createSchedule(missingDestReq, "C1001", baseTime));
    }

    @Test
    @DisplayName("Lifecycle state transitions: pause and resume active schedule")
    void testPauseAndResumeSchedule() {
        CreateScheduleRequest req = new CreateScheduleRequest(
                "ACC-SRC-1", "ACC-DST-2", null, "INTERNAL",
                new BigDecimal("100.00"), "USD", "Pause resume test", ScheduleType.ONE_TIME, null,
                baseTime.plus(1, ChronoUnit.DAYS), null
        );
        ScheduleResponse created = managementService.createSchedule(req, "C1001", baseTime);

        // Pause ACTIVE -> PAUSED
        ScheduleResponse paused = managementService.pauseSchedule(created.scheduleId(), "C1001", baseTime.plus(1, ChronoUnit.HOURS));
        assertEquals(ScheduleStatus.PAUSED, paused.status());

        // Pausing already paused schedule throws InvalidScheduleStateException
        assertThrows(InvalidScheduleStateException.class, () ->
                managementService.pauseSchedule(created.scheduleId(), "C1001", baseTime.plus(2, ChronoUnit.HOURS)));

        // Resume PAUSED -> ACTIVE
        ScheduleResponse resumed = managementService.resumeSchedule(created.scheduleId(), "C1001", baseTime.plus(3, ChronoUnit.HOURS));
        assertEquals(ScheduleStatus.ACTIVE, resumed.status());

        // Resuming already active schedule throws InvalidScheduleStateException
        assertThrows(InvalidScheduleStateException.class, () ->
                managementService.resumeSchedule(created.scheduleId(), "C1001", baseTime.plus(4, ChronoUnit.HOURS)));
    }

    @Test
    @DisplayName("Cancel schedule: transitions to CANCELLED and cancels pending occurrences")
    void testCancelSchedule_CascadesCancellation() {
        CreateScheduleRequest req = new CreateScheduleRequest(
                "ACC-SRC-1", "ACC-DST-2", null, "INTERNAL",
                new BigDecimal("100.00"), "USD", "Cancel test", ScheduleType.ONE_TIME, null,
                baseTime.plus(1, ChronoUnit.DAYS), null
        );
        ScheduleResponse created = managementService.createSchedule(req, "C1001", baseTime);

        ScheduleResponse cancelled = managementService.cancelSchedule(created.scheduleId(), "C1001", baseTime.plus(1, ChronoUnit.HOURS));
        assertEquals(ScheduleStatus.CANCELLED, cancelled.status());
        assertNull(cancelled.nextRunAt());

        // Verify pending occurrence is cancelled
        List<OccurrenceResponse> occurrences = managementService.listScheduleOccurrences(created.scheduleId(), "C1001");
        assertEquals(1, occurrences.size());
        assertEquals(OccurrenceStatus.FAILED, occurrences.get(0).status());
        assertEquals("SCHEDULE_CANCELLED", occurrences.get(0).statusReasonCode());
    }

    @Test
    @DisplayName("Customer isolation: customer B cannot access or modify customer A's schedule")
    void testCustomerIsolation() {
        CreateScheduleRequest req = new CreateScheduleRequest(
                "ACC-SRC-1", "ACC-DST-2", null, "INTERNAL",
                new BigDecimal("100.00"), "USD", "Customer A schedule", ScheduleType.ONE_TIME, null,
                baseTime.plus(1, ChronoUnit.DAYS), null
        );
        ScheduleResponse scheduleA = managementService.createSchedule(req, "CUSTOMER-A", baseTime);

        // Customer B cannot get schedule A
        assertThrows(UnauthorizedScheduleAccessException.class, () ->
                managementService.getSchedule(scheduleA.scheduleId(), "CUSTOMER-B"));

        // Customer B cannot pause schedule A
        assertThrows(UnauthorizedScheduleAccessException.class, () ->
                managementService.pauseSchedule(scheduleA.scheduleId(), "CUSTOMER-B", baseTime));

        // Customer B cannot cancel schedule A
        assertThrows(UnauthorizedScheduleAccessException.class, () ->
                managementService.cancelSchedule(scheduleA.scheduleId(), "CUSTOMER-B", baseTime));

        // Customer B cannot list occurrences of schedule A
        assertThrows(UnauthorizedScheduleAccessException.class, () ->
                managementService.listScheduleOccurrences(scheduleA.scheduleId(), "CUSTOMER-B"));
    }
}
