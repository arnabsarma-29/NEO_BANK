package com.neobank.transfer.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.neobank.transfer.dto.ExecuteScheduledTransferRequest;
import com.neobank.transfer.dto.ExecuteScheduledTransferResponse;
import com.neobank.transfer.dto.ScheduledTransferReconcileResponse;
import com.neobank.transfer.security.ScheduleServiceTokenInterceptor;
import com.neobank.transfer.service.TransferWorkflowService;
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

import java.math.BigDecimal;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class TransferInternalControllerTest {

    private static final String VALID_TOKEN = "dev-schedule-to-transfer-token-secret-local";
    private static final String HEADER_NAME = "X-Internal-Service-Token";

    @Mock
    private TransferWorkflowService workflowService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        TransferInternalController controller = new TransferInternalController(workflowService);
        ScheduleServiceTokenInterceptor interceptor = new ScheduleServiceTokenInterceptor(VALID_TOKEN);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addMappedInterceptors(new String[]{"/internal/transfers/**"}, interceptor)
                .setControllerAdvice(new TransferGlobalExceptionHandler())
                .build();

        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
    }

    private ExecuteScheduledTransferRequest sampleRequest() {
        return new ExecuteScheduledTransferRequest(
                "TRF-SCH-1001",
                "idemp-key-1",
                "SCH-001",
                "OCC-001",
                "CUST-001",
                "ACC-SOURCE-1",
                "ACC-DEST-2",
                null,
                "OWN_ACCOUNT",
                new BigDecimal("500.00"),
                "INR",
                "Monthly savings",
                Instant.now(),
                null
        );
    }

    // 1. Missing internal token -> 401; workflow and Ledger client are not called
    @Test
    @DisplayName("1. Missing internal token returns 401 Unauthorized; workflow is not called")
    void shouldReturn401WhenInternalTokenIsMissing() throws Exception {
        mockMvc.perform(post("/internal/transfers/scheduled-executions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(workflowService);
    }

    // 2. Invalid internal token -> 401
    @Test
    @DisplayName("2. Invalid internal token returns 401 Unauthorized; workflow is not called")
    void shouldReturn401WhenInternalTokenIsInvalid() throws Exception {
        mockMvc.perform(post("/internal/transfers/scheduled-executions")
                        .header(HEADER_NAME, "wrong-unauthorized-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(workflowService);
    }

    // 3. Valid token reaches the controller
    @Test
    @DisplayName("3. Valid token reaches the controller and delegates to workflow service")
    void shouldAllowValidTokenToReachController() throws Exception {
        when(workflowService.executeScheduledTransfer(any()))
                .thenReturn(new TransferWorkflowService.ScheduledExecutionOutcome(
                        HttpStatus.CREATED,
                        ExecuteScheduledTransferResponse.committed("TRF-SCH-1001", "TXN-SCH-1", Instant.now())
                ));

        mockMvc.perform(post("/internal/transfers/scheduled-executions")
                        .header(HEADER_NAME, VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isCreated());

        verify(workflowService, times(1)).executeScheduledTransfer(any());
    }

    // 4. New successful scheduled execution -> 201
    @Test
    @DisplayName("4. New successful scheduled execution returns 201 Created with COMMITTED status")
    void shouldReturn201ForNewSuccessfulScheduledExecution() throws Exception {
        Instant completedAt = Instant.now();
        when(workflowService.executeScheduledTransfer(any()))
                .thenReturn(new TransferWorkflowService.ScheduledExecutionOutcome(
                        HttpStatus.CREATED,
                        ExecuteScheduledTransferResponse.committed("TRF-SCH-1001", "TXN-SCH-1", completedAt)
                ));

        mockMvc.perform(post("/internal/transfers/scheduled-executions")
                        .header(HEADER_NAME, VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transferId").value("TRF-SCH-1001"))
                .andExpect(jsonPath("$.referenceNumber").value("TXN-SCH-1"))
                .andExpect(jsonPath("$.status").value("COMMITTED"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    // 5. Identical committed replay -> 200, without a second financial mutation
    @Test
    @DisplayName("5. Identical committed replay returns 200 OK with stored COMMITTED status")
    void shouldReturn200ForIdenticalCommittedReplay() throws Exception {
        Instant completedAt = Instant.now().minusSeconds(60);
        when(workflowService.executeScheduledTransfer(any()))
                .thenReturn(new TransferWorkflowService.ScheduledExecutionOutcome(
                        HttpStatus.OK,
                        ExecuteScheduledTransferResponse.committed("TRF-SCH-1001", "TXN-SCH-1", completedAt)
                ));

        mockMvc.perform(post("/internal/transfers/scheduled-executions")
                        .header(HEADER_NAME, VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferId").value("TRF-SCH-1001"))
                .andExpect(jsonPath("$.referenceNumber").value("TXN-SCH-1"))
                .andExpect(jsonPath("$.status").value("COMMITTED"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    // 6. Processing/uncertain outcome -> 202
    @Test
    @DisplayName("6. Processing or uncertain outcome returns 202 Accepted")
    void shouldReturn202ForProcessingOrUncertainOutcome() throws Exception {
        when(workflowService.executeScheduledTransfer(any()))
                .thenReturn(new TransferWorkflowService.ScheduledExecutionOutcome(
                        HttpStatus.ACCEPTED,
                        ExecuteScheduledTransferResponse.outcomeUnknown("TRF-SCH-1001", "TXN-SCH-1", "FINANCIAL_OUTCOME_PENDING_RECONCILIATION")
                ));

        mockMvc.perform(post("/internal/transfers/scheduled-executions")
                        .header(HEADER_NAME, VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.transferId").value("TRF-SCH-1001"))
                .andExpect(jsonPath("$.status").value("OUTCOME_UNKNOWN"))
                .andExpect(jsonPath("$.statusReasonCode").value("FINANCIAL_OUTCOME_PENDING_RECONCILIATION"))
                .andExpect(jsonPath("$.retryable").value(true));
    }

    // 7. Definitive business rejection -> 422
    @Test
    @DisplayName("7. Definitive business rejection returns 422 Unprocessable Entity")
    void shouldReturn422ForDefinitiveBusinessRejection() throws Exception {
        when(workflowService.executeScheduledTransfer(any()))
                .thenReturn(new TransferWorkflowService.ScheduledExecutionOutcome(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        ExecuteScheduledTransferResponse.rejected("TRF-SCH-1001", "TXN-SCH-1", "INSUFFICIENT_FUNDS")
                ));

        mockMvc.perform(post("/internal/transfers/scheduled-executions")
                        .header(HEADER_NAME, VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.transferId").value("TRF-SCH-1001"))
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.statusReasonCode").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    // 8. Conflicting transfer ID/request fingerprint -> 409
    @Test
    @DisplayName("8. Conflicting transfer ID or fingerprint reuse returns 409 Conflict")
    void shouldReturn409ForConflictingTransferIdOrFingerprint() throws Exception {
        when(workflowService.executeScheduledTransfer(any()))
                .thenReturn(new TransferWorkflowService.ScheduledExecutionOutcome(
                        HttpStatus.CONFLICT,
                        ExecuteScheduledTransferResponse.rejected("TRF-SCH-1001", "TXN-SCH-1", "TRANSFER_ID_REUSED_WITH_DIFFERENT_DETAILS")
                ));

        mockMvc.perform(post("/internal/transfers/scheduled-executions")
                        .header(HEADER_NAME, VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.transferId").value("TRF-SCH-1001"))
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.statusReasonCode").value("TRANSFER_ID_REUSED_WITH_DIFFERENT_DETAILS"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    // 9. Pre-Ledger infrastructure failure -> 503 only when safely classified as pre-execution
    @Test
    @DisplayName("9. Pre-Ledger infrastructure failure returns 503 Service Unavailable with retryable=true")
    void shouldReturn503ForPreLedgerInfrastructureFailure() throws Exception {
        when(workflowService.executeScheduledTransfer(any()))
                .thenReturn(new TransferWorkflowService.ScheduledExecutionOutcome(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        ExecuteScheduledTransferResponse.failedPreLedger("TRF-SCH-1001", "LEDGER_UNAVAILABLE")
                ));

        mockMvc.perform(post("/internal/transfers/scheduled-executions")
                        .header(HEADER_NAME, VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.transferId").value("TRF-SCH-1001"))
                .andExpect(jsonPath("$.status").value("FAILED_PRE_LEDGER"))
                .andExpect(jsonPath("$.statusReasonCode").value("LEDGER_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(true));
    }

    // 10. Reconciliation returns committed, rejected, uncertain, and not-found outcomes correctly
    @Test
    @DisplayName("10a. Reconciliation returns 200 OK for committed outcome")
    void shouldReturn200ForReconciledCommittedOutcome() throws Exception {
        Instant completedAt = Instant.now();
        when(workflowService.reconcileScheduledTransfer("TRF-SCH-1001"))
                .thenReturn(new TransferWorkflowService.ScheduledReconciliationOutcome(
                        HttpStatus.OK,
                        ScheduledTransferReconcileResponse.committed("TRF-SCH-1001", "TXN-SCH-1", "TXN-LEDGER-1", completedAt)
                ));

        mockMvc.perform(get("/internal/transfers/TRF-SCH-1001/reconcile")
                        .header(HEADER_NAME, VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferId").value("TRF-SCH-1001"))
                .andExpect(jsonPath("$.status").value("COMMITTED"))
                .andExpect(jsonPath("$.ledgerTransactionId").value("TXN-LEDGER-1"));
    }

    @Test
    @DisplayName("10b. Reconciliation returns 200 OK for definitively rejected outcome")
    void shouldReturn200ForReconciledRejectedOutcome() throws Exception {
        Instant completedAt = Instant.now();
        when(workflowService.reconcileScheduledTransfer("TRF-SCH-1001"))
                .thenReturn(new TransferWorkflowService.ScheduledReconciliationOutcome(
                        HttpStatus.OK,
                        ScheduledTransferReconcileResponse.rejected("TRF-SCH-1001", "TXN-SCH-1", "INSUFFICIENT_FUNDS", completedAt)
                ));

        mockMvc.perform(get("/internal/transfers/TRF-SCH-1001/reconcile")
                        .header(HEADER_NAME, VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferId").value("TRF-SCH-1001"))
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.statusReasonCode").value("INSUFFICIENT_FUNDS"));
    }

    @Test
    @DisplayName("10c. Reconciliation returns 202 Accepted for uncertain/processing outcome")
    void shouldReturn202ForReconciledUncertainOutcome() throws Exception {
        when(workflowService.reconcileScheduledTransfer("TRF-SCH-1001"))
                .thenReturn(new TransferWorkflowService.ScheduledReconciliationOutcome(
                        HttpStatus.ACCEPTED,
                        ScheduledTransferReconcileResponse.uncertain("TRF-SCH-1001", "TXN-SCH-1", "FINANCIAL_OUTCOME_PENDING_RECONCILIATION")
                ));

        mockMvc.perform(get("/internal/transfers/TRF-SCH-1001/reconcile")
                        .header(HEADER_NAME, VALID_TOKEN))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.transferId").value("TRF-SCH-1001"))
                .andExpect(jsonPath("$.status").value("OUTCOME_UNKNOWN"))
                .andExpect(jsonPath("$.retryable").value(true));
    }

    @Test
    @DisplayName("10d. Reconciliation returns 404 Not Found when transfer workflow record is absent")
    void shouldReturn404WhenReconcileTransferNotFound() throws Exception {
        when(workflowService.reconcileScheduledTransfer("TRF-UNKNOWN"))
                .thenReturn(new TransferWorkflowService.ScheduledReconciliationOutcome(
                        HttpStatus.NOT_FOUND,
                        null
                ));

        mockMvc.perform(get("/internal/transfers/TRF-UNKNOWN/reconcile")
                        .header(HEADER_NAME, VALID_TOKEN))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Reconciliation returns 401 Unauthorized when internal token is missing")
    void shouldReturn401OnReconcileWhenTokenMissing() throws Exception {
        mockMvc.perform(get("/internal/transfers/TRF-SCH-1001/reconcile"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(workflowService);
    }
}
