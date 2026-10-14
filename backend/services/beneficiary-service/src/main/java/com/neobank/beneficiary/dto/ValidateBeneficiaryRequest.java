package com.neobank.beneficiary.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.neobank.beneficiary.entity.BeneficiaryType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValidateBeneficiaryRequest {

    @JsonProperty("customerId")
    private String customerId;

    private String accountNumber;
    private String confirmAccountNumber;

    @JsonAlias({"ifsc", "ifsc_code"})
    private String ifscCode;

    private String bankName;
    private BeneficiaryType beneficiaryType;
    private String upiId;
}

