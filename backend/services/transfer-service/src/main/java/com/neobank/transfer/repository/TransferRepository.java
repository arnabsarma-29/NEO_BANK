package com.neobank.transfer.repository;

import com.neobank.transfer.domain.TransferWorkflow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TransferRepository extends JpaRepository<TransferWorkflow, String> {

    Optional<TransferWorkflow> findByTransferId(String transferId);

    Optional<TransferWorkflow> findByTransferIdAndCustomerId(String transferId, String customerId);

    Optional<TransferWorkflow> findByReferenceNumber(String referenceNumber);
}
