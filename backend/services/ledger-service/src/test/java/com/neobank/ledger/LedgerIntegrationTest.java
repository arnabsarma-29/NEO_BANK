package com.neobank.ledger;

import com.neobank.ledger.domain.*;
import com.neobank.ledger.dto.LedgerTransactionOutcomeResponse;
import com.neobank.ledger.dto.LedgerTransferExecutionRequest;
import com.neobank.ledger.exception.InsufficientFundsException;
import com.neobank.ledger.exception.TransactionNotFoundException;
import com.neobank.ledger.exception.TransferIdConflictException;
import com.neobank.ledger.repository.LedgerAccountRepository;
import com.neobank.ledger.repository.LedgerEntryRepository;
import com.neobank.ledger.repository.LedgerExecutionRepository;
import com.neobank.ledger.repository.LedgerTransactionRepository;
import com.neobank.ledger.repository.OutboxEventRepository;
import com.neobank.ledger.service.LedgerExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class LedgerIntegrationTest {

    @Autowired
    private LedgerExecutionService executionService;

    @Autowired
    private LedgerAccountRepository accountRepository;

    @Autowired
    private LedgerTransactionRepository transactionRepository;

    @Autowired
    private LedgerEntryRepository entryRepository;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private LedgerExecutionRepository executionRepository;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        entryRepository.deleteAll();
        transactionRepository.deleteAll();
        executionRepository.deleteAll();
        accountRepository.deleteAll();

        // Seed initial accounts
        LedgerAccount acc1 = new LedgerAccount("INT-ACC-101", new BigDecimal("5000.0000"), "INR", AccountStatus.ACTIVE);
        LedgerAccount acc2 = new LedgerAccount("INT-ACC-102", new BigDecimal("1000.0000"), "INR", AccountStatus.ACTIVE);
        accountRepository.save(acc1);
        accountRepository.save(acc2);
    }

    @Test
    @DisplayName("1. First successful transfer updates source and destination balances correctly and stores COMMITTED execution")
    void testEndToEndTransferExecution() {
        LedgerTransferExecutionRequest request = new LedgerTransferExecutionRequest(
                "INT-TRF-001",
                "INT-ACC-101",
                "INT-ACC-102",
                TransferType.OWN_ACCOUNT,
                new BigDecimal("1500.0000"),
                "INR",
                "INT-REF-001",
                "Integration Test Transfer",
                "Savings",
                null
        );

        LedgerExecutionService.ExecutionResult result = executionService.processTransfer(request);

        assertThat(result.isNew()).isTrue();
        assertThat(result.response().getTransferId()).isEqualTo("INT-TRF-001");
        assertThat(result.response().getStatus()).isEqualTo("COMMITTED");

        // Verify accounts in DB
        LedgerAccount acc1 = accountRepository.findById("INT-ACC-101").orElseThrow();
        LedgerAccount acc2 = accountRepository.findById("INT-ACC-102").orElseThrow();
        assertThat(acc1.getBalance()).isEqualByComparingTo(new BigDecimal("3500.0000"));
        assertThat(acc2.getBalance()).isEqualByComparingTo(new BigDecimal("2500.0000"));

        // Verify transaction in DB
        LedgerTransaction tx = transactionRepository.findByTransferId("INT-TRF-001").orElseThrow();
        assertThat(tx.getAmount()).isEqualByComparingTo(new BigDecimal("1500.0000"));
        assertThat(tx.getStatus()).isEqualTo("SUCCESS");

        // Verify ledger entries
        List<LedgerEntry> entries = entryRepository.findByTransactionId(tx.getId());
        assertThat(entries).hasSize(2);
        assertThat(entries).extracting(LedgerEntry::getEntryType)
                .containsExactlyInAnyOrder(EntryType.DEBIT, EntryType.CREDIT);

        // Verify outbox events in DB (1 TransactionCreatedEvent + 2 BalanceUpdatedEvent)
        List<OutboxEvent> outboxEvents = outboxRepository.findAll();
        assertThat(outboxEvents).hasSize(3);
        assertThat(outboxEvents).extracting(OutboxEvent::getEventType)
                .contains("TRANSACTION_CREATED", "BALANCE_UPDATED");

        // Verify durable execution store
        LedgerExecution execution = executionRepository.findByTransferId("INT-TRF-001").orElseThrow();
        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.COMMITTED);
        assertThat(execution.getLedgerTransactionId()).isEqualTo(tx.getId());
        assertThat(execution.getFinancialFingerprint()).isNotEmpty();
    }

    @Test
    @DisplayName("2. Replaying identical successful request does not repeat financial mutations and returns existing outcome")
    void testIdempotentRepeatExecutionReturnsSameOutcomeWithoutDuplicateRecords() {
        LedgerTransferExecutionRequest request = new LedgerTransferExecutionRequest(
                "INT-TRF-REPEAT",
                "INT-ACC-101",
                "INT-ACC-102",
                TransferType.OWN_ACCOUNT,
                new BigDecimal("500.0000"),
                "INR",
                "INT-REF-REPEAT",
                "Repeat Transfer",
                null,
                null
        );

        // First attempt
        LedgerExecutionService.ExecutionResult result1 = executionService.processTransfer(request);
        assertThat(result1.isNew()).isTrue();
        String transactionId = result1.response().getTransactionId();

        // Second attempt (identical retry)
        LedgerExecutionService.ExecutionResult result2 = executionService.processTransfer(request);
        assertThat(result2.isNew()).isFalse();
        assertThat(result2.response().getTransactionId()).isEqualTo(transactionId);
        assertThat(result2.response().getTransferId()).isEqualTo("INT-TRF-REPEAT");
        assertThat(result2.response().getStatus()).isEqualTo("COMMITTED");

        // Verify balances mutated exactly once: 5000 - 500 = 4500, 1000 + 500 = 1500
        LedgerAccount acc1 = accountRepository.findById("INT-ACC-101").orElseThrow();
        LedgerAccount acc2 = accountRepository.findById("INT-ACC-102").orElseThrow();
        assertThat(acc1.getBalance()).isEqualByComparingTo(new BigDecimal("4500.0000"));
        assertThat(acc2.getBalance()).isEqualByComparingTo(new BigDecimal("1500.0000"));

        // Exactly 1 execution record
        assertThat(executionRepository.count()).isEqualTo(1);
        // Exactly 1 transaction record in DB
        assertThat(transactionRepository.count()).isEqualTo(1);
        // Exactly 2 entry rows in DB
        assertThat(entryRepository.count()).isEqualTo(2);
        // Exactly 3 outbox event rows in DB
        assertThat(outboxRepository.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("3 & 4. Insufficient funds rejection is durably stored; leaves balances unchanged and writes no transaction/entries/outbox")
    void testDurableInsufficientFundsRejectionLeavesBalancesUntouched() {
        LedgerTransferExecutionRequest request = new LedgerTransferExecutionRequest(
                "INT-TRF-FAIL",
                "INT-ACC-101",
                "INT-ACC-102",
                TransferType.OWN_ACCOUNT,
                new BigDecimal("99999.0000"), // Exceeds balance of 5000
                "INR",
                "INT-REF-FAIL",
                "Failing Transfer",
                null,
                null
        );

        assertThatThrownBy(() -> executionService.processTransfer(request))
                .isInstanceOf(InsufficientFundsException.class);

        // Verify balances completely untouched
        LedgerAccount acc1 = accountRepository.findById("INT-ACC-101").orElseThrow();
        LedgerAccount acc2 = accountRepository.findById("INT-ACC-102").orElseThrow();
        assertThat(acc1.getBalance()).isEqualByComparingTo(new BigDecimal("5000.0000"));
        assertThat(acc2.getBalance()).isEqualByComparingTo(new BigDecimal("1000.0000"));

        // Verify 0 transactions, 0 entries, 0 outbox events created
        assertThat(transactionRepository.findByTransferId("INT-TRF-FAIL")).isEmpty();
        assertThat(entryRepository.findAll()).isEmpty();
        assertThat(outboxRepository.findAll()).isEmpty();

        // Verify durable REJECTED execution row in ledger_executions
        LedgerExecution execution = executionRepository.findByTransferId("INT-TRF-FAIL").orElseThrow();
        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.REJECTED);
        assertThat(execution.getStatusReasonCode()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(execution.getFailureMessage()).contains("Insufficient balance in source account");
        assertThat(execution.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("5. Replaying identical rejected request returns original rejection outcome and reason")
    void testReplayingRejectedRequestReturnsOriginalRejection() {
        LedgerTransferExecutionRequest request = new LedgerTransferExecutionRequest(
                "INT-TRF-REPLAY-REJ",
                "INT-ACC-101",
                "INT-ACC-102",
                TransferType.OWN_ACCOUNT,
                new BigDecimal("88888.0000"),
                "INR",
                "INT-REF-REJ",
                "Replay Rejection Transfer",
                null,
                null
        );

        // Initial attempt rejects
        assertThatThrownBy(() -> executionService.processTransfer(request))
                .isInstanceOf(InsufficientFundsException.class);

        // Even if we artificially credit the account with 100,000, the replayed request MUST still reject with stored outcome!
        LedgerAccount acc1 = accountRepository.findById("INT-ACC-101").orElseThrow();
        acc1.credit(new BigDecimal("100000.0000"));
        accountRepository.save(acc1);

        // Replay attempt must return stored rejection without re-evaluating!
        assertThatThrownBy(() -> executionService.processTransfer(request))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessageContaining("Insufficient balance in source account");

        // Verify zero transactions or entries created
        assertThat(transactionRepository.findByTransferId("INT-TRF-REPLAY-REJ")).isEmpty();
        assertThat(entryRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("6. Reusing transferId with modified financial parameters returns HTTP 409 Conflict")
    void testReusingTransferIdWithModifiedParametersThrowsConflict() {
        LedgerTransferExecutionRequest initialReq = new LedgerTransferExecutionRequest(
                "INT-TRF-CONFLICT",
                "INT-ACC-101",
                "INT-ACC-102",
                TransferType.OWN_ACCOUNT,
                new BigDecimal("500.0000"),
                "INR",
                "INT-REF-CONF-1",
                "Initial transfer",
                null,
                null
        );

        executionService.processTransfer(initialReq);

        // Modified amount: 1000 instead of 500
        LedgerTransferExecutionRequest conflictingReq = new LedgerTransferExecutionRequest(
                "INT-TRF-CONFLICT",
                "INT-ACC-101",
                "INT-ACC-102",
                TransferType.OWN_ACCOUNT,
                new BigDecimal("1000.0000"),
                "INR",
                "INT-REF-CONF-1",
                "Initial transfer",
                null,
                null
        );

        assertThatThrownBy(() -> executionService.processTransfer(conflictingReq))
                .isInstanceOf(TransferIdConflictException.class)
                .hasMessageContaining("different immutable financial details");

        // Balances must remain at 4500 and 1500 (conflicting request did not execute)
        LedgerAccount acc1 = accountRepository.findById("INT-ACC-101").orElseThrow();
        assertThat(acc1.getBalance()).isEqualByComparingTo(new BigDecimal("4500.0000"));
    }

    @Test
    @DisplayName("7. Concurrent identical requests produce at most one financial mutation and one authoritative outcome")
    void testConcurrentIdenticalRequestsExecuteSafely() throws Exception {
        int threadCount = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        List<Future<LedgerExecutionService.ExecutionResult>> futures = new ArrayList<>();

        LedgerTransferExecutionRequest request = new LedgerTransferExecutionRequest(
                "INT-TRF-CONCURRENT-IDEM",
                "INT-ACC-101",
                "INT-ACC-102",
                TransferType.OWN_ACCOUNT,
                new BigDecimal("1000.0000"),
                "INR",
                "INT-REF-CONC-1",
                "Concurrent transfer",
                null,
                null
        );

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                latch.await();
                return executionService.processTransfer(request);
            }));
        }

        // Release all threads simultaneously
        latch.countDown();

        AtomicInteger newCount = new AtomicInteger(0);
        String commonTxId = null;

        for (Future<LedgerExecutionService.ExecutionResult> future : futures) {
            LedgerExecutionService.ExecutionResult result = future.get(10, TimeUnit.SECONDS);
            assertThat(result.response().getTransferId()).isEqualTo("INT-TRF-CONCURRENT-IDEM");
            assertThat(result.response().getStatus()).isEqualTo("COMMITTED");
            if (result.isNew()) {
                newCount.incrementAndGet();
            }
            if (commonTxId == null) {
                commonTxId = result.response().getTransactionId();
            } else {
                assertThat(result.response().getTransactionId()).isEqualTo(commonTxId);
            }
        }

        executor.shutdown();

        // Exactly one thread committed the new transaction
        assertThat(newCount.get()).isEqualTo(1);

        // Account mutated exactly once: 5000 - 1000 = 4000, 1000 + 1000 = 2000
        LedgerAccount acc1 = accountRepository.findById("INT-ACC-101").orElseThrow();
        LedgerAccount acc2 = accountRepository.findById("INT-ACC-102").orElseThrow();
        assertThat(acc1.getBalance()).isEqualByComparingTo(new BigDecimal("4000.0000"));
        assertThat(acc2.getBalance()).isEqualByComparingTo(new BigDecimal("2000.0000"));

        // Exactly 1 execution, 1 transaction, 2 entries
        assertThat(executionRepository.count()).isEqualTo(1);
        assertThat(transactionRepository.count()).isEqualTo(1);
        assertThat(entryRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("8. Concurrent conflicting requests cannot both execute under the same transferId")
    void testConcurrentConflictingRequestsCannotBothExecute() throws Exception {
        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);

        LedgerTransferExecutionRequest req1 = new LedgerTransferExecutionRequest(
                "INT-TRF-CONC-CONFLICT",
                "INT-ACC-101",
                "INT-ACC-102",
                TransferType.OWN_ACCOUNT,
                new BigDecimal("500.0000"),
                "INR",
                "REF-CONC-A",
                "Transfer A",
                null,
                null
        );

        LedgerTransferExecutionRequest req2 = new LedgerTransferExecutionRequest(
                "INT-TRF-CONC-CONFLICT",
                "INT-ACC-101",
                "INT-ACC-102",
                TransferType.OWN_ACCOUNT,
                new BigDecimal("1500.0000"), // Conflicting amount
                "INR",
                "REF-CONC-B",
                "Transfer B",
                null,
                null
        );

        List<Throwable> caughtExceptions = Collections.synchronizedList(new ArrayList<>());
        List<LedgerExecutionService.ExecutionResult> results = Collections.synchronizedList(new ArrayList<>());

        Callable<Void> task1 = () -> {
            latch.await();
            try {
                results.add(executionService.processTransfer(req1));
            } catch (Throwable t) {
                caughtExceptions.add(t);
            }
            return null;
        };

        Callable<Void> task2 = () -> {
            latch.await();
            try {
                results.add(executionService.processTransfer(req2));
            } catch (Throwable t) {
                caughtExceptions.add(t);
            }
            return null;
        };

        Future<Void> f1 = executor.submit(task1);
        Future<Void> f2 = executor.submit(task2);

        latch.countDown();
        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Exactly one must succeed and the other must fail with TransferIdConflictException
        assertThat(results).hasSize(1);
        assertThat(caughtExceptions).hasSize(1);
        assertThat(caughtExceptions.get(0)).isInstanceOf(TransferIdConflictException.class);

        // Exactly 1 execution record in DB
        assertThat(executionRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("9. Lost execution response can be recovered through authoritative outcome lookup GET /internal/ledger/transfers/{transferId}")
    void testLostResponseRecoveredThroughAuthoritativeOutcomeLookup() {
        LedgerTransferExecutionRequest request = new LedgerTransferExecutionRequest(
                "INT-TRF-LOST-RES",
                "INT-ACC-101",
                "INT-ACC-102",
                TransferType.OWN_ACCOUNT,
                new BigDecimal("700.0000"),
                "INR",
                "INT-REF-LOST",
                "Lost Response Transfer",
                null,
                null
        );

        LedgerExecutionService.ExecutionResult executed = executionService.processTransfer(request);

        // Simulate lost HTTP response: caller performs reconciliation lookup
        LedgerTransactionOutcomeResponse lookup = executionService.getOutcome("INT-TRF-LOST-RES");

        assertThat(lookup.getTransferId()).isEqualTo("INT-TRF-LOST-RES");
        assertThat(lookup.getTransactionId()).isEqualTo(executed.response().getTransactionId());
        assertThat(lookup.getStatus()).isEqualTo("COMMITTED");
        assertThat(lookup.getAmount()).isEqualByComparingTo(new BigDecimal("700.0000"));
        assertThat(lookup.getSourceAccountId()).isEqualTo("INT-ACC-101");
        assertThat(lookup.getDestinationAccountId()).isEqualTo("INT-ACC-102");
    }

    @Test
    @DisplayName("10. Authoritative lookup recovers durable terminal rejection")
    void testRejectedOutcomeRecoveredThroughAuthoritativeOutcomeLookup() {
        LedgerTransferExecutionRequest request = new LedgerTransferExecutionRequest(
                "INT-TRF-LOST-REJ",
                "INT-ACC-101",
                "INT-ACC-102",
                TransferType.OWN_ACCOUNT,
                new BigDecimal("999999.0000"),
                "INR",
                "INT-REF-LOST-REJ",
                "Lost Rejection Transfer",
                null,
                null
        );

        assertThatThrownBy(() -> executionService.processTransfer(request))
                .isInstanceOf(InsufficientFundsException.class);

        // Simulate reconciliation lookup of the rejected transfer
        LedgerTransactionOutcomeResponse lookup = executionService.getOutcome("INT-TRF-LOST-REJ");

        assertThat(lookup.getTransferId()).isEqualTo("INT-TRF-LOST-REJ");
        assertThat(lookup.getStatus()).isEqualTo("REJECTED");
        assertThat(lookup.getStatusReasonCode()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(lookup.getFailureMessage()).contains("Insufficient balance in source account");
    }

    @Test
    @DisplayName("11. Non-existent transferId lookup returns TransactionNotFoundException (HTTP 404)")
    void testLookupNonExistentTransferThrowsTransactionNotFoundException() {
        assertThatThrownBy(() -> executionService.getOutcome("NON-EXISTENT-TRF"))
                .isInstanceOf(TransactionNotFoundException.class)
                .hasMessageContaining("No execution or transaction found");
    }

    @Test
    @DisplayName("12. Unique constraint on ledger_transactions uk_ledger_tx_transfer_id is preserved")
    void testUniqueTransferIdConstraintInDatabase() {
        LedgerTransaction tx1 = new LedgerTransaction(
                "TX-DUP-1",
                "TRF-UNIQUE-CHECK",
                "INT-ACC-101",
                "INT-ACC-102",
                TransferType.OWN_ACCOUNT,
                new BigDecimal("100.0000"),
                "INR",
                "SUCCESS",
                "REF-DUP-1",
                null, null, null,
                Instant.now()
        );
        transactionRepository.save(tx1);

        LedgerTransaction tx2 = new LedgerTransaction(
                "TX-DUP-2",
                "TRF-UNIQUE-CHECK", // Same transfer_id
                "INT-ACC-101",
                "INT-ACC-102",
                TransferType.OWN_ACCOUNT,
                new BigDecimal("200.0000"),
                "INR",
                "SUCCESS",
                "REF-DUP-2",
                null, null, null,
                Instant.now()
        );

        assertThatThrownBy(() -> transactionRepository.saveAndFlush(tx2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
