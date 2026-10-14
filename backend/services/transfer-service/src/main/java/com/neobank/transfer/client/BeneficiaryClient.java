package com.neobank.transfer.client;

import java.util.Optional;

/**
 * Resolves beneficiary details from Beneficiary Service under ADR-015.
 */
public interface BeneficiaryClient {

    record BeneficiaryDetails(
            String beneficiaryId,
            String destinationName,
            String destinationReference
    ) {}

    Optional<BeneficiaryDetails> resolveBeneficiary(String beneficiaryId, String customerId);
}
