package com.neobank.beneficiary.service;

import com.neobank.beneficiary.dto.BeneficiaryResponse;

import java.util.UUID;

public interface BeneficiaryActivationService {

    BeneficiaryResponse activateBeneficiary(UUID beneficiaryId, String actor, String clientIp);

    void processPendingCooldownActivations();
}
