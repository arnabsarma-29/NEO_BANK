package com.neobank.ledger.event;

import java.math.BigDecimal;
import java.time.Instant;

public class BalanceUpdatedEvent {

    private String eventId;
    private String eventType = "BALANCE_UPDATED";
    private String transferId;
    private String accountId;
    private String transactionId;
    private BigDecimal oldBalance;
    private BigDecimal newBalance;
    private BigDecimal amount;
    private String currency;
    private Instant timestamp;

    public BalanceUpdatedEvent() {
    }

    public BalanceUpdatedEvent(String eventId, String transferId, String accountId,
                               String transactionId, BigDecimal oldBalance, BigDecimal newBalance,
                               BigDecimal amount, String currency, Instant timestamp) {
        this.eventId = eventId;
        this.eventType = "BALANCE_UPDATED";
        this.transferId = transferId;
        this.accountId = accountId;
        this.transactionId = transactionId;
        this.oldBalance = oldBalance;
        this.newBalance = newBalance;
        this.amount = amount;
        this.currency = currency;
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

    public String getTransferId() {
        return transferId;
    }

    public void setTransferId(String transferId) {
        this.transferId = transferId;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    public BigDecimal getOldBalance() {
        return oldBalance;
    }

    public void setOldBalance(BigDecimal oldBalance) {
        this.oldBalance = oldBalance;
    }

    public BigDecimal getNewBalance() {
        return newBalance;
    }

    public void setNewBalance(BigDecimal newBalance) {
        this.newBalance = newBalance;
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

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }
}
