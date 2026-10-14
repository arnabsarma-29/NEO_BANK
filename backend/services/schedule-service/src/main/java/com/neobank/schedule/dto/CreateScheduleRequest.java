package com.neobank.schedule.dto;

import com.neobank.schedule.domain.RecurrenceFrequency;
import com.neobank.schedule.domain.ScheduleType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

public record CreateScheduleRequest(
    @NotBlank(message = "sourceAccountId is required")
    String sourceAccountId,

    String destinationAccountId,

    String beneficiaryId,

    @NotBlank(message = "transferType is required")
    String transferType,

    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.01", message = "amount must be greater than zero")
    BigDecimal amount,

    @NotBlank(message = "currency is required")
    @Size(min = 3, max = 3, message = "currency must be 3-letter ISO code")
    String currency,

    String remarks,

    @NotNull(message = "scheduleType is required")
    ScheduleType scheduleType,

    RecurrenceFrequency frequency,

    @NotNull(message = "startDate is required")
    Instant startDate,

    Instant endDate
) {
}
