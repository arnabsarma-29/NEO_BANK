package com.neobank.beneficiary.controller;

import com.neobank.beneficiary.dto.*;
import com.neobank.beneficiary.entity.BeneficiaryStatus;
import com.neobank.beneficiary.mapper.ApiResponse;
import com.neobank.beneficiary.security.SecurityUtils;
import com.neobank.beneficiary.service.BeneficiaryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/beneficiaries")
@RequiredArgsConstructor
@Slf4j
public class BeneficiaryController {
    private final BeneficiaryService beneficiaryService;
    @PostMapping
    public ResponseEntity<ApiResponse<BeneficiaryResponse>> createBeneficiary(
        @Valid @RequestBody BeneficiaryRequest request,
        HttpServletRequest httpServletRequest
    ) {
        String customerId = SecurityUtils.getAuthenticatedCustomerId();
        String clientIp = extractClientIp(httpServletRequest);
        String actor = customerId;
        BeneficiaryResponse response = beneficiaryService.createBeneficiary(
            request, customerId, actor, clientIp
        );
        URI location = URI.create("/api/v1/beneficiaries/" + response.getBeneficiaryId());
        return Responses.created(location, "Beneficiary successfully created in PENDING state.", response);
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<BeneficiaryResponse>>> listBeneficiaries(
        @RequestParam(required = false) BeneficiaryStatus status
    ) {
        String customerId = SecurityUtils.getAuthenticatedCustomerId();
        List<BeneficiaryResponse> list = beneficiaryService.getBeneficiaries(customerId, status);
        return Responses.ok("Beneficiaries retrieved successfully.", list);
    }

    @GetMapping("/{beneficiaryId}")
    public ResponseEntity<ApiResponse<BeneficiaryResponse>> getBeneficiary(
        @PathVariable UUID beneficiaryId
    ) {
        String customerId = SecurityUtils.getAuthenticatedCustomerId();
        BeneficiaryResponse response = beneficiaryService.getBeneficiary(beneficiaryId, customerId);
        return Responses.ok("Beneficiary retrieved successfully.", response);
    }

    @GetMapping("/{beneficiaryId}/status")
    public ResponseEntity<ApiResponse<BeneficiaryStatusResponse>> getBeneficiaryStatus(
        @PathVariable UUID beneficiaryId
    ) {
        String customerId = SecurityUtils.getAuthenticatedCustomerId();
        BeneficiaryStatusResponse response = beneficiaryService.getBeneficiaryStatus(beneficiaryId, customerId);
        return Responses.ok("Beneficiary status retrieved successfully.", response);
    }

//    @PostMapping("/validate")
//    public ResponseEntity<ApiResponse<ValidateBeneficiaryResponse>> validateBeneficiary(
//        @Valid @RequestBody ValidateBeneficiaryRequest request
//    ) {
//        String customerId = SecurityUtils.getAuthenticatedCustomerId();
//        ValidateBeneficiaryResponse response = beneficiaryService.validateBeneficiaryDetails(request, customerId);
//        return Responses.ok("Beneficiary validation completed.", response);
//    }

    @DeleteMapping("/{beneficiaryId}")
    public ResponseEntity<ApiResponse<BeneficiaryResponse>> deleteBeneficiary(
        @PathVariable UUID beneficiaryId,
        HttpServletRequest httpServletRequest
    ) {
        String customerId = SecurityUtils.getAuthenticatedCustomerId();
        String clientIp = extractClientIp(httpServletRequest);
        String actor = customerId;

        BeneficiaryResponse response = beneficiaryService.deleteBeneficiary(
            beneficiaryId, customerId, actor, clientIp
        );
        return Responses.ok("Beneficiary successfully removed.", response);
    }

    private String extractClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
