package com.neobank.account;

import com.neobank.account.domain.*;
import com.neobank.account.event.BalanceUpdatedEvent;
import com.neobank.account.event.TransactionCreatedEvent;
import com.neobank.account.exception.AccountNotFoundException;
import com.neobank.account.repository.AccountRepository;
import com.neobank.account.repository.AccountTransactionRepository;
import com.neobank.account.repository.ProcessedEventRepository;
import com.neobank.account.security.CustomerContextHolder;
import com.neobank.account.service.AccountProjectionService;
import com.neobank.account.service.AccountService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class AccountIntegrationTest {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AccountTransactionRepository transactionRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private AccountProjectionService projectionService;

    @Autowired
    private AccountService accountService;

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
        processedEventRepository.deleteAll();
        accountRepository.deleteAll();

        // Seed test accounts for Customer C1001 and Customer C2002
        Account acc1 = new Account(
                "ACC-1001", "1001000101", "C1001",
                AccountType.SAVINGS, "INR", new BigDecimal("10000.0000"),
                AccountStatus.ACTIVE, Instant.now()
        );
        Account acc2 = new Account(
                "ACC-1002", "1001000102", "C1001",
                AccountType.CURRENT, "INR", new BigDecimal("5000.0000"),
                AccountStatus.ACTIVE, Instant.now()
        );
        Account acc3 = new Account(
                "ACC-2001", "2001000201", "C2002",
                AccountType.SAVINGS, "INR", new BigDecimal("3000.0000"),
                AccountStatus.ACTIVE, Instant.now()
        );
        accountRepository.saveAll(List.of(acc1, acc2, acc3));
    }

    @AfterEach
    void tearDown() {
        CustomerContextHolder.clear();
    }

    @Test
    @DisplayName("End-to-End: Query customer accounts and enforce customer ownership")
    void shouldQueryCustomerAccountsWithStrictScoping() {
        // As Customer C1001
        var c1001Accounts = accountService.getAccountsForCustomer("C1001");
        assertThat(c1001Accounts).hasSize(2);
        assertThat(c1001Accounts).extracting("accountId").containsExactlyInAnyOrder("ACC-1001", "ACC-1002");

        // As Customer C2002
        var c2002Accounts = accountService.getAccountsForCustomer("C2002");
        assertThat(c2002Accounts).hasSize(1);
        assertThat(c2002Accounts.get(0).accountId()).isEqualTo("ACC-2001");

        // Detail check
        var detail1001 = accountService.getAccountDetail("ACC-1001", "C1001");
        assertThat(detail1001.accountId()).isEqualTo("ACC-1001");
        assertThat(detail1001.balance()).isEqualByComparingTo("10000.0000");

        // Ownership isolation: Customer C2002 querying ACC-1001 fails closed (throws AccountNotFoundException)
        assertThatThrownBy(() -> accountService.getAccountDetail("ACC-1001", "C2002"))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    @DisplayName("End-to-End: Synchronize balance read model from BalanceUpdatedEvent and deduplicate")
    void shouldSynchronizeBalanceAndDeduplicate() {
        String eventId = "EVT-BAL-001";
        BalanceUpdatedEvent event = new BalanceUpdatedEvent(
                eventId,
                "BALANCE_UPDATED",
                "TRF-100",
                "ACC-1001",
                "TX-100",
                new BigDecimal("10000.0000"),
                new BigDecimal("8500.0000"),
                new BigDecimal("1500.0000"),
                "INR",
                Instant.now()
        );

        // First application: success
        boolean firstApplied = projectionService.applyBalanceUpdatedEvent(event);
        assertThat(firstApplied).isTrue();

        Account updatedAccount = accountRepository.findById("ACC-1001").orElseThrow();
        assertThat(updatedAccount.getBalance()).isEqualByComparingTo("8500.0000");
        assertThat(processedEventRepository.existsByEventId(eventId)).isTrue();

        // Duplicate delivery with same eventId: skipped
        boolean secondApplied = projectionService.applyBalanceUpdatedEvent(event);
        assertThat(secondApplied).isFalse();
    }

    @Test
    @DisplayName("End-to-End: Synchronize transaction read model from TransactionCreatedEvent and deduplicate")
    void shouldSynchronizeTransactionHistoryAndDeduplicate() {
        String eventId = "EVT-TX-001";
        TransactionCreatedEvent event = new TransactionCreatedEvent(
                eventId,
                "TRANSACTION_CREATED",
                "TX-100",
                "TRF-100",
                "ACC-1001",
                "ACC-1002",
                "OWN_ACCOUNT",
                new BigDecimal("1500.0000"),
                "INR",
                "SUCCESS",
                "REF-100",
                "Internal transfer",
                "Savings Account",
                "XXXXXX0102",
                Instant.now()
        );

        // First application
        boolean firstApplied = projectionService.applyTransactionCreatedEvent(event);
        assertThat(firstApplied).isTrue();

        var sourceTxs = accountService.getAccountTransactions("ACC-1001", "C1001");
        assertThat(sourceTxs).hasSize(1);
        assertThat(sourceTxs.get(0).entryType()).isEqualTo(EntryType.DEBIT);
        assertThat(sourceTxs.get(0).amount()).isEqualByComparingTo("1500.0000");

        var destTxs = accountService.getAccountTransactions("ACC-1002", "C1001");
        assertThat(destTxs).hasSize(1);
        assertThat(destTxs.get(0).entryType()).isEqualTo(EntryType.CREDIT);
        assertThat(destTxs.get(0).amount()).isEqualByComparingTo("1500.0000");

        // Duplicate delivery with same eventId: skipped
        boolean secondApplied = projectionService.applyTransactionCreatedEvent(event);
        assertThat(secondApplied).isFalse();

        // Total rows unchanged
        assertThat(transactionRepository.count()).isEqualTo(2);
    }
}
