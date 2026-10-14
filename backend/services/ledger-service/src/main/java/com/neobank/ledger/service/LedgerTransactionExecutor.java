package com.neobank.ledger.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neobank.ledger.domain.*;
import com.neobank.ledger.dto.LedgerTransferExecutionRequest;
import com.neobank.ledger.event.BalanceUpdatedEvent;
import com.neobank.ledger.event.TransactionCreatedEvent;
import com.neobank.ledger.exception.AccountNotFoundException;
import com.neobank.ledger.exception.InsufficientFundsException;
import com.neobank.ledger.exception.InvalidRequestException;
import com.neobank.ledger.exception.LedgerException;
import com.neobank.ledger.exception.TransferNotExecutableException;
import com.neobank.ledger.repository.LedgerAccountRepository;
import com.neobank.ledger.repository.LedgerEntryRepository;
import com.neobank.ledger.repository.LedgerExecutionRepository;
import com.neobank.ledger.repository.LedgerTransactionRepository;
import com.neobank.ledger.repository.OutboxEventRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Component
public class LedgerTransactionExecutor {

    private final LedgerAccountRepository accountRepository;
    private final LedgerTransactionRepository transactionRepository;
    private final LedgerEntryRepository entryRepository;
    private final OutboxEventRepository outboxRepository;
    private final LedgerExecutionRepository executionRepository;
    private final ObjectMapper objectMapper;

