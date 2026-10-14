package com.neobank.beneficiary.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neobank.beneficiary.dto.IfscDetailsResponse;
import com.neobank.beneficiary.dto.IfscRegistrationRequest;
import com.neobank.beneficiary.exception.ApiException;
import com.neobank.beneficiary.exception.GlobalExceptionHandler;
import com.neobank.beneficiary.service.IfscService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class IfscControllerTest {

    @Mock
    private IfscService ifscService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        IfscController controller = new IfscController(ifscService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    @DisplayName("POST /beneficiaries/ifsc registers valid IFSC and returns 201 Created with Location header")
    void testRegisterIfsc_Success() throws Exception {
        IfscRegistrationRequest request = IfscRegistrationRequest.builder()
            .ifscCode("HDFC0001234")
            .bankName("HDFC Bank")
            .branchName("Koramangala")
            .city("Bengaluru")
            .state("Karnataka")
            .build();

        IfscDetailsResponse response = IfscDetailsResponse.builder()
            .ifscCode("HDFC0001234")
            .bankName("HDFC Bank")
            .branchName("Koramangala")
            .city("Bengaluru")
            .state("Karnataka")
            .status("ACTIVE")
            .build();

        when(ifscService.registerIfsc(any(IfscRegistrationRequest.class))).thenReturn(response);

        mockMvc.perform(post("/beneficiaries/ifsc")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", "/api/v1/beneficiaries/ifsc/HDFC0001234"))
            .andExpect(jsonPath("$.status", is(201)))
            .andExpect(jsonPath("$.data.ifscCode", is("HDFC0001234")))
            .andExpect(jsonPath("$.data.bankName", is("HDFC Bank")));

        verify(ifscService).registerIfsc(any(IfscRegistrationRequest.class));
    }

    @Test
    @DisplayName("POST /beneficiaries/ifsc returns 400 Bad Request when IFSC code is invalid")
    void testRegisterIfsc_InvalidFormat() throws Exception {
        IfscRegistrationRequest request = IfscRegistrationRequest.builder()
            .ifscCode("INVALID_CODE")
            .bankName("HDFC Bank")
            .branchName("Main Branch")
            .build();

        mockMvc.perform(post("/beneficiaries/ifsc")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status", is(400)))
            .andExpect(jsonPath("$.detail", containsString("Request validation failed.")));
    }

    @Test
    @DisplayName("GET /beneficiaries/ifsc returns list of IFSC records")
    void testGetAllIfsc_Success() throws Exception {
        IfscDetailsResponse item = IfscDetailsResponse.builder()
            .ifscCode("SBIN0001234")
            .bankName("State Bank of India")
            .branchName("MG Road")
            .build();

        when(ifscService.getAllIfsc()).thenReturn(List.of(item));

        mockMvc.perform(get("/beneficiaries/ifsc")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status", is(200)))
            .andExpect(jsonPath("$.data", hasSize(1)))
            .andExpect(jsonPath("$.data[0].ifscCode", is("SBIN0001234")));

        verify(ifscService).getAllIfsc();
    }

    @Test
    @DisplayName("GET /beneficiaries/ifsc/search?query=HDFC returns matching branches")
    void testSearchIfsc_Success() throws Exception {
        IfscDetailsResponse item = IfscDetailsResponse.builder()
            .ifscCode("HDFC0001234")
            .bankName("HDFC Bank")
            .branchName("Koramangala")
            .build();

        when(ifscService.searchIfsc("HDFC")).thenReturn(List.of(item));

        mockMvc.perform(get("/beneficiaries/ifsc/search")
                .param("query", "HDFC")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status", is(200)))
            .andExpect(jsonPath("$.data", hasSize(1)))
            .andExpect(jsonPath("$.data[0].bankName", is("HDFC Bank")));

        verify(ifscService).searchIfsc("HDFC");
    }

    @Test
    @DisplayName("GET /beneficiaries/ifsc/{code} returns branch details")
    void testGetIfscDetails_Success() throws Exception {
        IfscDetailsResponse item = IfscDetailsResponse.builder()
            .ifscCode("ICIC0001234")
            .bankName("ICICI Bank")
            .branchName("Indiranagar")
            .build();

        when(ifscService.getIfscDetails("ICIC0001234")).thenReturn(item);

        mockMvc.perform(get("/beneficiaries/ifsc/ICIC0001234")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status", is(200)))
            .andExpect(jsonPath("$.data.ifscCode", is("ICIC0001234")));

        verify(ifscService).getIfscDetails("ICIC0001234");
    }

    @Test
    @DisplayName("GET /beneficiaries/ifsc/{code} returns 404 when IFSC not found")
    void testGetIfscDetails_NotFound() throws Exception {
        when(ifscService.getIfscDetails("UNKN0000000"))
            .thenThrow(ApiException.notFound("IFSC code not found in directory: UNKN0000000"));

        mockMvc.perform(get("/beneficiaries/ifsc/UNKN0000000")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status", is(404)))
            .andExpect(jsonPath("$.detail", containsString("UNKN0000000")));
    }
}
