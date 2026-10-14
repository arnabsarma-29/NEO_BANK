package com.neobank.beneficiary.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neobank.beneficiary.dto.BeneficiaryResponse;
import com.neobank.beneficiary.dto.TransferEligibilityResponse;
import com.neobank.beneficiary.entity.BeneficiaryStatus;
import com.neobank.beneficiary.entity.BeneficiaryType;
import com.neobank.beneficiary.exception.ApiException;
import com.neobank.beneficiary.exception.GlobalExceptionHandler;
import com.neobank.beneficiary.service.BeneficiaryActivationService;
import com.neobank.beneficiary.service.BeneficiaryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class InternalBeneficiaryControllerTest {

    @Mock
    private BeneficiaryService beneficiaryService;

    @Mock
    private BeneficiaryActivationService beneficiaryActivationService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        InternalBeneficiaryController controller = new InternalBeneficiaryController(
            beneficiaryService, beneficiaryActivationService
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    @DisplayName("GET /internal/beneficiaries/{id} returns beneficiary successfully")
    void testGetBeneficiaryInternal_Success() throws Exception {
        UUID id = UUID.randomUUID();
        BeneficiaryResponse response = BeneficiaryResponse.builder()
            .beneficiaryId(id)
            .customerId("CUST-9999")
            .name("Internal Supplier")
            .beneficiaryType(BeneficiaryType.OTHER_BANK)
            .maskedAccountNumber("********4321")
            .status(BeneficiaryStatus.ACTIVE)
            .build();

        when(beneficiaryService.getBeneficiary(id, null)).thenReturn(response);

        mockMvc.perform(get("/internal/beneficiaries/{id}", id)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status", is(200)))
            .andExpect(jsonPath("$.data.beneficiaryId", is(id.toString())))
            .andExpect(jsonPath("$.data.customerId", is("CUST-9999")))
            .andExpect(jsonPath("$.data.maskedAccountNumber", is("********4321")));

        verify(beneficiaryService).getBeneficiary(id, null);
    }

    @Test
    @DisplayName("GET /internal/beneficiaries/{id}/transfer-eligibility returns eligible false when status is PENDING")
    void testCheckTransferEligibility_Pending() throws Exception {
        UUID id = UUID.randomUUID();
        TransferEligibilityResponse response = TransferEligibilityResponse.builder()
            .beneficiaryId(id)
            .eligible(false)
            .reason("BENEFICIARY_NOT_ACTIVE")
            .build();

        when(beneficiaryService.checkTransferEligibility(id)).thenReturn(response);

        mockMvc.perform(get("/internal/beneficiaries/{id}/transfer-eligibility", id)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status", is(200)))
            .andExpect(jsonPath("$.data.eligible", is(false)))
            .andExpect(jsonPath("$.data.reason", is("BENEFICIARY_NOT_ACTIVE")));

        verify(beneficiaryService).checkTransferEligibility(id);
    }

    @Test
    @DisplayName("GET /internal/beneficiaries/{id}/transfer-eligibility returns eligible true when ACTIVE")
    void testCheckTransferEligibility_Active() throws Exception {
        UUID id = UUID.randomUUID();
        TransferEligibilityResponse response = TransferEligibilityResponse.builder()
            .beneficiaryId(id)
            .eligible(true)
            .reason(null)
            .build();

        when(beneficiaryService.checkTransferEligibility(id)).thenReturn(response);

        mockMvc.perform(get("/internal/beneficiaries/{id}/transfer-eligibility", id)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status", is(200)))
            .andExpect(jsonPath("$.data.eligible", is(true)));

        verify(beneficiaryService).checkTransferEligibility(id);
    }

    @Test
    @DisplayName("POST /internal/beneficiaries/{id}/activate activates pending beneficiary")
    void testActivateBeneficiary_Success() throws Exception {
        UUID id = UUID.randomUUID();
        BeneficiaryResponse response = BeneficiaryResponse.builder()
            .beneficiaryId(id)
            .status(BeneficiaryStatus.ACTIVE)
            .build();

        when(beneficiaryActivationService.activateBeneficiary(eq(id), eq("BATCH_RUNNER"), any()))
            .thenReturn(response);

        mockMvc.perform(post("/internal/beneficiaries/{id}/activate", id)
                .param("actor", "BATCH_RUNNER")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status", is(200)))
            .andExpect(jsonPath("$.data.status", is("ACTIVE")));

        verify(beneficiaryActivationService).activateBeneficiary(eq(id), eq("BATCH_RUNNER"), any());
    }

    @Test
    @DisplayName("POST /internal/beneficiaries/{id}/activate returns 400 when transition illegal")
    void testActivateBeneficiary_IllegalTransition() throws Exception {
        UUID id = UUID.randomUUID();

        when(beneficiaryActivationService.activateBeneficiary(eq(id), any(), any()))
            .thenThrow(new ApiException(
                HttpStatus.BAD_REQUEST,
                "Cannot activate beneficiary in INACTIVE status. Only PENDING beneficiaries can be activated."
            ));

        mockMvc.perform(post("/internal/beneficiaries/{id}/activate", id)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status", is(400)))
            .andExpect(jsonPath("$.detail", containsString("Cannot activate beneficiary in INACTIVE status")));
    }

    @Test
    @DisplayName("GET /internal/beneficiaries/{id} returns 404 when beneficiary not found")
    void testGetBeneficiaryInternal_NotFound() throws Exception {
        UUID id = UUID.randomUUID();

        when(beneficiaryService.getBeneficiary(id, null))
            .thenThrow(ApiException.notFound("Beneficiary not found with ID: " + id));

        mockMvc.perform(get("/internal/beneficiaries/{id}", id)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status", is(404)))
            .andExpect(jsonPath("$.detail", containsString(id.toString())));
    }
}
