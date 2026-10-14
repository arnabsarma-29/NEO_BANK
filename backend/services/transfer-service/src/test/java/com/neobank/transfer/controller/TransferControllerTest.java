package com.neobank.transfer.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.neobank.transfer.domain.NextAction;
import com.neobank.transfer.domain.TransferStatus;
import com.neobank.transfer.domain.TransferType;
import com.neobank.transfer.domain.TransferWorkflow;
import com.neobank.transfer.dto.*;
import com.neobank.transfer.exception.IdempotencyConflictException;
import com.neobank.transfer.exception.InvalidIdempotencyKeyException;
import com.neobank.transfer.exception.OtpRateLimitException;
import com.neobank.transfer.exception.OtpVerificationException;
import com.neobank.transfer.exception.TransferNotFoundException;
import com.neobank.transfer.service.TransferWorkflowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class TransferControllerTest {

    @Mock
    private TransferWorkflowService workflowService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        TransferController controller = new TransferController(workflowService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new TransferGlobalExceptionHandler())
                .build();
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
    }

    @Test
    void shouldReturn400WhenIdempotencyKeyIsMissing() throws Exception {
        CreateTransferRequest request = new CreateTransferRequest(
                "ACC-1001", TransferType.OWN_ACCOUNT, "ACC-1002", null,
                new BigDecimal("500.00"), "INR", "Test remarks");

        when(workflowService.createTransfer(any(), eq(null), any()))
                .thenThrow(new InvalidIdempotencyKeyException("Idempotency-Key header is required"));

        mockMvc.perform(post("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
    }

    @Test
    void shouldReturn201WhenTransferCreatedSuccessfully() throws Exception {
        CreateTransferRequest request = new CreateTransferRequest(
                "ACC-1001", TransferType.OWN_ACCOUNT, "ACC-1002", null,
                new BigDecimal("500.00"), "INR", "Test remarks");

        Instant now = Instant.now();
        TransferWorkflow workflow = new TransferWorkflow(
                "TRF-1001", "TXN-SIM-1", "C1001", "ACC-1001", "ACC-1002", null,
                TransferType.OWN_ACCOUNT, new BigDecimal("500.00"), "INR", "Test remarks",
                "Own", "ACC-1002", TransferStatus.PENDING_CONFIRMATION, NextAction.CONFIRM_TRANSFER, now);

        CreateTransferResponse response = new CreateTransferResponse(
                "TRF-1001", "TXN-SIM-1", TransferStatus.PENDING_CONFIRMATION, TransferType.OWN_ACCOUNT,
                "ACC-1001", "ACC-1002", null, "Own", "ACC-1002",
                new BigDecimal("500.00"), "INR", "Test remarks", now, NextAction.CONFIRM_TRANSFER);

        when(workflowService.createTransfer(any(), eq("key-1"), any()))
                .thenReturn(new TransferWorkflowService.CreateTransferResult(workflow, true));
        when(workflowService.mapToCreateResponse(workflow)).thenReturn(response);

        mockMvc.perform(post("/api/transfers")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transferId").value("TRF-1001"))
                .andExpect(jsonPath("$.status").value("PENDING_CONFIRMATION"))
                .andExpect(jsonPath("$.nextAction").value("CONFIRM_TRANSFER"));
    }

    @Test
    void shouldReturn202WhenDuplicateRequestIsProcessing() throws Exception {
        CreateTransferRequest request = new CreateTransferRequest(
                "ACC-1001", TransferType.OWN_ACCOUNT, "ACC-1002", null,
                new BigDecimal("500.00"), "INR", "Test remarks");

        Instant now = Instant.now();
        TransferWorkflow workflow = new TransferWorkflow(
                "TRF-1001", "TXN-SIM-1", "C1001", "ACC-1001", "ACC-1002", null,
                TransferType.OWN_ACCOUNT, new BigDecimal("500.00"), "INR", "Test remarks",
                "Own", "ACC-1002", TransferStatus.PROCESSING, NextAction.WAIT, now);

        CreateTransferResponse response = new CreateTransferResponse(
                "TRF-1001", "TXN-SIM-1", TransferStatus.PROCESSING, TransferType.OWN_ACCOUNT,
                "ACC-1001", "ACC-1002", null, "Own", "ACC-1002",
                new BigDecimal("500.00"), "INR", "Test remarks", now, NextAction.WAIT);

        when(workflowService.createTransfer(any(), eq("key-1"), any()))
                .thenReturn(new TransferWorkflowService.CreateTransferResult(workflow, false));
        when(workflowService.mapToCreateResponse(workflow)).thenReturn(response);

        mockMvc.perform(post("/api/transfers")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.transferId").value("TRF-1001"))
                .andExpect(jsonPath("$.status").value("PROCESSING"));
    }

    @Test
    void shouldReturn409WhenIdempotencyKeyReusedWithDifferentRequest() throws Exception {
        CreateTransferRequest request = new CreateTransferRequest(
                "ACC-1001", TransferType.OWN_ACCOUNT, "ACC-1002", null,
                new BigDecimal("500.00"), "INR", "Test remarks");

        when(workflowService.createTransfer(any(), eq("key-1"), any()))
                .thenThrow(new IdempotencyConflictException("IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST"));

        mockMvc.perform(post("/api/transfers")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST"));
    }

    @Test
    void shouldReturn200ForValidTransferStatusQuery() throws Exception {
        Instant now = Instant.now();
        TransferWorkflow workflow = new TransferWorkflow(
                "TRF-1001", "TXN-SIM-1", "C1001", "ACC-1001", "ACC-1002", null,
                TransferType.OWN_ACCOUNT, new BigDecimal("500.00"), "INR", "Test remarks",
                "Own", "ACC-1002", TransferStatus.SUCCESS, NextAction.NONE, now);

        TransferStatusResponse response = new TransferStatusResponse(
                "TRF-1001", "TXN-SIM-1", TransferStatus.SUCCESS, TransferType.OWN_ACCOUNT,
                "ACC-1001", "ACC-1002", null, "Own", "ACC-1002",
                new BigDecimal("500.00"), "INR", "Test remarks", now, now, now,
                NextAction.NONE, null);

        when(workflowService.getTransfer(eq("TRF-1001"), any())).thenReturn(workflow);
        when(workflowService.mapToStatusResponse(workflow)).thenReturn(response);

        mockMvc.perform(get("/api/transfers/TRF-1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferId").value("TRF-1001"))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.completedAt").isNotEmpty());
    }

    @Test
    void shouldReturn404WhenTransferNotFound() throws Exception {
        when(workflowService.getTransfer(eq("TRF-9999"), any()))
                .thenThrow(new TransferNotFoundException("Transfer not found"));

        mockMvc.perform(get("/api/transfers/TRF-9999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSFER_NOT_FOUND"));
    }

    @Test
    void shouldReturn200WhenConfirmingTransfer() throws Exception {
        Instant expires = Instant.now().plusSeconds(300);
        ConfirmTransferResponse response = new ConfirmTransferResponse(
                "TRF-1001", "TXN-SIM-1", TransferStatus.AWAITING_OTP,
                NextAction.COMPLETE_OTP_VERIFICATION, "OTP-1234", expires);

        when(workflowService.confirmTransfer(eq("TRF-1001"), any())).thenReturn(response);

        mockMvc.perform(post("/api/transfers/TRF-1001/confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferId").value("TRF-1001"))
                .andExpect(jsonPath("$.status").value("AWAITING_OTP"))
                .andExpect(jsonPath("$.challengeId").value("OTP-1234"));
    }

    @Test
    void shouldReturn422WhenOtpIsInvalid() throws Exception {
        VerifyTransferOtpRequest request = new VerifyTransferOtpRequest("OTP-1234", "000000");

        when(workflowService.verifyOtp(eq("TRF-1001"), any(), any()))
                .thenThrow(new OtpVerificationException("Invalid OTP code"));

        mockMvc.perform(post("/api/transfers/TRF-1001/otp-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_OR_EXPIRED_OTP"));
    }

    @Test
    void shouldReturn429WhenOtpRateLimitOrLockoutOccurs() throws Exception {
        VerifyTransferOtpRequest request = new VerifyTransferOtpRequest("OTP-1234", "000000");

        when(workflowService.verifyOtp(eq("TRF-1001"), any(), any()))
                .thenThrow(new com.neobank.transfer.exception.OtpRateLimitException("OTP verification temporarily restricted for 15 minutes"));

        mockMvc.perform(post("/api/transfers/TRF-1001/otp-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("OTP_RATE_LIMIT_EXCEEDED"));
    }

    @Test
    void shouldReturn429WhenOtpLockoutOccurs() throws Exception {
        VerifyTransferOtpRequest request = new VerifyTransferOtpRequest("OTP-1234", "000000");

        when(workflowService.verifyOtp(eq("TRF-1001"), any(), any()))
                .thenThrow(new com.neobank.transfer.exception.OtpLockoutException("OTP verification locked for 15 minutes after 5 incorrect attempts"));

        mockMvc.perform(post("/api/transfers/TRF-1001/otp-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("OTP_RATE_LIMIT_EXCEEDED"));
    }

    @Test
    void shouldReturn200WhenResendingOtp() throws Exception {
        Instant expires = Instant.now().plusSeconds(300);
        ResendTransferOtpRequest request = new ResendTransferOtpRequest("OTP-1234");
        ConfirmTransferResponse response = new ConfirmTransferResponse(
                "TRF-1001", "TXN-SIM-1", TransferStatus.AWAITING_OTP,
                NextAction.COMPLETE_OTP_VERIFICATION, "OTP-1234", expires);

        when(workflowService.resendOtp(eq("TRF-1001"), any(), any())).thenReturn(response);

        mockMvc.perform(post("/api/transfers/TRF-1001/otp/resend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.challengeId").value("OTP-1234"));
    }

    @Test
    void shouldReturn429WhenCreateTransferBlockedByCustomerLockout() throws Exception {
        CreateTransferRequest request = new CreateTransferRequest(
                "ACC-1001", TransferType.OWN_ACCOUNT, "ACC-1002", null,
                new BigDecimal("500.00"), "INR", "Blocked transfer");

        when(workflowService.createTransfer(any(), eq("key-blocked"), any()))
                .thenThrow(new OtpRateLimitException("Transfer initiation temporarily restricted for 15 minutes due to exceeding maximum incorrect OTP attempts"));

        mockMvc.perform(post("/api/transfers")
                        .header("Idempotency-Key", "key-blocked")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("OTP_RATE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.message").value("Transfer initiation temporarily restricted for 15 minutes due to exceeding maximum incorrect OTP attempts"));
    }
}
