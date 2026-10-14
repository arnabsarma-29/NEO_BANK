package com.neobank.account.service;

import com.neobank.account.domain.Account;
import com.neobank.account.domain.AccountStatus;
import com.neobank.account.domain.AccountTransactionReadModel;
import com.neobank.account.dto.AccountDetailResponse;
import com.neobank.account.dto.AccountOwnershipResult;
import com.neobank.account.dto.AccountSummaryResponse;
import com.neobank.account.dto.AccountTransactionResponse;
import com.neobank.account.exception.AccountNotFoundException;
import com.neobank.account.repository.AccountRepository;
import com.neobank.account.repository.AccountTransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class AccountService {

    private final AccountRepository accountRepository;
    private final AccountTransactionRepository accountTransactionRepository;

    public AccountService(AccountRepository accountRepository,
                          AccountTransactionRepository accountTransactionRepository) {
        this.accountRepository = accountRepository;
        this.accountTransactionRepository = accountTransactionRepository;
    }

    /**
     * Retrieves all accounts belonging to the authenticated customer.
     * Empty list if customer has no accounts.
     */
    public List<AccountSummaryResponse> getAccountsForCustomer(String customerId) {
        if (customerId == null || customerId.isBlank()) {
            throw new IllegalArgumentException("customerId must not be null or blank");
        }
        return accountRepository.findByCustomerId(customerId).stream()
                .map(this::toSummaryResponse)
                .toList();
    }

    /**
     * Retrieves account details for an authorized account.
     * Throws AccountNotFoundException if the account does not exist or belongs to another customer,
     * ensuring fail-closed security and preventing account existence leakage (ADR-014).
     */
    public AccountDetailResponse getAccountDetail(String accountId, String customerId) {
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("accountId must not be null or blank");
        }
        if (customerId == null || customerId.isBlank()) {
            throw new IllegalArgumentException("customerId must not be null or blank");
        }

        Account account = accountRepository.findByIdAndCustomerId(accountId, customerId)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountId, accountId));

        return toDetailResponse(account);
    }

    /**
     * Retrieves transaction history read model for an authorized account.
     * Verifies customer ownership first; throws AccountNotFoundException if unauthorized.
     */
    public List<AccountTransactionResponse> getAccountTransactions(String accountId, String customerId) {
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("accountId must not be null or blank");
        }
        if (customerId == null || customerId.isBlank()) {
            throw new IllegalArgumentException("customerId must not be null or blank");
        }

        // Enforce ownership check before querying transaction history
        if (!accountRepository.existsByIdAndCustomerId(accountId, customerId)) {
            throw new AccountNotFoundException("Account not found: " + accountId, accountId);
        }

        List<AccountTransactionReadModel> transactions =
                accountTransactionRepository.findByAccountIdOrderByTimestampDesc(accountId);

        return transactions.stream()
                .map(this::toTransactionResponse)
                .toList();
    }

    /**
     * Service-local domain method to validate account ownership against canonical customer identity (ADR-014).
     * Used by Transfer Service integration boundary when finalized.
     */
    public AccountOwnershipResult validateAccountOwnership(String accountId, String customerId) {
        if (accountId == null || accountId.isBlank()) {
            return AccountOwnershipResult.invalid(accountId, customerId, "Account identifier must not be null or blank");
        }
        if (customerId == null || customerId.isBlank()) {
            return AccountOwnershipResult.invalid(accountId, customerId, "Customer identifier must not be null or blank");
        }

        Optional<Account> accountOpt = accountRepository.findById(accountId);
        if (accountOpt.isEmpty()) {
            return AccountOwnershipResult.invalid(accountId, customerId, "Account does not exist");
        }

        Account account = accountOpt.get();
        if (!account.getCustomerId().equals(customerId)) {
            return AccountOwnershipResult.invalid(accountId, customerId, "Account is not associated with customer");
        }

        if (account.getStatus() != AccountStatus.ACTIVE) {
            return AccountOwnershipResult.invalid(accountId, customerId, "Account is not active: " + account.getStatus());
        }

        return AccountOwnershipResult.valid(
                account.getId(),
                account.getCustomerId(),
                account.getStatus(),
                account.getAccountType(),
                account.getCurrency()
        );
    }

    private AccountSummaryResponse toSummaryResponse(Account account) {
        return new AccountSummaryResponse(
                account.getId(),
                account.getAccountNumber(),
                account.getMaskedAccountNumber(),
                account.getAccountType(),
                account.getCurrency(),
                account.getBalance(),
                account.getStatus(),
                account.getCreatedAt()
        );
    }

    private AccountDetailResponse toDetailResponse(Account account) {
        return new AccountDetailResponse(
                account.getId(),
                account.getAccountNumber(),
                account.getMaskedAccountNumber(),
                account.getCustomerId(),
                account.getAccountType(),
                account.getCurrency(),
                account.getBalance(),
                account.getStatus(),
                account.getVersion(),
                account.getCreatedAt(),
                account.getUpdatedAt()
        );
    }

    private AccountTransactionResponse toTransactionResponse(AccountTransactionReadModel tx) {
        return new AccountTransactionResponse(
                tx.getId(),
                tx.getAccount().getId(),
                tx.getTransactionId(),
                tx.getTransferId(),
                tx.getEntryType(),
                tx.getAmount(),
                tx.getCurrency(),
                tx.getTransferType(),
                tx.getReferenceNumber(),
                tx.getRemarks(),
                tx.getDestinationName(),
                tx.getDestinationReference(),
                tx.getTransactionTimestamp()
        );
    }
}
