package com.neobank.transfer.controller;

import com.neobank.transfer.dto.ExecuteScheduledTransferRequest;
import com.neobank.transfer.dto.ExecuteScheduledTransferResponse;
import com.neobank.transfer.dto.ScheduledTransferReconcileResponse;
import com.neobank.transfer.service.TransferWorkflowService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Internal REST controller for Schedule Service -> Transfer Service scheduled execution and reconciliation.
 * Protected by ScheduleServiceTokenInterceptor via X-Internal-Service-Token.
 * Delegates all financial workflow operations to TransferWorkflowService.
 */
@RestController
@RequestMapping("/internal/transfers")
public class TransferInternalController {

    private final TransferWorkflowService transferWorkflowService;

    public TransferInternalController(TransferWorkflowService transferWorkflowService) {
        this.transferWorkflowService = transferWorkflowService;
    }

    @PostMapping("/scheduled-executions")
    public ResponseEntity<ExecuteScheduledTransferResponse> executeScheduledTransfer(
            @Valid @RequestBody ExecuteScheduledTransferRequest request) {
        TransferWorkflowService.ScheduledExecutionOutcome outcome =
                transferWorkflowService.executeScheduledTransfer(request);
        return ResponseEntity.status(outcome.httpStatus()).body(outcome.response());
    }

    @GetMapping("/{transferId}/reconcile")
    public ResponseEntity<ScheduledTransferReconcileResponse> reconcileScheduledTransfer(
            @PathVariable("transferId") String transferId) {
        TransferWorkflowService.ScheduledReconciliationOutcome outcome =
                transferWorkflowService.reconcileScheduledTransfer(transferId);
        if (outcome.httpStatus() == HttpStatus.NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.status(outcome.httpStatus()).body(outcome.response());
    }
}
