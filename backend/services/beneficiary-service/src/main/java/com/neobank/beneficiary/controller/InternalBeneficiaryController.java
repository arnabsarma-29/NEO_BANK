package com.neobank.beneficiary.controller;

import com.neobank.beneficiary.dto.BeneficiaryResponse;
import com.neobank.beneficiary.dto.Responses;
import com.neobank.beneficiary.dto.TransferEligibilityResponse;
import com.neobank.beneficiary.mapper.ApiResponse;
import com.neobank.beneficiary.service.BeneficiaryActivationService;
import com.neobank.beneficiary.service.BeneficiaryService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/internal/beneficiaries")
@RequiredArgsConstructor
@Slf4j
//@PreAuthorize("hasAnyRole('INTERNAL_SERVICE', 'ADMIN')")
public class InternalBeneficiaryController {

    private final BeneficiaryService beneficiaryService;
    private final BeneficiaryActivationService beneficiaryActivationService;

    @GetMapping("/{beneficiaryId}")
    public ResponseEntity<ApiResponse<BeneficiaryResponse>> getBeneficiaryInternal(
        @PathVariable UUID beneficiaryId
    ) {
        BeneficiaryResponse response = beneficiaryService.getBeneficiary(beneficiaryId, null);
        return Responses.ok("Beneficiary retrieved successfully.", response);
    }

    @GetMapping("/{beneficiaryId}/transfer-eligibility")
    public ResponseEntity<ApiResponse<TransferEligibilityResponse>> checkTransferEligibility(
        @PathVariable UUID beneficiaryId
    ) {
        TransferEligibilityResponse response = beneficiaryService.checkTransferEligibility(beneficiaryId);
        return Responses.ok("Transfer eligibility evaluated.", response);
    }

    @PostMapping("/{beneficiaryId}/activate")
    public ResponseEntity<ApiResponse<BeneficiaryResponse>> activateBeneficiary(
        @PathVariable UUID beneficiaryId,
        @RequestParam(required = false, defaultValue = "INTERNAL_WORKER") String actor,
        HttpServletRequest request
    ) {
        String clientIp = request.getRemoteAddr();
        BeneficiaryResponse response = beneficiaryActivationService.activateBeneficiary(
            beneficiaryId, actor, clientIp
        );
        return Responses.ok("Beneficiary activated successfully.", response);
    }
}
