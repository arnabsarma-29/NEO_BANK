package com.neobank.schedule.repository;

import com.neobank.schedule.domain.OccurrenceStatus;
import com.neobank.schedule.domain.ScheduledOccurrence;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface ScheduledOccurrenceRepository extends JpaRepository<ScheduledOccurrence, String> {

    List<ScheduledOccurrence> findByScheduleIdOrderBySequenceNumberAsc(String scheduleId);

    Optional<ScheduledOccurrence> findByTransferId(String transferId);

    @Query(value = "SELECT CURRENT_TIMESTAMP", nativeQuery = true)
    Instant getCurrentDatabaseTime();

    @Query("SELECT o FROM ScheduledOccurrence o WHERE " +
           "((o.status IN (com.neobank.schedule.domain.OccurrenceStatus.PENDING, com.neobank.schedule.domain.OccurrenceStatus.RETRY_PENDING, com.neobank.schedule.domain.OccurrenceStatus.UNCERTAIN) AND o.retryAfter <= :now) " +
           " OR (o.status = com.neobank.schedule.domain.OccurrenceStatus.PROCESSING AND o.leaseExpiresAt <= :now)) " +
           "ORDER BY o.scheduledTime ASC")
    List<ScheduledOccurrence> findDueOccurrences(@Param("now") Instant now, Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ScheduledOccurrence o " +
           "SET o.claimOwner = :workerId, " +
           "    o.leaseExpiresAt = :leaseExpiresAt, " +
           "    o.claimVersion = o.claimVersion + 1, " +
           "    o.status = com.neobank.schedule.domain.OccurrenceStatus.PROCESSING, " +
           "    o.updatedAt = :now " +
           "WHERE o.occurrenceId = :occurrenceId " +
           "  AND o.claimVersion = :expectedClaimVersion " +
           "  AND ((o.status IN (com.neobank.schedule.domain.OccurrenceStatus.PENDING, com.neobank.schedule.domain.OccurrenceStatus.RETRY_PENDING, com.neobank.schedule.domain.OccurrenceStatus.UNCERTAIN) AND o.retryAfter <= :now) " +
           "       OR (o.status = com.neobank.schedule.domain.OccurrenceStatus.PROCESSING AND o.leaseExpiresAt <= :now))")
    int claimOccurrenceAtomic(
        @Param("occurrenceId") String occurrenceId,
        @Param("workerId") String workerId,
        @Param("expectedClaimVersion") long expectedClaimVersion,
        @Param("leaseExpiresAt") Instant leaseExpiresAt,
        @Param("now") Instant now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ScheduledOccurrence o " +
           "SET o.leaseExpiresAt = :leaseExpiresAt, " +
           "    o.updatedAt = :now " +
           "WHERE o.occurrenceId = :occurrenceId " +
           "  AND o.claimOwner = :workerId " +
           "  AND o.claimVersion = :claimVersion " +
           "  AND o.status = com.neobank.schedule.domain.OccurrenceStatus.PROCESSING " +
           "  AND o.leaseExpiresAt > :now")
    int renewLease(
        @Param("occurrenceId") String occurrenceId,
        @Param("workerId") String workerId,
        @Param("claimVersion") long claimVersion,
        @Param("leaseExpiresAt") Instant leaseExpiresAt,
        @Param("now") Instant now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ScheduledOccurrence o " +
           "SET o.status = :newStatus, " +
           "    o.statusReasonCode = :statusReasonCode, " +
           "    o.executionOutcome = :executionOutcome, " +
           "    o.failureReason = :failureReason, " +
           "    o.lastAttemptAt = :now, " +
           "    o.completedAt = :completedAt, " +
           "    o.retryAfter = :retryAfter, " +
           "    o.attemptCount = :attemptCount, " +
           "    o.updatedAt = :now " +
           "WHERE o.occurrenceId = :occurrenceId " +
           "  AND o.claimOwner = :workerId " +
           "  AND o.claimVersion = :claimVersion " +
           "  AND o.status = com.neobank.schedule.domain.OccurrenceStatus.PROCESSING " +
           "  AND o.leaseExpiresAt > :now")
    int recordExecutionOutcome(
        @Param("occurrenceId") String occurrenceId,
        @Param("workerId") String workerId,
        @Param("claimVersion") long claimVersion,
        @Param("newStatus") OccurrenceStatus newStatus,
        @Param("statusReasonCode") String statusReasonCode,
        @Param("executionOutcome") String executionOutcome,
        @Param("failureReason") String failureReason,
        @Param("retryAfter") Instant retryAfter,
        @Param("attemptCount") int attemptCount,
        @Param("completedAt") Instant completedAt,
        @Param("now") Instant now
    );
}
