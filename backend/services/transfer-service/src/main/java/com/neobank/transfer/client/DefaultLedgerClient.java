package com.neobank.transfer.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neobank.transfer.domain.TransferWorkflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Default implementation of LedgerClient implementing ADR-008 and ADR-009.
 * Communicates with Ledger Service over REST using the X-Internal-Service-Token header.
 * Provides 3 execution retry attempts (immediate, 250ms, 750ms) and outcome lookup reconciliation.
 */
@Component
@ConditionalOnMissingBean(type = "LedgerClientCustom")
public class DefaultLedgerClient implements LedgerClient {

    private static final Logger log = LoggerFactory.getLogger(DefaultLedgerClient.class);
    public static final String HEADER_SERVICE_TOKEN = "X-Internal-Service-Token";

    private final RestClient restClient;
    private final String ledgerBaseUrl;
    private final String serviceToken;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private long[] backoffs = {0L, 250L, 750L};

    @Autowired
    public DefaultLedgerClient(
            @Value("${neobank.ledger.url:http://localhost:8083}") String ledgerBaseUrl,
            @Value("${neobank.ledger.service-token:dev-transfer-to-ledger-token-secret-local}") String serviceToken) {
        this(RestClient.builder().requestFactory(createDefaultRequestFactory()), ledgerBaseUrl, serviceToken);
    }

    public DefaultLedgerClient(RestClient.Builder restClientBuilder, String ledgerBaseUrl, String serviceToken) {
        this.ledgerBaseUrl = ledgerBaseUrl;
        this.serviceToken = serviceToken;

        this.restClient = restClientBuilder
                .baseUrl(ledgerBaseUrl)
                .defaultHeader(HEADER_SERVICE_TOKEN, serviceToken)
                .build();
    }

