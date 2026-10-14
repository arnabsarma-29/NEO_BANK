package com.neobank.schedule.dto;

import com.neobank.schedule.domain.RecurrenceFrequency;
import com.neobank.schedule.domain.ScheduleStatus;
import com.neobank.schedule.domain.ScheduleType;
import com.neobank.schedule.domain.ScheduledTransfer;

import java.math.BigDecimal;
import java.time.Instant;

public record ScheduleResponse(
    String scheduleId,
    String customerId,
    String sourceAccountId,
    String destinationAccountId,
    String beneficiaryId,
    String transferType,
    BigDecimal amount,
    String currency,
    String remarks,
    ScheduleType scheduleType,
    RecurrenceFrequency frequency,
    Instant startDate,
    Instant endDate,
    Instant nextRunAt,
    ScheduleStatus status,
    int totalOccurrences,
    Instant createdAt,
    Instant updatedAt
) {
    public static ScheduleResponse from(ScheduledTransfer t) {
        return new ScheduleResponse(
            t.getScheduleId(),
            t.getCustomerId(),
            t.getSourceAccountId(),
            t.getDestinationAccountId(),
            t.getBeneficiaryId(),
            t.getTransferType(),
            t.getAmount(),
            t.getCurrency(),
            t.getRemarks(),
            t.getScheduleType(),
            t.getFrequency(),
            t.getStartDate(),
            t.getEndDate(),
            t.getNextRunAt(),
            t.getStatus(),
            t.getTotalOccurrences(),
            t.getCreatedAt(),
            t.getUpdatedAt()
        );
    }
}
