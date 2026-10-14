package com.neobank.beneficiary.service.impl;

import com.neobank.beneficiary.entity.Beneficiary;
import com.neobank.beneficiary.entity.BeneficiaryAuditLog;
import com.neobank.beneficiary.repository.BeneficiaryAuditLogRepository;
import com.neobank.beneficiary.service.BeneficiaryAuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BeneficiaryAuditServiceImpl implements BeneficiaryAuditService {

    private final BeneficiaryAuditLogRepository auditLogRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${neobank.kafka-topics.audit:audit.events}")
    private String auditTopic;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logCreation(Beneficiary beneficiary, String customerId, String actor, String clientIp) {
        String maskedAcc = com.neobank.beneficiary.util.AccountMaskingUtil.mask(beneficiary.getAccountNumber());
        String details = String.format(
            "Created beneficiary '%s' (Account: %s, IFSC: %s, Bank: %s, Type: %s) in PENDING state.",
            beneficiary.getName(), maskedAcc, beneficiary.getIfscCode(),
            beneficiary.getBankName(), beneficiary.getBeneficiaryType()
        );

        BeneficiaryAuditLog logEntry = new BeneficiaryAuditLog(
            beneficiary.getId(),
            beneficiary.getPublicId(),
            customerId,
            "BENEFICIARY_CREATED",
            null,
            beneficiary.getStatus().name(),
            details,
            actor != null ? actor : customerId,
            clientIp
        );
        auditLogRepository.save(logEntry);
        log.info("Audit log persisted: [action=BENEFICIARY_CREATED, beneficiaryId={}, customerId={}]",
            beneficiary.getPublicId(), customerId);

        publishKafkaAuditEvent("BENEFICIARY_CREATED", beneficiary.getPublicId(), customerId, details);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logStatusChange(Beneficiary beneficiary, String oldStatus, String newStatus, String actor, String clientIp) {
        String details = String.format(
            "Beneficiary '%s' status changed from %s to %s.",
            beneficiary.getName(), oldStatus, newStatus
        );

        BeneficiaryAuditLog logEntry = new BeneficiaryAuditLog(
            beneficiary.getId(),
            beneficiary.getPublicId(),
            beneficiary.getCustomerId(),
            "BENEFICIARY_STATUS_CHANGED",
            oldStatus,
            newStatus,
            details,
            actor != null ? actor : beneficiary.getCustomerId(),
            clientIp
        );
        auditLogRepository.save(logEntry);
        log.info("Audit log persisted: [action=BENEFICIARY_STATUS_CHANGED, beneficiaryId={}, old={}, new={}]",
            beneficiary.getPublicId(), oldStatus, newStatus);

        publishKafkaAuditEvent("BENEFICIARY_STATUS_CHANGED", beneficiary.getPublicId(), beneficiary.getCustomerId(), details);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logDeletion(UUID beneficiaryPublicId, String customerId, String oldStatus, String actor, String clientIp) {
        String details = "Beneficiary marked as INACTIVE / DELETED.";

        BeneficiaryAuditLog logEntry = new BeneficiaryAuditLog(
            null,
            beneficiaryPublicId,
            customerId,
            "BENEFICIARY_DELETED",
            oldStatus != null ? oldStatus : "ACTIVE",
            "INACTIVE",
            details,
            actor != null ? actor : customerId,
            clientIp
        );
        auditLogRepository.save(logEntry);
        log.info("Audit log persisted: [action=BENEFICIARY_DELETED, beneficiaryId={}]", beneficiaryPublicId);

        publishKafkaAuditEvent("BENEFICIARY_DELETED", beneficiaryPublicId, customerId, details);
    }

    private void publishKafkaAuditEvent(String eventType, UUID beneficiaryId, String customerId, String details) {
        try {
            Map<String, Object> event = Map.of(
                "eventType", eventType,
                "service", "beneficiary-service",
                "customerId", customerId != null ? customerId : "",
                "beneficiaryId", beneficiaryId != null ? beneficiaryId.toString() : "",
                "details", details,
                "timestamp", Instant.now().toString()
            );
            kafkaTemplate.send(auditTopic, customerId, event);
        } catch (Exception ex) {
            log.warn("Could not publish audit event to Kafka topic {}: {}", auditTopic, ex.getMessage());
        }
    }
}
