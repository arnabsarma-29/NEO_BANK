package com.neobank.transfer.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.neobank.transfer.domain.NextAction;
import com.neobank.transfer.domain.TransferStatus;
import com.neobank.transfer.domain.TransferType;
import com.neobank.transfer.domain.TransferWorkflow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class DefaultLedgerClientTest {

    private static final String BASE_URL = "http://localhost:8083";
    private static final String SERVICE_TOKEN = "dev-transfer-to-ledger-token-secret-local";

    private MockRestServiceServer mockServer;
    private DefaultLedgerClient ledgerClient;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        ledgerClient = new DefaultLedgerClient(builder, BASE_URL, SERVICE_TOKEN);
        ledgerClient.setBackoffs(new long[]{0L, 0L, 0L}); // Instantaneous backoff for fast tests
    }

    private TransferWorkflow createSampleWorkflow(String transferId) {
        return new TransferWorkflow(
                transferId,
                "REF-SIM-001",
                "C1001",
                "ACC-1001",
                "ACC-1002",
                null,
                TransferType.OWN_ACCOUNT,
                new BigDecimal("500.00"),
                "INR",
                "Test transfer",
                "Own Account",
                "ACC-1002",
                TransferStatus.READY_FOR_EXECUTION,
                NextAction.WAIT,
                Instant.now()
        );
    }

    @Test
    @DisplayName("1. Transfer Service sends the correct Ledger API request, payload, and X-Internal-Service-Token header")
    void shouldSendCorrectRequestAndTokenHeader() throws Exception {
        TransferWorkflow workflow = createSampleWorkflow("TRF-001");

        Map<String, Object> responseBody = Map.of(
                "transferId", "TRF-001",
                "transactionId", "TXN-999",
                "status", "COMMITTED",
                "amount", 500.00,
                "currency", "INR"
        );

        mockServer.expect(requestTo(BASE_URL + "/internal/ledger/transfers"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Service-Token", SERVICE_TOKEN))
                .andExpect(header("Content-Type", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(jsonPath("$.transferId").value("TRF-001"))
                .andExpect(jsonPath("$.sourceAccountId").value("ACC-1001"))
                .andExpect(jsonPath("$.destinationAccountId").value("ACC-1002"))
                .andExpect(jsonPath("$.transferType").value("OWN_ACCOUNT"))
                .andExpect(jsonPath("$.amount").value(500.00))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.referenceNumber").value("REF-SIM-001"))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(objectMapper.writeValueAsString(responseBody)));

        LedgerClient.LedgerExecutionOutcome outcome = ledgerClient.executeTransfer(workflow);

        mockServer.verify();
        assertThat(outcome.status()).isEqualTo(LedgerClient.LedgerOutcomeStatus.COMMITTED);
        assertThat(outcome.transactionId()).isEqualTo("TXN-999");
        assertThat(outcome.timestamp()).isNotNull();
    }

    @Test
    @DisplayName("2. Successful idempotent replay (HTTP 200 COMMITTED/SUCCESS) is propagated back to Transfer Service")
    void shouldHandle200OkIdempotentReplay() throws Exception {
        TransferWorkflow workflow = createSampleWorkflow("TRF-002");

        Map<String, Object> responseBody = Map.of(
                "transferId", "TRF-002",
                "transactionId", "TXN-ORIGINAL-002",
                "status", "SUCCESS"
        );

        mockServer.expect(requestTo(BASE_URL + "/internal/ledger/transfers"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Service-Token", SERVICE_TOKEN))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(objectMapper.writeValueAsString(responseBody)));

        LedgerClient.LedgerExecutionOutcome outcome = ledgerClient.executeTransfer(workflow);

        mockServer.verify();
        assertThat(outcome.status()).isEqualTo(LedgerClient.LedgerOutcomeStatus.COMMITTED);
        assertThat(outcome.transactionId()).isEqualTo("TXN-ORIGINAL-002");
    }

    @Test
    @DisplayName("3. Terminal business rejection (HTTP 422 INSUFFICIENT_FUNDS) is mapped to REJECTED without retry")
    void shouldHandle422InsufficientFundsWithoutRetry() throws Exception {
        TransferWorkflow workflow = createSampleWorkflow("TRF-003");

        Map<String, Object> errorBody = Map.of(
                "status", 422,
                "code", "INSUFFICIENT_FUNDS",
                "message", "Insufficient funds in source account",
                "transferId", "TRF-003"
        );

        mockServer.expect(requestTo(BASE_URL + "/internal/ledger/transfers"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(objectMapper.writeValueAsString(errorBody)));

        LedgerClient.LedgerExecutionOutcome outcome = ledgerClient.executeTransfer(workflow);

        mockServer.verify(); // Verifies exactly 1 request was made (no retry for terminal rejection)
        assertThat(outcome.status()).isEqualTo(LedgerClient.LedgerOutcomeStatus.REJECTED);
        assertThat(outcome.reasonCode()).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    @DisplayName("4. Conflicting request (HTTP 409) is rejected definitively without reconciling against conflicting transfer")
    void shouldRejectOn409ConflictWithoutReconciling() throws Exception {
        TransferWorkflow workflow = createSampleWorkflow("TRF-004");

        Map<String, Object> conflictError = Map.of(
                "status", 409,
                "code", "TRANSFER_ID_REUSED_WITH_DIFFERENT_DETAILS",
                "message", "The transferId is already associated with different immutable financial details.",
                "transferId", "TRF-004"
        );

        // POST returns 409 Conflict
        mockServer.expect(requestTo(BASE_URL + "/internal/ledger/transfers"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(objectMapper.writeValueAsString(conflictError)));

        // No GET request expected! A 409 must NOT reconcile or claim success.
        LedgerClient.LedgerExecutionOutcome outcome = ledgerClient.executeTransfer(workflow);

        mockServer.verify();
        assertThat(outcome.status()).isEqualTo(LedgerClient.LedgerOutcomeStatus.REJECTED);
        assertThat(outcome.reasonCode()).isEqualTo("TRANSFER_ID_REUSED_WITH_DIFFERENT_DETAILS");
    }

    @Test
    @DisplayName("5. Transient 500 error retries and succeeds on subsequent attempt retaining same transferId")
    void shouldRetryOnTransientErrorAndSucceed() throws Exception {
        TransferWorkflow workflow = createSampleWorkflow("TRF-005");

        Map<String, Object> successResponse = Map.of(
                "transferId", "TRF-005",
                "transactionId", "TXN-RETRY-005",
                "status", "COMMITTED"
        );

        // Attempt 1: 500 Internal Server Error
        mockServer.expect(requestTo(BASE_URL + "/internal/ledger/transfers"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.transferId").value("TRF-005"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        // Attempt 2: 201 Created
        mockServer.expect(requestTo(BASE_URL + "/internal/ledger/transfers"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.transferId").value("TRF-005"))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(objectMapper.writeValueAsString(successResponse)));

        LedgerClient.LedgerExecutionOutcome outcome = ledgerClient.executeTransfer(workflow);

        mockServer.verify();
        assertThat(outcome.status()).isEqualTo(LedgerClient.LedgerOutcomeStatus.COMMITTED);
        assertThat(outcome.transactionId()).isEqualTo("TXN-RETRY-005");
    }

    @Test
    @DisplayName("6. Exhausting all 3 attempts followed by 404 lookup yields UNCERTAIN outcome without failing transfer")
    void shouldYieldUncertainOutcomeWhenExhaustedAndLookup404() throws Exception {
        TransferWorkflow workflow = createSampleWorkflow("TRF-006");

        // Attempt 1, 2, 3 fail with 503 Service Unavailable
        for (int i = 1; i <= 3; i++) {
            mockServer.expect(requestTo(BASE_URL + "/internal/ledger/transfers"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(jsonPath("$.transferId").value("TRF-006"))
                    .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        }

        // Reconciliation lookup GET returns 404 Not Found
        mockServer.expect(requestTo(BASE_URL + "/internal/ledger/transfers/TRF-006"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Service-Token", SERVICE_TOKEN))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        LedgerClient.LedgerExecutionOutcome outcome = ledgerClient.executeTransfer(workflow);

        mockServer.verify();
        // Preserves UNCERTAIN status, allowing TransferWorkflowService to set OUTCOME_UNKNOWN rather than FAILED
        assertThat(outcome.status()).isEqualTo(LedgerClient.LedgerOutcomeStatus.UNCERTAIN);
        assertThat(outcome.reasonCode()).isEqualTo("FINANCIAL_OUTCOME_PENDING_RECONCILIATION");
    }

    @Test
    @DisplayName("7. lookupOutcome returns COMMITTED outcome when transaction is committed")
    void shouldLookupCommittedOutcome() throws Exception {
        Map<String, Object> committedResponse = Map.of(
                "transferId", "TRF-LKP-1",
                "transactionId", "TXN-LKP-1",
                "status", "COMMITTED"
        );
        mockServer.expect(requestTo(BASE_URL + "/internal/ledger/transfers/TRF-LKP-1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Service-Token", SERVICE_TOKEN))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(objectMapper.writeValueAsString(committedResponse)));

        Optional<LedgerClient.LedgerExecutionOutcome> opt = ledgerClient.lookupOutcome("TRF-LKP-1");
        assertThat(opt).isPresent();
        assertThat(opt.get().status()).isEqualTo(LedgerClient.LedgerOutcomeStatus.COMMITTED);
        assertThat(opt.get().transactionId()).isEqualTo("TXN-LKP-1");
        mockServer.verify();
    }

    @Test
    @DisplayName("8. lookupOutcome returns REJECTED outcome when transaction was rejected")
    void shouldLookupRejectedOutcome() throws Exception {
        Map<String, Object> rejectedResponse = Map.of(
                "transferId", "TRF-LKP-2",
                "status", "REJECTED",
                "statusReasonCode", "INSUFFICIENT_FUNDS"
        );
        mockServer.expect(requestTo(BASE_URL + "/internal/ledger/transfers/TRF-LKP-2"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Service-Token", SERVICE_TOKEN))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(objectMapper.writeValueAsString(rejectedResponse)));

        Optional<LedgerClient.LedgerExecutionOutcome> opt = ledgerClient.lookupOutcome("TRF-LKP-2");
        assertThat(opt).isPresent();
        assertThat(opt.get().status()).isEqualTo(LedgerClient.LedgerOutcomeStatus.REJECTED);
        assertThat(opt.get().reasonCode()).isEqualTo("INSUFFICIENT_FUNDS");
        mockServer.verify();
    }

    @Test
    @DisplayName("9. lookupOutcome returns empty when Ledger returns 404 Not Found")
    void shouldLookupNotFoundOutcome() throws Exception {
        mockServer.expect(requestTo(BASE_URL + "/internal/ledger/transfers/TRF-LKP-3"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Service-Token", SERVICE_TOKEN))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        Optional<LedgerClient.LedgerExecutionOutcome> opt = ledgerClient.lookupOutcome("TRF-LKP-3");
        assertThat(opt).isEmpty();
        mockServer.verify();
    }

    @Test
    @DisplayName("10. Service authentication failure (HTTP 401) sets AUTHENTICATION_FAILED without logging credentials")
    void shouldHandle401AuthFailure() throws Exception {
        TransferWorkflow workflow = createSampleWorkflow("TRF-010");

        mockServer.expect(requestTo(BASE_URL + "/internal/ledger/transfers"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        // Reconcile lookup also returns 401
        mockServer.expect(requestTo(BASE_URL + "/internal/ledger/transfers/TRF-010"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        LedgerClient.LedgerExecutionOutcome outcome = ledgerClient.executeTransfer(workflow);

        mockServer.verify();
        assertThat(outcome.status()).isEqualTo(LedgerClient.LedgerOutcomeStatus.UNCERTAIN);
        assertThat(outcome.reasonCode()).isEqualTo("AUTHENTICATION_FAILED");
    }

    @Test
    @DisplayName("11. In-flight execution (HTTP 202 PROCESSING) returns UNCERTAIN outcome with status PROCESSING")
    void shouldHandle202ProcessingOutcome() throws Exception {
        TransferWorkflow workflow = createSampleWorkflow("TRF-011");

        Map<String, Object> processingResponse = Map.of(
                "transferId", "TRF-011",
                "status", "PROCESSING"
        );

        mockServer.expect(requestTo(BASE_URL + "/internal/ledger/transfers"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.ACCEPTED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(objectMapper.writeValueAsString(processingResponse)));

        LedgerClient.LedgerExecutionOutcome outcome = ledgerClient.executeTransfer(workflow);

        mockServer.verify();
        assertThat(outcome.status()).isEqualTo(LedgerClient.LedgerOutcomeStatus.UNCERTAIN);
        assertThat(outcome.reasonCode()).isEqualTo("PROCESSING");
    }
}
