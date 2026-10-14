package com.neobank.schedule.service;

import java.time.Instant;

/**
 * Pluggable time provider for scheduling decisions under ADR-010.
 * In production, uses database time from ScheduledOccurrenceRepository.
 * In tests, enables deterministic time manipulation without sleeps.
 */
public interface TimeProvider {
    Instant getCurrentTime();
}
