package com.neobank.beneficiary.service;

import com.neobank.beneficiary.dto.*;
import com.neobank.beneficiary.entity.BeneficiaryStatus;

import java.util.List;
import java.util.UUID;

public interface BeneficiaryService {

    BeneficiaryResponse createBeneficiary(BeneficiaryRequest request, String customerId, String actor, String clientIp);

    List<BeneficiaryResponse> getBeneficiaries(String customerId, BeneficiaryStatus status);

    BeneficiaryResponse getBeneficiary(UUID beneficiaryId, String customerId);

    BeneficiaryStatusResponse getBeneficiaryStatus(UUID beneficiaryId, String customerId);

    ValidateBeneficiaryResponse validateBeneficiaryDetails(ValidateBeneficiaryRequest request, String customerId);

    TransferEligibilityResponse checkTransferEligibility(UUID beneficiaryId);

    BeneficiaryResponse deleteBeneficiary(UUID beneficiaryId, String customerId, String actor, String clientIp);
}
