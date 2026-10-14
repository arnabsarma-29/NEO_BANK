package com.neobank.ledger.repository;

import com.neobank.ledger.domain.LedgerTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, String> {

    Optional<LedgerTransaction> findByTransferId(String transferId);

    boolean existsByTransferId(String transferId);
}
