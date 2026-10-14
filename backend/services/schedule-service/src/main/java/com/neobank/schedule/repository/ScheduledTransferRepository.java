package com.neobank.schedule.repository;

import com.neobank.schedule.domain.ScheduleStatus;
import com.neobank.schedule.domain.ScheduledTransfer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ScheduledTransferRepository extends JpaRepository<ScheduledTransfer, String> {

    List<ScheduledTransfer> findByCustomerIdOrderByCreatedAtDesc(String customerId);

    List<ScheduledTransfer> findByStatus(ScheduleStatus status);
}
