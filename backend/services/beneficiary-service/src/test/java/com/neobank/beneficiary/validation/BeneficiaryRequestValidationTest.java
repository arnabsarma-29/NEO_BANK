package com.neobank.beneficiary.validation;

import com.neobank.beneficiary.dto.BeneficiaryRequest;
import com.neobank.beneficiary.entity.BeneficiaryType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BeneficiaryRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Test
    @DisplayName("Valid account-based beneficiary request should have zero violations")
    void validAccountBasedBeneficiary() {
        BeneficiaryRequest request = BeneficiaryRequest.builder()
            .name("Rahul Sharma")
            .accountNumber("123456789012")
            .confirmAccountNumber("123456789012")
            .ifscCode("SBIN0001234")
            .bankName("State Bank of India")
            .beneficiaryType(BeneficiaryType.OTHER_BANK)
            .maxLimit(new BigDecimal("50000.00"))
            .build();

        Set<ConstraintViolation<BeneficiaryRequest>> violations = validator.validate(request);
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Valid UPI beneficiary request should have zero violations without account/IFSC")
    void validUpiBeneficiary() {
        BeneficiaryRequest request = BeneficiaryRequest.builder()
            .name("Rahul Sharma")
            .beneficiaryType(BeneficiaryType.UPI)
            .upiId("rahul@mockbank")
            .build();

        Set<ConstraintViolation<BeneficiaryRequest>> violations = validator.validate(request);
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Account number and confirmation account number mismatch should produce violation")
    void accountAndConfirmMismatch() {
        BeneficiaryRequest request = BeneficiaryRequest.builder()
            .name("Rahul Sharma")
            .accountNumber("123456789012")
            .confirmAccountNumber("987654321098")
            .ifscCode("SBIN0001234")
            .build();

        Set<ConstraintViolation<BeneficiaryRequest>> violations = validator.validate(request);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().contains("confirmAccountNumber") &&
            v.getMessage().contains("do not match"));
    }

    @Test
    @DisplayName("Invalid IFSC format should produce violation")
    void invalidIfscFormat() {
        BeneficiaryRequest request = BeneficiaryRequest.builder()
            .name("Rahul Sharma")
            .accountNumber("123456789012")
            .confirmAccountNumber("123456789012")
            .ifscCode("INVALID_IFSC")
            .build();

        Set<ConstraintViolation<BeneficiaryRequest>> violations = validator.validate(request);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().contains("ifscCode") &&
            v.getMessage().contains("Invalid IFSC format"));
    }

    @Test
    @DisplayName("Invalid UPI ID format should produce violation")
    void invalidUpiIdFormat() {
        BeneficiaryRequest request = BeneficiaryRequest.builder()
            .name("Rahul Sharma")
            .beneficiaryType(BeneficiaryType.UPI)
            .upiId("not-a-valid-upi")
            .build();

        Set<ConstraintViolation<BeneficiaryRequest>> violations = validator.validate(request);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().contains("upiId") &&
            v.getMessage().contains("Invalid UPI ID format"));
    }

    @Test
    @DisplayName("Blank beneficiary name should produce violation")
    void blankName() {
        BeneficiaryRequest request = BeneficiaryRequest.builder()
            .name("   ")
            .accountNumber("123456789012")
            .confirmAccountNumber("123456789012")
            .ifscCode("SBIN0001234")
            .build();

        Set<ConstraintViolation<BeneficiaryRequest>> violations = validator.validate(request);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().contains("name"));
    }

    @Test
    @DisplayName("Zero or negative maxLimit should produce violation")
    void invalidMaxLimit() {
        BeneficiaryRequest request = BeneficiaryRequest.builder()
            .name("Rahul Sharma")
            .accountNumber("123456789012")
            .confirmAccountNumber("123456789012")
            .ifscCode("SBIN0001234")
            .maxLimit(new BigDecimal("0.0"))
            .build();

        Set<ConstraintViolation<BeneficiaryRequest>> violations = validator.validate(request);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().contains("maxLimit"));
    }
}
