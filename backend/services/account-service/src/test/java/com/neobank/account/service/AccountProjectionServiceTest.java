package com.neobank.account.service;

import com.neobank.account.domain.*;
import com.neobank.account.event.BalanceUpdatedEvent;
import com.neobank.account.event.TransactionCreatedEvent;
import com.neobank.account.repository.AccountRepository;
import com.neobank.account.repository.AccountTransactionRepository;
import com.neobank.account.repository.ProcessedEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountProjectionServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private AccountTransactionRepository accountTransactionRepository;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    private AccountProjectionService projectionService;

    @BeforeEach
    void setUp() {
        projectionService = new AccountProjectionService(
                accountRepository,
                accountTransactionRepository,
                processedEventRepository
        );
    }

    @Test
    @DisplayName("Should apply BalanceUpdatedEvent and update balance read model")
    void shouldApplyBalanceUpdatedEvent() {
        String eventId = "EVT-100";
        String accountId = "ACC-1001";
        BalanceUpdatedEvent event = new BalanceUpdatedEvent(
                eventId,
                "BALANCE_UPDATED",
                "TRF-100",
                accountId,
                "TX-100",
                new BigDecimal("1000.0000"),
                new BigDecimal("900.0000"),
                new BigDecimal("100.0000"),
                "INR",
                Instant.now()
        );

        Account account = new Account(accountId, "1001000101", "C1001", AccountType.SAVINGS, "INR",
                new BigDecimal("1000.0000"), AccountStatus.ACTIVE, Instant.now());

        when(processedEventRepository.existsByEventId(eventId)).thenReturn(false);
        when(accountRepository.findWithLockById(accountId)).thenReturn(Optional.of(account));

        boolean applied = projectionService.applyBalanceUpdatedEvent(event);

        assertThat(applied).isTrue();
        assertThat(account.getBalance()).isEqualByComparingTo("900.0000");
        verify(accountRepository).save(account);
        verify(processedEventRepository).save(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("Should skip duplicate BalanceUpdatedEvent by eventId (ADR-008)")
    void shouldSkipDuplicateBalanceUpdatedEvent() {
        String eventId = "EVT-100";
        BalanceUpdatedEvent event = new BalanceUpdatedEvent(
                eventId, "BALANCE_UPDATED", "TRF-100", "ACC-1001", "TX-100",
                BigDecimal.ZERO, BigDecimal.TEN, BigDecimal.TEN, "INR", Instant.now()
        );

        when(processedEventRepository.existsByEventId(eventId)).thenReturn(true);

        boolean applied = projectionService.applyBalanceUpdatedEvent(event);

        assertThat(applied).isFalse();
        verify(accountRepository, never()).findWithLockById(anyString());
        verify(accountRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should apply TransactionCreatedEvent and derive DEBIT & CREDIT entries")
    void shouldApplyTransactionCreatedEvent() {
        String eventId = "EVT-200";
        TransactionCreatedEvent event = new TransactionCreatedEvent(
                eventId,
                "TRANSACTION_CREATED",
                "TX-200",
                "TRF-200",
                "ACC-1001",
                "ACC-1002",
                "OWN_ACCOUNT",
                new BigDecimal("250.0000"),
                "INR",
                "SUCCESS",
                "REF-200",
                "Transfer to savings",
                "Destination Name",
                "XXXXXX1002",
                Instant.now()
        );

        Account sourceAcc = new Account("ACC-1001", "1001000101", "C1001", AccountType.CURRENT, "INR",
                BigDecimal.ZERO, AccountStatus.ACTIVE, Instant.now());
        Account destAcc = new Account("ACC-1002", "1001000102", "C1001", AccountType.SAVINGS, "INR",
                BigDecimal.ZERO, AccountStatus.ACTIVE, Instant.now());

        when(processedEventRepository.existsByEventId(eventId)).thenReturn(false);
        when(accountRepository.findById("ACC-1001")).thenReturn(Optional.of(sourceAcc));
        when(accountRepository.findById("ACC-1002")).thenReturn(Optional.of(destAcc));

        boolean applied = projectionService.applyTransactionCreatedEvent(event);

        assertThat(applied).isTrue();

        ArgumentCaptor<AccountTransactionReadModel> captor = ArgumentCaptor.forClass(AccountTransactionReadModel.class);
        verify(accountTransactionRepository, times(2)).save(captor.capture());

        var savedEntries = captor.getAllValues();
        assertThat(savedEntries).hasSize(2);

        AccountTransactionReadModel debit = savedEntries.stream()
                .filter(e -> e.getEntryType() == EntryType.DEBIT).findFirst().orElseThrow();
        assertThat(debit.getAccount().getId()).isEqualTo("ACC-1001");
        assertThat(debit.getAmount()).isEqualByComparingTo("250.0000");

        AccountTransactionReadModel credit = savedEntries.stream()
                .filter(e -> e.getEntryType() == EntryType.CREDIT).findFirst().orElseThrow();
        assertThat(credit.getAccount().getId()).isEqualTo("ACC-1002");
        assertThat(credit.getAmount()).isEqualByComparingTo("250.0000");

        verify(processedEventRepository).save(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("Should skip duplicate TransactionCreatedEvent by eventId (ADR-008)")
    void shouldSkipDuplicateTransactionCreatedEvent() {
        String eventId = "EVT-200";
        TransactionCreatedEvent event = new TransactionCreatedEvent(
                eventId, "TRANSACTION_CREATED", "TX-200", "TRF-200",
                "ACC-1001", null, "OTHER_BANK", BigDecimal.TEN, "INR",
                "SUCCESS", "REF-200", null, null, null, Instant.now()
        );

        when(processedEventRepository.existsByEventId(eventId)).thenReturn(true);

        boolean applied = projectionService.applyTransactionCreatedEvent(event);

        assertThat(applied).isFalse();
        verify(accountRepository, never()).findById(anyString());
        verify(accountTransactionRepository, never()).save(any());
    }
}
