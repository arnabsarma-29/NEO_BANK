package com.neobank.ledger.service;

import com.neobank.ledger.domain.OutboxEvent;
import com.neobank.ledger.domain.OutboxStatus;
import com.neobank.ledger.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Reliable Transactional Outbox Publisher for Ledger Service.
 * Periodically polls the outbox_events table for PENDING events,
 * publishes them to Kafka, and marks them PUBLISHED.
 * Guarantees at-least-once delivery to downstream consumers (Account Service).
 */
@Service
public class LedgerOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(LedgerOutboxPublisher.class);

    private final OutboxEventRepository outboxEventRepository;
    private final Optional<KafkaTemplate<String, String>> kafkaTemplate;
    private final String transactionCreatedTopic;
    private final String balanceUpdatedTopic;
    private final String ledgerEventsTopic;
    private final int maxRetries;
    private final boolean outboxEnabled;

    @Autowired
    public LedgerOutboxPublisher(
            OutboxEventRepository outboxEventRepository,
            Optional<KafkaTemplate<String, String>> kafkaTemplate,
            @Value("${neobank.kafka.topics.transaction-created:ledger.transaction-created}") String transactionCreatedTopic,
            @Value("${neobank.kafka.topics.balance-updated:ledger.balance-updated}") String balanceUpdatedTopic,
            @Value("${neobank.kafka.topics.ledger-events:ledger.events}") String ledgerEventsTopic,
            @Value("${neobank.ledger.outbox.max-retries:5}") int maxRetries,
            @Value("${neobank.ledger.outbox.enabled:false}") boolean outboxEnabled) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.transactionCreatedTopic = transactionCreatedTopic;
        this.balanceUpdatedTopic = balanceUpdatedTopic;
        this.ledgerEventsTopic = ledgerEventsTopic;
        this.maxRetries = maxRetries;
        this.outboxEnabled = outboxEnabled;
    }

    @Scheduled(fixedDelayString = "${neobank.ledger.outbox.delay-ms:2000}",
               initialDelayString = "${neobank.ledger.outbox.initial-delay-ms:2000}")
    public void scheduledPublish() {
        if (!outboxEnabled) {
            return;
        }
        publishPendingEvents();
    }

    /**
     * Polls and publishes all pending outbox events.
     * Can be invoked directly by tests or automatically via scheduling.
     *
     * @return count of successfully published events
     */
    @Transactional
    public int publishPendingEvents() {
        List<OutboxEvent> pendingEvents = outboxEventRepository.findByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING);
        if (pendingEvents.isEmpty()) {
            return 0;
        }

        int publishedCount = 0;
        for (OutboxEvent event : pendingEvents) {
            boolean success = publishSingleEvent(event);
            if (success) {
                publishedCount++;
            }
        }
        return publishedCount;
    }

    /**
     * Publishes a single outbox event to Kafka with error handling and retry tracking.
     */
    public boolean publishSingleEvent(OutboxEvent event) {
        if (kafkaTemplate.isEmpty()) {
            log.warn("KafkaTemplate not configured; skipping publish for outbox event [{}]", event.getId());
            return false;
        }

        String targetTopic = resolveTopic(event.getEventType());
        String partitionKey = event.getAggregateId();

        try {
            // Synchronous send with 5-second timeout to guarantee broker acknowledgment
            kafkaTemplate.get().send(targetTopic, partitionKey, event.getPayload())
                    .get(5, TimeUnit.SECONDS);

            event.setStatus(OutboxStatus.PUBLISHED);
            event.setPublishedAt(Instant.now());
            outboxEventRepository.save(event);
            log.info("Successfully published outbox event [{}] of type [{}] to topic [{}]",
                    event.getId(), event.getEventType(), targetTopic);
            return true;

        } catch (Exception ex) {
            int newRetry = event.getRetryCount() + 1;
            event.setRetryCount(newRetry);
            if (newRetry >= maxRetries) {
                event.setStatus(OutboxStatus.FAILED);
                log.error("Outbox event [{}] exceeded max retries ({}). Marked FAILED: {}",
                        event.getId(), maxRetries, ex.getMessage());
            } else {
                log.warn("Failed to publish outbox event [{}] (attempt {}/{}): {}",
                        event.getId(), newRetry, maxRetries, ex.getMessage());
            }
            outboxEventRepository.save(event);
            return false;
        }
    }

    private String resolveTopic(String eventType) {
        if ("TRANSACTION_CREATED".equalsIgnoreCase(eventType)) {
            return transactionCreatedTopic;
        } else if ("BALANCE_UPDATED".equalsIgnoreCase(eventType)) {
            return balanceUpdatedTopic;
        }
        return ledgerEventsTopic;
    }
}
