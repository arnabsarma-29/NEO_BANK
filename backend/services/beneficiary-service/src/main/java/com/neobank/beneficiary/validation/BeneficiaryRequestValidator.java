package com.neobank.beneficiary.validation;

import com.neobank.beneficiary.dto.BeneficiaryRequest;
import com.neobank.beneficiary.entity.BeneficiaryType;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Objects;
import java.util.regex.Pattern;

public class BeneficiaryRequestValidator implements ConstraintValidator<ValidBeneficiary, BeneficiaryRequest> {

    private static final Pattern IFSC_PATTERN = Pattern.compile("^[A-Z]{4}0[A-Z0-9]{6}$");
    private static final Pattern UPI_PATTERN = Pattern.compile("^[a-zA-Z0-9.\\-_]{2,256}@[a-zA-Z]{2,64}$");

    @Override
    public boolean isValid(BeneficiaryRequest request, ConstraintValidatorContext context) {
        if (request == null) {
            return true;
        }

        boolean valid = true;
        context.disableDefaultConstraintViolation();

        boolean isUpi = request.getBeneficiaryType() == BeneficiaryType.UPI;

        if (isUpi) {
            // UPI validation
            String upiId = request.getUpiId();
            if (upiId == null || upiId.isBlank()) {
                context.buildConstraintViolationWithTemplate("UPI ID is required for UPI beneficiaries")
                    .addPropertyNode("upiId")
                    .addConstraintViolation();
                valid = false;
            } else if (!UPI_PATTERN.matcher(upiId.trim()).matches()) {
                context.buildConstraintViolationWithTemplate("Invalid UPI ID format. Expected format: username@bankname")
                    .addPropertyNode("upiId")
                    .addConstraintViolation();
                valid = false;
            }
        } else {
            // Account-based validation (SAME_BANK, OTHER_BANK, or default)
            String accountNo = request.getAccountNumber();
            String confirmAccountNo = request.getConfirmAccountNumber();
            String ifsc = request.getIfscCode();

            if (accountNo == null || accountNo.isBlank()) {
                context.buildConstraintViolationWithTemplate("Account number is required")
                    .addPropertyNode("accountNumber")
                    .addConstraintViolation();
                valid = false;
            }

            if (confirmAccountNo == null || confirmAccountNo.isBlank()) {
                context.buildConstraintViolationWithTemplate("Confirmation account number is required")
                    .addPropertyNode("confirmAccountNumber")
                    .addConstraintViolation();
                valid = false;
            } else if (accountNo != null && !Objects.equals(accountNo.trim(), confirmAccountNo.trim())) {
                context.buildConstraintViolationWithTemplate("Account number and confirmation account number do not match.")
                    .addPropertyNode("confirmAccountNumber")
                    .addConstraintViolation();
                valid = false;
            }

            if (ifsc == null || ifsc.isBlank()) {
                context.buildConstraintViolationWithTemplate("IFSC code is required")
                    .addPropertyNode("ifscCode")
                    .addConstraintViolation();
                valid = false;
            } else if (!IFSC_PATTERN.matcher(ifsc.trim().toUpperCase()).matches()) {
                context.buildConstraintViolationWithTemplate(
                    "Invalid IFSC format. Must be 4 uppercase letters, followed by 0, and 6 alphanumeric characters (e.g., SBIN0001234)"
                )
                    .addPropertyNode("ifscCode")
                    .addConstraintViolation();
                valid = false;
            }
        }

        return valid;
    }
}
