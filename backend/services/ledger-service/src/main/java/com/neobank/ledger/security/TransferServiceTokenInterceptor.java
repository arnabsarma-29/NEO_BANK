package com.neobank.ledger.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Validates the X-Internal-Service-Token header for internal service-to-service communication.
 * Rejects missing or invalid tokens with HTTP 401 Unauthorized before any financial execution occurs.
 * Note: Never log token values.
 */
@Component
public class TransferServiceTokenInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TransferServiceTokenInterceptor.class);
    public static final String HEADER_SERVICE_TOKEN = "X-Internal-Service-Token";

    private final String expectedToken;

    public TransferServiceTokenInterceptor(
            @Value("${neobank.security.internal.transfer-token:dev-transfer-to-ledger-token-secret-local}") String expectedToken) {
        this.expectedToken = expectedToken;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String token = request.getHeader(HEADER_SERVICE_TOKEN);
        if (token == null || !expectedToken.equals(token)) {
            log.warn("Unauthorized internal service request to {} rejected (invalid or missing service token)", request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"status\":401,\"code\":\"UNAUTHORIZED\",\"errorCode\":\"UNAUTHORIZED\",\"message\":\"Missing or invalid internal service token\"}");
            return false;
        }
        return true;
    }
}
