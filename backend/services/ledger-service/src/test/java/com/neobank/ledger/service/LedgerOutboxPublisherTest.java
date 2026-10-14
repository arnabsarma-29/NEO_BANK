package com.neobank.ledger.service;

import com.neobank.ledger.domain.OutboxEvent;
import com.neobank.ledger.domain.OutboxStatus;
import com.neobank.ledger.repository.OutboxEventRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class LedgerOutboxPublisherTest {

    private OutboxEventRepository outboxEventRepository;
    private KafkaTemplate<String, String> kafkaTemplate;
    private LedgerOutboxPublisher publisher;

    private OutboxEvent pendingBalanceEvent;
    private OutboxEvent pendingTxEvent;

    @BeforeEach
    void setUp() {
        outboxEventRepository = mock(OutboxEventRepository.class);
        kafkaTemplate = mock(KafkaTemplate.class);

        publisher = new LedgerOutboxPublisher(
                outboxEventRepository,
                Optional.of(kafkaTemplate),
                "ledger.transaction-created",
                "ledger.balance-updated",
                "ledger.events",
                5,
                true
        );

        pendingBalanceEvent = new OutboxEvent(
                "EVT-001",
                "LEDGER_ACCOUNT",
                "ACC-101",
                "BALANCE_UPDATED",
                "{\"eventId\":\"EVT-001\",\"eventType\":\"BALANCE_UPDATED\",\"newBalance\":850.00}",
                OutboxStatus.PENDING,
                Instant.now()
        );

        pendingTxEvent = new OutboxEvent(
                "EVT-002",
                "LEDGER_TRANSACTION",
                "TX-101",
                "TRANSACTION_CREATED",
                "{\"eventId\":\"EVT-002\",\"eventType\":\"TRANSACTION_CREATED\",\"amount\":150.00}",
                OutboxStatus.PENDING,
                Instant.now()
        );
    }

    @Test
    @DisplayName("1. When no pending events exist, zero events are published")
    void testPublish_WhenNoPendingEvents_DoesNothing() {
        when(outboxEventRepository.findByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING))
                .thenReturn(Collections.emptyList());

        int count = publisher.publishPendingEvents();

        assertEquals(0, count);
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("2. Pending events are published to respective Kafka topics and marked PUBLISHED")
    void testPublish_SuccessfulSend_MarksEventPublished() {
        when(outboxEventRepository.findByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING))
                .thenReturn(List.of(pendingBalanceEvent, pendingTxEvent));

        ProducerRecord<String, String> record = new ProducerRecord<>("topic", "key", "val");
        RecordMetadata metadata = new RecordMetadata(new TopicPartition("topic", 0), 0, 0, 0, 0, 0);
        SendResult<String, String> sendResult = new SendResult<>(record, metadata);
        CompletableFuture<SendResult<String, String>> future = CompletableFuture.completedFuture(sendResult);

        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(future);

        int count = publisher.publishPendingEvents();

        assertEquals(2, count);

        // Verify balance event went to ledger.balance-updated
        verify(kafkaTemplate).send("ledger.balance-updated", "ACC-101", pendingBalanceEvent.getPayload());
        assertEquals(OutboxStatus.PUBLISHED, pendingBalanceEvent.getStatus());
        assertNotNull(pendingBalanceEvent.getPublishedAt());

        // Verify transaction event went to ledger.transaction-created
        verify(kafkaTemplate).send("ledger.transaction-created", "TX-101", pendingTxEvent.getPayload());
        assertEquals(OutboxStatus.PUBLISHED, pendingTxEvent.getStatus());
        assertNotNull(pendingTxEvent.getPublishedAt());

        verify(outboxEventRepository, times(2)).save(any(OutboxEvent.class));
    }

    @Test
    @DisplayName("3. Failed send increments retry count and marks FAILED after max retries")
    void testPublish_FailedSend_IncrementsRetriesAndMarksFailed() {
        when(outboxEventRepository.findByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING))
                .thenReturn(List.of(pendingBalanceEvent));

        CompletableFuture<SendResult<String, String>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Kafka broker unreachable"));
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(failedFuture);

        // First failure: retry count becomes 1, status remains PENDING
        int count = publisher.publishPendingEvents();
        assertEquals(0, count);
        assertEquals(1, pendingBalanceEvent.getRetryCount());
        assertEquals(OutboxStatus.PENDING, pendingBalanceEvent.getStatus());

        // Set retry count to 4 (so next failure hits max 5)
        pendingBalanceEvent.setRetryCount(4);
        count = publisher.publishPendingEvents();
        assertEquals(0, count);
        assertEquals(5, pendingBalanceEvent.getRetryCount());
        assertEquals(OutboxStatus.FAILED, pendingBalanceEvent.getStatus());
    }
}
