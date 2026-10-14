package com.neobank.beneficiary.service;

import com.neobank.beneficiary.entity.Beneficiary;
import com.neobank.beneficiary.entity.BeneficiaryAuditLog;
import com.neobank.beneficiary.entity.BeneficiaryStatus;
import com.neobank.beneficiary.entity.BeneficiaryType;
import com.neobank.beneficiary.repository.BeneficiaryAuditLogRepository;
import com.neobank.beneficiary.service.impl.BeneficiaryAuditServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BeneficiaryAuditServiceImplTest {

    @Mock
    private BeneficiaryAuditLogRepository auditLogRepository;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    private BeneficiaryAuditServiceImpl auditService;

    @BeforeEach
    void setUp() {
        auditService = new BeneficiaryAuditServiceImpl(auditLogRepository, kafkaTemplate);
        ReflectionTestUtils.setField(auditService, "auditTopic", "audit.events");
    }

    @Test
    @DisplayName("logCreation persists audit log and masks raw account number in details")
    void logCreationMasksAccountNumber() {
        Beneficiary b = new Beneficiary();
        b.setName("Amit Kumar");
        b.setAccountNumber("123456789012");
        b.setIfscCode("SBIN0001234");
        b.setBankName("SBI");
        b.setBeneficiaryType(BeneficiaryType.OTHER_BANK);
        b.setStatus(BeneficiaryStatus.PENDING);
        ReflectionTestUtils.setField(b, "publicId", UUID.randomUUID());

        auditService.logCreation(b, "CUST-1001", "CUST-1001", "127.0.0.1");

        ArgumentCaptor<BeneficiaryAuditLog> captor = ArgumentCaptor.forClass(BeneficiaryAuditLog.class);
        verify(auditLogRepository).save(captor.capture());

        BeneficiaryAuditLog log = captor.getValue();
        assertThat(log.getAction()).isEqualTo("BENEFICIARY_CREATED");
        assertThat(log.getCustomerId()).isEqualTo("CUST-1001");
        assertThat(log.getDetails()).contains("********9012");
        assertThat(log.getDetails()).doesNotContain("123456789012");

        verify(kafkaTemplate).send(eq("audit.events"), eq("CUST-1001"), any());
    }

    @Test
    @DisplayName("logStatusChange records real old and new status")
    void logStatusChangeRecordsStatuses() {
        Beneficiary b = new Beneficiary();
        b.setName("Amit");
        b.setCustomerId("CUST-1001");
        ReflectionTestUtils.setField(b, "publicId", UUID.randomUUID());

        auditService.logStatusChange(b, "PENDING", "ACTIVE", "ADMIN", "127.0.0.1");

        ArgumentCaptor<BeneficiaryAuditLog> captor = ArgumentCaptor.forClass(BeneficiaryAuditLog.class);
        verify(auditLogRepository).save(captor.capture());

        BeneficiaryAuditLog log = captor.getValue();
        assertThat(log.getOldStatus()).isEqualTo("PENDING");
        assertThat(log.getNewStatus()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("logDeletion records passed old status and INACTIVE as new status")
    void logDeletionRecordsAccurateOldStatus() {
        UUID publicId = UUID.randomUUID();

        auditService.logDeletion(publicId, "CUST-1001", "ACTIVE", "CUST-1001", "127.0.0.1");

        ArgumentCaptor<BeneficiaryAuditLog> captor = ArgumentCaptor.forClass(BeneficiaryAuditLog.class);
        verify(auditLogRepository).save(captor.capture());

        BeneficiaryAuditLog log = captor.getValue();
        assertThat(log.getAction()).isEqualTo("BENEFICIARY_DELETED");
        assertThat(log.getOldStatus()).isEqualTo("ACTIVE");
        assertThat(log.getNewStatus()).isEqualTo("INACTIVE");
    }
}
