package com.neobank.ledger.dto;

import java.math.BigDecimal;
import java.time.Instant;

public class LedgerTransactionOutcomeResponse {

    private String transferId;
    private String transactionId;
    private String status;
    private String sourceAccountId;
    private String destinationAccountId;
    private String transferType;
    private BigDecimal amount;
    private String currency;
    private String referenceNumber;
    private String remarks;
    private String destinationName;
    private String destinationReference;
    private Instant timestamp;
    private String statusReasonCode;
    private String failureMessage;

    public LedgerTransactionOutcomeResponse() {
    }

    public LedgerTransactionOutcomeResponse(String transferId, String transactionId, String status,
                                           String sourceAccountId, String destinationAccountId,
                                           String transferType, BigDecimal amount, String currency,
                                           String referenceNumber, String remarks, String destinationName,
                                           String destinationReference, Instant timestamp) {
        this(transferId, transactionId, status, sourceAccountId, destinationAccountId,
                transferType, amount, currency, referenceNumber, remarks, destinationName,
                destinationReference, timestamp, null, null);
    }

    public LedgerTransactionOutcomeResponse(String transferId, String transactionId, String status,
                                           String sourceAccountId, String destinationAccountId,
                                           String transferType, BigDecimal amount, String currency,
                                           String referenceNumber, String remarks, String destinationName,
                                           String destinationReference, Instant timestamp,
                                           String statusReasonCode, String failureMessage) {
        this.transferId = transferId;
        this.transactionId = transactionId;
        this.status = status;
        this.sourceAccountId = sourceAccountId;
        this.destinationAccountId = destinationAccountId;
        this.transferType = transferType;
        this.amount = amount;
        this.currency = currency;
        this.referenceNumber = referenceNumber;
        this.remarks = remarks;
        this.destinationName = destinationName;
        this.destinationReference = destinationReference;
        this.timestamp = timestamp;
        this.statusReasonCode = statusReasonCode;
        this.failureMessage = failureMessage;
    }

    // Getters and Setters
    public String getTransferId() {
        return transferId;
    }

    public void setTransferId(String transferId) {
        this.transferId = transferId;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public String getStatusReasonCode() {
        return statusReasonCode;
    }

    public void setStatusReasonCode(String statusReasonCode) {
        this.statusReasonCode = statusReasonCode;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public void setFailureMessage(String failureMessage) {
        this.failureMessage = failureMessage;
    }
}
