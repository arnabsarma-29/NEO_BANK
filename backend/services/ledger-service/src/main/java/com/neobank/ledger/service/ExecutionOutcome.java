package com.neobank.ledger.service;

import com.neobank.ledger.domain.LedgerExecution;
import com.neobank.ledger.domain.LedgerTransaction;
import com.neobank.ledger.exception.LedgerException;

public sealed interface ExecutionOutcome {

    record Committed(LedgerExecution execution, LedgerTransaction transaction) implements ExecutionOutcome {}

    record Rejected(LedgerExecution execution, LedgerException exception) implements ExecutionOutcome {}
}
