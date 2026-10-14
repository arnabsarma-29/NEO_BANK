package com.neobank.schedule.dto;

import com.neobank.schedule.domain.OccurrenceStatus;
import com.neobank.schedule.domain.ScheduledOccurrence;

import java.time.Instant;

public record OccurrenceResponse(
    String occurrenceId,
    String scheduleId,
    int sequenceNumber,
    String transferId,
    Instant scheduledTime,
    Instant retryAfter,
    OccurrenceStatus status,
    String statusReasonCode,
    int attemptCount,
    int maxAttempts,
    String claimOwner,
    long claimVersion,
    String executionOutcome,
    String failureReason,
    Instant lastAttemptAt,
    Instant completedAt,
    Instant createdAt,
    Instant updatedAt
) {
    public static OccurrenceResponse from(ScheduledOccurrence o) {
        return new OccurrenceResponse(
            o.getOccurrenceId(),
            o.getScheduleId(),
            o.getSequenceNumber(),
            o.getTransferId(),
            o.getScheduledTime(),
            o.getRetryAfter(),
            o.getStatus(),
            o.getStatusReasonCode(),
            o.getAttemptCount(),
            o.getMaxAttempts(),
            o.getClaimOwner(),
            o.getClaimVersion(),
            o.getExecutionOutcome(),
            o.getFailureReason(),
            o.getLastAttemptAt(),
            o.getCompletedAt(),
            o.getCreatedAt(),
            o.getUpdatedAt()
        );
    }
}
