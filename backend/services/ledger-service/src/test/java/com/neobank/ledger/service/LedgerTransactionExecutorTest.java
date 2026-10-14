package com.neobank.ledger.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.neobank.ledger.domain.*;
import com.neobank.ledger.dto.LedgerTransferExecutionRequest;
import com.neobank.ledger.exception.AccountNotFoundException;
import com.neobank.ledger.exception.InsufficientFundsException;
import com.neobank.ledger.exception.TransferNotExecutableException;
import com.neobank.ledger.repository.LedgerAccountRepository;
import com.neobank.ledger.repository.LedgerEntryRepository;
import com.neobank.ledger.repository.LedgerExecutionRepository;
import com.neobank.ledger.repository.LedgerTransactionRepository;
import com.neobank.ledger.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LedgerTransactionExecutorTest {

    @Mock
    private LedgerAccountRepository accountRepository;

    @Mock
    private LedgerTransactionRepository transactionRepository;

    @Mock
    private LedgerEntryRepository entryRepository;

    @Mock
    private OutboxEventRepository outboxRepository;

    @Mock
    private LedgerExecutionRepository executionRepository;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private LedgerTransactionExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new LedgerTransactionExecutor(
                accountRepository,
                transactionRepository,
                entryRepository,
                outboxRepository,
                executionRepository,
                objectMapper
        );
        lenient().when(executionRepository.saveAndFlush(any(LedgerExecution.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("Should execute OWN_ACCOUNT transfer: debit source, credit destination, 2 entries, 3 outbox events, COMMITTED execution")
    void shouldExecuteOwnAccountTransfer() {
        String sourceId = "ACC-001";
        String destId = "ACC-002";
        LedgerAccount sourceAcc = new LedgerAccount(sourceId, new BigDecimal("1000.0000"), "INR", AccountStatus.ACTIVE);
        LedgerAccount destAcc = new LedgerAccount(destId, new BigDecimal("500.0000"), "INR", AccountStatus.ACTIVE);

        when(accountRepository.findWithLockByAccountId(sourceId)).thenReturn(Optional.of(sourceAcc));
        when(accountRepository.findWithLockByAccountId(destId)).thenReturn(Optional.of(destAcc));
        when(transactionRepository.saveAndFlush(any(LedgerTransaction.class))).thenAnswer(invocation -> invocation.getArgument(0));

        LedgerTransferExecutionRequest req = new LedgerTransferExecutionRequest(
                "TRF-1001",
                sourceId,
                destId,
                TransferType.OWN_ACCOUNT,
                new BigDecimal("200.0000"),
                "INR",
                "REF-001",
                "Self transfer",
                "My Savings",
                null
        );

        ExecutionOutcome outcome = executor.execute(req, "test-fingerprint");

        assertThat(outcome).isInstanceOf(ExecutionOutcome.Committed.class);
        ExecutionOutcome.Committed committed = (ExecutionOutcome.Committed) outcome;
        assertThat(committed.transaction().getTransferId()).isEqualTo("TRF-1001");
        assertThat(committed.transaction().getStatus()).isEqualTo("SUCCESS");
        assertThat(committed.execution().getStatus()).isEqualTo(ExecutionStatus.COMMITTED);
        assertThat(committed.execution().getLedgerTransactionId()).isNotNull();
        assertThat(sourceAcc.getBalance()).isEqualByComparingTo(new BigDecimal("800.0000"));
        assertThat(destAcc.getBalance()).isEqualByComparingTo(new BigDecimal("700.0000"));

        // 2 entries (debit + credit)
        verify(entryRepository, times(2)).save(any(LedgerEntry.class));

        // 3 outbox events (1 transaction + 2 balance events)
        verify(outboxRepository, times(3)).save(any(OutboxEvent.class));
    }

    @Test
    @DisplayName("Should execute external OTHER_BANK transfer: debit source only, 1 entry, 2 outbox events, COMMITTED execution")
    void shouldExecuteExternalTransfer() {
        String sourceId = "ACC-001";
        LedgerAccount sourceAcc = new LedgerAccount(sourceId, new BigDecimal("1000.0000"), "INR", AccountStatus.ACTIVE);

        when(accountRepository.findWithLockByAccountId(sourceId)).thenReturn(Optional.of(sourceAcc));
        when(transactionRepository.saveAndFlush(any(LedgerTransaction.class))).thenAnswer(invocation -> invocation.getArgument(0));

        LedgerTransferExecutionRequest req = new LedgerTransferExecutionRequest(
                "TRF-1002",
                sourceId,
                null,
                TransferType.OTHER_BANK,
                new BigDecimal("300.0000"),
                "INR",
                "REF-002",
                "Vendor payment",
                "Jane Doe",
                "HDFC0001234/9876543210"
        );

        ExecutionOutcome outcome = executor.execute(req, "test-fingerprint");

        assertThat(outcome).isInstanceOf(ExecutionOutcome.Committed.class);
        ExecutionOutcome.Committed committed = (ExecutionOutcome.Committed) outcome;
        assertThat(committed.transaction().getTransferId()).isEqualTo("TRF-1002");
        assertThat(committed.execution().getStatus()).isEqualTo(ExecutionStatus.COMMITTED);
        assertThat(sourceAcc.getBalance()).isEqualByComparingTo(new BigDecimal("700.0000"));

        // 1 entry (debit only)
        verify(entryRepository, times(1)).save(any(LedgerEntry.class));

        // 2 outbox events (1 transaction + 1 balance event)
        verify(outboxRepository, times(2)).save(any(OutboxEvent.class));
    }

    @Test
    @DisplayName("Should record REJECTED execution with INSUFFICIENT_FUNDS and not mutate balances or write financial records")
    void shouldThrowInsufficientFundsException() {
        String sourceId = "ACC-001";
        String destId = "ACC-002";
        LedgerAccount sourceAcc = new LedgerAccount(sourceId, new BigDecimal("50.0000"), "INR", AccountStatus.ACTIVE);
        LedgerAccount destAcc = new LedgerAccount(destId, new BigDecimal("500.0000"), "INR", AccountStatus.ACTIVE);

        when(accountRepository.findWithLockByAccountId(sourceId)).thenReturn(Optional.of(sourceAcc));
        when(accountRepository.findWithLockByAccountId(destId)).thenReturn(Optional.of(destAcc));

        LedgerTransferExecutionRequest req = new LedgerTransferExecutionRequest(
                "TRF-1003",
                sourceId,
                destId,
                TransferType.OWN_ACCOUNT,
                new BigDecimal("100.0000"),
                "INR",
                "REF-003",
                "Transfer",
                null,
                null
        );

        ExecutionOutcome outcome = executor.execute(req, "test-fingerprint");

        assertThat(outcome).isInstanceOf(ExecutionOutcome.Rejected.class);
        ExecutionOutcome.Rejected rejected = (ExecutionOutcome.Rejected) outcome;
        assertThat(rejected.execution().getStatus()).isEqualTo(ExecutionStatus.REJECTED);
        assertThat(rejected.execution().getStatusReasonCode()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(rejected.exception()).isInstanceOf(InsufficientFundsException.class);

        assertThat(sourceAcc.getBalance()).isEqualByComparingTo(new BigDecimal("50.0000"));
        verify(transactionRepository, never()).saveAndFlush(any());
        verify(entryRepository, never()).save(any());
        verify(outboxRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should record REJECTED execution with ACCOUNT_NOT_FOUND when account does not exist")
    void shouldThrowAccountNotFoundException() {
        when(accountRepository.findWithLockByAccountId("MISSING-ACC")).thenReturn(Optional.empty());

        LedgerTransferExecutionRequest req = new LedgerTransferExecutionRequest(
                "TRF-1004",
                "MISSING-ACC",
                null,
                TransferType.MOCK_UPI,
                new BigDecimal("50.0000"),
                "INR",
                "REF-004",
                "UPI",
                "User",
                "user@mock"
        );

        ExecutionOutcome outcome = executor.execute(req, "test-fingerprint");

        assertThat(outcome).isInstanceOf(ExecutionOutcome.Rejected.class);
        ExecutionOutcome.Rejected rejected = (ExecutionOutcome.Rejected) outcome;
        assertThat(rejected.execution().getStatus()).isEqualTo(ExecutionStatus.REJECTED);
        assertThat(rejected.execution().getStatusReasonCode()).isEqualTo("ACCOUNT_NOT_FOUND");
        assertThat(rejected.exception()).isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    @DisplayName("Should record REJECTED execution with ACCOUNT_NOT_ACTIVE when account is suspended")
    void shouldThrowTransferNotExecutableWhenAccountSuspended() {
        LedgerAccount suspended = new LedgerAccount("SUS-ACC", new BigDecimal("1000.0000"), "INR", AccountStatus.SUSPENDED);
        when(accountRepository.findWithLockByAccountId("SUS-ACC")).thenReturn(Optional.of(suspended));

        LedgerTransferExecutionRequest req = new LedgerTransferExecutionRequest(
                "TRF-1005",
                "SUS-ACC",
                null,
                TransferType.MOCK_UPI,
                new BigDecimal("50.0000"),
                "INR",
                "REF-005",
                "UPI",
                "User",
                "user@mock"
        );

        ExecutionOutcome outcome = executor.execute(req, "test-fingerprint");

        assertThat(outcome).isInstanceOf(ExecutionOutcome.Rejected.class);
        ExecutionOutcome.Rejected rejected = (ExecutionOutcome.Rejected) outcome;
        assertThat(rejected.execution().getStatus()).isEqualTo(ExecutionStatus.REJECTED);
        assertThat(rejected.execution().getStatusReasonCode()).isEqualTo("ACCOUNT_NOT_ACTIVE");
        assertThat(rejected.exception()).isInstanceOf(TransferNotExecutableException.class);
    }

    @Test
    @DisplayName("Should record REJECTED execution with CURRENCY_MISMATCH on currency mismatch")
    void shouldThrowTransferNotExecutableOnCurrencyMismatch() {
        LedgerAccount usdAccount = new LedgerAccount("USD-ACC", new BigDecimal("1000.0000"), "USD", AccountStatus.ACTIVE);
        when(accountRepository.findWithLockByAccountId("USD-ACC")).thenReturn(Optional.of(usdAccount));

        LedgerTransferExecutionRequest req = new LedgerTransferExecutionRequest(
                "TRF-1006",
                "USD-ACC",
                null,
                TransferType.OTHER_BANK,
                new BigDecimal("50.0000"),
                "INR", // Mismatched currency
                "REF-006",
                "Remittance",
                "User",
                "ICIC0001234/1234"
        );

        ExecutionOutcome outcome = executor.execute(req, "test-fingerprint");

        assertThat(outcome).isInstanceOf(ExecutionOutcome.Rejected.class);
        ExecutionOutcome.Rejected rejected = (ExecutionOutcome.Rejected) outcome;
        assertThat(rejected.execution().getStatus()).isEqualTo(ExecutionStatus.REJECTED);
        assertThat(rejected.execution().getStatusReasonCode()).isEqualTo("CURRENCY_MISMATCH");
        assertThat(rejected.exception()).isInstanceOf(TransferNotExecutableException.class);
    }
}
