package com.neobank.ledger.repository;

import com.neobank.ledger.domain.LedgerExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LedgerExecutionRepository extends JpaRepository<LedgerExecution, String> {

    Optional<LedgerExecution> findByTransferId(String transferId);
}
