package com.neobank.schedule.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Spring-managed poller that periodically triggers ScheduleExecutionWorker.
 * Uses fixedDelay to ensure subsequent poll cycles wait until the previous one finishes.
 * Concurrent executions across multiple instances or polls are protected by
 * atomic database claiming and monotonic fencing tokens in OccurrenceClaimService.
 */
@Component
@ConditionalOnProperty(name = "neobank.schedule.worker.enabled", havingValue = "true", matchIfMissing = true)
public class ScheduledOccurrencePoller {

    private static final Logger log = LoggerFactory.getLogger(ScheduledOccurrencePoller.class);

    private final ScheduleExecutionWorker worker;
    private final String workerId;
    private final int batchSize;

    public ScheduledOccurrencePoller(
            ScheduleExecutionWorker worker,
            @Value("${neobank.schedule.worker.id:}") String configuredWorkerId,
            @Value("${neobank.schedule.worker.batch-size:10}") int batchSize) {
        this.worker = worker;
        this.workerId = (configuredWorkerId != null && !configuredWorkerId.isBlank())
                ? configuredWorkerId
                : "worker-" + UUID.randomUUID().toString().substring(0, 8);
        this.batchSize = batchSize;
        log.info("Initialized ScheduledOccurrencePoller with workerId [{}] and batchSize [{}]", this.workerId, this.batchSize);
    }

    @Scheduled(fixedDelayString = "${neobank.schedule.worker.fixed-delay-ms:5000}",
               initialDelayString = "${neobank.schedule.worker.initial-delay-ms:5000}")
    public void pollAndProcessDueOccurrences() {
        try {
            List<ExecutionReport> reports = worker.processDueOccurrences(workerId, batchSize);
            if (!reports.isEmpty()) {
                log.info("Worker [{}] processed {} due occurrence(s)", workerId, reports.size());
            }
        } catch (Exception ex) {
            log.error("Worker [{}] encountered unexpected error during polling cycle: {}", workerId, ex.getMessage(), ex);
        }
    }

    public String getWorkerId() {
        return workerId;
    }

    public int getBatchSize() {
        return batchSize;
    }
}
