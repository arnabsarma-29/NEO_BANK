package com.neobank.schedule.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "scheduled_occurrences")
public class ScheduledOccurrence {

    @Id
    @Column(name = "occurrence_id", length = 64, nullable = false)
    private String occurrenceId;

    @Column(name = "schedule_id", length = 64, nullable = false)
    private String scheduleId;

    @Column(name = "sequence_number", nullable = false)
    private int sequenceNumber;

    @Column(name = "transfer_id", length = 64, nullable = false, unique = true)
    private String transferId;

    @Column(name = "scheduled_time", nullable = false)
    private Instant scheduledTime;

    @Column(name = "retry_after")
    private Instant retryAfter;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    private OccurrenceStatus status;

    @Column(name = "status_reason_code", length = 64)
    private String statusReasonCode;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "claim_owner", length = 128)
    private String claimOwner;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "claim_version", nullable = false)
    private long claimVersion;

    @Column(name = "execution_outcome", length = 64)
    private String executionOutcome;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public ScheduledOccurrence() {
    }

    public ScheduledOccurrence(String occurrenceId, String scheduleId, int sequenceNumber,
                               String transferId, Instant scheduledTime, Instant retryAfter,
                               OccurrenceStatus status, String statusReasonCode,
                               int attemptCount, int maxAttempts, String claimOwner,
                               Instant leaseExpiresAt, long claimVersion,
                               String executionOutcome, String failureReason,
                               Instant lastAttemptAt, Instant completedAt,
                               Instant createdAt, Instant updatedAt) {
        this.occurrenceId = occurrenceId;
        this.scheduleId = scheduleId;
        this.sequenceNumber = sequenceNumber;
        this.transferId = transferId;
        this.scheduledTime = scheduledTime;
        this.retryAfter = retryAfter;
        this.status = status;
        this.statusReasonCode = statusReasonCode;
        this.attemptCount = attemptCount;
        this.maxAttempts = maxAttempts;
        this.claimOwner = claimOwner;
        this.leaseExpiresAt = leaseExpiresAt;
        this.claimVersion = claimVersion;
        this.executionOutcome = executionOutcome;
        this.failureReason = failureReason;
        this.lastAttemptAt = lastAttemptAt;
        this.completedAt = completedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    // Getters and Setters
    public String getOccurrenceId() {
        return occurrenceId;
    }

    public void setOccurrenceId(String occurrenceId) {
        this.occurrenceId = occurrenceId;
    }

    public String getScheduleId() {
        return scheduleId;
    }

    public void setScheduleId(String scheduleId) {
        this.scheduleId = scheduleId;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public void setSequenceNumber(int sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
    }

    public String getTransferId() {
        return transferId;
    }

    public void setTransferId(String transferId) {
        this.transferId = transferId;
    }

    public Instant getScheduledTime() {
        return scheduledTime;
    }

    public void setScheduledTime(Instant scheduledTime) {
        this.scheduledTime = scheduledTime;
    }

    public Instant getRetryAfter() {
        return retryAfter;
    }

    public void setRetryAfter(Instant retryAfter) {
        this.retryAfter = retryAfter;
    }

    public OccurrenceStatus getStatus() {
        return status;
    }

    public void setStatus(OccurrenceStatus status) {
        this.status = status;
    }

    public String getStatusReasonCode() {
        return statusReasonCode;
    }

    public void setStatusReasonCode(String statusReasonCode) {
        this.statusReasonCode = statusReasonCode;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(int attemptCount) {
        this.attemptCount = attemptCount;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public String getClaimOwner() {
        return claimOwner;
    }

    public void setClaimOwner(String claimOwner) {
        this.claimOwner = claimOwner;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public void setLeaseExpiresAt(Instant leaseExpiresAt) {
        this.leaseExpiresAt = leaseExpiresAt;
    }

    public long getClaimVersion() {
        return claimVersion;
    }

    public void setClaimVersion(long claimVersion) {
        this.claimVersion = claimVersion;
    }

    public String getExecutionOutcome() {
        return executionOutcome;
    }

    public void setExecutionOutcome(String executionOutcome) {
        this.executionOutcome = executionOutcome;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public void setLastAttemptAt(Instant lastAttemptAt) {
        this.lastAttemptAt = lastAttemptAt;
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
