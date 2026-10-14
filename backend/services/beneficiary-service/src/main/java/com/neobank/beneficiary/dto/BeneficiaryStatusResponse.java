package com.neobank.beneficiary.dto;

import com.neobank.beneficiary.entity.BeneficiaryStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BeneficiaryStatusResponse {

    private UUID beneficiaryId;
    private BeneficiaryStatus status;
    private boolean coolingPeriodActive;
    private LocalDateTime coolingPeriodEndsAt;
    private Long coolingPeriodRemainingSeconds;
    private boolean eligibleForTransfer;
}
