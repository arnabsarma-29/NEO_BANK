package com.neobank.ledger.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "ledger_transactions")
public class LedgerTransaction {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Column(name = "transfer_id", length = 64, nullable = false, unique = true)
    private String transferId;

    @Column(name = "source_account_id", length = 64, nullable = false)
    private String sourceAccountId;

    @Column(name = "destination_account_id", length = 64)
    private String destinationAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "transfer_type", length = 20, nullable = false)
    private TransferType transferType;

    @Column(name = "amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal amount;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "status", length = 20, nullable = false)
    private String status = "SUCCESS";

    @Column(name = "reference_number", length = 64, nullable = false)
    private String referenceNumber;

    @Column(name = "remarks", length = 255)
    private String remarks;

    @Column(name = "destination_name", length = 128)
    private String destinationName;

    @Column(name = "destination_reference", length = 128)
    private String destinationReference;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public LedgerTransaction() {
    }

    public LedgerTransaction(String id, String transferId, String sourceAccountId, String destinationAccountId,
                             TransferType transferType, BigDecimal amount, String currency, String status,
                             String referenceNumber, String remarks, String destinationName,
                             String destinationReference, Instant createdAt) {
        this.id = id;
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
        this.createdAt = createdAt != null ? createdAt : Instant.now();
    }

    // Getters and Setters
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LedgerTransaction that = (LedgerTransaction) o;
        return Objects.equals(id, that.id) || Objects.equals(transferId, that.transferId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, transferId);
    }
}
