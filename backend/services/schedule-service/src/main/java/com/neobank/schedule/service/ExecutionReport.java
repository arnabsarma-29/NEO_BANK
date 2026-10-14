package com.neobank.schedule.service;

import com.neobank.schedule.domain.OccurrenceStatus;

public record ExecutionReport(
    String occurrenceId,
    String transferId,
    boolean executed,
    OccurrenceStatus outcomeStatus,
    String details
) {
}
