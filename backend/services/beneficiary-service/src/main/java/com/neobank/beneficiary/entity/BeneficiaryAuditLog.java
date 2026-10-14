package com.neobank.beneficiary.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "beneficiary_audit_logs")
@Getter
@Setter
@NoArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class BeneficiaryAuditLog {
    @Id
    @GeneratedValue(strategy =  GenerationType.UUID)
    private UUID  id;

    @Column(name = "beneficiary_id")
    private Long beneficiaryId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "beneficiary_public_id", columnDefinition = "char(36)", length = 36)
    private UUID beneficiaryPublicId;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    @Column(name = "action", nullable = false, length = 50)
    private String action;

    @Column(name = "old_status", length = 30)
    private String oldStatus;

    @Column(name = "new_status", length = 30)
    private String newStatus;

    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    @Column(name = "performed_by", length = 64)
    private String performedBy;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @CreatedDate
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    public BeneficiaryAuditLog(Long beneficiaryId, UUID beneficiaryPublicId, String customerId,
                               String action, String oldStatus, String newStatus,
                               String details, String performedBy, String ipAddress) {
        this.beneficiaryId = beneficiaryId;
        this.beneficiaryPublicId = beneficiaryPublicId;
        this.customerId = customerId;
        this.action = action;
        this.oldStatus = oldStatus;
        this.newStatus = newStatus;
        this.details = details;
        this.performedBy = performedBy;
        this.ipAddress = ipAddress;
        this.createdAt = LocalDateTime.now();
    }
}
