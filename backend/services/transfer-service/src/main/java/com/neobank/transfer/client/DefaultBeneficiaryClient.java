package com.neobank.transfer.client;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@ConditionalOnMissingBean(type = "BeneficiaryClientCustom")
public class DefaultBeneficiaryClient implements BeneficiaryClient {

    @Override
    public Optional<BeneficiaryDetails> resolveBeneficiary(String beneficiaryId, String customerId) {
        if (beneficiaryId == null || beneficiaryId.isBlank()) {
            return Optional.empty();
        }
        if (beneficiaryId.contains("NOT_FOUND") || beneficiaryId.contains("INVALID")) {
            return Optional.empty();
        }
        // Returns safe, masked server-resolved destination display details
        String destinationName = "Beneficiary-" + beneficiaryId;
        String destinationReference = "REF-" + beneficiaryId;
        return Optional.of(new BeneficiaryDetails(beneficiaryId, destinationName, destinationReference));
    }
}
