package com.neobank.ledger.controller;

import com.neobank.ledger.dto.LedgerTransactionOutcomeResponse;
import com.neobank.ledger.dto.LedgerTransferExecutionRequest;
import com.neobank.ledger.service.LedgerExecutionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/ledger/transfers")
public class LedgerInternalController {

    private final LedgerExecutionService ledgerExecutionService;

    public LedgerInternalController(LedgerExecutionService ledgerExecutionService) {
        this.ledgerExecutionService = ledgerExecutionService;
    }

    /**
     * Executes a financial transfer idempotently or returns existing outcome.
     * HTTP 201 Created for new transaction committed.
     * HTTP 200 OK for existing matching transaction.
     * HTTP 202 Accepted if execution is actively PROCESSING.
     */
    @PostMapping
    public ResponseEntity<LedgerTransactionOutcomeResponse> executeTransfer(
            @Valid @RequestBody LedgerTransferExecutionRequest request) {
        LedgerExecutionService.ExecutionResult result = ledgerExecutionService.processTransfer(request);
        if ("PROCESSING".equals(result.response().getStatus())) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(result.response());
        }
        if (result.isNew()) {
            return ResponseEntity.status(HttpStatus.CREATED).body(result.response());
        } else {
            return ResponseEntity.ok(result.response());
        }
    }

    /**
     * Retrieves an authoritative transaction outcome for reconciliation.
     * HTTP 200 OK if found (COMMITTED or REJECTED).
     * HTTP 202 Accepted if currently PROCESSING.
     * HTTP 404 Not Found if no execution visible.
     */
    @GetMapping("/{transferId}")
    public ResponseEntity<LedgerTransactionOutcomeResponse> getTransferOutcome(
            @PathVariable("transferId") String transferId) {
        LedgerTransactionOutcomeResponse response = ledgerExecutionService.getOutcome(transferId);
        if ("PROCESSING".equals(response.getStatus())) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
        }
        return ResponseEntity.ok(response);
    }
}
