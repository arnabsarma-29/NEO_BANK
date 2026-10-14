package com.neobank.transfer.repository;

import com.neobank.transfer.domain.TransferIdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TransferIdempotencyRepository extends JpaRepository<TransferIdempotencyRecord, String> {

    Optional<TransferIdempotencyRecord> findByCustomerIdAndOperationAndIdempotencyKey(
            String customerId, String operation, String idempotencyKey);
}
