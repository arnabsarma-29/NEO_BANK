package com.neobank.ledger.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "ledger_executions")
public class LedgerExecution {

    @Id
    @Column(name = "transfer_id", length = 64, nullable = false)
    private String transferId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private ExecutionStatus status;

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

    @Column(name = "reference_number", length = 64)
    private String referenceNumber;

    @Column(name = "ledger_transaction_id", length = 64)
    private String ledgerTransactionId;

    @Column(name = "status_reason_code", length = 64)
    private String statusReasonCode;

    @Column(name = "failure_message", length = 255)
    private String failureMessage;

    @Column(name = "financial_fingerprint", length = 64, nullable = false)
    private String financialFingerprint;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "version", nullable = false)
    private Long version = 1L;

    public LedgerExecution() {
    }

    public LedgerExecution(String transferId, ExecutionStatus status, String sourceAccountId,
                           String destinationAccountId, TransferType transferType, BigDecimal amount,
                           String currency, String referenceNumber, String ledgerTransactionId,
                           String statusReasonCode, String failureMessage, String financialFingerprint,
                           Instant createdAt, Instant completedAt, Long version) {
        this.transferId = transferId;
        this.status = status;
        this.sourceAccountId = sourceAccountId;
        this.destinationAccountId = destinationAccountId;
        this.transferType = transferType;
        this.amount = amount;
        this.currency = currency;
        this.referenceNumber = referenceNumber;
        this.ledgerTransactionId = ledgerTransactionId;
        this.statusReasonCode = statusReasonCode;
        this.failureMessage = failureMessage;
        this.financialFingerprint = financialFingerprint;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.completedAt = completedAt;
        this.version = version != null ? version : 1L;
    }

    public String getTransferId() {
        return transferId;
    }

    public void setTransferId(String transferId) {
        this.transferId = transferId;
    }

    public ExecutionStatus getStatus() {
        return status;
    }

    public void setStatus(ExecutionStatus status) {
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

    public String getReferenceNumber() {
        return referenceNumber;
    }

    public void setReferenceNumber(String referenceNumber) {
        this.referenceNumber = referenceNumber;
    }

    public String getLedgerTransactionId() {
        return ledgerTransactionId;
    }

    public void setLedgerTransactionId(String ledgerTransactionId) {
        this.ledgerTransactionId = ledgerTransactionId;
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

    public String getFinancialFingerprint() {
        return financialFingerprint;
    }

    public void setFinancialFingerprint(String financialFingerprint) {
        this.financialFingerprint = financialFingerprint;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LedgerExecution that = (LedgerExecution) o;
        return Objects.equals(transferId, that.transferId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(transferId);
    }
}
