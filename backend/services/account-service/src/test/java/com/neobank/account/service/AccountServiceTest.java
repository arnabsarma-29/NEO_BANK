package com.neobank.account.service;

import com.neobank.account.domain.*;
import com.neobank.account.dto.AccountDetailResponse;
import com.neobank.account.dto.AccountOwnershipResult;
import com.neobank.account.dto.AccountSummaryResponse;
import com.neobank.account.dto.AccountTransactionResponse;
import com.neobank.account.exception.AccountNotFoundException;
import com.neobank.account.repository.AccountRepository;
import com.neobank.account.repository.AccountTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private AccountTransactionRepository accountTransactionRepository;

    private AccountService accountService;

    @BeforeEach
    void setUp() {
        accountService = new AccountService(accountRepository, accountTransactionRepository);
    }

    @Test
    @DisplayName("Should return customer accounts list")
    void shouldReturnAccountsForCustomer() {
        Account acc1 = new Account("ACC-1001", "1001000101", "C1001", AccountType.SAVINGS, "INR",
                new BigDecimal("1000.0000"), AccountStatus.ACTIVE, Instant.now());
        when(accountRepository.findByCustomerId("C1001")).thenReturn(List.of(acc1));

        List<AccountSummaryResponse> results = accountService.getAccountsForCustomer("C1001");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).accountId()).isEqualTo("ACC-1001");
        assertThat(results.get(0).maskedAccountNumber()).isEqualTo("XXXXXX0101");
        assertThat(results.get(0).balance()).isEqualByComparingTo("1000.0000");
    }

    @Test
    @DisplayName("Should return account detail when owned by customer")
    void shouldReturnAccountDetailWhenOwned() {
        Account acc = new Account("ACC-1001", "1001000101", "C1001", AccountType.SAVINGS, "INR",
                new BigDecimal("1000.0000"), AccountStatus.ACTIVE, Instant.now());
        when(accountRepository.findByIdAndCustomerId("ACC-1001", "C1001")).thenReturn(Optional.of(acc));

        AccountDetailResponse result = accountService.getAccountDetail("ACC-1001", "C1001");

        assertThat(result.accountId()).isEqualTo("ACC-1001");
        assertThat(result.customerId()).isEqualTo("C1001");
        assertThat(result.balance()).isEqualByComparingTo("1000.0000");
    }

    @Test
    @DisplayName("Should throw AccountNotFoundException when account belongs to another customer (prevents disclosure)")
    void shouldThrowWhenAccountBelongsToAnotherCustomer() {
        when(accountRepository.findByIdAndCustomerId("ACC-1001", "C9999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.getAccountDetail("ACC-1001", "C9999"))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessageContaining("Account not found: ACC-1001");
    }

    @Test
    @DisplayName("Should return transaction history when account is owned")
    void shouldReturnTransactionsWhenOwned() {
        when(accountRepository.existsByIdAndCustomerId("ACC-1001", "C1001")).thenReturn(true);
        Account acc = new Account("ACC-1001", "1001000101", "C1001", AccountType.SAVINGS, "INR",
                BigDecimal.ZERO, AccountStatus.ACTIVE, Instant.now());
        AccountTransactionReadModel tx = new AccountTransactionReadModel(
                "ATX-1", acc, "TX-1", "TRF-1", EntryType.DEBIT, new BigDecimal("100.0000"),
                "INR", TransferType.OWN_ACCOUNT, "REF-1", "Remarks", null, null,
                Instant.now(), Instant.now()
        );
        when(accountTransactionRepository.findByAccountIdOrderByTimestampDesc("ACC-1001")).thenReturn(List.of(tx));

        List<AccountTransactionResponse> results = accountService.getAccountTransactions("ACC-1001", "C1001");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).transactionId()).isEqualTo("TX-1");
        assertThat(results.get(0).entryType()).isEqualTo(EntryType.DEBIT);
    }

    @Test
    @DisplayName("Should reject transaction history query when account not owned")
    void shouldRejectTransactionsWhenNotOwned() {
        when(accountRepository.existsByIdAndCustomerId("ACC-1001", "C9999")).thenReturn(false);

        assertThatThrownBy(() -> accountService.getAccountTransactions("ACC-1001", "C9999"))
                .isInstanceOf(AccountNotFoundException.class);
        verify(accountTransactionRepository, never()).findByAccountIdOrderByTimestampDesc(anyString());
    }

    @Test
    @DisplayName("validateAccountOwnership: should return valid for active owned account")
    void validateOwnership_ShouldReturnValid() {
        Account acc = new Account("ACC-1001", "1001000101", "C1001", AccountType.SAVINGS, "INR",
                BigDecimal.ZERO, AccountStatus.ACTIVE, Instant.now());
        when(accountRepository.findById("ACC-1001")).thenReturn(Optional.of(acc));

        AccountOwnershipResult result = accountService.validateAccountOwnership("ACC-1001", "C1001");

        assertThat(result.valid()).isTrue();
        assertThat(result.accountId()).isEqualTo("ACC-1001");
        assertThat(result.customerId()).isEqualTo("C1001");
        assertThat(result.status()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    @DisplayName("validateAccountOwnership: should return invalid for missing account")
    void validateOwnership_ShouldReturnInvalidWhenMissing() {
        when(accountRepository.findById("ACC-9999")).thenReturn(Optional.empty());

        AccountOwnershipResult result = accountService.validateAccountOwnership("ACC-9999", "C1001");

        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).contains("Account does not exist");
    }

    @Test
    @DisplayName("validateAccountOwnership: should return invalid when customer ID does not match")
    void validateOwnership_ShouldReturnInvalidWhenCustomerMismatch() {
        Account acc = new Account("ACC-1001", "1001000101", "C1001", AccountType.SAVINGS, "INR",
                BigDecimal.ZERO, AccountStatus.ACTIVE, Instant.now());
        when(accountRepository.findById("ACC-1001")).thenReturn(Optional.of(acc));

        AccountOwnershipResult result = accountService.validateAccountOwnership("ACC-1001", "C2002");

        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).contains("Account is not associated with customer");
    }

    @Test
    @DisplayName("validateAccountOwnership: should return invalid when account is FROZEN")
    void validateOwnership_ShouldReturnInvalidWhenFrozen() {
        Account acc = new Account("ACC-1001", "1001000101", "C1001", AccountType.SAVINGS, "INR",
                BigDecimal.ZERO, AccountStatus.FROZEN, Instant.now());
        when(accountRepository.findById("ACC-1001")).thenReturn(Optional.of(acc));

        AccountOwnershipResult result = accountService.validateAccountOwnership("ACC-1001", "C1001");

        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).contains("Account is not active: FROZEN");
    }
}
