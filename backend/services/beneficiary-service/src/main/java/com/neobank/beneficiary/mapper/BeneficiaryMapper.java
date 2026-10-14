package com.neobank.beneficiary.mapper;

import com.neobank.beneficiary.dto.BeneficiaryResponse;
import com.neobank.beneficiary.dto.BeneficiaryStatusResponse;
import com.neobank.beneficiary.dto.IfscDetailsResponse;
import com.neobank.beneficiary.entity.Beneficiary;
import com.neobank.beneficiary.entity.BeneficiaryStatus;
import com.neobank.beneficiary.entity.IfscDirectory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

@Component
public class BeneficiaryMapper {

    public BeneficiaryResponse toResponse(Beneficiary entity) {
        if (entity == null) {
            return null;
        }

        String masked = com.neobank.beneficiary.util.AccountMaskingUtil.mask(entity.getAccountNumber());
        return BeneficiaryResponse.builder()
            .beneficiaryId(entity.getPublicId())
            .customerId(entity.getCustomerId())
            .name(entity.getName())
            .nickname(entity.getNickname())
            .maskedAccountNumber(masked)
            .accountNumber(masked)
            .ifscCode(entity.getIfscCode())
            .bankName(entity.getBankName())
            .branchName(entity.getBranchName())
            .beneficiaryType(entity.getBeneficiaryType())
            .upiId(entity.getUpiId())
            .email(entity.getEmail())
            .phoneNumber(entity.getPhoneNumber())
            .maxLimit(entity.getMaxLimit())
            .status(entity.getStatus())
            .coolingPeriodEndsAt(entity.getCoolingPeriodEndsAt())
            .coolingPeriodActive(entity.isCoolingPeriodActive())
            .createdAt(entity.getCreatedAt())
            .build();
    }

    public BeneficiaryStatusResponse toStatusResponse(Beneficiary entity) {
        if (entity == null) {
            return null;
        }

        boolean coolingActive = entity.isCoolingPeriodActive();
        Long remainingSeconds = null;
        if (coolingActive && entity.getCoolingPeriodEndsAt() != null) {
            remainingSeconds = Math.max(0, Duration.between(LocalDateTime.now(), entity.getCoolingPeriodEndsAt()).getSeconds());
        }

        boolean eligibleForTransfer = entity.getStatus() == BeneficiaryStatus.ACTIVE && !coolingActive;

        return BeneficiaryStatusResponse.builder()
            .beneficiaryId(entity.getPublicId())
            .status(entity.getStatus())
            .coolingPeriodActive(coolingActive)
            .coolingPeriodEndsAt(entity.getCoolingPeriodEndsAt())
            .coolingPeriodRemainingSeconds(remainingSeconds)
            .eligibleForTransfer(eligibleForTransfer)
            .build();
    }

    public IfscDetailsResponse toIfscResponse(IfscDirectory directory) {
        if (directory == null) {
            return null;
        }

        return IfscDetailsResponse.builder()
            .ifscCode(directory.getIfscCode())
            .bankName(directory.getBankName())
            .branchName(directory.getBranchName())
            .address(directory.getAddress())
            .city(directory.getCity())
            .state(directory.getState())
            .status(directory.getStatus())
            .build();
    }

    private String maskAccountNumber(String accountNumber) {
        if (accountNumber == null || accountNumber.length() < 4) {
            return "****";
        }
        int len = accountNumber.length();
        String last4 = accountNumber.substring(len - 4);
        return "*".repeat(Math.max(0, len - 4)) + last4;
    }
}
