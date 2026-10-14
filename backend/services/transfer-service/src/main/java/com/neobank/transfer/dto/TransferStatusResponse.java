package com.neobank.transfer.dto;

import com.neobank.transfer.domain.NextAction;
import com.neobank.transfer.domain.TransferStatus;
import com.neobank.transfer.domain.TransferType;

import java.math.BigDecimal;
import java.time.Instant;

public class TransferStatusResponse {

    private String transferId;
    private String referenceNumber;
    private TransferStatus status;
    private TransferType transferType;
    private String sourceAccountId;
    private String destinationAccountId;
    private String beneficiaryId;
    private String destinationName;
    private String destinationReference;
    private BigDecimal amount;
    private String currency;
    private String remarks;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant completedAt;
    private NextAction nextAction;
    private String statusReasonCode;

    public TransferStatusResponse() {
    }

    public TransferStatusResponse(String transferId, String referenceNumber, TransferStatus status,
                                  TransferType transferType, String sourceAccountId,
                                  String destinationAccountId, String beneficiaryId,
                                  String destinationName, String destinationReference,
                                  BigDecimal amount, String currency, String remarks,
                                  Instant createdAt, Instant updatedAt, Instant completedAt,
                                  NextAction nextAction, String statusReasonCode) {
        this.transferId = transferId;
        this.referenceNumber = referenceNumber;
        this.status = status;
        this.transferType = transferType;
        this.sourceAccountId = sourceAccountId;
        this.destinationAccountId = destinationAccountId;
        this.beneficiaryId = beneficiaryId;
        this.destinationName = destinationName;
        this.destinationReference = destinationReference;
        this.amount = amount;
        this.currency = currency;
        this.remarks = remarks;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.completedAt = completedAt;
        this.nextAction = nextAction;
        this.statusReasonCode = statusReasonCode;
    }

    // Getters and Setters
    public String getTransferId() {
        return transferId;
    }

    public void setTransferId(String transferId) {
        this.transferId = transferId;
    }

    public String getReferenceNumber() {
        return referenceNumber;
    }

    public void setReferenceNumber(String referenceNumber) {
        this.referenceNumber = referenceNumber;
    }

    public TransferStatus getStatus() {
        return status;
    }

    public void setStatus(TransferStatus status) {
        this.status = status;
    }

    public TransferType getTransferType() {
        return transferType;
    }

    public void setTransferType(TransferType transferType) {
        this.transferType = transferType;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public NextAction getNextAction() {
        return nextAction;
    }

    public void setNextAction(NextAction nextAction) {
        this.nextAction = nextAction;
    }

    public String getStatusReasonCode() {
        return statusReasonCode;
    }

    public void setStatusReasonCode(String statusReasonCode) {
        this.statusReasonCode = statusReasonCode;
    }
}