    private static SimpleClientHttpRequestFactory createDefaultRequestFactory() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        return requestFactory;
    }

    void setBackoffs(long[] backoffs) {
        this.backoffs = backoffs;
    }

    @Override
    public LedgerExecutionOutcome executeTransfer(TransferWorkflow transfer) {
        Exception lastException = null;

        Map<String, Object> payload = new HashMap<>();
        payload.put("transferId", transfer.getTransferId());
        payload.put("sourceAccountId", transfer.getSourceAccountId());
        payload.put("destinationAccountId", transfer.getDestinationAccountId());
        payload.put("transferType", transfer.getTransferType() != null ? transfer.getTransferType().name() : null);
        payload.put("amount", transfer.getAmount());
        payload.put("currency", transfer.getCurrency());
        payload.put("referenceNumber", transfer.getReferenceNumber());
        payload.put("remarks", transfer.getRemarks());
        payload.put("destinationName", transfer.getDestinationName());
        payload.put("destinationReference", transfer.getDestinationReference());

        for (int attempt = 1; attempt <= 3; attempt++) {
            if (attempt > 1) {
                try {
                    long delay = (attempt - 1 < backoffs.length) ? backoffs[attempt - 1] : 0L;
                    if (delay > 0) {
                        Thread.sleep(delay);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return reconcileOrUncertain(transfer.getTransferId(), "EXECUTION_INTERRUPTED");
                }
            }

            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> response = restClient.post()
                        .uri("/internal/ledger/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(payload)
                        .retrieve()
                        .body(Map.class);

                if (response != null) {
                    String status = (String) response.get("status");
                    if ("COMMITTED".equals(status) || "SUCCESS".equals(status)) {
                        String txnId = (String) response.get("transactionId");
                        return LedgerExecutionOutcome.committed(txnId, Instant.now());
                    } else if ("PROCESSING".equals(status)) {
                        return LedgerExecutionOutcome.uncertain("PROCESSING");
                    } else if ("REJECTED".equals(status)) {
                        String reasonCode = (String) response.get("statusReasonCode");
                        return LedgerExecutionOutcome.rejected(reasonCode != null ? reasonCode : "REJECTED_BY_LEDGER");
                    }
                }
            } catch (RestClientResponseException ex) {
                int status = ex.getStatusCode().value();
                if (status == 400 || status == 422) {
                    log.warn("Ledger rejected transfer {}: status={}, body={}",
                            transfer.getTransferId(), status, ex.getResponseBodyAsString());
                    String reasonCode = parseErrorCode(ex, "REJECTED_BY_LEDGER");
                    return LedgerExecutionOutcome.rejected(reasonCode);
                } else if (status == 409) {
                    // ADR-008: HTTP 409 represents conflicting transferId reuse with different financial details.
                    // This is a definitive rejection of the conflicting request and MUST NOT reconcile against
                    // the original execution outcome.
                    log.warn("Ledger rejected transfer {} with 409 Conflict (conflicting transferId reuse)",
                            transfer.getTransferId());
                    String reasonCode = parseErrorCode(ex, "TRANSFER_ID_REUSED_WITH_DIFFERENT_DETAILS");
                    return LedgerExecutionOutcome.rejected(reasonCode);
                } else if (status == 401 || status == 403) {
                    log.error("Service authentication failure calling Ledger for transfer {}: HTTP {}",
                            transfer.getTransferId(), status);
                    return reconcileOrUncertain(transfer.getTransferId(), "AUTHENTICATION_FAILED");
                } else {
                    lastException = ex;
                    log.warn("Transient HTTP error from Ledger on attempt {}: status={}, message={}",
                            attempt, status, ex.getMessage());
                }
            } catch (Exception ex) {
                lastException = ex;
                log.warn("Network or transport error calling Ledger on attempt {}: {}", attempt, ex.getMessage());
            }
        }

        log.error("Exhausted all 3 execution attempts for transfer {}, initiating reconciliation",
                transfer.getTransferId(), lastException);
        return reconcileOrUncertain(transfer.getTransferId(), "FINANCIAL_OUTCOME_PENDING_RECONCILIATION");
    }

    private String parseErrorCode(RestClientResponseException ex, String defaultCode) {
        try {
            Map<?, ?> errMap = objectMapper.readValue(ex.getResponseBodyAsByteArray(), Map.class);
            if (errMap.containsKey("code")) {
                return String.valueOf(errMap.get("code"));
            } else if (errMap.containsKey("errorCode")) {
                return String.valueOf(errMap.get("errorCode"));
            }
        } catch (Exception parseEx) {
            String body = ex.getResponseBodyAsString();
            if (body != null) {
                if (body.contains("INSUFFICIENT_FUNDS")) return "INSUFFICIENT_FUNDS";
                if (body.contains("TRANSFER_NOT_EXECUTABLE")) return "TRANSFER_NOT_EXECUTABLE";
                if (body.contains("ACCOUNT_NOT_FOUND")) return "ACCOUNT_NOT_FOUND";
                if (body.contains("TRANSFER_ID_REUSED_WITH_DIFFERENT_DETAILS")) return "TRANSFER_ID_REUSED_WITH_DIFFERENT_DETAILS";
                if (body.contains("INVALID_REQUEST")) return "INVALID_REQUEST";
            }
        }
        return defaultCode;
    }

    private LedgerExecutionOutcome reconcileOrUncertain(String transferId, String defaultReasonCode) {
        Optional<LedgerExecutionOutcome> lookup = lookupOutcome(transferId);
        if (lookup.isPresent()) {
            return lookup.get();
        }
        return LedgerExecutionOutcome.uncertain(defaultReasonCode);
    }

    @Override
    public Optional<LedgerExecutionOutcome> lookupOutcome(String transferId) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.get()
                    .uri("/internal/ledger/transfers/{transferId}", transferId)
                    .retrieve()
                    .body(Map.class);

            if (response != null) {
                String status = (String) response.get("status");
                if ("COMMITTED".equals(status) || "SUCCESS".equals(status)) {
                    String txnId = (String) response.get("transactionId");
                    return Optional.of(LedgerExecutionOutcome.committed(txnId, Instant.now()));
                } else if ("REJECTED".equals(status)) {
                    String reasonCode = (String) response.get("statusReasonCode");
                    return Optional.of(LedgerExecutionOutcome.rejected(reasonCode != null ? reasonCode : "REJECTED_BY_LEDGER"));
                }
            }
        } catch (RestClientResponseException ex) {
            log.info("Outcome lookup for transfer {} returned HTTP {}: {}",
                    transferId, ex.getStatusCode().value(), ex.getMessage());
        } catch (Exception ex) {
            log.warn("Outcome lookup for transfer {} returned error: {}", transferId, ex.getMessage());
        }
        return Optional.empty();
    }
}
