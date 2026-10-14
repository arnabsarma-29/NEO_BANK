package com.neobank.beneficiary.repository;

import com.neobank.beneficiary.entity.Beneficiary;
import com.neobank.beneficiary.entity.BeneficiaryStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BeneficiaryRepository extends JpaRepository<Beneficiary, Long> {

    Optional<Beneficiary> findByPublicId(UUID publicId);

    Optional<Beneficiary> findByPublicIdAndCustomerId(UUID publicId, String customerId);

    List<Beneficiary> findByCustomerId(String customerId);

    List<Beneficiary> findByCustomerIdAndStatus(String customerId, BeneficiaryStatus status);

    List<Beneficiary> findByStatus(BeneficiaryStatus status);

    boolean existsByCustomerIdAndAccountNumberAndIfscCodeAndStatusIn(
        String customerId, String accountNumber, String ifscCode, Collection<BeneficiaryStatus> statuses
    );

    boolean existsByCustomerIdAndUpiIdAndStatusIn(
        String customerId, String upiId, Collection<BeneficiaryStatus> statuses
    );

    List<Beneficiary> findByStatusAndCoolingPeriodEndsAtBefore(
        BeneficiaryStatus status, LocalDateTime threshold
    );
}
