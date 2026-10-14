package com.neobank.transfer.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "transfer_idempotency_records", uniqueConstraints = {
        @UniqueConstraint(name = "uk_transfer_idempotency", columnNames = {"customer_id", "operation", "idempotency_key"})
})
public class TransferIdempotencyRecord implements org.springframework.data.domain.Persistable<String> {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Column(name = "customer_id", length = 64, nullable = false)
    private String customerId;

    @Column(name = "operation", length = 64, nullable = false)
    private String operation;

    @Column(name = "idempotency_key", length = 128, nullable = false)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", length = 64, nullable = false)
    private String requestFingerprint;

    @Column(name = "transfer_id", length = 64, nullable = false)
    private String transferId;

    @Column(name = "status", length = 32, nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Transient
    private boolean isNewEntity = true;

    protected TransferIdempotencyRecord() {
    }

    public TransferIdempotencyRecord(String customerId, String operation, String idempotencyKey,
                                     String requestFingerprint, String transferId, String status, Instant now) {
        this.id = java.util.UUID.randomUUID().toString();
        this.customerId = customerId;
        this.operation = operation;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.transferId = transferId;
        this.status = status;
        this.createdAt = now;
        this.updatedAt = now;
        this.isNewEntity = true;
    }

    @Override
    public boolean isNew() {
        return isNewEntity;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNewEntity = false;
    }

    // Getters and Setters
    @Override
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public void setRequestFingerprint(String requestFingerprint) {
        this.requestFingerprint = requestFingerprint;
    }

    public String getTransferId() {
        return transferId;
    }

    public void setTransferId(String transferId) {
        this.transferId = transferId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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
}
