package com.neobank.ledger.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.neobank.ledger.domain.TransferType;
import com.neobank.ledger.dto.LedgerTransactionOutcomeResponse;
import com.neobank.ledger.dto.LedgerTransferExecutionRequest;
import com.neobank.ledger.exception.AccountNotFoundException;
import com.neobank.ledger.exception.InsufficientFundsException;
import com.neobank.ledger.exception.TransactionNotFoundException;
import com.neobank.ledger.exception.TransferIdConflictException;
import com.neobank.ledger.security.TransferServiceTokenInterceptor;
import com.neobank.ledger.service.LedgerExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class LedgerInternalControllerTest {

    private static final String VALID_TOKEN = "dev-transfer-to-ledger-token-secret-local";
    private static final String HEADER_NAME = TransferServiceTokenInterceptor.HEADER_SERVICE_TOKEN;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Mock
    private LedgerExecutionService ledgerExecutionService;

    @BeforeEach
    void setUp() {
        TransferServiceTokenInterceptor interceptor = new TransferServiceTokenInterceptor(VALID_TOKEN);
        mockMvc = MockMvcBuilders.standaloneSetup(new LedgerInternalController(ledgerExecutionService))
                .setControllerAdvice(new LedgerGlobalExceptionHandler())
                .addInterceptors(interceptor)
                .build();
    }

    @Test
    @DisplayName("POST /internal/ledger/transfers returns 401 Unauthorized when service token is missing")
    void shouldReturn401WhenServiceTokenMissingOnPost() throws Exception {
        LedgerTransferExecutionRequest request = new LedgerTransferExecutionRequest(
                "TRF-100", "ACC-1", "ACC-2", TransferType.OWN_ACCOUNT,
                new BigDecimal("500.00"), "INR", "REF-100", "Remarks", "Dest", null);

        mockMvc.perform(post("/internal/ledger/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));

        verifyNoInteractions(ledgerExecutionService);
    }

    @Test
    @DisplayName("POST /internal/ledger/transfers returns 401 Unauthorized when service token is invalid")
    void shouldReturn401WhenServiceTokenInvalidOnPost() throws Exception {
        LedgerTransferExecutionRequest request = new LedgerTransferExecutionRequest(
                "TRF-100", "ACC-1", "ACC-2", TransferType.OWN_ACCOUNT,
                new BigDecimal("500.00"), "INR", "REF-100", "Remarks", "Dest", null);

        mockMvc.perform(post("/internal/ledger/transfers")
                        .header(HEADER_NAME, "wrong-token-value")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));

        verifyNoInteractions(ledgerExecutionService);
    }

    @Test
    @DisplayName("GET /internal/ledger/transfers/{transferId} returns 401 Unauthorized when service token is missing")
    void shouldReturn401WhenServiceTokenMissingOnGet() throws Exception {
        mockMvc.perform(get("/internal/ledger/transfers/TRF-100"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));

        verifyNoInteractions(ledgerExecutionService);
    }

    @Test
    @DisplayName("GET /internal/ledger/transfers/{transferId} returns 401 Unauthorized when service token is invalid")
    void shouldReturn401WhenServiceTokenInvalidOnGet() throws Exception {
        mockMvc.perform(get("/internal/ledger/transfers/TRF-100")
                        .header(HEADER_NAME, "invalid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));

        verifyNoInteractions(ledgerExecutionService);
    }

    @Test
    @DisplayName("POST /internal/ledger/transfers returns 201 Created when new transaction is committed")
    void shouldReturn201CreatedWhenNewTransaction() throws Exception {
        LedgerTransferExecutionRequest request = new LedgerTransferExecutionRequest(
                "TRF-100",
                "ACC-1",
                "ACC-2",
                TransferType.OWN_ACCOUNT,
                new BigDecimal("500.00"),
                "INR",
                "REF-100",
                "Remarks",
                "Dest",
                null
        );

        LedgerTransactionOutcomeResponse response = new LedgerTransactionOutcomeResponse(
                "TRF-100",
                "TX-100",
                "COMMITTED",
                "ACC-1",
                "ACC-2",
                "OWN_ACCOUNT",
                new BigDecimal("500.00"),
                "INR",
                "REF-100",
                "Remarks",
                "Dest",
                null,
                Instant.now()
        );

        when(ledgerExecutionService.processTransfer(any()))
                .thenReturn(new LedgerExecutionService.ExecutionResult(response, true));

        mockMvc.perform(post("/internal/ledger/transfers")
                        .header(HEADER_NAME, VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transferId").value("TRF-100"))
                .andExpect(jsonPath("$.transactionId").value("TX-100"))
                .andExpect(jsonPath("$.status").value("COMMITTED"));
    }

    @Test
    @DisplayName("POST /internal/ledger/transfers returns 200 OK when matching transaction already exists")
    void shouldReturn200OkWhenMatchingTransactionExists() throws Exception {
        LedgerTransferExecutionRequest request = new LedgerTransferExecutionRequest(
                "TRF-100",
                "ACC-1",
                "ACC-2",
                TransferType.OWN_ACCOUNT,
                new BigDecimal("500.00"),
                "INR",
                "REF-100",
                "Remarks",
                "Dest",
                null
        );

        LedgerTransactionOutcomeResponse response = new LedgerTransactionOutcomeResponse(
                "TRF-100",
                "TX-100",
                "COMMITTED",
                "ACC-1",
                "ACC-2",
                "OWN_ACCOUNT",
                new BigDecimal("500.00"),
                "INR",
                "REF-100",
                "Remarks",
                "Dest",
                null,
                Instant.now()
        );

        when(ledgerExecutionService.processTransfer(any()))
                .thenReturn(new LedgerExecutionService.ExecutionResult(response, false));

        mockMvc.perform(post("/internal/ledger/transfers")
                        .header(HEADER_NAME, VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferId").value("TRF-100"))
                .andExpect(jsonPath("$.status").value("COMMITTED"));
    }

    @Test
    @DisplayName("POST /internal/ledger/transfers returns 202 Accepted when execution is actively PROCESSING")
    void shouldReturn202AcceptedWhenProcessing() throws Exception {
        LedgerTransferExecutionRequest request = new LedgerTransferExecutionRequest(
                "TRF-100",
                "ACC-1",
                "ACC-2",
                TransferType.OWN_ACCOUNT,
                new BigDecimal("500.00"),
                "INR",
                "REF-100",
                "Remarks",
                "Dest",
                null
        );

        LedgerTransactionOutcomeResponse response = new LedgerTransactionOutcomeResponse(
                "TRF-100",
                null,
                "PROCESSING",
                "ACC-1",
                "ACC-2",
                "OWN_ACCOUNT",
                new BigDecimal("500.00"),
                "INR",
                "REF-100",
                "Remarks",
                "Dest",
                null,
                Instant.now()
        );

        when(ledgerExecutionService.processTransfer(any()))
                .thenReturn(new LedgerExecutionService.ExecutionResult(response, false));

        mockMvc.perform(post("/internal/ledger/transfers")
                        .header(HEADER_NAME, VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.transferId").value("TRF-100"))
                .andExpect(jsonPath("$.status").value("PROCESSING"));
    }

    @Test
    @DisplayName("POST /internal/ledger/transfers returns 409 Conflict when transferId has conflicting details")
    void shouldReturn409ConflictOnConflictingDuplicate() throws Exception {
        LedgerTransferExecutionRequest request = new LedgerTransferExecutionRequest(
                "TRF-100",
                "ACC-1",
                "ACC-2",
                TransferType.OWN_ACCOUNT,
                new BigDecimal("500.00"),
                "INR",
                "REF-100",
                "Remarks",
                "Dest",
                null
        );

        when(ledgerExecutionService.processTransfer(any()))
                .thenThrow(new TransferIdConflictException("Conflict detected", "TRF-100"));

        mockMvc.perform(post("/internal/ledger/transfers")
                        .header(HEADER_NAME, VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSFER_ID_REUSED_WITH_DIFFERENT_DETAILS"))
                .andExpect(jsonPath("$.transferId").value("TRF-100"));
    }

    @Test
    @DisplayName("POST /internal/ledger/transfers returns 422 Unprocessable Entity when balance is insufficient")
    void shouldReturn422OnInsufficientFunds() throws Exception {
        LedgerTransferExecutionRequest request = new LedgerTransferExecutionRequest(
                "TRF-100",
                "ACC-1",
                "ACC-2",
                TransferType.OWN_ACCOUNT,
                new BigDecimal("50000.00"),
                "INR",
                "REF-100",
                "Remarks",
                "Dest",
                null
        );

        when(ledgerExecutionService.processTransfer(any()))
                .thenThrow(new InsufficientFundsException("Insufficient funds in account", "TRF-100"));

        mockMvc.perform(post("/internal/ledger/transfers")
                        .header(HEADER_NAME, VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.transferId").value("TRF-100"));
    }

    @Test
    @DisplayName("POST /internal/ledger/transfers returns 404 Not Found when account is missing")
    void shouldReturn404OnAccountNotFound() throws Exception {
        LedgerTransferExecutionRequest request = new LedgerTransferExecutionRequest(
                "TRF-100",
                "ACC-MISSING",
                "ACC-2",
                TransferType.OWN_ACCOUNT,
                new BigDecimal("500.00"),
                "INR",
                "REF-100",
                "Remarks",
                "Dest",
                null
        );

        when(ledgerExecutionService.processTransfer(any()))
                .thenThrow(new AccountNotFoundException("Source account not found", "TRF-100"));

        mockMvc.perform(post("/internal/ledger/transfers")
                        .header(HEADER_NAME, VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"))
                .andExpect(jsonPath("$.transferId").value("TRF-100"));
    }

    @Test
    @DisplayName("GET /internal/ledger/transfers/{transferId} returns 200 OK when COMMITTED")
    void shouldReturn200OnGetTransferOutcome() throws Exception {
        LedgerTransactionOutcomeResponse response = new LedgerTransactionOutcomeResponse(
                "TRF-200",
                "TX-200",
                "COMMITTED",
                "ACC-1",
                null,
                "OTHER_BANK",
                new BigDecimal("150.00"),
                "INR",
                "REF-200",
                "Rent",
                "Landlord",
                "ICIC0001234/12345678",
                Instant.now()
        );

        when(ledgerExecutionService.getOutcome("TRF-200")).thenReturn(response);

        mockMvc.perform(get("/internal/ledger/transfers/TRF-200")
                        .header(HEADER_NAME, VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferId").value("TRF-200"))
                .andExpect(jsonPath("$.transactionId").value("TX-200"))
                .andExpect(jsonPath("$.status").value("COMMITTED"));
    }

    @Test
    @DisplayName("GET /internal/ledger/transfers/{transferId} returns 200 OK when REJECTED")
    void shouldReturn200OnGetRejectedTransferOutcome() throws Exception {
        LedgerTransactionOutcomeResponse response = new LedgerTransactionOutcomeResponse(
                "TRF-201",
                null,
                "REJECTED",
                "ACC-1",
                null,
                "OTHER_BANK",
                new BigDecimal("150000.00"),
                "INR",
                "REF-201",
                "Rent",
                "Landlord",
                "ICIC0001234/12345678",
                Instant.now(),
                "INSUFFICIENT_FUNDS",
                "Insufficient balance in source account"
        );

        when(ledgerExecutionService.getOutcome("TRF-201")).thenReturn(response);

        mockMvc.perform(get("/internal/ledger/transfers/TRF-201")
                        .header(HEADER_NAME, VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferId").value("TRF-201"))
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.statusReasonCode").value("INSUFFICIENT_FUNDS"));
    }

    @Test
    @DisplayName("GET /internal/ledger/transfers/{transferId} returns 202 Accepted when PROCESSING")
    void shouldReturn202OnGetProcessingTransferOutcome() throws Exception {
        LedgerTransactionOutcomeResponse response = new LedgerTransactionOutcomeResponse(
                "TRF-202",
                null,
                "PROCESSING",
                "ACC-1",
                null,
                "OTHER_BANK",
                new BigDecimal("150.00"),
                "INR",
                "REF-202",
                "Rent",
                "Landlord",
                "ICIC0001234/12345678",
                Instant.now()
        );

        when(ledgerExecutionService.getOutcome("TRF-202")).thenReturn(response);

        mockMvc.perform(get("/internal/ledger/transfers/TRF-202")
                        .header(HEADER_NAME, VALID_TOKEN))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.transferId").value("TRF-202"))
                .andExpect(jsonPath("$.status").value("PROCESSING"));
    }

    @Test
    @DisplayName("GET /internal/ledger/transfers/{transferId} returns 404 Not Found when transaction not found")
    void shouldReturn404OnGetTransferOutcomeNotFound() throws Exception {
        when(ledgerExecutionService.getOutcome("TRF-NOT-FOUND"))
                .thenThrow(new TransactionNotFoundException("No transaction found", "TRF-NOT-FOUND"));

        mockMvc.perform(get("/internal/ledger/transfers/TRF-NOT-FOUND")
                        .header(HEADER_NAME, VALID_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSACTION_NOT_FOUND"))
                .andExpect(jsonPath("$.transferId").value("TRF-NOT-FOUND"));
    }
}
