package com.neobank.transfer.dto;

import com.neobank.transfer.domain.TransferType;
import com.neobank.transfer.exception.ValidationException;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.Instant;

public class ExecuteScheduledTransferRequest {

    @NotBlank(message = "transferId is required")
    private String transferId;

    @NotBlank(message = "idempotencyKey is required")
    private String idempotencyKey;

    private String scheduleId;
    private String occurrenceId;

    @NotBlank(message = "customerId is required")
    private String customerId;

    @NotBlank(message = "sourceAccountId is required")
    private String sourceAccountId;

    private String destinationAccountId;
    private String beneficiaryId;

    @NotBlank(message = "transferType is required")
    private String transferType;

    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.0001", message = "amount must be positive")
    private BigDecimal amount;

    @NotBlank(message = "currency is required")
    @Pattern(regexp = "^[A-Za-z]{3}$", message = "currency must be a 3-letter ISO code")
    private String currency;

    private String remarks;
    private Instant scheduledExecutionTime;
    private String grantId;

    public ExecuteScheduledTransferRequest() {
    }

    public ExecuteScheduledTransferRequest(String transferId, String idempotencyKey, String scheduleId,
                                           String occurrenceId, String customerId, String sourceAccountId,
                                           String destinationAccountId, String beneficiaryId, String transferType,
                                           BigDecimal amount, String currency, String remarks,
                                           Instant scheduledExecutionTime, String grantId) {
        this.transferId = transferId;
        this.idempotencyKey = idempotencyKey;
        this.scheduleId = scheduleId;
        this.occurrenceId = occurrenceId;
        this.customerId = customerId;
        this.sourceAccountId = sourceAccountId;
        this.destinationAccountId = destinationAccountId;
        this.beneficiaryId = beneficiaryId;
        this.transferType = transferType;
        this.amount = amount;
        this.currency = currency;
        this.remarks = remarks;
        this.scheduledExecutionTime = scheduledExecutionTime;
        this.grantId = grantId;
    }

    public TransferType resolveTransferType() {
        if (transferType == null || transferType.isBlank()) {
            throw new ValidationException("transferType is required");
        }
        String upper = transferType.trim().toUpperCase();
        if ("OWN_ACCOUNT".equals(upper) || "INTERNAL".equals(upper) || "INTERNAL_OWN".equals(upper)) {
            return TransferType.OWN_ACCOUNT;
        }
        if ("OTHER_BANK".equals(upper) || "INTERNAL_THIRD_PARTY".equals(upper)
                || "EXTERNAL_NEFT".equals(upper) || "EXTERNAL_RTGS".equals(upper) || "EXTERNAL_IMPS".equals(upper)) {
            return TransferType.OTHER_BANK;
        }
        if ("MOCK_UPI".equals(upper) || "UPI".equals(upper)) {
            return TransferType.MOCK_UPI;
        }
        try {
            return TransferType.valueOf(upper);
        } catch (IllegalArgumentException ex) {
            throw new ValidationException("Unsupported transferType: " + transferType);
        }
    }

    // Getters and Setters
    public String getTransferId() {
        return transferId;
    }

    public void setTransferId(String transferId) {
        this.transferId = transferId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getScheduleId() {
        return scheduleId;
    }

    public void setScheduleId(String scheduleId) {
        this.scheduleId = scheduleId;
    }

    public String getOccurrenceId() {
        return occurrenceId;
    }

    public void setOccurrenceId(String occurrenceId) {
        this.occurrenceId = occurrenceId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
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

    public String getBeneficiaryId() {
        return beneficiaryId;
    }

    public void setBeneficiaryId(String beneficiaryId) {
        this.beneficiaryId = beneficiaryId;
    }

    public String getTransferType() {
        return transferType;
    }

    public void setTransferType(String transferType) {
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

    public String getRemarks() {
        return remarks;
    }

    public void setRemarks(String remarks) {
        this.remarks = remarks;
    }

    public Instant getScheduledExecutionTime() {
        return scheduledExecutionTime;
    }

    public void setScheduledExecutionTime(Instant scheduledExecutionTime) {
        this.scheduledExecutionTime = scheduledExecutionTime;
    }

    public String getGrantId() {
        return grantId;
    }

    public void setGrantId(String grantId) {
        this.grantId = grantId;
    }
}
