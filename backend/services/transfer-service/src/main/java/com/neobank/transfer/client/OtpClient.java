package com.neobank.transfer.client;

import java.time.Instant;

/**
 * Communicates with Authentication Service for OTP lifecycle under ADR-011.
 */
public interface OtpClient {

    record OtpChallengeResult(
            String challengeId,
            Instant expiresAt
    ) {}

    OtpChallengeResult createChallenge(String customerId, String transferId);

    boolean verifyOtp(String challengeId, String otpCode);

    OtpChallengeResult resendOtp(String challengeId);

    /**
     * Checks whether the customer is eligible to initiate a new transfer workflow.
     * Enforces the ADR-020 customer-level 15-minute verification restriction at the authentication boundary
     * before any transfer entity is persisted.
     *
     * @param customerId Canonical customer identity
     * @throws com.neobank.transfer.exception.OtpRateLimitException if customer is currently restricted
     */
    void validateTransferInitiation(String customerId);
}
