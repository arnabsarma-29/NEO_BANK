package com.neobank.beneficiary.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(
    name = "beneficiaries",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_customer_beneficiary_acc",
            columnNames = {"customer_id", "account_number", "ifsc_code"}
        )
    }
)
@Getter
@Setter
@NoArgsConstructor
public class Beneficiary extends BaseEntity {

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "nickname", length = 50)
    private String nickname;

    @Column(name = "account_number", length = 50)
    private String accountNumber;

    @Column(name = "ifsc_code", length = 20)
    private String ifscCode;

    @Column(name = "bank_name", length = 100)
    private String bankName;

    @Column(name = "branch_name", length = 100)
    private String branchName;

    @Enumerated(EnumType.STRING)
    @Column(name = "beneficiary_type", nullable = false, length = 30)
    private BeneficiaryType beneficiaryType = BeneficiaryType.OTHER_BANK;

    @Column(name = "upi_id", length = 100)
    private String upiId;

    @Column(name = "email", length = 100)
    private String email;

    @Column(name = "phone_number", length = 20)
    private String phoneNumber;

    @Column(name = "max_limit", precision = 15, scale = 2)
    private BigDecimal maxLimit;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private BeneficiaryStatus status = BeneficiaryStatus.PENDING;

    @Column(name = "cooling_period_ends_at")
    private LocalDateTime coolingPeriodEndsAt;

    public boolean isCoolingPeriodActive() {
        return coolingPeriodEndsAt != null && LocalDateTime.now().isBefore(coolingPeriodEndsAt);
    }
}
