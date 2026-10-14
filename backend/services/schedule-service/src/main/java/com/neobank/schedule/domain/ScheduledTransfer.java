package com.neobank.schedule.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "scheduled_transfers")
public class ScheduledTransfer {

    @Id
    @Column(name = "schedule_id", length = 64, nullable = false)
    private String scheduleId;

    @Column(name = "customer_id", length = 64, nullable = false)
    private String customerId;

    @Column(name = "source_account_id", length = 64, nullable = false)
    private String sourceAccountId;

    @Column(name = "destination_account_id", length = 64)
    private String destinationAccountId;

    @Column(name = "beneficiary_id", length = 64)
    private String beneficiaryId;

    @Column(name = "transfer_type", length = 32, nullable = false)
    private String transferType;

    @Column(name = "amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal amount;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "remarks", length = 255)
    private String remarks;

    @Enumerated(EnumType.STRING)
    @Column(name = "schedule_type", length = 32, nullable = false)
    private ScheduleType scheduleType;

    @Enumerated(EnumType.STRING)
    @Column(name = "frequency", length = 32)
    private RecurrenceFrequency frequency;

    @Column(name = "start_date", nullable = false)
    private Instant startDate;

    @Column(name = "end_date")
    private Instant endDate;

    @Column(name = "next_run_at")
    private Instant nextRunAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    private ScheduleStatus status;

    @Column(name = "total_occurrences", nullable = false)
    private int totalOccurrences;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public ScheduledTransfer() {
    }

    public ScheduledTransfer(String scheduleId, String customerId, String sourceAccountId,
                             String destinationAccountId, String beneficiaryId, String transferType,
                             BigDecimal amount, String currency, String remarks,
                             ScheduleType scheduleType, RecurrenceFrequency frequency,
                             Instant startDate, Instant endDate, Instant nextRunAt,
                             ScheduleStatus status, int totalOccurrences,
                             Instant createdAt, Instant updatedAt) {
        this.scheduleId = scheduleId;
        this.customerId = customerId;
        this.sourceAccountId = sourceAccountId;
        this.destinationAccountId = destinationAccountId;
        this.beneficiaryId = beneficiaryId;
        this.transferType = transferType;
        this.amount = amount;
        this.currency = currency;
        this.remarks = remarks;
        this.scheduleType = scheduleType;
        this.frequency = frequency;
        this.startDate = startDate;
        this.endDate = endDate;
        this.nextRunAt = nextRunAt;
        this.status = status;
        this.totalOccurrences = totalOccurrences;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    // Getters and Setters
    public String getScheduleId() {
        return scheduleId;
    }

    public void setScheduleId(String scheduleId) {
        this.scheduleId = scheduleId;
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

    public ScheduleType getScheduleType() {
        return scheduleType;
    }

    public void setScheduleType(ScheduleType scheduleType) {
        this.scheduleType = scheduleType;
    }

    public RecurrenceFrequency getFrequency() {
        return frequency;
    }

    public void setFrequency(RecurrenceFrequency frequency) {
        this.frequency = frequency;
    }

    public Instant getStartDate() {
        return startDate;
    }

    public void setStartDate(Instant startDate) {
        this.startDate = startDate;
    }

    public Instant getEndDate() {
        return endDate;
    }

    public void setEndDate(Instant endDate) {
        this.endDate = endDate;
    }

    public Instant getNextRunAt() {
        return nextRunAt;
    }

    public void setNextRunAt(Instant nextRunAt) {
        this.nextRunAt = nextRunAt;
    }

    public ScheduleStatus getStatus() {
        return status;
    }

    public void setStatus(ScheduleStatus status) {
        this.status = status;
    }

    public int getTotalOccurrences() {
        return totalOccurrences;
    }

    public void setTotalOccurrences(int totalOccurrences) {
        this.totalOccurrences = totalOccurrences;
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
