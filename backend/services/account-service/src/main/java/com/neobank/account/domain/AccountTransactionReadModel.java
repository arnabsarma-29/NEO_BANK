package com.neobank.account.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "account_transactions")
public class AccountTransactionReadModel {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(name = "transaction_id", length = 64, nullable = false)
    private String transactionId;

    @Column(name = "transfer_id", length = 64, nullable = false)
    private String transferId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", length = 10, nullable = false)
    private EntryType entryType;

    @Column(name = "amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal amount;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "transfer_type", length = 20, nullable = false)
    private TransferType transferType;

    @Column(name = "reference_number", length = 64, nullable = false)
    private String referenceNumber;

    @Column(name = "remarks", length = 255)
    private String remarks;

    @Column(name = "destination_name", length = 128)
    private String destinationName;

    @Column(name = "destination_reference", length = 128)
    private String destinationReference;

    @Column(name = "transaction_timestamp", nullable = false)
    private Instant transactionTimestamp;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AccountTransactionReadModel() {
    }

    public AccountTransactionReadModel(String id, Account account, String transactionId, String transferId,
                                       EntryType entryType, BigDecimal amount, String currency,
                                       TransferType transferType, String referenceNumber, String remarks,
                                       String destinationName, String destinationReference,
                                       Instant transactionTimestamp, Instant createdAt) {
        this.id = id;
        this.account = account;
        this.transactionId = transactionId;
        this.transferId = transferId;
        this.entryType = entryType;
        this.amount = amount;
        this.currency = currency;
        this.transferType = transferType;
        this.referenceNumber = referenceNumber;
        this.remarks = remarks;
        this.destinationName = destinationName;
        this.destinationReference = destinationReference;
        this.transactionTimestamp = transactionTimestamp;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
    }

    public String getId() {
        return id;
    }

    public Account getAccount() {
        return account;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getTransferId() {
        return transferId;
    }

    public EntryType getEntryType() {
        return entryType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public TransferType getTransferType() {
        return transferType;
    }

    public String getReferenceNumber() {
        return referenceNumber;
    }

    public String getRemarks() {
        return remarks;
    }

    public String getDestinationName() {
        return destinationName;
    }

    public String getDestinationReference() {
        return destinationReference;
    }

    public Instant getTransactionTimestamp() {
        return transactionTimestamp;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
