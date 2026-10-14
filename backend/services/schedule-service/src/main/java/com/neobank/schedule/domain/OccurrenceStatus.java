package com.neobank.schedule.domain;

public enum OccurrenceStatus {
    PENDING,
    PROCESSING,
    RETRY_PENDING,
    COMPLETED,
    FAILED,
    UNCERTAIN
}
