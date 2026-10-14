package com.neobank.beneficiary.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValidateBeneficiaryResponse {

    private boolean valid;
    private String bankName;
    private String branchName;
    private String message;
    private Map<String, String> fieldErrors;
}
