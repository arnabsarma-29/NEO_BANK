package com.neobank.schedule.service;

import com.neobank.schedule.domain.*;
import com.neobank.schedule.dto.CreateScheduleRequest;
import com.neobank.schedule.dto.OccurrenceResponse;
import com.neobank.schedule.dto.ScheduleResponse;
import com.neobank.schedule.exception.InvalidScheduleRequestException;
import com.neobank.schedule.exception.InvalidScheduleStateException;
import com.neobank.schedule.exception.ScheduleNotFoundException;
import com.neobank.schedule.exception.UnauthorizedScheduleAccessException;
import com.neobank.schedule.repository.ScheduledOccurrenceRepository;
import com.neobank.schedule.repository.ScheduledTransferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ScheduleManagementService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleManagementService.class);

    private final ScheduledTransferRepository transferRepository;
    private final ScheduledOccurrenceRepository occurrenceRepository;
    private final TimeProvider timeProvider;

    public ScheduleManagementService(ScheduledTransferRepository transferRepository,
                                     ScheduledOccurrenceRepository occurrenceRepository,
                                     TimeProvider timeProvider) {
        this.transferRepository = transferRepository;
        this.occurrenceRepository = occurrenceRepository;
        this.timeProvider = timeProvider;
    }

    /**
     * Creates a new scheduled transfer and its initial occurrence using database time under ADR-010.
     */
    @Transactional
    public ScheduleResponse createSchedule(CreateScheduleRequest req, String customerId) {
        return createSchedule(req, customerId, timeProvider.getCurrentTime());
    }

    /**
     * Creates a new scheduled transfer and its initial occurrence with explicit timestamp.
     */
    @Transactional
    public ScheduleResponse createSchedule(CreateScheduleRequest req, String customerId, Instant now) {
        if (req.startDate() == null || !req.startDate().isAfter(now)) {
            throw new InvalidScheduleRequestException("Start date must be strictly in the future");
        }

        if (req.scheduleType() == ScheduleType.RECURRING) {
            if (req.frequency() == null) {
                throw new InvalidScheduleRequestException("Frequency is required for recurring schedules");
            }
            if (req.endDate() != null && !req.endDate().isAfter(req.startDate())) {
                throw new InvalidScheduleRequestException("End date must be strictly after start date");
            }
        }

        if ((req.destinationAccountId() == null || req.destinationAccountId().isBlank()) &&
            (req.beneficiaryId() == null || req.beneficiaryId().isBlank())) {
            throw new InvalidScheduleRequestException("Either destinationAccountId or beneficiaryId must be specified");
        }

        String scheduleId = "SCH-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String initialOccurrenceId = "OCC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String initialTransferId = "TRF-SCH-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        ScheduledTransfer transfer = new ScheduledTransfer(
                scheduleId,
                customerId,
                req.sourceAccountId(),
                req.destinationAccountId(),
                req.beneficiaryId(),
                req.transferType(),
                req.amount(),
                req.currency(),
                req.remarks(),
                req.scheduleType(),
                req.scheduleType() == ScheduleType.RECURRING ? req.frequency() : null,
                req.startDate(),
                req.endDate(),
                req.startDate(),
                ScheduleStatus.ACTIVE,
                1,
                now,
                now
        );

        ScheduledOccurrence occurrence = new ScheduledOccurrence(
                initialOccurrenceId,
                scheduleId,
                1,
                initialTransferId,
                req.startDate(),
                req.startDate(),
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

        ScheduledTransfer saved = transferRepository.save(transfer);
        occurrenceRepository.save(occurrence);

        log.info("Created scheduled transfer [{}] for customer [{}] with initial occurrence [{}] and transferId [{}]",
                scheduleId, customerId, initialOccurrenceId, initialTransferId);

        return ScheduleResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public ScheduleResponse getSchedule(String scheduleId, String customerId) {
        ScheduledTransfer transfer = transferRepository.findById(scheduleId)
                .orElseThrow(() -> new ScheduleNotFoundException("Schedule not found: " + scheduleId));

        if (!transfer.getCustomerId().equals(customerId)) {
            throw new UnauthorizedScheduleAccessException("Customer not authorized to access schedule: " + scheduleId);
        }

        return ScheduleResponse.from(transfer);
    }

    @Transactional(readOnly = true)
    public List<ScheduleResponse> listCustomerSchedules(String customerId) {
        return transferRepository.findByCustomerIdOrderByCreatedAtDesc(customerId)
                .stream()
                .map(ScheduleResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<OccurrenceResponse> listScheduleOccurrences(String scheduleId, String customerId) {
        ScheduledTransfer transfer = transferRepository.findById(scheduleId)
                .orElseThrow(() -> new ScheduleNotFoundException("Schedule not found: " + scheduleId));

        if (!transfer.getCustomerId().equals(customerId)) {
            throw new UnauthorizedScheduleAccessException("Customer not authorized to access schedule: " + scheduleId);
        }

        return occurrenceRepository.findByScheduleIdOrderBySequenceNumberAsc(scheduleId)
                .stream()
                .map(OccurrenceResponse::from)
                .toList();
    }

    @Transactional
    public ScheduleResponse pauseSchedule(String scheduleId, String customerId) {
        return pauseSchedule(scheduleId, customerId, timeProvider.getCurrentTime());
    }

    @Transactional
    public ScheduleResponse pauseSchedule(String scheduleId, String customerId, Instant now) {
        ScheduledTransfer transfer = transferRepository.findById(scheduleId)
                .orElseThrow(() -> new ScheduleNotFoundException("Schedule not found: " + scheduleId));

        if (!transfer.getCustomerId().equals(customerId)) {
            throw new UnauthorizedScheduleAccessException("Customer not authorized to modify schedule: " + scheduleId);
        }

        if (transfer.getStatus() != ScheduleStatus.ACTIVE) {
            throw new InvalidScheduleStateException("Only ACTIVE schedules can be paused. Current: " + transfer.getStatus());
        }

        transfer.setStatus(ScheduleStatus.PAUSED);
        transfer.setUpdatedAt(now);
        ScheduledTransfer updated = transferRepository.save(transfer);
        log.info("Paused schedule [{}] for customer [{}]", scheduleId, customerId);
        return ScheduleResponse.from(updated);
    }

    @Transactional
    public ScheduleResponse resumeSchedule(String scheduleId, String customerId) {
        return resumeSchedule(scheduleId, customerId, timeProvider.getCurrentTime());
    }

    @Transactional
    public ScheduleResponse resumeSchedule(String scheduleId, String customerId, Instant now) {
        ScheduledTransfer transfer = transferRepository.findById(scheduleId)
                .orElseThrow(() -> new ScheduleNotFoundException("Schedule not found: " + scheduleId));

        if (!transfer.getCustomerId().equals(customerId)) {
            throw new UnauthorizedScheduleAccessException("Customer not authorized to modify schedule: " + scheduleId);
        }

        if (transfer.getStatus() != ScheduleStatus.PAUSED) {
            throw new InvalidScheduleStateException("Only PAUSED schedules can be resumed. Current: " + transfer.getStatus());
        }

        transfer.setStatus(ScheduleStatus.ACTIVE);
        transfer.setUpdatedAt(now);
        ScheduledTransfer updated = transferRepository.save(transfer);
        log.info("Resumed schedule [{}] for customer [{}]", scheduleId, customerId);
        return ScheduleResponse.from(updated);
    }

    @Transactional
    public ScheduleResponse cancelSchedule(String scheduleId, String customerId) {
        return cancelSchedule(scheduleId, customerId, timeProvider.getCurrentTime());
    }

    @Transactional
    public ScheduleResponse cancelSchedule(String scheduleId, String customerId, Instant now) {
        ScheduledTransfer transfer = transferRepository.findById(scheduleId)
                .orElseThrow(() -> new ScheduleNotFoundException("Schedule not found: " + scheduleId));

        if (!transfer.getCustomerId().equals(customerId)) {
            throw new UnauthorizedScheduleAccessException("Customer not authorized to cancel schedule: " + scheduleId);
        }

        if (transfer.getStatus() == ScheduleStatus.COMPLETED || transfer.getStatus() == ScheduleStatus.CANCELLED) {
            throw new InvalidScheduleStateException("Schedule already terminated with status: " + transfer.getStatus());
        }

        transfer.setStatus(ScheduleStatus.CANCELLED);
        transfer.setNextRunAt(null);
        transfer.setUpdatedAt(now);
        ScheduledTransfer updated = transferRepository.save(transfer);

        // Cancel any pending occurrences
        List<ScheduledOccurrence> occurrences = occurrenceRepository.findByScheduleIdOrderBySequenceNumberAsc(scheduleId);
        for (ScheduledOccurrence occ : occurrences) {
            if (occ.getStatus() == OccurrenceStatus.PENDING || occ.getStatus() == OccurrenceStatus.RETRY_PENDING) {
                occ.setStatus(OccurrenceStatus.FAILED);
                occ.setFailureReason("Schedule cancelled by customer");
                occ.setStatusReasonCode("SCHEDULE_CANCELLED");
                occ.setUpdatedAt(now);
                occurrenceRepository.save(occ);
            }
        }

        log.info("Cancelled schedule [{}] and its pending occurrences for customer [{}]", scheduleId, customerId);
        return ScheduleResponse.from(updated);
    }
}
