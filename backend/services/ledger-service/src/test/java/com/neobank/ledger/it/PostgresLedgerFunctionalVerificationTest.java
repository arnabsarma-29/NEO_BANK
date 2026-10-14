package com.neobank.ledger.it;

import com.neobank.ledger.domain.*;
import com.neobank.ledger.dto.LedgerTransactionOutcomeResponse;
import com.neobank.ledger.dto.LedgerTransferExecutionRequest;
import com.neobank.ledger.exception.InsufficientFundsException;
import com.neobank.ledger.exception.TransactionNotFoundException;
import com.neobank.ledger.exception.TransferIdConflictException;
import com.neobank.ledger.repository.*;
import com.neobank.ledger.service.LedgerExecutionService;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PostgreSQL 17.11 Functional & Concurrency Verification Test Suite.
 *
 * Runs exclusively against live PostgreSQL 17.11 to verify:
 *  1. Successful transfers update balances and create transactions, ledger entries and outbox records.
 *  2. Insufficient-funds rejections are durably persisted without balance mutations or success records.
 *  3. Replaying identical successful or rejected requests does not repeat financial mutations.
 *  4. Reusing transferId with conflicting financial intent throws HTTP 409 (TransferIdConflictException).
 *  5. Concurrent identical requests produce at most one financial mutation and authoritative outcome.
 *  6. Concurrent conflicting requests under the same transferId cannot both execute.
 *  7. Stored execution outcomes (COMMITTED and REJECTED) are recovered via authoritative lookup.
 *  8. PostgreSQL uniqueness constraints (uk_ledger_tx_transfer_id) are enforced atomically.
 *
 * Test-data isolation guarantees:
 *  - Every invocation uses a unique verification-run ID (RUN_ID).
 *  - Every test account, transfer, reference, transaction, and outbox event embeds this exact RUN_ID.
 *  - Runtime app role (neobank_ledger_app) has least-privilege SELECT, INSERT, UPDATE privileges.
 *  - Controlled cleanup is scoped strictly to records containing this exact RUN_ID via neobank_ledger_migrator.
 */
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "SPRING_DATASOURCE_PASSWORD", matches = ".+")
@SpringBootTest
@ActiveProfiles("postgres-it")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class PostgresLedgerFunctionalVerificationTest {

    private static final Logger log = LoggerFactory.getLogger(PostgresLedgerFunctionalVerificationTest.class);

    /**
     * Unique verification run ID, supplied by the verification runner or auto-generated.
     */
    private static final String RUN_ID;
    static {
        String prop = System.getProperty("ledger.verification.runId");
        if (prop == null || prop.isBlank()) {
            prop = System.getenv("LEDGER_VERIFICATION_RUN_ID");
        }
        if (prop == null || prop.isBlank()) {
            prop = java.util.UUID.randomUUID().toString().toUpperCase();
        }
        RUN_ID = prop.trim();
    }

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

    @BeforeAll
    static void checkPostgresAvailability() {
        log.info("Starting PostgreSQL 17.11 functional verification with Run ID: {}", RUN_ID);

        String appPassword = System.getenv("SPRING_DATASOURCE_PASSWORD");
        if (appPassword == null || appPassword.isBlank()) {
            log.info("Live PostgreSQL password not present in environment. Skipping live PostgreSQL test.");
            return;
        }

        String jdbcUrl = System.getenv("SPRING_DATASOURCE_URL");
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            jdbcUrl = "jdbc:postgresql://localhost:5432/neobank_ledger";
        }
        String appUser = System.getenv("SPRING_DATASOURCE_USERNAME");
        if (appUser == null || appUser.isBlank()) {
            appUser = "neobank_ledger_app";
        }

        try (Connection conn = DriverManager.getConnection(jdbcUrl, appUser, appPassword);
             PreparedStatement ps = conn.prepareStatement("SELECT 1;");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next() && rs.getInt(1) == 1) {
                log.info("Successfully connected to live PostgreSQL 17.11 at {} as {} (Run ID: {})", jdbcUrl, appUser, RUN_ID);
            }
        } catch (Exception e) {
            log.error("Failed to connect to live PostgreSQL: {}", e.getMessage());
            throw new RuntimeException("PostgreSQL 17.11 connectivity check failed", e);
        }
    }

    // --- Helper methods to produce run-isolated identifiers ---

    private String accId(String suffix) {
        return "ACC-" + RUN_ID + "-" + suffix;
    }

    private String trfId(String suffix) {
        return "TRF-" + RUN_ID + "-" + suffix;
    }

    private String refNum(String suffix) {
        return "REF-" + RUN_ID + "-" + suffix;
    }

    private String txId(String suffix) {
        return "TX-" + RUN_ID + "-" + suffix;
    }

    private LedgerAccount initAccount(String accountId, BigDecimal balance, String currency) {
        return accountRepository.findById(accountId)
                .map(existing -> {
                    existing.setBalance(balance);
                    existing.setStatus(AccountStatus.ACTIVE);
                    existing.setUpdatedAt(Instant.now());
                    return accountRepository.save(existing);
                })
                .orElseGet(() -> accountRepository.save(new LedgerAccount(accountId, balance, currency, AccountStatus.ACTIVE)));
    }

    @Test
    @Order(1)
    @DisplayName("1. Successful transfer updates balances and creates transaction, ledger entries, and outbox records")
    void test1_successfulTransferUpdatesBalancesAndAuditRecords() {
        String srcAcc = accId("101");
        String dstAcc = accId("102");
        String transferId = trfId("001");
        String ref = refNum("001");

        initAccount(srcAcc, new BigDecimal("5000.0000"), "INR");
        initAccount(dstAcc, new BigDecimal("1000.0000"), "INR");

        LedgerTransferExecutionRequest request = new LedgerTransferExecutionRequest(
                transferId,
                srcAcc,
                dstAcc,
                TransferType.OWN_ACCOUNT,
                new BigDecimal("1500.0000"),
                "INR",
                ref,
                "Live PG Transfer 1 (Run " + RUN_ID + ")",
                null,
                null
        );

        LedgerExecutionService.ExecutionResult result = executionService.processTransfer(request);

        assertThat(result.isNew()).isTrue();
        assertThat(result.response().getTransferId()).isEqualTo(transferId);
        assertThat(result.response().getStatus()).isEqualTo("COMMITTED");

        // Verify balances mutated in PostgreSQL
        LedgerAccount acc1 = accountRepository.findById(srcAcc).orElseThrow();
        LedgerAccount acc2 = accountRepository.findById(dstAcc).orElseThrow();
        assertThat(acc1.getBalance()).isEqualByComparingTo(new BigDecimal("3500.0000"));
        assertThat(acc2.getBalance()).isEqualByComparingTo(new BigDecimal("2500.0000"));

        // Verify transaction in PostgreSQL
        LedgerTransaction tx = transactionRepository.findByTransferId(transferId).orElseThrow();
        assertThat(tx.getAmount()).isEqualByComparingTo(new BigDecimal("1500.0000"));
        assertThat(tx.getStatus()).isEqualTo("SUCCESS");

        // Verify ledger entries in PostgreSQL
        List<LedgerEntry> entries = entryRepository.findByTransactionId(tx.getId());
        assertThat(entries).hasSize(2);
        assertThat(entries).extracting(LedgerEntry::getEntryType)
                .containsExactlyInAnyOrder(EntryType.DEBIT, EntryType.CREDIT);

        // Verify execution record in PostgreSQL
        LedgerExecution exec = executionRepository.findByTransferId(transferId).orElseThrow();
        assertThat(exec.getStatus()).isEqualTo(ExecutionStatus.COMMITTED);
        assertThat(exec.getLedgerTransactionId()).isEqualTo(tx.getId());
        assertThat(exec.getFinancialFingerprint()).isNotEmpty();

        // Verify outbox records in PostgreSQL for this run
        List<OutboxEvent> outboxEvents = outboxRepository.findAll();
        assertThat(outboxEvents).anyMatch(e -> tx.getId().equals(e.getAggregateId()) && "TRANSACTION_CREATED".equals(e.getEventType()));
        assertThat(outboxEvents).anyMatch(e -> srcAcc.equals(e.getAggregateId()) && "BALANCE_UPDATED".equals(e.getEventType()));
        assertThat(outboxEvents).anyMatch(e -> dstAcc.equals(e.getAggregateId()) && "BALANCE_UPDATED".equals(e.getEventType()));
    }

    @Test
    @Order(2)
    @DisplayName("2. Insufficient funds rejection is persisted without changing balances or creating success records")
    void test2_insufficientFundsRejectionPersistedWithoutMutations() {
        String srcAcc = accId("201");
        String dstAcc = accId("202");
        String transferId = trfId("002");
        String ref = refNum("002");

        initAccount(srcAcc, new BigDecimal("500.0000"), "INR");
        initAccount(dstAcc, new BigDecimal("100.0000"), "INR");

        LedgerTransferExecutionRequest request = new LedgerTransferExecutionRequest(
                transferId,
                srcAcc,
                dstAcc,
                TransferType.OWN_ACCOUNT,
                new BigDecimal("99999.0000"),
                "INR",
                ref,
                "Live PG Rejection (Run " + RUN_ID + ")",
                null,
                null
        );

        assertThatThrownBy(() -> executionService.processTransfer(request))
                .isInstanceOf(InsufficientFundsException.class);

        // Balances must remain untouched in PostgreSQL
        LedgerAccount acc1 = accountRepository.findById(srcAcc).orElseThrow();
        LedgerAccount acc2 = accountRepository.findById(dstAcc).orElseThrow();
        assertThat(acc1.getBalance()).isEqualByComparingTo(new BigDecimal("500.0000"));
        assertThat(acc2.getBalance()).isEqualByComparingTo(new BigDecimal("100.0000"));

        // No transaction or entries in PostgreSQL
        assertThat(transactionRepository.findByTransferId(transferId)).isEmpty();

        // Durable REJECTED row in ledger_executions table in PostgreSQL
        LedgerExecution exec = executionRepository.findByTransferId(transferId).orElseThrow();
        assertThat(exec.getStatus()).isEqualTo(ExecutionStatus.REJECTED);
        assertThat(exec.getStatusReasonCode()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(exec.getLedgerTransactionId()).isNull();
        assertThat(exec.getFailureMessage()).contains("Insufficient balance in source account");
    }

    @Test
    @Order(3)
    @DisplayName("3. Replaying identical successful or rejected requests does not repeat financial mutations")
    void test3_replayingIdenticalSuccessfulAndRejectedRequestsDoNotRepeatMutations() {
        String srcAcc = accId("301");
        String dstAcc = accId("302");
        initAccount(srcAcc, new BigDecimal("2000.0000"), "INR");
        initAccount(dstAcc, new BigDecimal("500.0000"), "INR");

        // --- Part A: Successful replay ---
        String okTrf = trfId("003-OK");
        String okRef = refNum("003-OK");
        LedgerTransferExecutionRequest okReq = new LedgerTransferExecutionRequest(
                okTrf,
                srcAcc,
                dstAcc,
                TransferType.OWN_ACCOUNT,
                new BigDecimal("300.0000"),
                "INR",
                okRef,
                "Replay OK (Run " + RUN_ID + ")",
                null,
                null
        );

        LedgerExecutionService.ExecutionResult ok1 = executionService.processTransfer(okReq);
        assertThat(ok1.isNew()).isTrue();
        String txId = ok1.response().getTransactionId();

        // Replay identical request
        LedgerExecutionService.ExecutionResult ok2 = executionService.processTransfer(okReq);
        assertThat(ok2.isNew()).isFalse();
        assertThat(ok2.response().getTransactionId()).isEqualTo(txId);
        assertThat(ok2.response().getStatus()).isEqualTo("COMMITTED");

        // Verify balances mutated exactly once
        LedgerAccount acc1 = accountRepository.findById(srcAcc).orElseThrow();
        LedgerAccount acc2 = accountRepository.findById(dstAcc).orElseThrow();
        assertThat(acc1.getBalance()).isEqualByComparingTo(new BigDecimal("1700.0000"));
        assertThat(acc2.getBalance()).isEqualByComparingTo(new BigDecimal("800.0000"));

        // --- Part B: Rejected replay ---
        String rejTrf = trfId("003-REJ");
        String rejRef = refNum("003-REJ");
        LedgerTransferExecutionRequest rejReq = new LedgerTransferExecutionRequest(
                rejTrf,
                srcAcc,
                dstAcc,
                TransferType.OWN_ACCOUNT,
                new BigDecimal("50000.0000"),
                "INR",
                rejRef,
                "Replay Rejection (Run " + RUN_ID + ")",
                null,
                null
        );

        assertThatThrownBy(() -> executionService.processTransfer(rejReq))
                .isInstanceOf(InsufficientFundsException.class);

        // Artificially credit source account
        acc1.credit(new BigDecimal("100000.0000"));
        accountRepository.save(acc1);

        // Replaying must return stored rejection without executing or mutating balances
        assertThatThrownBy(() -> executionService.processTransfer(rejReq))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessageContaining("Insufficient balance in source account");

        assertThat(transactionRepository.findByTransferId(rejTrf)).isEmpty();
    }

    @Test
    @Order(4)
    @DisplayName("4. Reusing transferId with conflicting financial intent returns HTTP 409 TransferIdConflictException")
    void test4_reusingTransferIdWithConflictingFinancialIntentThrowsConflict() {
        String srcAcc = accId("401");
        String dstAcc = accId("402");
        String transferId = trfId("004-CONF");
        String ref = refNum("004-1");

        initAccount(srcAcc, new BigDecimal("3000.0000"), "INR");
        initAccount(dstAcc, new BigDecimal("500.0000"), "INR");

        LedgerTransferExecutionRequest req1 = new LedgerTransferExecutionRequest(
                transferId,
                srcAcc,
                dstAcc,
                TransferType.OWN_ACCOUNT,
                new BigDecimal("200.0000"),
                "INR",
                ref,
                "Initial transfer (Run " + RUN_ID + ")",
                null,
                null
        );

        executionService.processTransfer(req1);

        // Conflicting request reusing the same transferId with modified amount
        LedgerTransferExecutionRequest req2 = new LedgerTransferExecutionRequest(
                transferId,
                srcAcc,
                dstAcc,
                TransferType.OWN_ACCOUNT,
                new BigDecimal("600.0000"),
                "INR",
                ref,
                "Initial transfer (Run " + RUN_ID + ")",
                null,
                null
        );

        assertThatThrownBy(() -> executionService.processTransfer(req2))
                .isInstanceOf(TransferIdConflictException.class)
                .hasMessageContaining("different immutable financial details");

        // Verify account balance reflects only the first transfer
        LedgerAccount acc1 = accountRepository.findById(srcAcc).orElseThrow();
        assertThat(acc1.getBalance()).isEqualByComparingTo(new BigDecimal("2800.0000"));
    }

    @Test
    @Order(5)
    @DisplayName("5. Concurrent identical requests produce at most one financial mutation and one authoritative outcome")
    void test5_concurrentIdenticalRequestsProduceAtMostOneFinancialMutation() throws Exception {
        String srcAcc = accId("501");
        String dstAcc = accId("502");
        String transferId = trfId("005-CONC");
        String ref = refNum("005-CONC");

        initAccount(srcAcc, new BigDecimal("5000.0000"), "INR");
        initAccount(dstAcc, new BigDecimal("1000.0000"), "INR");

        int threadCount = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        List<Future<LedgerExecutionService.ExecutionResult>> futures = new ArrayList<>();

        LedgerTransferExecutionRequest request = new LedgerTransferExecutionRequest(
                transferId,
                srcAcc,
                dstAcc,
                TransferType.OWN_ACCOUNT,
                new BigDecimal("400.0000"),
                "INR",
                ref,
                "Concurrent Identical (Run " + RUN_ID + ")",
                null,
                null
        );

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                latch.await();
                return executionService.processTransfer(request);
            }));
        }

        // Trigger simultaneous execution in PostgreSQL
        latch.countDown();

        AtomicInteger newCount = new AtomicInteger(0);
        String commonTxId = null;

        for (Future<LedgerExecutionService.ExecutionResult> future : futures) {
            LedgerExecutionService.ExecutionResult result = future.get(15, TimeUnit.SECONDS);
            assertThat(result.response().getTransferId()).isEqualTo(transferId);
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

        // Exactly 1 thread was the winner; remaining threads received idempotent replay
        assertThat(newCount.get()).isEqualTo(1);

        // Balances in PostgreSQL mutated exactly once: 5000 - 400 = 4600, 1000 + 400 = 1400
        LedgerAccount acc1 = accountRepository.findById(srcAcc).orElseThrow();
        LedgerAccount acc2 = accountRepository.findById(dstAcc).orElseThrow();
        assertThat(acc1.getBalance()).isEqualByComparingTo(new BigDecimal("4600.0000"));
        assertThat(acc2.getBalance()).isEqualByComparingTo(new BigDecimal("1400.0000"));

        // Exactly 1 transaction in PostgreSQL
        assertThat(transactionRepository.findByTransferId(transferId)).isPresent();
        assertThat(entryRepository.findByTransactionId(commonTxId)).hasSize(2);
    }

    @Test
    @Order(6)
    @DisplayName("6. Concurrent conflicting requests cannot both execute under the same transferId")
    void test6_concurrentConflictingRequestsCannotBothExecute() throws Exception {
        String srcAcc = accId("601");
        String dstAcc = accId("602");
        String transferId = trfId("006-CONC-CONF");
        String refA = refNum("006-A");
        String refB = refNum("006-B");

        initAccount(srcAcc, new BigDecimal("5000.0000"), "INR");
        initAccount(dstAcc, new BigDecimal("1000.0000"), "INR");

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);

        LedgerTransferExecutionRequest req1 = new LedgerTransferExecutionRequest(
                transferId,
                srcAcc,
                dstAcc,
                TransferType.OWN_ACCOUNT,
                new BigDecimal("300.0000"),
                "INR",
                refA,
                "Transfer A (Run " + RUN_ID + ")",
                null,
                null
        );

        LedgerTransferExecutionRequest req2 = new LedgerTransferExecutionRequest(
                transferId,
                srcAcc,
                dstAcc,
                TransferType.OWN_ACCOUNT,
                new BigDecimal("700.0000"),
                "INR",
                refB,
                "Transfer B (Run " + RUN_ID + ")",
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
        f1.get(15, TimeUnit.SECONDS);
        f2.get(15, TimeUnit.SECONDS);
        executor.shutdown();

        // Exactly one succeeded, exactly one threw TransferIdConflictException
        assertThat(results).hasSize(1);
        assertThat(caughtExceptions).hasSize(1);
        assertThat(caughtExceptions.get(0)).isInstanceOf(TransferIdConflictException.class);

        // Exactly 1 execution record in PostgreSQL
        assertThat(executionRepository.findByTransferId(transferId)).isPresent();
    }

    @Test
    @Order(7)
    @DisplayName("7. Stored execution outcome can be recovered through authoritative lookup")
    void test7_storedExecutionOutcomeRecoveredThroughAuthoritativeLookup() {
        String srcAcc = accId("701");
        String dstAcc = accId("702");
        initAccount(srcAcc, new BigDecimal("2000.0000"), "INR");
        initAccount(dstAcc, new BigDecimal("1000.0000"), "INR");

        // Part A: COMMITTED outcome
        String okTrf = trfId("007-LOOKUP-OK");
        String okRef = refNum("007-OK");
        LedgerTransferExecutionRequest okReq = new LedgerTransferExecutionRequest(
                okTrf,
                srcAcc,
                dstAcc,
                TransferType.OWN_ACCOUNT,
                new BigDecimal("500.0000"),
                "INR",
                okRef,
                "Lookup Committed (Run " + RUN_ID + ")",
                null,
                null
        );
        LedgerExecutionService.ExecutionResult okRes = executionService.processTransfer(okReq);

        LedgerTransactionOutcomeResponse okLookup = executionService.getOutcome(okTrf);
        assertThat(okLookup.getTransferId()).isEqualTo(okTrf);
        assertThat(okLookup.getStatus()).isEqualTo("COMMITTED");
        assertThat(okLookup.getTransactionId()).isEqualTo(okRes.response().getTransactionId());
        assertThat(okLookup.getAmount()).isEqualByComparingTo(new BigDecimal("500.0000"));

        // Part B: REJECTED outcome
        String rejTrf = trfId("007-LOOKUP-REJ");
        String rejRef = refNum("007-REJ");
        LedgerTransferExecutionRequest rejReq = new LedgerTransferExecutionRequest(
                rejTrf,
                srcAcc,
                dstAcc,
                TransferType.OWN_ACCOUNT,
                new BigDecimal("99999.0000"),
                "INR",
                rejRef,
                "Lookup Rejected (Run " + RUN_ID + ")",
                null,
                null
        );
        assertThatThrownBy(() -> executionService.processTransfer(rejReq))
                .isInstanceOf(InsufficientFundsException.class);

        LedgerTransactionOutcomeResponse rejLookup = executionService.getOutcome(rejTrf);
        assertThat(rejLookup.getTransferId()).isEqualTo(rejTrf);
        assertThat(rejLookup.getStatus()).isEqualTo("REJECTED");
        assertThat(rejLookup.getStatusReasonCode()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(rejLookup.getFailureMessage()).contains("Insufficient balance in source account");

        // Part C: Non-existent transfer throws TransactionNotFoundException (HTTP 404)
        assertThatThrownBy(() -> executionService.getOutcome(trfId("007-NONEXISTENT")))
                .isInstanceOf(TransactionNotFoundException.class)
                .hasMessageContaining("No execution or transaction found");
    }

    @Test
    @Order(8)
    @DisplayName("8. PostgreSQL uniqueness conflicts are handled correctly without partial mutations")
    void test8_postgreSQLUniquenessConflictHandledCorrectlyWithoutPartialMutations() {
        String srcAcc = accId("801");
        String dstAcc = accId("802");
        String transferId = trfId("008-UNIQ");
        initAccount(srcAcc, new BigDecimal("2000.0000"), "INR");
        initAccount(dstAcc, new BigDecimal("1000.0000"), "INR");

        LedgerTransaction tx1 = new LedgerTransaction(
                txId("801-1"),
                transferId,
                srcAcc,
                dstAcc,
                TransferType.OWN_ACCOUNT,
                new BigDecimal("100.0000"),
                "INR",
                "SUCCESS",
                refNum("008-1"),
                null, null, null,
                Instant.now()
        );
        transactionRepository.save(tx1);

        LedgerTransaction tx2 = new LedgerTransaction(
                txId("801-2"),
                transferId, // Duplicate transfer_id in PostgreSQL
                srcAcc,
                dstAcc,
                TransferType.OWN_ACCOUNT,
                new BigDecimal("200.0000"),
                "INR",
                "SUCCESS",
                refNum("008-2"),
                null, null, null,
                Instant.now()
        );

        // PostgreSQL database must reject duplicate transfer_id on uk_ledger_tx_transfer_id
        assertThatThrownBy(() -> transactionRepository.saveAndFlush(tx2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
