package com.neobank.account.service;

import com.neobank.account.domain.*;
import com.neobank.account.event.BalanceUpdatedEvent;
import com.neobank.account.event.TransactionCreatedEvent;
import com.neobank.account.repository.AccountRepository;
import com.neobank.account.repository.AccountTransactionRepository;
import com.neobank.account.repository.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class AccountProjectionService {

    private static final Logger log = LoggerFactory.getLogger(AccountProjectionService.class);

    private final AccountRepository accountRepository;
    private final AccountTransactionRepository accountTransactionRepository;
    private final ProcessedEventRepository processedEventRepository;

    public AccountProjectionService(AccountRepository accountRepository,
                                    AccountTransactionRepository accountTransactionRepository,
                                    ProcessedEventRepository processedEventRepository) {
        this.accountRepository = accountRepository;
        this.accountTransactionRepository = accountTransactionRepository;
        this.processedEventRepository = processedEventRepository;
    }

    /**
     * Projects a BalanceUpdatedEvent (ADR-004, 10 fields) onto the Account balance read model.
     * Deduplicates using eventId (ADR-008).
     *
     * @return true if event was newly applied, false if duplicate (skipped)
     */
    public boolean applyBalanceUpdatedEvent(BalanceUpdatedEvent event) {
        if (event == null || event.eventId() == null || event.accountId() == null) {
            throw new IllegalArgumentException("BalanceUpdatedEvent, eventId, and accountId must not be null");
        }

        // Deduplication by eventId (ADR-008)
        if (processedEventRepository.existsByEventId(event.eventId())) {
            log.info("Duplicate BalanceUpdatedEvent received, skipping. eventId={}", event.eventId());
            return false;
        }

        Optional<Account> accountOpt = accountRepository.findWithLockById(event.accountId());
        if (accountOpt.isPresent()) {
            Account account = accountOpt.get();
            account.updateBalance(event.newBalance());
            accountRepository.save(account);
            log.info("Updated balance read model for accountId={} to {}", account.getId(), event.newBalance());
        } else {
            log.warn("Account {} not found in Account Service during balance projection", event.accountId());
        }

        processedEventRepository.save(new ProcessedEvent(
                event.eventId(),
                event.eventType() != null ? event.eventType() : "BALANCE_UPDATED",
                Instant.now()
        ));
        return true;
    }

    /**
     * Projects a TransactionCreatedEvent (ADR-007, 15 fields) onto the transaction-history read model.
     * Derives a DEBIT entry for the source account and, for internal transfers, a CREDIT entry for the destination.
     * Deduplicates using eventId (ADR-008).
     *
     * @return true if event was newly applied, false if duplicate (skipped)
     */
    public boolean applyTransactionCreatedEvent(TransactionCreatedEvent event) {
        if (event == null || event.eventId() == null || event.transactionId() == null) {
            throw new IllegalArgumentException("TransactionCreatedEvent, eventId, and transactionId must not be null");
        }

        // Deduplication by eventId (ADR-008)
        if (processedEventRepository.existsByEventId(event.eventId())) {
            log.info("Duplicate TransactionCreatedEvent received, skipping. eventId={}", event.eventId());
            return false;
        }

        TransferType transferType;
        try {
            transferType = TransferType.valueOf(event.transferType());
        } catch (Exception e) {
            transferType = TransferType.OWN_ACCOUNT;
        }

        Instant txTime = event.timestamp() != null ? event.timestamp() : Instant.now();

        // 1. Source Account: project DEBIT transaction record if internal account exists
        if (event.sourceAccountId() != null) {
            Optional<Account> sourceOpt = accountRepository.findById(event.sourceAccountId());
            if (sourceOpt.isPresent()) {
                AccountTransactionReadModel debitEntry = new AccountTransactionReadModel(
                        "ATX-" + UUID.randomUUID(),
                        sourceOpt.get(),
                        event.transactionId(),
                        event.transferId(),
                        EntryType.DEBIT,
                        event.amount(),
                        event.currency(),
                        transferType,
                        event.referenceNumber(),
                        event.remarks(),
                        event.destinationName(),
                        event.destinationReference(),
                        txTime,
                        Instant.now()
                );
                accountTransactionRepository.save(debitEntry);
                log.info("Created DEBIT transaction read model for accountId={}", event.sourceAccountId());
            }
        }

        // 2. Destination Account: project CREDIT transaction record if internal own-account transfer
        if (event.destinationAccountId() != null) {
            Optional<Account> destOpt = accountRepository.findById(event.destinationAccountId());
            if (destOpt.isPresent()) {
                AccountTransactionReadModel creditEntry = new AccountTransactionReadModel(
                        "ATX-" + UUID.randomUUID(),
                        destOpt.get(),
                        event.transactionId(),
                        event.transferId(),
                        EntryType.CREDIT,
                        event.amount(),
                        event.currency(),
                        transferType,
                        event.referenceNumber(),
                        event.remarks(),
                        event.destinationName(),
                        event.destinationReference(),
                        txTime,
                        Instant.now()
                );
                accountTransactionRepository.save(creditEntry);
                log.info("Created CREDIT transaction read model for accountId={}", event.destinationAccountId());
            }
        }

        processedEventRepository.save(new ProcessedEvent(
                event.eventId(),
                event.eventType() != null ? event.eventType() : "TRANSACTION_CREATED",
                Instant.now()
        ));
        return true;
    }
}
