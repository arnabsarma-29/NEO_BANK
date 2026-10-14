package com.neobank.beneficiary.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransferEligibilityResponse {

    private UUID beneficiaryId;
    private boolean eligible;
    private String reason;
}
