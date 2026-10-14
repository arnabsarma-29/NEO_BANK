package com.neobank.schedule.service;

import com.neobank.schedule.repository.ScheduledOccurrenceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Provides database time for lease decisions under ADR-010.
 */
@Service
@Primary
public class DatabaseTimeService implements TimeProvider {

    private static final Logger log = LoggerFactory.getLogger(DatabaseTimeService.class);

    private final ScheduledOccurrenceRepository occurrenceRepository;

    public DatabaseTimeService(ScheduledOccurrenceRepository occurrenceRepository) {
        this.occurrenceRepository = occurrenceRepository;
    }

    @Override
    public Instant getCurrentTime() {
        try {
            Instant dbTime = occurrenceRepository.getCurrentDatabaseTime();
            if (dbTime != null) {
                return dbTime;
            }
        } catch (Exception ex) {
            log.warn("Failed to query database time directly, falling back to local clock: {}", ex.getMessage());
        }
        return Instant.now();
    }
}
