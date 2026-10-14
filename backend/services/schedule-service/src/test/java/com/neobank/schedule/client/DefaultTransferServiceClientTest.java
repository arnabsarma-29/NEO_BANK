package com.neobank.schedule.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class DefaultTransferServiceClientTest {

    private static final String BASE_URL = "http://localhost:8082";
    private static final String TOKEN = "dev-schedule-to-transfer-token-secret-local";

    private RestClient.Builder restClientBuilder;
    private MockRestServiceServer mockServer;
    private DefaultTransferServiceClient client;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        restClientBuilder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();
        client = new DefaultTransferServiceClient(
                restClientBuilder,
                BASE_URL,
                TOKEN,
                objectMapper
        );
    }

    private TransferExecutionRequest sampleRequest() {
        return new TransferExecutionRequest(
                "TRF-SCH-1001",
                "IDEMP-TRF-SCH-1001",
                "CUST-001",
                "ACC-SRC-1",
                "ACC-DST-2",
                null,
                "OWN_ACCOUNT",
                new BigDecimal("150.00"),
                "INR",
                "Scheduled recurring transfer"
        );
    }

    // 1. Correct execution URL, HTTP method, JSON payload, and token header
    @Test
    @DisplayName("1. Correct execution URL, POST method, JSON payload, and X-Internal-Service-Token header")
    void shouldSendCorrectExecutionRequestWithUrlMethodPayloadAndHeader() {
        mockServer.expect(requestTo("http://localhost:8082/internal/transfers/scheduled-executions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Service-Token", TOKEN))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.transferId").value("TRF-SCH-1001"))
                .andExpect(jsonPath("$.idempotencyKey").value("IDEMP-TRF-SCH-1001"))
                .andExpect(jsonPath("$.customerId").value("CUST-001"))
                .andExpect(jsonPath("$.sourceAccountId").value("ACC-SRC-1"))
                .andExpect(jsonPath("$.destinationAccountId").value("ACC-DST-2"))
                .andExpect(jsonPath("$.transferType").value("OWN_ACCOUNT"))
                .andExpect(jsonPath("$.amount").value(150.00))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"transferId\":\"TRF-SCH-1001\",\"referenceNumber\":\"TXN-SCH-1\",\"status\":\"COMMITTED\",\"retryable\":false}"));

        TransferExecutionResponse response = client.executeTransfer(sampleRequest());

        mockServer.verify();
        assertEquals("SUCCESS", response.status());
        assertEquals("TRF-SCH-1001", response.transferId());
        assertEquals("TXN-SCH-1", response.referenceNumber());
        assertFalse(response.retryable());
        assertNull(response.failureReason());
    }

    // 2. Correct reconciliation URL and token header
    @Test
    @DisplayName("2. Correct reconciliation URL, GET method, and token header")
    void shouldSendCorrectReconciliationRequestWithUrlMethodAndHeader() {
        mockServer.expect(requestTo("http://localhost:8082/internal/transfers/TRF-SCH-1001/reconcile"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Service-Token", TOKEN))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"transferId\":\"TRF-SCH-1001\",\"referenceNumber\":\"TXN-SCH-1\",\"status\":\"COMMITTED\",\"retryable\":false}"));

        TransferStatusResponse response = client.reconcileTransfer("TRF-SCH-1001");

        mockServer.verify();
        assertEquals("SUCCESS", response.status());
        assertEquals("TRF-SCH-1001", response.transferId());
        assertEquals("TXN-SCH-1", response.referenceNumber());
        assertNull(response.failureReason());
    }

    // 3. HTTP 200 and 201 committed outcomes -> SUCCESS
    @Test
    @DisplayName("3a. HTTP 201 with COMMITTED status maps to SUCCESS")
    void shouldMapHttp201CommittedToSuccess() {
        mockServer.expect(requestTo("http://localhost:8082/internal/transfers/scheduled-executions"))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"transferId\":\"TRF-SCH-1001\",\"referenceNumber\":\"TXN-SCH-201\",\"status\":\"COMMITTED\"}"));

        TransferExecutionResponse response = client.executeTransfer(sampleRequest());

        mockServer.verify();
        assertEquals("SUCCESS", response.status());
        assertEquals("TXN-SCH-201", response.referenceNumber());
        assertFalse(response.retryable());
    }

    @Test
    @DisplayName("3b. HTTP 200 with COMMITTED status maps to SUCCESS (idempotent replay)")
    void shouldMapHttp200CommittedToSuccess() {
        mockServer.expect(requestTo("http://localhost:8082/internal/transfers/scheduled-executions"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"transferId\":\"TRF-SCH-1001\",\"referenceNumber\":\"TXN-SCH-200\",\"status\":\"COMMITTED\"}"));

        TransferExecutionResponse response = client.executeTransfer(sampleRequest());

        mockServer.verify();
        assertEquals("SUCCESS", response.status());
        assertEquals("TXN-SCH-200", response.referenceNumber());
        assertFalse(response.retryable());
    }

    // 4. HTTP 202 processing/uncertain outcomes -> OUTCOME_UNKNOWN
    @Test
    @DisplayName("4a. HTTP 202 with PROCESSING maps to OUTCOME_UNKNOWN with retryable=true")
    void shouldMapHttp202ProcessingToOutcomeUnknown() {
        mockServer.expect(requestTo("http://localhost:8082/internal/transfers/scheduled-executions"))
                .andRespond(withStatus(HttpStatus.ACCEPTED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"transferId\":\"TRF-SCH-1001\",\"status\":\"PROCESSING\",\"nextAction\":\"WAIT\",\"retryable\":true}"));

        TransferExecutionResponse response = client.executeTransfer(sampleRequest());

        mockServer.verify();
        assertEquals("OUTCOME_UNKNOWN", response.status());
        assertTrue(response.retryable());
    }

    @Test
    @DisplayName("4b. HTTP 202 with OUTCOME_UNKNOWN maps to OUTCOME_UNKNOWN with retryable=true")
    void shouldMapHttp202OutcomeUnknownToOutcomeUnknown() {
        mockServer.expect(requestTo("http://localhost:8082/internal/transfers/scheduled-executions"))
                .andRespond(withStatus(HttpStatus.ACCEPTED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"transferId\":\"TRF-SCH-1001\",\"status\":\"OUTCOME_UNKNOWN\",\"failureReason\":\"FINANCIAL_OUTCOME_PENDING_RECONCILIATION\",\"retryable\":true}"));

        TransferExecutionResponse response = client.executeTransfer(sampleRequest());

        mockServer.verify();
        assertEquals("OUTCOME_UNKNOWN", response.status());
        assertEquals("FINANCIAL_OUTCOME_PENDING_RECONCILIATION", response.failureReason());
        assertTrue(response.retryable());
    }

    // 5. HTTP 422 definitive rejection -> FAILED, non-retryable
    @Test
    @DisplayName("5. HTTP 422 definitive rejection maps to FAILED and non-retryable")
    void shouldMapHttp422DefinitiveRejectionToPermanentFailure() {
        mockServer.expect(requestTo("http://localhost:8082/internal/transfers/scheduled-executions"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"transferId\":\"TRF-SCH-1001\",\"status\":\"REJECTED\",\"failureReason\":\"INSUFFICIENT_FUNDS\",\"retryable\":false}"));

        TransferExecutionResponse response = client.executeTransfer(sampleRequest());

        mockServer.verify();
        assertEquals("FAILED", response.status());
        assertEquals("INSUFFICIENT_FUNDS", response.failureReason());
        assertFalse(response.retryable());
    }

    // 6. HTTP 409 conflict -> FAILED, non-retryable; does not report success
    @Test
    @DisplayName("6. HTTP 409 conflict maps to FAILED and non-retryable")
    void shouldMapHttp409ConflictToPermanentFailure() {
        mockServer.expect(requestTo("http://localhost:8082/internal/transfers/scheduled-executions"))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"transferId\":\"TRF-SCH-1001\",\"status\":\"REJECTED\",\"failureReason\":\"TRANSFER_ID_REUSED_WITH_DIFFERENT_DETAILS\",\"retryable\":false}"));

        TransferExecutionResponse response = client.executeTransfer(sampleRequest());

        mockServer.verify();
        assertEquals("FAILED", response.status());
        assertEquals("TRANSFER_ID_REUSED_WITH_DIFFERENT_DETAILS", response.failureReason());
        assertFalse(response.retryable());
    }

    // 7. HTTP 503 retryable pre-Ledger failure -> FAILED, retryable=true
    @Test
    @DisplayName("7. HTTP 503 pre-Ledger failure maps to retryable transient failure")
    void shouldMapHttp503ToTransientFailure() {
        mockServer.expect(requestTo("http://localhost:8082/internal/transfers/scheduled-executions"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"transferId\":\"TRF-SCH-1001\",\"status\":\"FAILED_PRE_LEDGER\",\"failureReason\":\"LEDGER_UNAVAILABLE\",\"retryable\":true}"));

        TransferExecutionResponse response = client.executeTransfer(sampleRequest());

        mockServer.verify();
        assertEquals("FAILED", response.status());
        assertEquals("LEDGER_UNAVAILABLE", response.failureReason());
        assertTrue(response.retryable());
    }

    // 8. HTTP 401/403 authentication failure -> permanent failure, not normal transient business failure
    @Test
    @DisplayName("8a. HTTP 401 authentication failure maps to permanent failure without normal retry")
    void shouldMapHttp401ToPermanentFailure() {
        mockServer.expect(requestTo("http://localhost:8082/internal/transfers/scheduled-executions"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"UNAUTHORIZED\",\"message\":\"Missing or invalid internal service token\"}"));

        TransferExecutionResponse response = client.executeTransfer(sampleRequest());

        mockServer.verify();
        assertEquals("FAILED", response.status());
        assertFalse(response.retryable());
        assertTrue(response.failureReason().contains("AUTHENTICATION_CONFIGURATION_FAILURE"));
    }

    @Test
    @DisplayName("8b. HTTP 403 authorization failure maps to permanent failure")
    void shouldMapHttp403ToPermanentFailure() {
        mockServer.expect(requestTo("http://localhost:8082/internal/transfers/scheduled-executions"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"FORBIDDEN\",\"message\":\"Access denied\"}"));

        TransferExecutionResponse response = client.executeTransfer(sampleRequest());

        mockServer.verify();
        assertEquals("FAILED", response.status());
        assertFalse(response.retryable());
        assertTrue(response.failureReason().contains("AUTHENTICATION_CONFIGURATION_FAILURE"));
    }

    // 9. Timeout or connection loss mapped to uncertainty
    @Test
    @DisplayName("9. Connection loss or read timeout maps to OUTCOME_UNKNOWN (not definitive failure)")
    void shouldMapTimeoutOrConnectionLossToOutcomeUnknown() {
        mockServer.expect(requestTo("http://localhost:8082/internal/transfers/scheduled-executions"))
                .andRespond(request -> {
                    throw new ResourceAccessException("I/O error on POST request: Read timed out");
                });

        TransferExecutionResponse response = client.executeTransfer(sampleRequest());

        mockServer.verify();
        assertEquals("OUTCOME_UNKNOWN", response.status());
        assertTrue(response.retryable());
        assertTrue(response.failureReason().contains("NETWORK_TIMEOUT_OR_CONNECTION_LOST"));
    }

    // 10. Reconciliation: 200 committed, 200 rejected, 202, and 404
    @Test
    @DisplayName("10a. Reconciliation HTTP 200 with COMMITTED maps to SUCCESS")
    void shouldMapReconciliation200CommittedToSuccess() {
        mockServer.expect(requestTo("http://localhost:8082/internal/transfers/TRF-SCH-1001/reconcile"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"transferId\":\"TRF-SCH-1001\",\"referenceNumber\":\"TXN-REC-1\",\"status\":\"COMMITTED\"}"));

        TransferStatusResponse response = client.reconcileTransfer("TRF-SCH-1001");

        mockServer.verify();
        assertEquals("SUCCESS", response.status());
        assertEquals("TXN-REC-1", response.referenceNumber());
        assertNull(response.failureReason());
    }

    @Test
    @DisplayName("10b. Reconciliation HTTP 200 with REJECTED maps to FAILED")
    void shouldMapReconciliation200RejectedToFailed() {
        mockServer.expect(requestTo("http://localhost:8082/internal/transfers/TRF-SCH-1001/reconcile"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"transferId\":\"TRF-SCH-1001\",\"referenceNumber\":\"TXN-REC-1\",\"status\":\"REJECTED\",\"failureReason\":\"INSUFFICIENT_FUNDS\"}"));

        TransferStatusResponse response = client.reconcileTransfer("TRF-SCH-1001");

        mockServer.verify();
        assertEquals("FAILED", response.status());
        assertEquals("INSUFFICIENT_FUNDS", response.failureReason());
    }

    @Test
    @DisplayName("10c. Reconciliation HTTP 202 maps to UNKNOWN preserving uncertainty semantics")
    void shouldMapReconciliation202ToUnknown() {
        mockServer.expect(requestTo("http://localhost:8082/internal/transfers/TRF-SCH-1001/reconcile"))
                .andRespond(withStatus(HttpStatus.ACCEPTED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"transferId\":\"TRF-SCH-1001\",\"status\":\"PROCESSING\",\"nextAction\":\"WAIT\"}"));

        TransferStatusResponse response = client.reconcileTransfer("TRF-SCH-1001");

        mockServer.verify();
        assertEquals("UNKNOWN", response.status());
    }

    @Test
    @DisplayName("10d. Reconciliation HTTP 404 maps to UNKNOWN (404 does not prove failure)")
    void shouldMapReconciliation404ToUnknown() {
        mockServer.expect(requestTo("http://localhost:8082/internal/transfers/TRF-SCH-1001/reconcile"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        TransferStatusResponse response = client.reconcileTransfer("TRF-SCH-1001");

        mockServer.verify();
        assertEquals("UNKNOWN", response.status());
        assertEquals("TRANSFER_NOT_FOUND_DOWNSTREAM", response.failureReason());
    }

    // 11. Unexpected response/status combinations do not become success
    @Test
    @DisplayName("11a. HTTP 200 with unexpected status string maps to OUTCOME_UNKNOWN, not SUCCESS")
    void shouldMapUnexpectedStatusIn200ToOutcomeUnknown() {
        mockServer.expect(requestTo("http://localhost:8082/internal/transfers/scheduled-executions"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"transferId\":\"TRF-SCH-1001\",\"status\":\"SUSPENDED\"}"));

        TransferExecutionResponse response = client.executeTransfer(sampleRequest());

        mockServer.verify();
        assertEquals("OUTCOME_UNKNOWN", response.status());
        assertTrue(response.failureReason().contains("UNEXPECTED_STATUS_IN_SUCCESS_RESPONSE"));
    }

    @Test
    @DisplayName("11b. HTTP 200 with empty body maps to OUTCOME_UNKNOWN, not SUCCESS")
    void shouldMapEmptyBodyIn200ToOutcomeUnknown() {
        mockServer.expect(requestTo("http://localhost:8082/internal/transfers/scheduled-executions"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(""));

        TransferExecutionResponse response = client.executeTransfer(sampleRequest());

        mockServer.verify();
        assertEquals("OUTCOME_UNKNOWN", response.status());
        assertEquals("EMPTY_RESPONSE_BODY", response.failureReason());
    }

    @Test
    @DisplayName("11c. HTTP 200 with malformed JSON maps to OUTCOME_UNKNOWN, not SUCCESS")
    void shouldMapMalformedJsonToOutcomeUnknown() {
        mockServer.expect(requestTo("http://localhost:8082/internal/transfers/scheduled-executions"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{not valid json syntax"));

        TransferExecutionResponse response = client.executeTransfer(sampleRequest());

        mockServer.verify();
        assertEquals("OUTCOME_UNKNOWN", response.status());
        assertTrue(response.failureReason().contains("MALFORMED_JSON_RESPONSE"));
    }

    @Test
    @DisplayName("11d. HTTP 500 internal server error maps to OUTCOME_UNKNOWN, not SUCCESS")
    void shouldMapHttp500ToOutcomeUnknown() {
        mockServer.expect(requestTo("http://localhost:8082/internal/transfers/scheduled-executions"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"INTERNAL_SERVER_ERROR\",\"message\":\"An unexpected error occurred\"}"));

        TransferExecutionResponse response = client.executeTransfer(sampleRequest());

        mockServer.verify();
        assertEquals("OUTCOME_UNKNOWN", response.status());
        assertTrue(response.failureReason().contains("HTTP_ERROR_500"));
    }
}
