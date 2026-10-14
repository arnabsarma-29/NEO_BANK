package com.neobank.transfer.dto;

import com.neobank.transfer.domain.TransferType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

public class CreateTransferRequest {

    @NotBlank(message = "sourceAccountId is required")
    private String sourceAccountId;

    @NotNull(message = "transferType is required")
    private TransferType transferType;

    private String destinationAccountId;

    private String beneficiaryId;

    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.0001", message = "amount must be positive")
    private BigDecimal amount;

    @NotBlank(message = "currency is required")
    @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be a 3-letter ISO code")
    private String currency;

    private String remarks;

    public CreateTransferRequest() {
    }

    public CreateTransferRequest(String sourceAccountId, TransferType transferType,
                                 String destinationAccountId, String beneficiaryId,
                                 BigDecimal amount, String currency, String remarks) {
        this.sourceAccountId = sourceAccountId;
        this.transferType = transferType;
        this.destinationAccountId = destinationAccountId;
        this.beneficiaryId = beneficiaryId;
        this.amount = amount;
        this.currency = currency;
        this.remarks = remarks;
    }

    // Getters and Setters
    public String getSourceAccountId() {
        return sourceAccountId;
    }

    public void setSourceAccountId(String sourceAccountId) {
        this.sourceAccountId = sourceAccountId;
    }

    public TransferType getTransferType() {
        return transferType;
    }

    public void setTransferType(TransferType transferType) {
        this.transferType = transferType;
    }

    public String getDestinationAccountId() {
        return destinationAccountId;
    }

    public void setDestinationAccountId(String destinationAccountId) {
        this.destinationAccountId = destinationAccountId;
    }

    public String getBeneficiaryId() {
        return beneficiaryId;
    }

    public void setBeneficiaryId(String beneficiaryId) {
        this.beneficiaryId = beneficiaryId;
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

    public String getRemarks() {
        return remarks;
    }

    public void setRemarks(String remarks) {
        this.remarks = remarks;
    }
}
