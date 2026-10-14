package com.neobank.beneficiary.service.impl;

import com.neobank.beneficiary.dto.BeneficiaryResponse;
import com.neobank.beneficiary.entity.Beneficiary;
import com.neobank.beneficiary.entity.BeneficiaryStatus;
import com.neobank.beneficiary.exception.ApiException;
import com.neobank.beneficiary.mapper.BeneficiaryMapper;
import com.neobank.beneficiary.repository.BeneficiaryRepository;
import com.neobank.beneficiary.service.BeneficiaryActivationService;
import com.neobank.beneficiary.service.BeneficiaryAuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BeneficiaryActivationServiceImpl implements BeneficiaryActivationService {

    private final BeneficiaryRepository beneficiaryRepository;
    private final BeneficiaryAuditService auditService;
    private final BeneficiaryMapper beneficiaryMapper;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${neobank.kafka-topics.beneficiary-activated:beneficiary.activated}")
    private String beneficiaryActivatedTopic;

    @Value("${neobank.beneficiary.auto-activation-enabled:false}")
    private boolean autoActivationEnabled;

    @Override
    @Transactional
    public BeneficiaryResponse activateBeneficiary(UUID beneficiaryId, String actor, String clientIp) {
        Beneficiary beneficiary = beneficiaryRepository.findByPublicId(beneficiaryId)
            .orElseThrow(() -> ApiException.notFound("Beneficiary not found with ID: " + beneficiaryId));

        if (beneficiary.getStatus() == BeneficiaryStatus.ACTIVE) {
            log.info("Beneficiary {} is already ACTIVE. Returning current state idempotently.", beneficiaryId);
            return beneficiaryMapper.toResponse(beneficiary);
        }

        if (beneficiary.getStatus() != BeneficiaryStatus.PENDING) {
            throw ApiException.badRequest(
                "Cannot activate beneficiary with status '" + beneficiary.getStatus() + "'. Only PENDING beneficiaries can be activated."
            );
        }

        BeneficiaryStatus oldStatus = beneficiary.getStatus();
        beneficiary.setStatus(BeneficiaryStatus.ACTIVE);
        Beneficiary saved = beneficiaryRepository.save(beneficiary);

        // Audit log & status change
        auditService.logStatusChange(saved, oldStatus.name(), BeneficiaryStatus.ACTIVE.name(), actor, clientIp);

        // Publish Kafka event
        publishBeneficiaryActivatedEvent(saved);

        log.info("Beneficiary {} transitioned to ACTIVE state by actor '{}'", beneficiaryId, actor);
        return beneficiaryMapper.toResponse(saved);
    }

    @Override
    @Scheduled(cron = "${neobank.beneficiary.activation-job-cron:0 * * * * *}")
    @Transactional
    public void processPendingCooldownActivations() {
        if (!autoActivationEnabled) {
            log.debug("Beneficiary automatic cooldown activation is disabled by configuration (pending PO business decision).");
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        List<Beneficiary> pendingExpired = beneficiaryRepository
            .findByStatusAndCoolingPeriodEndsAtBefore(BeneficiaryStatus.PENDING, now);

        if (pendingExpired.isEmpty()) {
            return;
        }

        log.info("Found {} pending beneficiaries past their cooling period. Activating...", pendingExpired.size());
        for (Beneficiary beneficiary : pendingExpired) {
            try {
                beneficiary.setStatus(BeneficiaryStatus.ACTIVE);
                Beneficiary saved = beneficiaryRepository.save(beneficiary);

                auditService.logStatusChange(
                    saved, BeneficiaryStatus.PENDING.name(), BeneficiaryStatus.ACTIVE.name(),
                    "SYSTEM_SCHEDULER", "127.0.0.1"
                );

                publishBeneficiaryActivatedEvent(saved);
                log.info("Auto-activated beneficiary {} after cooling period", saved.getPublicId());
            } catch (Exception e) {
                log.error("Failed to auto-activate beneficiary {}: {}", beneficiary.getPublicId(), e.getMessage(), e);
            }
        }
    }

    private void publishBeneficiaryActivatedEvent(Beneficiary beneficiary) {
        try {
            String maskedAcc = com.neobank.beneficiary.util.AccountMaskingUtil.mask(beneficiary.getAccountNumber());
            Map<String, Object> event = new java.util.HashMap<>();
            event.put("eventType", "BENEFICIARY_ACTIVATED");
            event.put("beneficiaryId", beneficiary.getPublicId().toString());
            event.put("customerId", beneficiary.getCustomerId());
            event.put("name", beneficiary.getName());
            event.put("accountNumber", maskedAcc != null ? maskedAcc : "");
            event.put("maskedAccountNumber", maskedAcc != null ? maskedAcc : "");
            event.put("ifscCode", beneficiary.getIfscCode() != null ? beneficiary.getIfscCode() : "");
            event.put("activatedAt", Instant.now().toString());

            kafkaTemplate.send(beneficiaryActivatedTopic, beneficiary.getCustomerId(), event);
        } catch (Exception e) {
            log.warn("Could not publish event to Kafka topic {}: {}", beneficiaryActivatedTopic, e.getMessage());
        }
    }
}
