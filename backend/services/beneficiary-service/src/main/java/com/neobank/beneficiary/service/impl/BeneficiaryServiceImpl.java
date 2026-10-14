package com.neobank.beneficiary.service.impl;

import com.neobank.beneficiary.dto.*;
import com.neobank.beneficiary.entity.*;
import com.neobank.beneficiary.exception.ApiException;
import com.neobank.beneficiary.mapper.BeneficiaryMapper;
import com.neobank.beneficiary.repository.BeneficiaryRepository;
import com.neobank.beneficiary.service.BeneficiaryAuditService;
import com.neobank.beneficiary.service.BeneficiaryService;
import com.neobank.beneficiary.service.IfscService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class BeneficiaryServiceImpl implements BeneficiaryService {

    private static final Pattern UPI_PATTERN = Pattern.compile("^[a-zA-Z0-9.\\-_]{2,256}@[a-zA-Z]{2,64}$");

    private final BeneficiaryRepository beneficiaryRepository;
    private final IfscService ifscService;
    private final BeneficiaryAuditService auditService;
    private final BeneficiaryMapper beneficiaryMapper;

    @Value("${neobank.beneficiary.cooling-period-minutes:30}")
    private int coolingPeriodMinutes;

    @Override
    public BeneficiaryResponse createBeneficiary(BeneficiaryRequest request, String customerId, String actor, String clientIp) {
        if (customerId == null || customerId.isBlank()) {
            throw ApiException.badRequest("Customer identity could not be verified from security context.");
        }
        String resolvedCustomerId = customerId.trim();

        if (request.getName() == null || request.getName().isBlank()) {
            throw ApiException.badRequest("Beneficiary name is required");
        }

        boolean isUpi = request.getBeneficiaryType() == BeneficiaryType.UPI;
        Beneficiary beneficiary = new Beneficiary();
        beneficiary.setCustomerId(resolvedCustomerId);
        beneficiary.setName(request.getName().trim());
        beneficiary.setNickname(request.getNickname() != null && !request.getNickname().isBlank() ? request.getNickname().trim() : null);
        beneficiary.setEmail(request.getEmail() != null && !request.getEmail().isBlank() ? request.getEmail().trim() : null);
        beneficiary.setPhoneNumber(request.getPhoneNumber() != null && !request.getPhoneNumber().isBlank() ? request.getPhoneNumber().trim() : null);
        beneficiary.setMaxLimit(request.getMaxLimit());

        List<BeneficiaryStatus> activeOrPending = List.of(BeneficiaryStatus.PENDING, BeneficiaryStatus.ACTIVE);

        if (isUpi) {
            // UPI Flow
            String upiId = request.getUpiId();
            if (upiId == null || upiId.isBlank() || !UPI_PATTERN.matcher(upiId.trim()).matches()) {
                throw ApiException.badRequest("Invalid UPI ID format. Expected format: username@bankname");
            }
            String normalizedUpi = upiId.trim();

            boolean existsUpi = beneficiaryRepository.existsByCustomerIdAndUpiIdAndStatusIn(
                resolvedCustomerId, normalizedUpi, activeOrPending
            );
            if (existsUpi) {
                throw ApiException.conflict("A beneficiary with this UPI ID already exists.");
            }

            beneficiary.setBeneficiaryType(BeneficiaryType.UPI);
            beneficiary.setUpiId(normalizedUpi);
            beneficiary.setBankName(request.getBankName() != null && !request.getBankName().isBlank() ? request.getBankName().trim() : "Mock UPI Bank");
            beneficiary.setBranchName("UPI Virtual Branch");
            beneficiary.setAccountNumber(null);
            beneficiary.setIfscCode(null);
        } else {
            // Account-based Flow (SAME_BANK, OTHER_BANK, or default)
            String rawAccount = request.getAccountNumber();
            String rawConfirm = request.getConfirmAccountNumber();

            if (rawAccount == null || rawAccount.isBlank()) {
                throw ApiException.badRequest("Account number is required");
            }
            if (rawConfirm == null || rawConfirm.isBlank()) {
                throw ApiException.badRequest("Confirmation account number is required");
            }

            String normalizedAccount = rawAccount.trim();
            String normalizedConfirm = rawConfirm.trim();

            if (!Objects.equals(normalizedAccount, normalizedConfirm)) {
                throw ApiException.badRequest("Account number and confirmation account number do not match.");
            }

            if (request.getIfscCode() == null || request.getIfscCode().isBlank()) {
                throw ApiException.badRequest("IFSC code is required");
            }

            // Strict IFSC directory lookup: rejects unknown or invalid IFSC codes
            IfscDirectory ifscDirectory = ifscService.validateAndGet(request.getIfscCode());

            String bankName = (request.getBankName() != null && !request.getBankName().isBlank())
                ? request.getBankName().trim()
                : ifscDirectory.getBankName();

            String branchName = (request.getBranchName() != null && !request.getBranchName().isBlank())
                ? request.getBranchName().trim()
                : ifscDirectory.getBranchName();

            // Duplicate prevention check for active/pending
            boolean existsAccount = beneficiaryRepository.existsByCustomerIdAndAccountNumberAndIfscCodeAndStatusIn(
                resolvedCustomerId, normalizedAccount, ifscDirectory.getIfscCode(), activeOrPending
            );
            if (existsAccount) {
                throw ApiException.conflict("This beneficiary already exists.");
            }

            beneficiary.setBeneficiaryType(
                request.getBeneficiaryType() != null ? request.getBeneficiaryType() : BeneficiaryType.OTHER_BANK
            );
            beneficiary.setAccountNumber(normalizedAccount);
            beneficiary.setIfscCode(ifscDirectory.getIfscCode());
            beneficiary.setBankName(bankName);
            beneficiary.setBranchName(branchName);
            beneficiary.setUpiId(request.getUpiId());
        }

        // Lifecycle: start as PENDING with cooling period placeholder
        beneficiary.setStatus(BeneficiaryStatus.PENDING);
        beneficiary.setCoolingPeriodEndsAt(LocalDateTime.now().plusMinutes(coolingPeriodMinutes));

        Beneficiary saved;
        try {
            saved = beneficiaryRepository.save(beneficiary);
        } catch (DataIntegrityViolationException ex) {
            log.warn("Database unique constraint violation during beneficiary creation: {}", ex.getMessage());
            throw ApiException.conflict("This beneficiary already exists.");
        }

        String effectiveActor = (actor != null && !actor.isBlank()) ? actor : resolvedCustomerId;
        auditService.logCreation(saved, resolvedCustomerId, effectiveActor, clientIp);

        log.info("Beneficiary created successfully [publicId={}, customerId={}, status={}]",
            saved.getPublicId(), resolvedCustomerId, saved.getStatus());

        return beneficiaryMapper.toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BeneficiaryResponse> getBeneficiaries(String customerId, BeneficiaryStatus status) {
        if (customerId == null || customerId.isBlank()) {
            throw ApiException.badRequest("Authenticated customer ID is required.");
        }

        String resolvedCustomerId = customerId.trim();
        List<Beneficiary> list;
        if (status != null) {
            list = beneficiaryRepository.findByCustomerIdAndStatus(resolvedCustomerId, status);
        } else {
            list = beneficiaryRepository.findByCustomerId(resolvedCustomerId);
        }

        return list.stream()
            .map(beneficiaryMapper::toResponse)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public BeneficiaryResponse getBeneficiary(UUID beneficiaryId, String customerId) {
        Beneficiary entity;
        if (customerId != null && !customerId.isBlank()) {
            entity = beneficiaryRepository.findByPublicIdAndCustomerId(beneficiaryId, customerId.trim())
                .orElseThrow(() -> ApiException.notFound("Beneficiary not found with ID: " + beneficiaryId));
        } else {
            // Internal service-to-service lookup
            entity = beneficiaryRepository.findByPublicId(beneficiaryId)
                .orElseThrow(() -> ApiException.notFound("Beneficiary not found with ID: " + beneficiaryId));
        }

        return beneficiaryMapper.toResponse(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public BeneficiaryStatusResponse getBeneficiaryStatus(UUID beneficiaryId, String customerId) {
        if (customerId == null || customerId.isBlank()) {
            throw ApiException.badRequest("Authenticated customer ID is required.");
        }

        Beneficiary entity = beneficiaryRepository.findByPublicIdAndCustomerId(beneficiaryId, customerId.trim())
            .orElseThrow(() -> ApiException.notFound("Beneficiary not found with ID: " + beneficiaryId));

        return beneficiaryMapper.toStatusResponse(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public ValidateBeneficiaryResponse validateBeneficiaryDetails(ValidateBeneficiaryRequest request, String customerId) {
        Map<String, String> fieldErrors = new HashMap<>();

        boolean isUpi = request.getBeneficiaryType() == BeneficiaryType.UPI;
        String bankName = null;
        String branchName = null;

        if (isUpi) {
            if (request.getUpiId() == null || request.getUpiId().isBlank()) {
                fieldErrors.put("upiId", "UPI ID is required");
            } else if (!UPI_PATTERN.matcher(request.getUpiId().trim()).matches()) {
                fieldErrors.put("upiId", "Invalid UPI ID format (expected user@mockbank)");
            }
        } else {
            String acc = request.getAccountNumber();
            String confirmAcc = request.getConfirmAccountNumber();

            if (acc == null || acc.isBlank()) {
                fieldErrors.put("accountNumber", "Account number is required");
            }
            if (confirmAcc == null || confirmAcc.isBlank()) {
                fieldErrors.put("confirmAccountNumber", "Confirmation account number is required");
            } else if (acc != null && !Objects.equals(acc.trim(), confirmAcc.trim())) {
                fieldErrors.put("confirmAccountNumber", "Account number and confirmation account number do not match");
            }

            if (request.getIfscCode() == null || request.getIfscCode().isBlank()) {
                fieldErrors.put("ifscCode", "IFSC code is required");
            } else {
                try {
                    // Strict directory lookup
                    IfscDirectory directory = ifscService.validateAndGet(request.getIfscCode());
                    bankName = (request.getBankName() != null && !request.getBankName().isBlank())
                        ? request.getBankName().trim()
                        : directory.getBankName();
                    branchName = directory.getBranchName();
                } catch (Exception e) {
                    fieldErrors.put("ifscCode", e.getMessage());
                }
            }
        }

        String resolvedCustId = (customerId != null && !customerId.isBlank()) ? customerId.trim() : null;

        if (fieldErrors.isEmpty() && resolvedCustId != null) {
            List<BeneficiaryStatus> activeOrPending = List.of(BeneficiaryStatus.PENDING, BeneficiaryStatus.ACTIVE);
            if (!isUpi && request.getAccountNumber() != null && request.getIfscCode() != null) {
                if (beneficiaryRepository.existsByCustomerIdAndAccountNumberAndIfscCodeAndStatusIn(
                    resolvedCustId, request.getAccountNumber().trim(), request.getIfscCode().trim().toUpperCase(), activeOrPending)) {
                    fieldErrors.put("accountNumber", "This beneficiary already exists for this account and IFSC.");
                }
            } else if (isUpi && request.getUpiId() != null) {
                if (beneficiaryRepository.existsByCustomerIdAndUpiIdAndStatusIn(
                    resolvedCustId, request.getUpiId().trim(), activeOrPending)) {
                    fieldErrors.put("upiId", "A beneficiary with this UPI ID already exists.");
                }
            }
        }

        boolean isValid = fieldErrors.isEmpty();
        return ValidateBeneficiaryResponse.builder()
            .valid(isValid)
            .bankName(bankName)
            .branchName(branchName)
            .message(isValid ? "Beneficiary details are valid." : "Validation failed. Please correct the highlighted errors.")
            .fieldErrors(fieldErrors)
            .build();
    }

    @Override
    public TransferEligibilityResponse checkTransferEligibility(UUID beneficiaryId) {
        Beneficiary beneficiary = beneficiaryRepository.findByPublicId(beneficiaryId)
            .orElseThrow(() -> ApiException.notFound("Beneficiary not found with ID: " + beneficiaryId));

        if (beneficiary.getStatus() != BeneficiaryStatus.ACTIVE) {
            return TransferEligibilityResponse.builder()
                .beneficiaryId(beneficiaryId)
                .eligible(false)
                .reason("BENEFICIARY_NOT_ACTIVE")
                .build();
        }

        if (beneficiary.isCoolingPeriodActive()) {
            return TransferEligibilityResponse.builder()
                .beneficiaryId(beneficiaryId)
                .eligible(false)
                .reason("BENEFICIARY_IN_COOLING_PERIOD")
                .build();
        }

        return TransferEligibilityResponse.builder()
            .beneficiaryId(beneficiaryId)
            .eligible(true)
            .reason("Beneficiary is eligible for transfer.")
            .build();
    }

    @Override
    @Transactional
    public BeneficiaryResponse deleteBeneficiary(UUID beneficiaryId, String customerId, String actor, String clientIp) {
        if (customerId == null || customerId.isBlank()) {
            throw ApiException.badRequest("Authenticated customer ID is required.");
        }

        Beneficiary beneficiary = beneficiaryRepository.findByPublicIdAndCustomerId(beneficiaryId, customerId.trim())
            .orElseThrow(() -> ApiException.notFound("Beneficiary not found with ID: " + beneficiaryId));

        BeneficiaryStatus oldStatus = beneficiary.getStatus();
        beneficiary.setStatus(BeneficiaryStatus.INACTIVE);
        Beneficiary saved = beneficiaryRepository.save(beneficiary);

        String effectiveActor = (actor != null && !actor.isBlank()) ? actor : customerId.trim();
        auditService.logStatusChange(saved, oldStatus.name(), BeneficiaryStatus.INACTIVE.name(), effectiveActor, clientIp);
        auditService.logDeletion(beneficiaryId, customerId.trim(), oldStatus.name(), effectiveActor, clientIp);

        return beneficiaryMapper.toResponse(saved);
    }
}
