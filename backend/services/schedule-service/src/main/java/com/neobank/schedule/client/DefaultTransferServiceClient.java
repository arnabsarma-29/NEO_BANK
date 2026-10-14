package com.neobank.schedule.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.Optional;

/**
 * HTTP client communicating with Transfer Service's internal endpoints:
 * - POST /internal/transfers/scheduled-executions
 * - GET /internal/transfers/{transferId}/reconcile
 * Authenticated via X-Internal-Service-Token.
 */
@Component
public class DefaultTransferServiceClient implements TransferServiceClient {

    private static final Logger log = LoggerFactory.getLogger(DefaultTransferServiceClient.class);
    public static final String HEADER_SERVICE_TOKEN = "X-Internal-Service-Token";

    private final RestClient restClient;
    private final String transferServiceUrl;
    private final String serviceToken;
    private final ObjectMapper objectMapper;

    @Autowired
    public DefaultTransferServiceClient(
            @Value("${neobank.transfer.url:http://localhost:8082}") String transferServiceUrl,
            @Value("${neobank.schedule.transfer-service-token:dev-schedule-to-transfer-token-secret-local}") String serviceToken,
            Optional<ObjectMapper> objectMapper,
            Optional<RestClient.Builder> restClientBuilder) {
        this(restClientBuilder.orElseGet(() -> RestClient.builder().requestFactory(createDefaultRequestFactory())),
                transferServiceUrl,
                serviceToken,
                objectMapper.orElseGet(() -> new ObjectMapper().registerModule(new JavaTimeModule())));
    }

    public DefaultTransferServiceClient(
            RestClient.Builder restClientBuilder,
            String transferServiceUrl,
            String serviceToken,
            ObjectMapper objectMapper) {
        this.transferServiceUrl = transferServiceUrl;
        this.serviceToken = serviceToken;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper().registerModule(new JavaTimeModule());

        this.restClient = restClientBuilder
                .baseUrl(transferServiceUrl)
                .defaultHeader(HEADER_SERVICE_TOKEN, serviceToken)
                .build();
    }

