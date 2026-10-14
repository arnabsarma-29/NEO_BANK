package com.neobank.beneficiary.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenProviderTest {

    private JwtTokenProvider tokenProvider;
    private static final String SECRET = "my-super-secret-jwt-key-which-is-at-least-32-chars-long";

    @BeforeEach
    void setUp() {
        tokenProvider = new JwtTokenProvider(SECRET, 3600);
    }

    @Test
    @DisplayName("Generate and validate valid JWT token")
    void generateAndValidateToken() {
        String token = tokenProvider.generateToken("CUST-1001", "CUSTOMER");

        assertThat(token).isNotBlank();
        assertThat(tokenProvider.validateToken(token)).isTrue();

        Authentication auth = tokenProvider.getAuthentication(token);
        assertThat(auth).isNotNull();
        assertThat(auth.getName()).isEqualTo("CUST-1001");
        assertThat(auth.getAuthorities()).anyMatch(a -> a.getAuthority().equals("ROLE_CUSTOMER"));
    }

    @Test
    @DisplayName("Generate internal service token with ROLE_INTERNAL_SERVICE")
    void generateInternalServiceToken() {
        String token = tokenProvider.generateToken("TRANSFER_SERVICE", "INTERNAL_SERVICE");

        assertThat(tokenProvider.validateToken(token)).isTrue();
        Authentication auth = tokenProvider.getAuthentication(token);
        assertThat(auth.getAuthorities()).anyMatch(a -> a.getAuthority().equals("ROLE_INTERNAL_SERVICE"));
    }

    @Test
    @DisplayName("Reject invalid token")
    void rejectInvalidToken() {
        assertThat(tokenProvider.validateToken("invalid.token.here")).isFalse();
        assertThat(tokenProvider.validateToken("")).isFalse();
        assertThat(tokenProvider.validateToken(null)).isFalse();
    }

    @Test
    @DisplayName("Reject expired token")
    void rejectExpiredToken() {
        String expiredToken = tokenProvider.generateToken("CUST-1001", "CUSTOMER", -10);
        assertThat(tokenProvider.validateToken(expiredToken)).isFalse();
    }
}
