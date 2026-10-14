package com.neobank.beneficiary.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = BeneficiaryRequestValidator.class)
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidBeneficiary {

    String message() default "Invalid beneficiary request details";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