    private static SimpleClientHttpRequestFactory createDefaultRequestFactory() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        return requestFactory;
    }

    @Override
    public TransferExecutionResponse executeTransfer(TransferExecutionRequest request) {
        String transferId = request.transferId();
        log.info("Dispatching scheduled transfer [{}] to Transfer Service at {}/internal/transfers/scheduled-executions",
                transferId, transferServiceUrl);

        try {
            ResponseEntity<String> response = restClient.post()
                    .uri("/internal/transfers/scheduled-executions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .toEntity(String.class);

            int statusCode = response.getStatusCode().value();
            String responseBody = response.getBody();
            return parseExecutionSuccessOrAccepted(transferId, statusCode, responseBody);

        } catch (RestClientResponseException ex) {
            int statusCode = ex.getStatusCode().value();
            String responseBody = ex.getResponseBodyAsString();
            log.warn("Transfer Service execution returned HTTP {} for transfer [{}]: {}",
                    statusCode, transferId, responseBody);
            return handleExecutionHttpError(transferId, statusCode, responseBody);

        } catch (ResourceAccessException ex) {
            log.error("Network or timeout error calling Transfer Service for transfer [{}]: {}",
                    transferId, ex.getMessage());
            return TransferExecutionResponse.outcomeUnknown(transferId,
                    "NETWORK_TIMEOUT_OR_CONNECTION_LOST: " + ex.getMessage());

        } catch (Exception ex) {
            log.error("Unexpected client error calling Transfer Service for transfer [{}]: {}",
                    transferId, ex.getMessage(), ex);
            return TransferExecutionResponse.outcomeUnknown(transferId,
                    "UNEXPECTED_CLIENT_ERROR: " + ex.getMessage());
        }
    }

    private TransferExecutionResponse parseExecutionSuccessOrAccepted(String transferId, int statusCode, String body) {
        if (body == null || body.isBlank()) {
            log.warn("Transfer Service returned empty body with HTTP {} for transfer [{}]", statusCode, transferId);
            return TransferExecutionResponse.outcomeUnknown(transferId, "EMPTY_RESPONSE_BODY");
        }

        try {
            JsonNode root = objectMapper.readTree(body);
            String status = root.path("status").asText(null);
            String refNumber = root.path("referenceNumber").asText(null);
            String failureReason = root.hasNonNull("failureReason")
                    ? root.path("failureReason").asText()
                    : root.path("statusReasonCode").asText(null);

            if (statusCode == 200 || statusCode == 201) {
                if ("COMMITTED".equalsIgnoreCase(status) || "SUCCESS".equalsIgnoreCase(status)) {
                    return TransferExecutionResponse.success(transferId, refNumber != null ? refNumber : transferId);
                }
                log.warn("Transfer Service returned HTTP {} with unexpected non-committed status '{}' for transfer [{}]",
                        statusCode, status, transferId);
                return TransferExecutionResponse.outcomeUnknown(transferId,
                        "UNEXPECTED_STATUS_IN_SUCCESS_RESPONSE: " + status);
            }

            if (statusCode == 202) {
                return TransferExecutionResponse.outcomeUnknown(transferId,
                        failureReason != null ? failureReason : "PROCESSING_OR_UNCERTAIN");
            }

            log.warn("Transfer Service returned unexpected 2xx status {} for transfer [{}]", statusCode, transferId);
            return TransferExecutionResponse.outcomeUnknown(transferId, "UNEXPECTED_2XX_STATUS: " + statusCode);

        } catch (Exception ex) {
            log.error("Failed to parse JSON response for transfer [{}]: {}", transferId, ex.getMessage());
            return TransferExecutionResponse.outcomeUnknown(transferId, "MALFORMED_JSON_RESPONSE: " + ex.getMessage());
        }
    }

    private TransferExecutionResponse handleExecutionHttpError(String transferId, int statusCode, String responseBody) {
        String parsedReason = extractReason(responseBody);

        if (statusCode == 422) {
            return TransferExecutionResponse.permanentFailure(transferId,
                    parsedReason != null ? parsedReason : "DEFINITIVE_REJECTION");
        }

        if (statusCode == 409) {
            return TransferExecutionResponse.permanentFailure(transferId,
                    parsedReason != null ? parsedReason : "TRANSFER_OR_IDEMPOTENCY_CONFLICT");
        }

        if (statusCode == 503) {
            return TransferExecutionResponse.transientFailure(transferId,
                    parsedReason != null ? parsedReason : "FAILED_PRE_LEDGER");
        }

        if (statusCode == 401 || statusCode == 403) {
            return TransferExecutionResponse.permanentFailure(transferId,
                    "AUTHENTICATION_CONFIGURATION_FAILURE: HTTP " + statusCode + (parsedReason != null ? " - " + parsedReason : ""));
        }

        return TransferExecutionResponse.outcomeUnknown(transferId,
                "HTTP_ERROR_" + statusCode + (parsedReason != null ? ": " + parsedReason : ""));
    }

    @Override
    public TransferStatusResponse reconcileTransfer(String transferId) {
        log.info("Reconciling scheduled transfer [{}] with Transfer Service at {}/internal/transfers/{}/reconcile",
                transferId, transferServiceUrl, transferId);

        try {
            ResponseEntity<String> response = restClient.get()
                    .uri("/internal/transfers/{transferId}/reconcile", transferId)
                    .retrieve()
                    .toEntity(String.class);

            int statusCode = response.getStatusCode().value();
            String responseBody = response.getBody();
            return parseReconciliationResponse(transferId, statusCode, responseBody);

        } catch (RestClientResponseException ex) {
            int statusCode = ex.getStatusCode().value();
            String responseBody = ex.getResponseBodyAsString();
            log.warn("Transfer Service reconciliation returned HTTP {} for transfer [{}]: {}",
                    statusCode, transferId, responseBody);

            if (statusCode == 404) {
                // A 404 does not prove that a delayed original request can never execute.
                // Preserve existing uncertainty semantics.
                return new TransferStatusResponse(transferId, null, "UNKNOWN", "TRANSFER_NOT_FOUND_DOWNSTREAM");
            }

            if (statusCode == 401 || statusCode == 403) {
                return new TransferStatusResponse(transferId, null, "UNKNOWN",
                        "AUTHENTICATION_CONFIGURATION_FAILURE: HTTP " + statusCode);
            }

            return new TransferStatusResponse(transferId, null, "UNKNOWN",
                    "HTTP_ERROR_" + statusCode + (extractReason(responseBody) != null ? ": " + extractReason(responseBody) : ""));

        } catch (ResourceAccessException ex) {
            log.error("Network or timeout error reconciling transfer [{}]: {}", transferId, ex.getMessage());
            return new TransferStatusResponse(transferId, null, "UNKNOWN",
                    "NETWORK_TIMEOUT_OR_CONNECTION_LOST: " + ex.getMessage());

        } catch (Exception ex) {
            log.error("Unexpected client error reconciling transfer [{}]: {}", transferId, ex.getMessage(), ex);
            return new TransferStatusResponse(transferId, null, "UNKNOWN",
                    "UNEXPECTED_CLIENT_ERROR: " + ex.getMessage());
        }
    }

    private TransferStatusResponse parseReconciliationResponse(String transferId, int statusCode, String body) {
        if (body == null || body.isBlank()) {
            return new TransferStatusResponse(transferId, null, "UNKNOWN", "EMPTY_RECONCILIATION_BODY");
        }

        try {
            JsonNode root = objectMapper.readTree(body);
            String status = root.path("status").asText(null);
            String refNumber = root.path("referenceNumber").asText(null);
            String failureReason = root.hasNonNull("failureReason")
                    ? root.path("failureReason").asText()
                    : root.path("statusReasonCode").asText(null);

            if (statusCode == 200) {
                if ("COMMITTED".equalsIgnoreCase(status) || "SUCCESS".equalsIgnoreCase(status)) {
                    return new TransferStatusResponse(transferId, refNumber, "SUCCESS", null);
                }
                if ("REJECTED".equalsIgnoreCase(status) || "FAILED".equalsIgnoreCase(status)) {
                    return new TransferStatusResponse(transferId, refNumber, "FAILED",
                            failureReason != null ? failureReason : "REJECTED_BY_TRANSFER_SERVICE");
                }
                return new TransferStatusResponse(transferId, refNumber, "UNKNOWN", "UNEXPECTED_STATUS_IN_200: " + status);
            }

            if (statusCode == 202) {
                return new TransferStatusResponse(transferId, refNumber, "UNKNOWN",
                        failureReason != null ? failureReason : "PROCESSING_OR_UNCERTAIN");
            }

            return new TransferStatusResponse(transferId, refNumber, "UNKNOWN", "UNEXPECTED_STATUS_CODE: " + statusCode);

        } catch (Exception ex) {
            log.error("Failed to parse reconciliation JSON for transfer [{}]: {}", transferId, ex.getMessage());
            return new TransferStatusResponse(transferId, null, "UNKNOWN", "MALFORMED_JSON_RESPONSE: " + ex.getMessage());
        }
    }

    private String extractReason(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (root.hasNonNull("failureReason")) {
                return root.path("failureReason").asText();
            }
            if (root.hasNonNull("statusReasonCode")) {
                return root.path("statusReasonCode").asText();
            }
            if (root.hasNonNull("code")) {
                return root.path("code").asText();
            }
            if (root.hasNonNull("message")) {
                return root.path("message").asText();
            }
        } catch (Exception ignored) {
            // Ignore parse errors and return null
        }
        return null;
    }
}
