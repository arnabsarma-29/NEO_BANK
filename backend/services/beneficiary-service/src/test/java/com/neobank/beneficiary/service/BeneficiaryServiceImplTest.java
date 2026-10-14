package com.neobank.beneficiary.service;

import com.neobank.beneficiary.dto.*;
import com.neobank.beneficiary.entity.*;
import com.neobank.beneficiary.exception.ApiException;
import com.neobank.beneficiary.mapper.BeneficiaryMapper;
import com.neobank.beneficiary.repository.BeneficiaryRepository;
import com.neobank.beneficiary.service.impl.BeneficiaryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BeneficiaryServiceImplTest {

    @Mock
    private BeneficiaryRepository beneficiaryRepository;

    @Mock
    private IfscService ifscService;

    @Mock
    private BeneficiaryAuditService auditService;

    private BeneficiaryMapper beneficiaryMapper;
    private BeneficiaryServiceImpl beneficiaryService;

    private static final String CUST_ID = "CUST-1001";

    @BeforeEach
    void setUp() {
        beneficiaryMapper = new BeneficiaryMapper();
        beneficiaryService = new BeneficiaryServiceImpl(
            beneficiaryRepository,
            ifscService,
            auditService,
            beneficiaryMapper
        );
        ReflectionTestUtils.setField(beneficiaryService, "coolingPeriodMinutes", 30);
    }

    @Test
    @DisplayName("Create valid account-based beneficiary creates record in PENDING state and masks account in response")
    void createValidAccountBeneficiary() {
        BeneficiaryRequest request = BeneficiaryRequest.builder()
            .name("Amit Kumar")
            .accountNumber("123456789012")
            .confirmAccountNumber("123456789012")
            .ifscCode("SBIN0001234")
            .bankName("State Bank of India")
            .beneficiaryType(BeneficiaryType.OTHER_BANK)
            .build();

        IfscDirectory directory = new IfscDirectory();
        directory.setIfscCode("SBIN0001234");
        directory.setBankName("State Bank of India");
        directory.setBranchName("Main Branch");

        when(ifscService.validateAndGet("SBIN0001234")).thenReturn(directory);
        when(beneficiaryRepository.existsByCustomerIdAndAccountNumberAndIfscCodeAndStatusIn(
            eq(CUST_ID), eq("123456789012"), eq("SBIN0001234"), any()
        )).thenReturn(false);

        Beneficiary saved = new Beneficiary();
        saved.setCustomerId(CUST_ID);
        saved.setName("Amit Kumar");
        saved.setAccountNumber("123456789012");
        saved.setIfscCode("SBIN0001234");
        saved.setBankName("State Bank of India");
        saved.setStatus(BeneficiaryStatus.PENDING);
        saved.setCoolingPeriodEndsAt(LocalDateTime.now().plusMinutes(30));
        UUID publicId = UUID.randomUUID();
        ReflectionTestUtils.setField(saved, "publicId", publicId);

        when(beneficiaryRepository.save(any(Beneficiary.class))).thenReturn(saved);

        BeneficiaryResponse response = beneficiaryService.createBeneficiary(request, CUST_ID, CUST_ID, "127.0.0.1");

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(BeneficiaryStatus.PENDING);
        assertThat(response.getMaskedAccountNumber()).isEqualTo("********9012");
        // Verify raw account number is NOT returned in plain text
        assertThat(response.getAccountNumber()).isEqualTo("********9012");
        assertThat(response.getCoolingPeriodEndsAt()).isNotNull();

        verify(ifscService).validateAndGet("SBIN0001234");
        verify(auditService).logCreation(eq(saved), eq(CUST_ID), eq(CUST_ID), eq("127.0.0.1"));
    }

    @Test
    @DisplayName("Create beneficiary with account number and confirmation mismatch throws 400 Bad Request")
    void createBeneficiaryAccountMismatchThrowsBadRequest() {
        BeneficiaryRequest request = BeneficiaryRequest.builder()
            .name("Amit Kumar")
            .accountNumber("123456789012")
            .confirmAccountNumber("987654321098")
            .ifscCode("SBIN0001234")
            .build();

        assertThatThrownBy(() -> beneficiaryService.createBeneficiary(request, CUST_ID, CUST_ID, "127.0.0.1"))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> {
                ApiException apiEx = (ApiException) ex;
                assertThat(apiEx.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(apiEx.getBody().getDetail()).contains("do not match");
            });

        verify(beneficiaryRepository, never()).save(any());
    }

    @Test
    @DisplayName("Create beneficiary rejects duplicate active or pending account with 409 Conflict")
    void createBeneficiaryRejectsDuplicateAccount() {
        BeneficiaryRequest request = BeneficiaryRequest.builder()
            .name("Amit Kumar")
            .accountNumber("123456789012")
            .confirmAccountNumber("123456789012")
            .ifscCode("SBIN0001234")
            .build();

        IfscDirectory directory = new IfscDirectory();
        directory.setIfscCode("SBIN0001234");
        directory.setBankName("SBI");

        when(ifscService.validateAndGet("SBIN0001234")).thenReturn(directory);
        when(beneficiaryRepository.existsByCustomerIdAndAccountNumberAndIfscCodeAndStatusIn(
            eq(CUST_ID), eq("123456789012"), eq("SBIN0001234"), any()
        )).thenReturn(true);

        assertThatThrownBy(() -> beneficiaryService.createBeneficiary(request, CUST_ID, CUST_ID, "127.0.0.1"))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> {
                ApiException apiEx = (ApiException) ex;
                assertThat(apiEx.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(apiEx.getBody().getDetail()).contains("This beneficiary already exists");
            });

        verify(beneficiaryRepository, never()).save(any());
    }

    @Test
    @DisplayName("Create beneficiary with unknown IFSC throws 400 and never saves beneficiary")
    void createBeneficiaryUnknownIfscThrowsBadRequest() {
        BeneficiaryRequest request = BeneficiaryRequest.builder()
            .name("Amit Kumar")
            .accountNumber("123456789012")
            .confirmAccountNumber("123456789012")
            .ifscCode("ZZZZ0009999")
            .build();

        when(ifscService.validateAndGet("ZZZZ0009999"))
            .thenThrow(ApiException.badRequest("IFSC code not found in directory: ZZZZ0009999"));

        assertThatThrownBy(() -> beneficiaryService.createBeneficiary(request, CUST_ID, CUST_ID, "127.0.0.1"))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> {
                ApiException apiEx = (ApiException) ex;
                assertThat(apiEx.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(apiEx.getBody().getDetail()).contains("IFSC code not found in directory");
            });

        verify(beneficiaryRepository, never()).save(any());
    }

    @Test
    @DisplayName("Create valid UPI beneficiary succeeds without account/IFSC")
    void createValidUpiBeneficiary() {
        BeneficiaryRequest request = BeneficiaryRequest.builder()
            .name("Priya Patel")
            .beneficiaryType(BeneficiaryType.UPI)
            .upiId("priya@mockbank")
            .build();

        when(beneficiaryRepository.existsByCustomerIdAndUpiIdAndStatusIn(
            eq(CUST_ID), eq("priya@mockbank"), any()
        )).thenReturn(false);

        Beneficiary saved = new Beneficiary();
        saved.setCustomerId(CUST_ID);
        saved.setName("Priya Patel");
        saved.setBeneficiaryType(BeneficiaryType.UPI);
        saved.setUpiId("priya@mockbank");
        saved.setStatus(BeneficiaryStatus.PENDING);
        saved.setCoolingPeriodEndsAt(LocalDateTime.now().plusMinutes(30));
        UUID publicId = UUID.randomUUID();
        ReflectionTestUtils.setField(saved, "publicId", publicId);

        when(beneficiaryRepository.save(any(Beneficiary.class))).thenReturn(saved);

        BeneficiaryResponse response = beneficiaryService.createBeneficiary(request, CUST_ID, CUST_ID, "127.0.0.1");

        assertThat(response).isNotNull();
        assertThat(response.getBeneficiaryType()).isEqualTo(BeneficiaryType.UPI);
        assertThat(response.getUpiId()).isEqualTo("priya@mockbank");
        assertThat(response.getStatus()).isEqualTo(BeneficiaryStatus.PENDING);
        verify(ifscService, never()).validateAndGet(any());
    }

    @Test
    @DisplayName("getBeneficiaries strictly scopes to authenticated customer and never calls findAll()")
    void getBeneficiariesStrictlyScopesToCustomer() {
        Beneficiary b = new Beneficiary();
        b.setCustomerId(CUST_ID);
        b.setName("Test Ben");
        b.setStatus(BeneficiaryStatus.ACTIVE);
        ReflectionTestUtils.setField(b, "publicId", UUID.randomUUID());

        when(beneficiaryRepository.findByCustomerId(CUST_ID)).thenReturn(List.of(b));

        List<BeneficiaryResponse> list = beneficiaryService.getBeneficiaries(CUST_ID, null);

        assertThat(list).hasSize(1);
        verify(beneficiaryRepository).findByCustomerId(CUST_ID);
        verify(beneficiaryRepository, never()).findAll();
    }

    @Test
    @DisplayName("getBeneficiary throws 404 when beneficiary belongs to another customer")
    void getBeneficiaryThrowsNotFoundForOtherCustomer() {
        UUID benId = UUID.randomUUID();
        when(beneficiaryRepository.findByPublicIdAndCustomerId(benId, CUST_ID))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> beneficiaryService.getBeneficiary(benId, CUST_ID))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> {
                ApiException apiEx = (ApiException) ex;
                assertThat(apiEx.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            });
    }

    @Test
    @DisplayName("checkTransferEligibility returns not eligible when beneficiary is PENDING")
    void checkTransferEligibilityForPendingBeneficiary() {
        UUID benId = UUID.randomUUID();
        Beneficiary b = new Beneficiary();
        b.setStatus(BeneficiaryStatus.PENDING);
        ReflectionTestUtils.setField(b, "publicId", benId);

        when(beneficiaryRepository.findByPublicId(benId)).thenReturn(Optional.of(b));

        TransferEligibilityResponse response = beneficiaryService.checkTransferEligibility(benId);

        assertThat(response.isEligible()).isFalse();
        assertThat(response.getReason()).isEqualTo("BENEFICIARY_NOT_ACTIVE");
    }

    @Test
    @DisplayName("checkTransferEligibility returns not eligible when cooling period is active")
    void checkTransferEligibilityDuringCoolingPeriod() {
        UUID benId = UUID.randomUUID();
        Beneficiary b = new Beneficiary();
        b.setStatus(BeneficiaryStatus.ACTIVE);
        b.setCoolingPeriodEndsAt(LocalDateTime.now().plusMinutes(15));
        ReflectionTestUtils.setField(b, "publicId", benId);

        when(beneficiaryRepository.findByPublicId(benId)).thenReturn(Optional.of(b));

        TransferEligibilityResponse response = beneficiaryService.checkTransferEligibility(benId);

        assertThat(response.isEligible()).isFalse();
        assertThat(response.getReason()).isEqualTo("BENEFICIARY_IN_COOLING_PERIOD");
    }

    @Test
    @DisplayName("checkTransferEligibility returns eligible when ACTIVE and cooling period has expired")
    void checkTransferEligibilityWhenActiveAndExpiredCooling() {
        UUID benId = UUID.randomUUID();
        Beneficiary b = new Beneficiary();
        b.setStatus(BeneficiaryStatus.ACTIVE);
        b.setCoolingPeriodEndsAt(LocalDateTime.now().minusMinutes(5));
        ReflectionTestUtils.setField(b, "publicId", benId);

        when(beneficiaryRepository.findByPublicId(benId)).thenReturn(Optional.of(b));

        TransferEligibilityResponse response = beneficiaryService.checkTransferEligibility(benId);

        assertThat(response.isEligible()).isTrue();
        assertThat(response.getReason()).isNull();
    }

    @Test
    @DisplayName("deleteBeneficiary soft-deletes to INACTIVE and logs audit")
    void deleteBeneficiarySoftDeletesAndAudits() {
        UUID benId = UUID.randomUUID();
        Beneficiary b = new Beneficiary();
        b.setCustomerId(CUST_ID);
        b.setStatus(BeneficiaryStatus.ACTIVE);
        ReflectionTestUtils.setField(b, "publicId", benId);

        when(beneficiaryRepository.findByPublicIdAndCustomerId(benId, CUST_ID))
            .thenReturn(Optional.of(b));
        when(beneficiaryRepository.save(any(Beneficiary.class))).thenReturn(b);

        BeneficiaryResponse response = beneficiaryService.deleteBeneficiary(benId, CUST_ID, CUST_ID, "127.0.0.1");

        assertThat(response.getStatus()).isEqualTo(BeneficiaryStatus.INACTIVE);
        verify(auditService).logStatusChange(any(), eq("ACTIVE"), eq("INACTIVE"), eq(CUST_ID), eq("127.0.0.1"));
        verify(auditService).logDeletion(eq(benId), eq(CUST_ID), eq("ACTIVE"), eq(CUST_ID), eq("127.0.0.1"));
    }
}
