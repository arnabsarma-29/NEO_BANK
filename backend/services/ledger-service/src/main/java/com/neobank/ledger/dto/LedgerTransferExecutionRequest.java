package com.neobank.ledger.dto;

import com.neobank.ledger.domain.TransferType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

public class LedgerTransferExecutionRequest {

    @NotBlank(message = "transferId is required")
    private String transferId;

    @NotBlank(message = "sourceAccountId is required")
    private String sourceAccountId;

    private String destinationAccountId;

    @NotNull(message = "transferType is required")
    private TransferType transferType;

    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.0001", message = "Amount must be positive")
    private BigDecimal amount;

    @NotBlank(message = "currency is required")
    @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be a 3-letter ISO code")
    private String currency;

    @NotBlank(message = "referenceNumber is required")
    private String referenceNumber;

    private String remarks;

    private String destinationName;

    private String destinationReference;

    public LedgerTransferExecutionRequest() {
    }

    public LedgerTransferExecutionRequest(String transferId, String sourceAccountId, String destinationAccountId,
                                        TransferType transferType, BigDecimal amount, String currency,
                                        String referenceNumber, String remarks, String destinationName,
                                        String destinationReference) {
        this.transferId = transferId;
        this.sourceAccountId = sourceAccountId;
        this.destinationAccountId = destinationAccountId;
        this.transferType = transferType;
        this.amount = amount;
        this.currency = currency;
        this.referenceNumber = referenceNumber;
        this.remarks = remarks;
        this.destinationName = destinationName;
        this.destinationReference = destinationReference;
    }

    // Getters and Setters
    public String getTransferId() {
        return transferId;
    }

    public void setTransferId(String transferId) {
        this.transferId = transferId;
    }

    public String getSourceAccountId() {
        return sourceAccountId;
    }

    public void setSourceAccountId(String sourceAccountId) {
        this.sourceAccountId = sourceAccountId;
    }

    public String getDestinationAccountId() {
        return destinationAccountId;
    }

    public void setDestinationAccountId(String destinationAccountId) {
        this.destinationAccountId = destinationAccountId;
    }

    public TransferType getTransferType() {
        return transferType;
    }

    public void setTransferType(TransferType transferType) {
        this.transferType = transferType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getReferenceNumber() {
        return referenceNumber;
    }

    public void setReferenceNumber(String referenceNumber) {
        this.referenceNumber = referenceNumber;
    }

    public String getRemarks() {
        return remarks;
    }

    public void setRemarks(String remarks) {
        this.remarks = remarks;
    }

    public String getDestinationName() {
        return destinationName;
    }

    public void setDestinationName(String destinationName) {
        this.destinationName = destinationName;
    }

    public String getDestinationReference() {
        return destinationReference;
    }

    public void setDestinationReference(String destinationReference) {
        this.destinationReference = destinationReference;
    }
}
