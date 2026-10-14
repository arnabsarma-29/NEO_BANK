package com.neobank.transfer.controller;

import com.neobank.transfer.domain.TransferStatus;
import com.neobank.transfer.domain.TransferWorkflow;
import com.neobank.transfer.dto.*;
import com.neobank.transfer.exception.UnauthorizedException;
import com.neobank.transfer.security.CustomerContextHolder;
import com.neobank.transfer.security.CustomerPrincipal;
import com.neobank.transfer.service.TransferWorkflowService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/transfers")
public class TransferController {

    private final TransferWorkflowService workflowService;

    public TransferController(TransferWorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    /**
     * Section 6.1: Create Transfer API
     * Creates a new transfer workflow (201 Created) or returns existing (202 Accepted).
     */
    @PostMapping
    public ResponseEntity<CreateTransferResponse> createTransfer(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateTransferRequest request) {
        String customerId = getAuthenticatedCustomerId();
        TransferWorkflowService.CreateTransferResult result =
                workflowService.createTransfer(request, idempotencyKey, customerId);

        CreateTransferResponse response = workflowService.mapToCreateResponse(result.transfer());
        if (result.isNew()) {
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } else {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
        }
    }

    /**
     * Section 6.2: Transfer Status API
     * Retrieves current customer-facing transfer workflow state.
     */
    @GetMapping("/{transferId}")
    public ResponseEntity<TransferStatusResponse> getTransferStatus(
            @PathVariable("transferId") String transferId) {
        String customerId = getAuthenticatedCustomerId();
        TransferWorkflow workflow = workflowService.getTransfer(transferId, customerId);
        return ResponseEntity.ok(workflowService.mapToStatusResponse(workflow));
    }

    /**
     * Section 6.3: Confirm Transfer API
     * Confirms the transfer and initiates OTP challenge or direct execution.
     */
    @PostMapping("/{transferId}/confirm")
    public ResponseEntity<ConfirmTransferResponse> confirmTransfer(
            @PathVariable("transferId") String transferId) {
        String customerId = getAuthenticatedCustomerId();
        ConfirmTransferResponse response = workflowService.confirmTransfer(transferId, customerId);
        return ResponseEntity.ok(response);
    }

    /**
     * Section 6.4: Submit OTP Verification API
     * Verifies customer OTP and proceeds to financial execution.
     */
    @PostMapping("/{transferId}/otp-verification")
    public ResponseEntity<TransferStatusResponse> verifyOtp(
            @PathVariable("transferId") String transferId,
            @Valid @RequestBody VerifyTransferOtpRequest request) {
        String customerId = getAuthenticatedCustomerId();
        TransferWorkflow workflow = workflowService.verifyOtp(transferId, customerId, request);
        TransferStatusResponse response = workflowService.mapToStatusResponse(workflow);

        if (workflow.getStatus() == TransferStatus.PROCESSING
                || workflow.getStatus() == TransferStatus.OUTCOME_UNKNOWN) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
        }
        return ResponseEntity.ok(response);
    }

    /**
     * Section 6.5: Resend OTP API
     * Coordinates replacement OTP issuance via Authentication Service.
     */
    @PostMapping("/{transferId}/otp/resend")
    public ResponseEntity<ConfirmTransferResponse> resendOtp(
            @PathVariable("transferId") String transferId,
            @Valid @RequestBody ResendTransferOtpRequest request) {
        String customerId = getAuthenticatedCustomerId();
        ConfirmTransferResponse response = workflowService.resendOtp(transferId, customerId, request);
        return ResponseEntity.ok(response);
    }

    private String getAuthenticatedCustomerId() {
        return CustomerContextHolder.getCustomer()
                .map(CustomerPrincipal::customerId)
                .orElse("C1001"); // Default customer context for unauthenticated local testing/fallback
    }
}
