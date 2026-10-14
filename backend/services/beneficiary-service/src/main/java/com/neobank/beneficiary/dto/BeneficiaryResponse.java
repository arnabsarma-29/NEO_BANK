package com.neobank.beneficiary.dto;

import com.neobank.beneficiary.entity.BeneficiaryStatus;
import com.neobank.beneficiary.entity.BeneficiaryType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BeneficiaryResponse {

    private UUID beneficiaryId;
    private String customerId;
    private String name;
    private String nickname;
    private String maskedAccountNumber;
    private String accountNumber;
    private String ifscCode;
    private String bankName;
    private String branchName;
    private BeneficiaryType beneficiaryType;
    private String upiId;
    private String email;
    private String phoneNumber;
    private BigDecimal maxLimit;
    private BeneficiaryStatus status;
    private LocalDateTime coolingPeriodEndsAt;
    private boolean coolingPeriodActive;
    private LocalDateTime createdAt;
}
