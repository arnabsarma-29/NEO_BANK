package com.neobank.transfer.domain;

import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "transfers")
public class TransferWorkflow implements Persistable<String> {

    @Id
    @Column(name = "transfer_id", length = 64, nullable = false)
    private String transferId;

    @Column(name = "reference_number", length = 64, nullable = false, unique = true)
    private String referenceNumber;

    @Column(name = "customer_id", length = 64, nullable = false)
    private String customerId;

    @Column(name = "source_account_id", length = 64, nullable = false)
    private String sourceAccountId;

    @Column(name = "destination_account_id", length = 64)
    private String destinationAccountId;

    @Column(name = "beneficiary_id", length = 64)
    private String beneficiaryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "transfer_type", length = 32, nullable = false)
    private TransferType transferType;

    @Column(name = "amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal amount;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "remarks", length = 255)
    private String remarks;

    @Column(name = "destination_name", length = 128)
    private String destinationName;

    @Column(name = "destination_reference", length = 128)
    private String destinationReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    private TransferStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "next_action", length = 32, nullable = false)
    private NextAction nextAction;

    @Column(name = "challenge_id", length = 64)
    private String challengeId;

    @Column(name = "status_reason_code", length = 64)
    private String statusReasonCode;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Transient
    private boolean isNewEntity = true;

    protected TransferWorkflow() {
    }

    public TransferWorkflow(String transferId, String referenceNumber, String customerId,
                            String sourceAccountId, String destinationAccountId, String beneficiaryId,
                            TransferType transferType, BigDecimal amount, String currency,
                            String remarks, String destinationName, String destinationReference,
                            TransferStatus status, NextAction nextAction, Instant now) {
        this.transferId = transferId;
        this.referenceNumber = referenceNumber;
        this.customerId = customerId;
        this.sourceAccountId = sourceAccountId;
        this.destinationAccountId = destinationAccountId;
        this.beneficiaryId = beneficiaryId;
        this.transferType = transferType;
        this.amount = amount;
        this.currency = currency;
        this.remarks = remarks;
        this.destinationName = destinationName;
        this.destinationReference = destinationReference;
        this.status = status;
        this.nextAction = nextAction;
        this.createdAt = now;
        this.updatedAt = now;
        this.isNewEntity = true;
    }

    @Override
    public String getId() {
        return transferId;
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

    public TransferStatus getStatus() {
        return status;
    }

    public void setStatus(TransferStatus status) {
        this.status = status;
    }

    public NextAction getNextAction() {
        return nextAction;
    }

    public void setNextAction(NextAction nextAction) {
        this.nextAction = nextAction;
    }

    public String getChallengeId() {
        return challengeId;
    }

    public void setChallengeId(String challengeId) {
        this.challengeId = challengeId;
    }

    public String getStatusReasonCode() {
        return statusReasonCode;
    }

    public void setStatusReasonCode(String statusReasonCode) {
        this.statusReasonCode = statusReasonCode;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
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

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
