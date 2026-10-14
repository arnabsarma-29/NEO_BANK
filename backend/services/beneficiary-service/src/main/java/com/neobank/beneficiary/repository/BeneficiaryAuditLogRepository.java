package com.neobank.beneficiary.repository;

import com.neobank.beneficiary.entity.BeneficiaryAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BeneficiaryAuditLogRepository extends JpaRepository<BeneficiaryAuditLog, UUID> {

    List<BeneficiaryAuditLog> findByCustomerIdOrderByCreatedAtDesc(String customerId);

    List<BeneficiaryAuditLog> findByBeneficiaryPublicIdOrderByCreatedAtDesc(UUID beneficiaryPublicId);
}
