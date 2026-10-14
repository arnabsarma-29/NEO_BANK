package com.neobank.ledger.event;

import java.math.BigDecimal;
import java.time.Instant;

public class TransactionCreatedEvent {

    private String eventId;
    private String eventType = "TRANSACTION_CREATED";
    private String transactionId;
    private String transferId;
    private String sourceAccountId;
    private String destinationAccountId;
    private String transferType;
    private BigDecimal amount;
    private String currency;
    private String status = "SUCCESS";
    private String referenceNumber;
    private String remarks;
    private String destinationName;
    private String destinationReference;
    private Instant timestamp;

    public TransactionCreatedEvent() {
    }

    public TransactionCreatedEvent(String eventId, String transactionId, String transferId,
                                 String sourceAccountId, String destinationAccountId, String transferType,
                                 BigDecimal amount, String currency, String status, String referenceNumber,
                                 String remarks, String destinationName, String destinationReference,
                                 Instant timestamp) {
        this.eventId = eventId;
        this.eventType = "TRANSACTION_CREATED";
        this.transactionId = transactionId;
        this.transferId = transferId;
        this.sourceAccountId = sourceAccountId;
        this.destinationAccountId = destinationAccountId;
        this.transferType = transferType;
        this.amount = amount;
        this.currency = currency;
        this.status = status != null ? status : "SUCCESS";
        this.referenceNumber = referenceNumber;
        this.remarks = remarks;
        this.destinationName = destinationName;
        this.destinationReference = destinationReference;
        this.timestamp = timestamp;
    }

    // Getters and Setters
    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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
}
