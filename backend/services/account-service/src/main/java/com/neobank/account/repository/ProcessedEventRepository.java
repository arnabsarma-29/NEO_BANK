package com.neobank.account.repository;

import com.neobank.account.domain.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {

    boolean existsByEventId(String eventId);
}
