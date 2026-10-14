package com.neobank.account.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neobank.account.event.BalanceUpdatedEvent;
import com.neobank.account.event.TransactionCreatedEvent;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Idempotent Kafka consumer in Account Service that ingests Ledger Service events:
 * - BALANCE_UPDATED (ADR-004): Updates Account balance read model
 * - TRANSACTION_CREATED (ADR-007): Updates Account transaction-history read model
 *
 * Idempotency & Deduplication:
 * Delegates to AccountProjectionService, which records eventId in processed_events table.
 * Duplicate deliveries or retries are safely skipped without double-mutating balances or records.
 */
@Service
public class AccountLedgerEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(AccountLedgerEventConsumer.class);

    private final AccountProjectionService projectionService;
    private final ObjectMapper objectMapper;

    @Autowired
    public AccountLedgerEventConsumer(AccountProjectionService projectionService, Optional<ObjectMapper> objectMapper) {
        this.projectionService = projectionService;
        this.objectMapper = objectMapper.orElseGet(() -> new ObjectMapper().registerModule(new JavaTimeModule()));
    }

    public AccountLedgerEventConsumer(AccountProjectionService projectionService) {
        this(projectionService, Optional.empty());
    }

    public AccountLedgerEventConsumer(AccountProjectionService projectionService, ObjectMapper objectMapper) {
        this(projectionService, Optional.ofNullable(objectMapper));
    }

    @KafkaListener(
        topics = {
            "${neobank.kafka.topics.balance-updated:ledger.balance-updated}",
            "${neobank.kafka.topics.transaction-created:ledger.transaction-created}",
            "${neobank.kafka.topics.ledger-events:ledger.events}"
        },
        groupId = "${spring.kafka.consumer.group-id:account-service-group}",
        autoStartup = "${neobank.account.kafka.enabled:false}"
    )
    public void onMessage(String payload) {
        consumePayload(payload);
    }

    /**
     * Parses and projects a ledger event payload onto the read models idempotently.
     *
     * @param payload JSON string of the event
     * @return true if newly applied, false if skipped as duplicate or unhandled
     */
    public boolean consumePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            log.warn("Received empty event payload in Account Service consumer");
            return false;
        }

        try {
            JsonNode root = objectMapper.readTree(payload);
            String eventType = root.hasNonNull("eventType") ? root.path("eventType").asText() : null;

            if ("BALANCE_UPDATED".equalsIgnoreCase(eventType)) {
                BalanceUpdatedEvent event = objectMapper.treeToValue(root, BalanceUpdatedEvent.class);
                boolean applied = projectionService.applyBalanceUpdatedEvent(event);
                log.info("Processed BALANCE_UPDATED event [{}] for account [{}]. Newly applied: {}",
                        event.eventId(), event.accountId(), applied);
                return applied;

            } else if ("TRANSACTION_CREATED".equalsIgnoreCase(eventType)) {
                TransactionCreatedEvent event = objectMapper.treeToValue(root, TransactionCreatedEvent.class);
                boolean applied = projectionService.applyTransactionCreatedEvent(event);
                log.info("Processed TRANSACTION_CREATED event [{}] for transaction [{}]. Newly applied: {}",
                        event.eventId(), event.transactionId(), applied);
                return applied;

            } else {
                log.warn("Ignored unsupported event type [{}] in payload: {}", eventType, payload);
                return false;
            }

        } catch (Exception ex) {
            log.error("Failed to deserialize or project ledger event in Account Service: {}", ex.getMessage(), ex);
            throw new RuntimeException("Error processing ledger event", ex);
        }
    }

    public boolean consumeBalanceUpdated(BalanceUpdatedEvent event) {
        return projectionService.applyBalanceUpdatedEvent(event);
    }

    public boolean consumeTransactionCreated(TransactionCreatedEvent event) {
        return projectionService.applyTransactionCreatedEvent(event);
    }
}
