package com.neobank.beneficiary.service;

import com.neobank.beneficiary.dto.BeneficiaryResponse;
import com.neobank.beneficiary.entity.Beneficiary;
import com.neobank.beneficiary.entity.BeneficiaryStatus;
import com.neobank.beneficiary.exception.ApiException;
import com.neobank.beneficiary.mapper.BeneficiaryMapper;
import com.neobank.beneficiary.repository.BeneficiaryRepository;
import com.neobank.beneficiary.service.impl.BeneficiaryActivationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BeneficiaryActivationServiceImplTest {

    @Mock
    private BeneficiaryRepository beneficiaryRepository;

    @Mock
    private BeneficiaryAuditService auditService;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    private BeneficiaryMapper beneficiaryMapper;
    private BeneficiaryActivationServiceImpl activationService;

    @BeforeEach
    void setUp() {
        beneficiaryMapper = new BeneficiaryMapper();
        activationService = new BeneficiaryActivationServiceImpl(
            beneficiaryRepository,
            auditService,
            beneficiaryMapper,
            kafkaTemplate
        );
        ReflectionTestUtils.setField(activationService, "beneficiaryActivatedTopic", "beneficiary.activated");
        ReflectionTestUtils.setField(activationService, "autoActivationEnabled", false);
    }

    @Test
    @DisplayName("activateBeneficiary transitions PENDING beneficiary to ACTIVE, audits, and emits Kafka event with masked account")
    void activatePendingBeneficiarySucceeds() {
        UUID benId = UUID.randomUUID();
        Beneficiary beneficiary = new Beneficiary();
        beneficiary.setCustomerId("CUST-1001");
        beneficiary.setName("Amit");
        beneficiary.setAccountNumber("123456789012");
        beneficiary.setIfscCode("SBIN0001234");
        beneficiary.setStatus(BeneficiaryStatus.PENDING);
        ReflectionTestUtils.setField(beneficiary, "publicId", benId);

        when(beneficiaryRepository.findByPublicId(benId)).thenReturn(Optional.of(beneficiary));
        when(beneficiaryRepository.save(any(Beneficiary.class))).thenAnswer(invocation -> invocation.getArgument(0));

        BeneficiaryResponse response = activationService.activateBeneficiary(benId, "INTERNAL_WORKER", "127.0.0.1");

        assertThat(response.getStatus()).isEqualTo(BeneficiaryStatus.ACTIVE);
        verify(beneficiaryRepository).save(beneficiary);
        verify(auditService).logStatusChange(any(), eq("PENDING"), eq("ACTIVE"), eq("INTERNAL_WORKER"), eq("127.0.0.1"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> eventCaptor = ArgumentCaptor.forClass(Map.class);
        verify(kafkaTemplate).send(eq("beneficiary.activated"), eq("CUST-1001"), eventCaptor.capture());

        Map<String, Object> event = eventCaptor.getValue();
        assertThat(event.get("eventType")).isEqualTo("BENEFICIARY_ACTIVATED");
        // Verify account number in Kafka event is masked
        assertThat(event.get("accountNumber")).isEqualTo("********9012");
    }

    @Test
    @DisplayName("activateBeneficiary is idempotent for already ACTIVE beneficiary without duplicate audit or Kafka event")
    void activateAlreadyActiveBeneficiaryIsIdempotent() {
        UUID benId = UUID.randomUUID();
        Beneficiary beneficiary = new Beneficiary();
        beneficiary.setStatus(BeneficiaryStatus.ACTIVE);
        ReflectionTestUtils.setField(beneficiary, "publicId", benId);

        when(beneficiaryRepository.findByPublicId(benId)).thenReturn(Optional.of(beneficiary));

        BeneficiaryResponse response = activationService.activateBeneficiary(benId, "WORKER", "127.0.0.1");

        assertThat(response.getStatus()).isEqualTo(BeneficiaryStatus.ACTIVE);
        verify(beneficiaryRepository, never()).save(any());
        verify(auditService, never()).logStatusChange(any(), any(), any(), any(), any());
        verify(kafkaTemplate, never()).send(any(), any(), any());
    }

    @Test
    @DisplayName("activateBeneficiary rejects activation of INACTIVE beneficiary with 400 Bad Request")
    void activateInactiveBeneficiaryThrowsBadRequest() {
        UUID benId = UUID.randomUUID();
        Beneficiary beneficiary = new Beneficiary();
        beneficiary.setStatus(BeneficiaryStatus.INACTIVE);
        ReflectionTestUtils.setField(beneficiary, "publicId", benId);

        when(beneficiaryRepository.findByPublicId(benId)).thenReturn(Optional.of(beneficiary));

        assertThatThrownBy(() -> activationService.activateBeneficiary(benId, "WORKER", "127.0.0.1"))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> {
                ApiException apiEx = (ApiException) ex;
                assertThat(apiEx.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(apiEx.getBody().getDetail()).contains("Only PENDING beneficiaries can be activated");
            });

        verify(beneficiaryRepository, never()).save(any());
        verify(auditService, never()).logStatusChange(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("activateBeneficiary rejects activation of REJECTED beneficiary with 400 Bad Request")
    void activateRejectedBeneficiaryThrowsBadRequest() {
        UUID benId = UUID.randomUUID();
        Beneficiary beneficiary = new Beneficiary();
        beneficiary.setStatus(BeneficiaryStatus.REJECTED);
        ReflectionTestUtils.setField(beneficiary, "publicId", benId);

        when(beneficiaryRepository.findByPublicId(benId)).thenReturn(Optional.of(beneficiary));

        assertThatThrownBy(() -> activationService.activateBeneficiary(benId, "WORKER", "127.0.0.1"))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> {
                ApiException apiEx = (ApiException) ex;
                assertThat(apiEx.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(apiEx.getBody().getDetail()).contains("Only PENDING beneficiaries can be activated");
            });

        verify(beneficiaryRepository, never()).save(any());
    }

    @Test
    @DisplayName("processPendingCooldownActivations does nothing when auto-activation-enabled is false")
    void schedulerDoesNothingWhenDisabled() {
        ReflectionTestUtils.setField(activationService, "autoActivationEnabled", false);

        activationService.processPendingCooldownActivations();

        verify(beneficiaryRepository, never()).findByStatusAndCoolingPeriodEndsAtBefore(any(), any());
        verify(beneficiaryRepository, never()).save(any());
    }

    @Test
    @DisplayName("processPendingCooldownActivations auto-activates expired pending beneficiaries when enabled")
    void schedulerActivatesWhenEnabled() {
        ReflectionTestUtils.setField(activationService, "autoActivationEnabled", true);

        Beneficiary b = new Beneficiary();
        b.setCustomerId("CUST-1001");
        b.setStatus(BeneficiaryStatus.PENDING);
        b.setAccountNumber("123456789012");
        ReflectionTestUtils.setField(b, "publicId", UUID.randomUUID());

        when(beneficiaryRepository.findByStatusAndCoolingPeriodEndsAtBefore(eq(BeneficiaryStatus.PENDING), any()))
            .thenReturn(List.of(b));
        when(beneficiaryRepository.save(any())).thenReturn(b);

        activationService.processPendingCooldownActivations();

        assertThat(b.getStatus()).isEqualTo(BeneficiaryStatus.ACTIVE);
        verify(beneficiaryRepository).save(b);
        verify(auditService).logStatusChange(any(), eq("PENDING"), eq("ACTIVE"), eq("SYSTEM_SCHEDULER"), eq("127.0.0.1"));
    }
}
