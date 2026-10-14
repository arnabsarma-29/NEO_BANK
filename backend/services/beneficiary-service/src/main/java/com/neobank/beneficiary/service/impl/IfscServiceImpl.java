package com.neobank.beneficiary.service.impl;

import com.neobank.beneficiary.dto.IfscDetailsResponse;
import com.neobank.beneficiary.dto.IfscRegistrationRequest;
import com.neobank.beneficiary.entity.IfscDirectory;
import com.neobank.beneficiary.exception.ApiException;
import com.neobank.beneficiary.mapper.BeneficiaryMapper;
import com.neobank.beneficiary.repository.IfscDirectoryRepository;
import com.neobank.beneficiary.service.IfscService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class IfscServiceImpl implements IfscService {

    private static final Pattern IFSC_PATTERN = Pattern.compile("^[A-Z]{4}0[A-Z0-9]{6}$");

    private final IfscDirectoryRepository ifscDirectoryRepository;
    private final BeneficiaryMapper beneficiaryMapper;

    @Override
    @Transactional(readOnly = true)
    public IfscDetailsResponse getIfscDetails(String ifscCode) {
        IfscDirectory entity = validateAndGet(ifscCode);
        return beneficiaryMapper.toIfscResponse(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public List<IfscDetailsResponse> searchIfsc(String query) {
        if (query == null || query.trim().length() < 2) {
            return List.of();
        }
        return ifscDirectoryRepository.searchByQuery(query.trim())
            .stream()
            .map(beneficiaryMapper::toIfscResponse)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<IfscDetailsResponse> getAllIfsc() {
        return ifscDirectoryRepository.findAll()
            .stream()
            .map(beneficiaryMapper::toIfscResponse)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public IfscDirectory validateAndGet(String ifscCode) {
        if (ifscCode == null || ifscCode.isBlank()) {
            throw ApiException.badRequest("IFSC code is required");
        }

        String normalizedCode = ifscCode.trim().toUpperCase();
        if (!IFSC_PATTERN.matcher(normalizedCode).matches()) {
            throw ApiException.badRequest(
                "Invalid IFSC code format: " + ifscCode + ". Must be 4 uppercase letters, 0, and 6 alphanumeric characters (e.g. SBIN0001234)."
            );
        }

        return ifscDirectoryRepository.findByIfscCodeIgnoreCase(normalizedCode)
            .orElseThrow(() -> ApiException.badRequest(
                "IFSC code not found in directory: " + normalizedCode + ". Please enter a valid IFSC code or register it first."
            ));
    }

    @Override
    @Transactional
    public IfscDetailsResponse registerIfsc(IfscRegistrationRequest request) {
        if (request.getIfscCode() == null || request.getIfscCode().isBlank()) {
            throw ApiException.badRequest("IFSC code is required");
        }

        String normalizedCode = request.getIfscCode().trim().toUpperCase();
        if (!IFSC_PATTERN.matcher(normalizedCode).matches()) {
            throw ApiException.badRequest(
                "Invalid IFSC code format: " + request.getIfscCode() + ". Must be 4 uppercase letters, 0, and 6 alphanumeric characters (e.g. SBIN0001234)."
            );
        }

        IfscDirectory directory = ifscDirectoryRepository.findByIfscCodeIgnoreCase(normalizedCode)
            .orElseGet(IfscDirectory::new);

        directory.setIfscCode(normalizedCode);
        directory.setBankName(request.getBankName().trim());
        directory.setBranchName(request.getBranchName() != null ? request.getBranchName().trim() : "Main Branch");
        directory.setAddress(request.getAddress() != null ? request.getAddress().trim() : null);
        directory.setCity(request.getCity() != null ? request.getCity().trim() : null);
        directory.setState(request.getState() != null ? request.getState().trim() : null);
        directory.setStatus("ACTIVE");

        IfscDirectory saved = ifscDirectoryRepository.save(directory);
        log.info("Registered/updated IFSC code {} for bank '{}'", saved.getIfscCode(), saved.getBankName());
        return beneficiaryMapper.toIfscResponse(saved);
    }

    @Override
    @Transactional
    public IfscDirectory getOrCreateIfsc(String ifscCode, String fallbackBankName, String fallbackBranchName) {
        if (ifscCode == null || ifscCode.isBlank()) {
            throw ApiException.badRequest("IFSC code is required");
        }

        String normalizedCode = ifscCode.trim().toUpperCase();
        if (!IFSC_PATTERN.matcher(normalizedCode).matches()) {
            throw ApiException.badRequest(
                "Invalid IFSC code format: " + ifscCode + ". Must be 4 uppercase letters, 0, and 6 alphanumeric characters (e.g. SBIN0001234)."
            );
        }

        return ifscDirectoryRepository.findByIfscCodeIgnoreCase(normalizedCode)
            .orElseGet(() -> {
                if (fallbackBankName != null && !fallbackBankName.isBlank()) {
                    IfscDirectory newEntry = new IfscDirectory();
                    newEntry.setIfscCode(normalizedCode);
                    newEntry.setBankName(fallbackBankName.trim());
                    newEntry.setBranchName(fallbackBranchName != null && !fallbackBranchName.isBlank()
                        ? fallbackBranchName.trim()
                        : "Main Branch");
                    newEntry.setStatus("ACTIVE");
                    IfscDirectory saved = ifscDirectoryRepository.save(newEntry);
                    log.info("Dynamically registered IFSC code {} for bank '{}'", normalizedCode, fallbackBankName);
                    return saved;
                }
                throw ApiException.badRequest(
                    "IFSC code not found in directory: " + normalizedCode + ". Please provide bankName in the request or register the IFSC code first."
                );
            });
    }
}
