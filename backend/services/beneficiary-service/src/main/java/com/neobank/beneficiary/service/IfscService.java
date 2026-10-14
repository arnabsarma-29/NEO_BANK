package com.neobank.beneficiary.service;

import com.neobank.beneficiary.dto.IfscDetailsResponse;
import com.neobank.beneficiary.dto.IfscRegistrationRequest;
import com.neobank.beneficiary.entity.IfscDirectory;

import java.util.List;

public interface IfscService {

    IfscDetailsResponse getIfscDetails(String ifscCode);

    List<IfscDetailsResponse> searchIfsc(String query);

    List<IfscDetailsResponse> getAllIfsc();

    IfscDirectory validateAndGet(String ifscCode);

    IfscDetailsResponse registerIfsc(IfscRegistrationRequest request);

    IfscDirectory getOrCreateIfsc(String ifscCode, String fallbackBankName, String fallbackBranchName);
}

