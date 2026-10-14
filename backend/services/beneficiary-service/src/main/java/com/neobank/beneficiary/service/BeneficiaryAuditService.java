package com.neobank.beneficiary.service;

import com.neobank.beneficiary.entity.Beneficiary;

import java.util.UUID;

public interface BeneficiaryAuditService {

    void logCreation(Beneficiary beneficiary, String customerId, String actor, String clientIp);

    void logStatusChange(Beneficiary beneficiary, String oldStatus, String newStatus, String actor, String clientIp);

    void logDeletion(UUID beneficiaryPublicId, String customerId, String oldStatus, String actor, String clientIp);
}
