package com.neobank.beneficiary.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.neobank.beneficiary.entity.BeneficiaryType;
import com.neobank.beneficiary.validation.ValidBeneficiary;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ValidBeneficiary
public class BeneficiaryRequest {

    @JsonProperty("customerId")
    private String customerId;

    @NotBlank(message = "Beneficiary name is required")
    @Size(max = 100, message = "Beneficiary name must not exceed 100 characters")
    private String name;

    @Size(max = 50, message = "Nickname must not exceed 50 characters")
    private String nickname;

    private String accountNumber;

    private String confirmAccountNumber;

    @JsonAlias({"ifsc", "ifsc_code"})
    private String ifscCode;

    private String bankName;

    private String branchName;

    private BeneficiaryType beneficiaryType;

    private String upiId;

    @Email(message = "Invalid email address format")
    private String email;

    private String phoneNumber;

    @DecimalMin(value = "0.0", inclusive = false, message = "Max limit must be greater than zero")
    private BigDecimal maxLimit;
}
