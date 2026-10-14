package com.neobank.account.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.neobank.account.event.BalanceUpdatedEvent;
import com.neobank.account.event.TransactionCreatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class AccountLedgerEventConsumerTest {

    private AccountProjectionService projectionService;
    private ObjectMapper objectMapper;
    private AccountLedgerEventConsumer consumer;

    @BeforeEach
    void setUp() {
        projectionService = mock(AccountProjectionService.class);
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        consumer = new AccountLedgerEventConsumer(projectionService, objectMapper);
    }

    @Test
    @DisplayName("1. Ingests BALANCE_UPDATED payload and delegates to AccountProjectionService")
    void testConsume_BalanceUpdatedPayload_AppliesSuccessfully() {
        String payload = """
        {
            "eventId": "EVT-BAL-001",
            "eventType": "BALANCE_UPDATED",
            "transferId": "TRF-101",
            "accountId": "ACC-101",
            "transactionId": "TX-101",
            "oldBalance": 1000.00,
            "newBalance": 850.00,
            "amount": 150.00,
            "currency": "INR",
            "timestamp": "2026-10-15T10:00:00Z"
        }
        """;

        when(projectionService.applyBalanceUpdatedEvent(any(BalanceUpdatedEvent.class))).thenReturn(true);

        boolean result = consumer.consumePayload(payload);

        assertTrue(result);
        ArgumentCaptor<BalanceUpdatedEvent> captor = ArgumentCaptor.forClass(BalanceUpdatedEvent.class);
        verify(projectionService).applyBalanceUpdatedEvent(captor.capture());
        BalanceUpdatedEvent event = captor.getValue();
        assertEquals("EVT-BAL-001", event.eventId());
        assertEquals("ACC-101", event.accountId());
        assertEquals(0, new BigDecimal("850.00").compareTo(event.newBalance()));
    }

    @Test
    @DisplayName("2. Ingests TRANSACTION_CREATED payload and delegates to AccountProjectionService")
    void testConsume_TransactionCreatedPayload_AppliesSuccessfully() {
        String payload = """
        {
            "eventId": "EVT-TX-001",
            "eventType": "TRANSACTION_CREATED",
            "transactionId": "TX-101",
            "transferId": "TRF-101",
            "sourceAccountId": "ACC-101",
            "destinationAccountId": "ACC-102",
            "transferType": "OWN_ACCOUNT",
            "amount": 150.00,
            "currency": "INR",
            "status": "SUCCESS",
            "referenceNumber": "REF-101",
            "remarks": "Test transfer",
            "destinationName": "Destination Account",
            "destinationReference": "ACC-102",
            "timestamp": "2026-10-15T10:00:00Z"
        }
        """;

        when(projectionService.applyTransactionCreatedEvent(any(TransactionCreatedEvent.class))).thenReturn(true);

        boolean result = consumer.consumePayload(payload);

        assertTrue(result);
        ArgumentCaptor<TransactionCreatedEvent> captor = ArgumentCaptor.forClass(TransactionCreatedEvent.class);
        verify(projectionService).applyTransactionCreatedEvent(captor.capture());
        TransactionCreatedEvent event = captor.getValue();
        assertEquals("EVT-TX-001", event.eventId());
        assertEquals("TX-101", event.transactionId());
        assertEquals("ACC-101", event.sourceAccountId());
        assertEquals("ACC-102", event.destinationAccountId());
    }

    @Test
    @DisplayName("3. Idempotent consumption: duplicate event return false without error")
    void testConsume_DuplicateEvent_ReturnsFalseIdempotently() {
        String payload = """
        {
            "eventId": "EVT-BAL-001",
            "eventType": "BALANCE_UPDATED",
            "transferId": "TRF-101",
            "accountId": "ACC-101",
            "newBalance": 850.00
        }
        """;

        // Projection service indicates event was already processed (duplicate)
        when(projectionService.applyBalanceUpdatedEvent(any(BalanceUpdatedEvent.class))).thenReturn(false);

        boolean result = consumer.consumePayload(payload);

        assertFalse(result, "Duplicate event delivery should be skipped idempotently");
    }
}