    public LedgerTransactionExecutor(LedgerAccountRepository accountRepository,
                                    LedgerTransactionRepository transactionRepository,
                                    LedgerEntryRepository entryRepository,
                                    OutboxEventRepository outboxRepository,
                                    LedgerExecutionRepository executionRepository,
                                    ObjectMapper objectMapper) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.entryRepository = entryRepository;
        this.outboxRepository = outboxRepository;
        this.executionRepository = executionRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Executes atomic financial mutations, transaction record insertion, entries,
     * outbox event persistence, and durable outcome store update in a single dedicated database transaction.
     * No durable PROCESSING row is written, eliminating stranded in-progress states on crash.
     * If a duplicate transferId races concurrently, PostgreSQL unique/PK constraint throws
     * DataIntegrityViolationException (SQLSTATE 23505), rolling back the loser completely.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ExecutionOutcome execute(LedgerTransferExecutionRequest request, String financialFingerprint) {
        String transferId = request.getTransferId();
        String sourceAccountId = request.getSourceAccountId();
        String destinationAccountId = request.getDestinationAccountId();
        BigDecimal amount = request.getAmount();
        String currency = request.getCurrency();
        TransferType transferType = request.getTransferType();
        Instant now = Instant.now();

        // 1. Pessimistic account locking & validation
        LedgerAccount sourceAccount;
        LedgerAccount destAccount = null;

        if (transferType == TransferType.OWN_ACCOUNT) {
            if (sourceAccountId.equals(destinationAccountId)) {
                throw new InvalidRequestException("Source and destination accounts must be different", transferId);
            }

            Optional<LedgerAccount> sourceOpt;
            Optional<LedgerAccount> destOpt;

            // Ordered pessimistic locking by accountId to prevent deadlocks across concurrent transfers
            if (sourceAccountId.compareTo(destinationAccountId) < 0) {
                sourceOpt = accountRepository.findWithLockByAccountId(sourceAccountId);
                destOpt = accountRepository.findWithLockByAccountId(destinationAccountId);
            } else {
                destOpt = accountRepository.findWithLockByAccountId(destinationAccountId);
                sourceOpt = accountRepository.findWithLockByAccountId(sourceAccountId);
            }

            if (sourceOpt.isEmpty()) {
                return recordRejection(request, financialFingerprint, "ACCOUNT_NOT_FOUND",
                        "Source account not found: " + sourceAccountId,
                        new AccountNotFoundException("Source account not found: " + sourceAccountId, transferId));
            }
            if (destOpt.isEmpty()) {
                return recordRejection(request, financialFingerprint, "ACCOUNT_NOT_FOUND",
                        "Destination account not found: " + destinationAccountId,
                        new AccountNotFoundException("Destination account not found: " + destinationAccountId, transferId));
            }

            sourceAccount = sourceOpt.get();
            destAccount = destOpt.get();

            if (sourceAccount.getStatus() != AccountStatus.ACTIVE) {
                return recordRejection(request, financialFingerprint, "ACCOUNT_NOT_ACTIVE",
                        "Source account is not active: " + sourceAccount.getStatus(),
                        new TransferNotExecutableException("ACCOUNT_NOT_ACTIVE",
                                "Source account is not active: " + sourceAccount.getStatus(), transferId));
            }
            if (destAccount.getStatus() != AccountStatus.ACTIVE) {
                return recordRejection(request, financialFingerprint, "ACCOUNT_NOT_ACTIVE",
                        "Destination account is not active: " + destAccount.getStatus(),
                        new TransferNotExecutableException("ACCOUNT_NOT_ACTIVE",
                                "Destination account is not active: " + destAccount.getStatus(), transferId));
            }

            if (!sourceAccount.getCurrency().equalsIgnoreCase(currency)) {
                return recordRejection(request, financialFingerprint, "CURRENCY_MISMATCH",
                        "Source account currency " + sourceAccount.getCurrency() + " does not match transfer currency " + currency,
                        new TransferNotExecutableException("CURRENCY_MISMATCH",
                                "Source account currency " + sourceAccount.getCurrency() + " does not match transfer currency " + currency, transferId));
            }
            if (!destAccount.getCurrency().equalsIgnoreCase(currency)) {
                return recordRejection(request, financialFingerprint, "CURRENCY_MISMATCH",
                        "Destination account currency " + destAccount.getCurrency() + " does not match transfer currency " + currency,
                        new TransferNotExecutableException("CURRENCY_MISMATCH",
                                "Destination account currency " + destAccount.getCurrency() + " does not match transfer currency " + currency, transferId));
            }
        } else {
            // External transfer (OTHER_BANK, MOCK_UPI) - lock source account only
            Optional<LedgerAccount> sourceOpt = accountRepository.findWithLockByAccountId(sourceAccountId);
            if (sourceOpt.isEmpty()) {
                return recordRejection(request, financialFingerprint, "ACCOUNT_NOT_FOUND",
                        "Source account not found: " + sourceAccountId,
                        new AccountNotFoundException("Source account not found: " + sourceAccountId, transferId));
            }

            sourceAccount = sourceOpt.get();

            if (sourceAccount.getStatus() != AccountStatus.ACTIVE) {
                return recordRejection(request, financialFingerprint, "ACCOUNT_NOT_ACTIVE",
                        "Source account is not active: " + sourceAccount.getStatus(),
                        new TransferNotExecutableException("ACCOUNT_NOT_ACTIVE",
                                "Source account is not active: " + sourceAccount.getStatus(), transferId));
            }

            if (!sourceAccount.getCurrency().equalsIgnoreCase(currency)) {
                return recordRejection(request, financialFingerprint, "CURRENCY_MISMATCH",
                        "Source account currency " + sourceAccount.getCurrency() + " does not match transfer currency " + currency,
                        new TransferNotExecutableException("CURRENCY_MISMATCH",
                                "Source account currency " + sourceAccount.getCurrency() + " does not match transfer currency " + currency, transferId));
            }
        }

        // 2. Validate sufficient balance
        BigDecimal oldSourceBalance = sourceAccount.getBalance();
        if (oldSourceBalance.compareTo(amount) < 0) {
            return recordRejection(request, financialFingerprint, "INSUFFICIENT_FUNDS",
                    "Insufficient balance in source account: " + oldSourceBalance + " < " + amount,
                    new InsufficientFundsException(
                            "Insufficient balance in source account: " + oldSourceBalance + " < " + amount, transferId));
        }

        // 3. Atomic balance mutations
        sourceAccount.debit(amount);
        accountRepository.save(sourceAccount);
        BigDecimal newSourceBalance = sourceAccount.getBalance();

        BigDecimal oldDestBalance = null;
        BigDecimal newDestBalance = null;
        if (destAccount != null) {
            oldDestBalance = destAccount.getBalance();
            destAccount.credit(amount);
            accountRepository.save(destAccount);
            newDestBalance = destAccount.getBalance();
        }

        String transactionId = "TX-" + UUID.randomUUID();

        // 4. Authoritative transaction record
        LedgerTransaction tx = new LedgerTransaction(
                transactionId,
                transferId,
                sourceAccountId,
                destinationAccountId,
                transferType,
                amount,
                currency,
                "SUCCESS",
                request.getReferenceNumber(),
                request.getRemarks(),
                request.getDestinationName(),
                request.getDestinationReference(),
                now
        );
        LedgerTransaction savedTx = transactionRepository.saveAndFlush(tx);

        // 5. Simple Atomic Debit/Credit Ledger entries
        LedgerEntry debitEntry = new LedgerEntry(
                "ENT-" + UUID.randomUUID(),
                transactionId,
                sourceAccountId,
                EntryType.DEBIT,
                amount,
                currency,
                now
        );
        entryRepository.save(debitEntry);

        if (destAccount != null) {
            LedgerEntry creditEntry = new LedgerEntry(
                    "ENT-" + UUID.randomUUID(),
                    transactionId,
                    destinationAccountId,
                    EntryType.CREDIT,
                    amount,
                    currency,
                    now
            );
            entryRepository.save(creditEntry);
        }

        // 6. Transactional Outbox Events
        TransactionCreatedEvent txCreatedEvent = new TransactionCreatedEvent(
                UUID.randomUUID().toString(),
                transactionId,
                transferId,
                sourceAccountId,
                destinationAccountId,
                transferType.name(),
                amount,
                currency,
                "SUCCESS",
                request.getReferenceNumber(),
                request.getRemarks(),
                request.getDestinationName(),
                request.getDestinationReference(),
                now
        );
        saveOutboxEvent("LEDGER_TRANSACTION", transactionId, "TRANSACTION_CREATED", txCreatedEvent, now);

        BalanceUpdatedEvent sourceBalanceEvent = new BalanceUpdatedEvent(
                UUID.randomUUID().toString(),
                transferId,
                sourceAccountId,
                transactionId,
                oldSourceBalance,
                newSourceBalance,
                amount,
                currency,
                now
        );
        saveOutboxEvent("LEDGER_ACCOUNT", sourceAccountId, "BALANCE_UPDATED", sourceBalanceEvent, now);

        if (destAccount != null) {
            BalanceUpdatedEvent destBalanceEvent = new BalanceUpdatedEvent(
                    UUID.randomUUID().toString(),
                    transferId,
                    destinationAccountId,
                    transactionId,
                    oldDestBalance,
                    newDestBalance,
                    amount,
                    currency,
                    now
            );
            saveOutboxEvent("LEDGER_ACCOUNT", destinationAccountId, "BALANCE_UPDATED", destBalanceEvent, now);
        }

        // 7. Record COMMITTED outcome in the same atomic transaction
        LedgerExecution execution = new LedgerExecution(
                transferId,
                ExecutionStatus.COMMITTED,
                sourceAccountId,
                destinationAccountId,
                transferType,
                amount,
                currency,
                request.getReferenceNumber(),
                transactionId,
                null, // statusReasonCode
                null, // failureMessage
                financialFingerprint,
                now,
                now, // completedAt
                1L // version
        );
        executionRepository.saveAndFlush(execution);

        return new ExecutionOutcome.Committed(execution, savedTx);
    }

