package com.neobank.beneficiary.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IfscRegistrationRequest {

    @NotBlank(message = "IFSC code is required")
    @JsonAlias({"ifsc", "ifsc_code"})
    @Pattern(
        regexp = "^[A-Z]{4}0[A-Z0-9]{6}$",
        message = "Invalid IFSC format. Must be 4 uppercase letters, followed by 0, and 6 alphanumeric characters (e.g., SBIN0001234)"
    )
    private String ifscCode;

    @NotBlank(message = "Bank name is required")
    private String bankName;

    private String branchName;

    private String address;

    private String city;

    private String state;
}
