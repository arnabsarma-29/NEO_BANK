package com.neobank.account.repository;

import com.neobank.account.domain.Account;
import com.neobank.account.domain.AccountStatus;
import com.neobank.account.domain.AccountType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AccountRepositoryTest {

    @Autowired
    private AccountRepository accountRepository;

    @BeforeEach
    void setUp() {
        accountRepository.deleteAll();
    }

    @Test
    @DisplayName("Should persist and find account by ID and customer ID")
    void shouldPersistAndFindAccount() {
        Account account = new Account(
                "ACC-1001",
                "1001000101",
                "C1001",
                AccountType.SAVINGS,
                "INR",
                new BigDecimal("5000.0000"),
                AccountStatus.ACTIVE,
                Instant.now()
        );
        accountRepository.save(account);

        Optional<Account> found = accountRepository.findByIdAndCustomerId("ACC-1001", "C1001");
        assertThat(found).isPresent();
        assertThat(found.get().getAccountNumber()).isEqualTo("1001000101");
        assertThat(found.get().getBalance()).isEqualByComparingTo("5000.0000");
        assertThat(found.get().getMaskedAccountNumber()).isEqualTo("XXXXXX0101");
    }

    @Test
    @DisplayName("Should isolate accounts by customer ID")
    void shouldFindAccountsByCustomerId() {
        Account acc1 = new Account("ACC-1001", "1001000101", "C1001", AccountType.SAVINGS, "INR", BigDecimal.ZERO, AccountStatus.ACTIVE, Instant.now());
        Account acc2 = new Account("ACC-1002", "1001000102", "C1001", AccountType.CURRENT, "INR", BigDecimal.ZERO, AccountStatus.ACTIVE, Instant.now());
        Account acc3 = new Account("ACC-2001", "2001000201", "C2002", AccountType.SAVINGS, "INR", BigDecimal.ZERO, AccountStatus.ACTIVE, Instant.now());

        accountRepository.saveAll(List.of(acc1, acc2, acc3));

        List<Account> c1001Accounts = accountRepository.findByCustomerId("C1001");
        assertThat(c1001Accounts).hasSize(2);
        assertThat(c1001Accounts).extracting(Account::getId).containsExactlyInAnyOrder("ACC-1001", "ACC-1002");

        List<Account> c2002Accounts = accountRepository.findByCustomerId("C2002");
        assertThat(c2002Accounts).hasSize(1);
        assertThat(c2002Accounts.get(0).getId()).isEqualTo("ACC-2001");
    }

    @Test
    @DisplayName("Should return empty when account belongs to a different customer")
    void shouldReturnEmptyForMismatchedCustomer() {
        Account account = new Account("ACC-1001", "1001000101", "C1001", AccountType.SAVINGS, "INR", BigDecimal.ZERO, AccountStatus.ACTIVE, Instant.now());
        accountRepository.save(account);

        Optional<Account> result = accountRepository.findByIdAndCustomerId("ACC-1001", "C9999");
        assertThat(result).isEmpty();
        assertThat(accountRepository.existsByIdAndCustomerId("ACC-1001", "C9999")).isFalse();
    }
}