    private ExecutionOutcome recordRejection(LedgerTransferExecutionRequest request, String financialFingerprint,
                                            String reasonCode, String failureMessage, LedgerException ex) {
        Instant now = Instant.now();
        LedgerExecution execution = new LedgerExecution(
                request.getTransferId(),
                ExecutionStatus.REJECTED,
                request.getSourceAccountId(),
                request.getDestinationAccountId(),
                request.getTransferType(),
                request.getAmount(),
                request.getCurrency(),
                request.getReferenceNumber(),
                null, // ledgerTransactionId
                reasonCode,
                failureMessage,
                financialFingerprint,
                now,
                now, // completedAt
                1L // version
        );
        executionRepository.saveAndFlush(execution);
        return new ExecutionOutcome.Rejected(execution, ex);
    }

    private void saveOutboxEvent(String aggregateType, String aggregateId, String eventType, Object payloadObj, Instant now) {
        try {
            String jsonPayload = objectMapper.writeValueAsString(payloadObj);
            OutboxEvent outboxEvent = new OutboxEvent(
                    UUID.randomUUID().toString(),
                    aggregateType,
                    aggregateId,
                    eventType,
                    jsonPayload,
                    OutboxStatus.PENDING,
                    now
            );
            outboxRepository.save(outboxEvent);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize outbox event payload: " + eventType, e);
        }
    }
}
