package com.neobank.schedule.service;

import com.neobank.schedule.domain.ScheduledOccurrence;

public record ClaimResult(
    boolean claimed,
    ScheduledOccurrence occurrence,
    long claimVersion,
    String failureReason
) {
    public static ClaimResult success(ScheduledOccurrence occurrence, long claimVersion) {
        return new ClaimResult(true, occurrence, claimVersion, null);
    }

    public static ClaimResult failed(String reason) {
        return new ClaimResult(false, null, 0L, reason);
    }
}
