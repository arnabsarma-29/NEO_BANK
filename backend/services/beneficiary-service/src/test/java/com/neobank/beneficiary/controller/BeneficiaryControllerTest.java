package com.neobank.beneficiary.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neobank.beneficiary.dto.BeneficiaryRequest;
import com.neobank.beneficiary.dto.BeneficiaryResponse;
import com.neobank.beneficiary.dto.BeneficiaryStatusResponse;
import com.neobank.beneficiary.entity.BeneficiaryStatus;
import com.neobank.beneficiary.entity.BeneficiaryType;
import com.neobank.beneficiary.exception.ApiException;
import com.neobank.beneficiary.exception.GlobalExceptionHandler;
import com.neobank.beneficiary.service.BeneficiaryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class BeneficiaryControllerTest {

    @Mock
    private BeneficiaryService beneficiaryService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    private static final String CUST_ID = "CUST-1001";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        BeneficiaryController controller = new BeneficiaryController(beneficiaryService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateUser(String customerId) {
        User user = new User(customerId, "", List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    @DisplayName("POST /beneficiaries without authentication returns 401 Unauthorized")
    void createBeneficiaryUnauthenticatedReturns401() throws Exception {
        BeneficiaryRequest request = BeneficiaryRequest.builder()
            .name("Amit Kumar")
            .accountNumber("123456789012")
            .confirmAccountNumber("123456789012")
            .ifscCode("SBIN0001234")
            .build();

        mockMvc.perform(post("/beneficiaries")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status").value(401))
            .andExpect(jsonPath("$.detail").value(containsString("Authentication required")));
    }

    @Test
    @DisplayName("POST /beneficiaries with valid request returns 201 Created with Location header and masked account")
    void createBeneficiaryValidReturns201Created() throws Exception {
        authenticateUser(CUST_ID);

        BeneficiaryRequest request = BeneficiaryRequest.builder()
            .name("Amit Kumar")
            .accountNumber("123456789012")
            .confirmAccountNumber("123456789012")
            .ifscCode("SBIN0001234")
            .beneficiaryType(BeneficiaryType.OTHER_BANK)
            .build();

        UUID benId = UUID.randomUUID();
        BeneficiaryResponse response = BeneficiaryResponse.builder()
            .beneficiaryId(benId)
            .customerId(CUST_ID)
            .name("Amit Kumar")
            .maskedAccountNumber("********9012")
            .accountNumber("********9012")
            .ifscCode("SBIN0001234")
            .status(BeneficiaryStatus.PENDING)
            .build();

        when(beneficiaryService.createBeneficiary(any(BeneficiaryRequest.class), eq(CUST_ID), eq(CUST_ID), any()))
            .thenReturn(response);

        mockMvc.perform(post("/beneficiaries")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", "/api/v1/beneficiaries/" + benId))
            .andExpect(jsonPath("$.status").value(201))
            .andExpect(jsonPath("$.data.beneficiaryId").value(benId.toString()))
            .andExpect(jsonPath("$.data.status").value("PENDING"))
            .andExpect(jsonPath("$.data.maskedAccountNumber").value("********9012"))
            .andExpect(jsonPath("$.data.accountNumber").value("********9012"));

        verify(beneficiaryService).createBeneficiary(any(BeneficiaryRequest.class), eq(CUST_ID), eq(CUST_ID), any());
    }

    @Test
    @DisplayName("POST /beneficiaries with validation error returns 400 Bad Request with field errors")
    void createBeneficiaryValidationErrorReturns400() throws Exception {
        authenticateUser(CUST_ID);

        // Blank name and mismatch account numbers
        BeneficiaryRequest request = BeneficiaryRequest.builder()
            .name("")
            .accountNumber("123456789012")
            .confirmAccountNumber("987654321098")
            .ifscCode("SBIN0001234")
            .build();

        mockMvc.perform(post("/beneficiaries")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.errors[*].field", hasItem("name")));
    }

    @Test
    @DisplayName("POST /beneficiaries when service throws conflict returns 409 Conflict")
    void createBeneficiaryDuplicateReturns409Conflict() throws Exception {
        authenticateUser(CUST_ID);

        BeneficiaryRequest request = BeneficiaryRequest.builder()
            .name("Amit Kumar")
            .accountNumber("123456789012")
            .confirmAccountNumber("123456789012")
            .ifscCode("SBIN0001234")
            .build();

        when(beneficiaryService.createBeneficiary(any(), eq(CUST_ID), eq(CUST_ID), any()))
            .thenThrow(ApiException.conflict("This beneficiary already exists."));

        mockMvc.perform(post("/beneficiaries")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.status").value(409))
            .andExpect(jsonPath("$.detail").value(containsString("This beneficiary already exists")));
    }

    @Test
    @DisplayName("GET /beneficiaries returns list for authenticated customer")
    void listBeneficiariesReturnsList() throws Exception {
        authenticateUser(CUST_ID);

        UUID benId = UUID.randomUUID();
        BeneficiaryResponse b1 = BeneficiaryResponse.builder()
            .beneficiaryId(benId)
            .customerId(CUST_ID)
            .name("Amit Kumar")
            .maskedAccountNumber("********9012")
            .status(BeneficiaryStatus.PENDING)
            .build();

        when(beneficiaryService.getBeneficiaries(CUST_ID, null))
            .thenReturn(List.of(b1));

        mockMvc.perform(get("/beneficiaries"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(200))
            .andExpect(jsonPath("$.data[0].beneficiaryId").value(benId.toString()))
            .andExpect(jsonPath("$.data[0].customerId").value(CUST_ID));

        verify(beneficiaryService).getBeneficiaries(CUST_ID, null);
    }

    @Test
    @DisplayName("GET /beneficiaries/{id} returns beneficiary for authenticated customer")
    void getBeneficiaryReturnsSuccess() throws Exception {
        authenticateUser(CUST_ID);

        UUID benId = UUID.randomUUID();
        BeneficiaryResponse b = BeneficiaryResponse.builder()
            .beneficiaryId(benId)
            .customerId(CUST_ID)
            .name("Amit Kumar")
            .maskedAccountNumber("********9012")
            .status(BeneficiaryStatus.ACTIVE)
            .build();

        when(beneficiaryService.getBeneficiary(benId, CUST_ID)).thenReturn(b);

        mockMvc.perform(get("/beneficiaries/" + benId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(200))
            .andExpect(jsonPath("$.data.beneficiaryId").value(benId.toString()));
    }

    @Test
    @DisplayName("GET /beneficiaries/{id}/status returns status details")
    void getBeneficiaryStatusReturnsSuccess() throws Exception {
        authenticateUser(CUST_ID);

        UUID benId = UUID.randomUUID();
        BeneficiaryStatusResponse response = BeneficiaryStatusResponse.builder()
            .beneficiaryId(benId)
            .status(BeneficiaryStatus.PENDING)
            .coolingPeriodActive(true)
            .coolingPeriodRemainingSeconds(1800L)
            .eligibleForTransfer(false)
            .build();

        when(beneficiaryService.getBeneficiaryStatus(benId, CUST_ID)).thenReturn(response);

        mockMvc.perform(get("/beneficiaries/" + benId + "/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("PENDING"))
            .andExpect(jsonPath("$.data.coolingPeriodActive").value(true))
            .andExpect(jsonPath("$.data.eligibleForTransfer").value(false));
    }

    @Test
    @DisplayName("DELETE /beneficiaries/{id} returns 200 OK")
    void deleteBeneficiaryReturnsSuccess() throws Exception {
        authenticateUser(CUST_ID);

        UUID benId = UUID.randomUUID();
        BeneficiaryResponse response = BeneficiaryResponse.builder()
            .beneficiaryId(benId)
            .customerId(CUST_ID)
            .status(BeneficiaryStatus.INACTIVE)
            .build();

        when(beneficiaryService.deleteBeneficiary(eq(benId), eq(CUST_ID), eq(CUST_ID), any()))
            .thenReturn(response);

        mockMvc.perform(delete("/beneficiaries/" + benId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(200))
            .andExpect(jsonPath("$.data.status").value("INACTIVE"));
    }
}
