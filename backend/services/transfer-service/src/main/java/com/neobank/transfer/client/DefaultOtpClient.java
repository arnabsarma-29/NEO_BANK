package com.neobank.transfer.client;

import com.neobank.transfer.exception.OtpLockoutException;
import com.neobank.transfer.exception.OtpRateLimitException;
import com.neobank.transfer.exception.OtpVerificationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local simulation adapter for Authentication Service OTP management.
 * Enforces finalized ADR-011 and ADR-019 policies:
 * - 5-minute OTP validity (Section 5.1)
 * - Maximum 5 incorrect verification attempts per challenge (Section 5.2)
 * - 15-minute temporary verification restriction upon reaching 5 failures (Section 5.5)
 * - 30-second resend cooldown and maximum 3 resends per challenge (Section 5.3)
 * - Immediate invalidation of previous OTP on resend without resetting failed-attempt count (Section 5.2, 5.3)
 * - Permanent challenge lockout and transfer rejection on 5th incorrect attempt (ADR-019)
 */
@Component
@ConditionalOnMissingBean(type = "OtpClientCustom")
public class DefaultOtpClient implements OtpClient {

    public static final int MAX_FAILED_ATTEMPTS = 5;
    public static final int MAX_RESENDS = 3;
    public static final Duration OTP_VALIDITY = Duration.ofMinutes(5);
    public static final Duration RESEND_COOLDOWN = Duration.ofSeconds(30);
    public static final Duration RESTRICTION_DURATION = Duration.ofMinutes(15);

    @Value("${neobank.otp.allow-test-shortcuts:false}")
    private boolean allowTestShortcuts = false;

    public boolean isAllowTestShortcuts() {
        return allowTestShortcuts;
    }

    public void setAllowTestShortcuts(boolean allowTestShortcuts) {
        this.allowTestShortcuts = allowTestShortcuts;
    }

    public static class ChallengeState {
        private final String challengeId;
        private final String customerId;
        private final String transferId;
        private String currentOtp;
        private Instant expiresAt;
        private Instant lastSentAt;
        private Instant restrictedUntil;
        private int resendCount = 0;
        private int failedAttempts = 0;
        private boolean locked = false;

        public ChallengeState(String challengeId, String customerId, String transferId, String initialOtp, Instant now) {
            this.challengeId = challengeId;
            this.customerId = customerId;
            this.transferId = transferId;
            this.currentOtp = initialOtp;
            this.lastSentAt = now;
            this.expiresAt = now.plus(OTP_VALIDITY);
        }

        public String getChallengeId() { return challengeId; }
        public String getCustomerId() { return customerId; }
        public String getTransferId() { return transferId; }
        public String getCurrentOtp() { return currentOtp; }
        public Instant getExpiresAt() { return expiresAt; }
        public Instant getLastSentAt() { return lastSentAt; }
        public Instant getRestrictedUntil() { return restrictedUntil; }
        public int getResendCount() { return resendCount; }
        public int getFailedAttempts() { return failedAttempts; }
        public boolean isLocked() { return locked; }

        public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
        public void setLastSentAt(Instant lastSentAt) { this.lastSentAt = lastSentAt; }
        public void setRestrictedUntil(Instant restrictedUntil) { this.restrictedUntil = restrictedUntil; }
        public void setLocked(boolean locked) { this.locked = locked; }
    }

    private final Map<String, ChallengeState> challenges = new ConcurrentHashMap<>();
    private final Map<String, Instant> customerRestrictions = new ConcurrentHashMap<>();
    private Clock clock = Clock.systemUTC();

    public Clock getClock() {
        return clock;
    }

    public void setClock(Clock clock) {
        this.clock = clock;
    }

    private Instant getCurrentTime() {
        return Instant.now(clock);
    }

    public boolean isCustomerRestricted(String customerId) {
        return isCustomerRestricted(customerId, getCurrentTime());
    }

    public boolean isCustomerRestricted(String customerId, Instant now) {
        if (customerId == null) {
            return false;
        }
        Instant restrictedUntil = customerRestrictions.get(customerId);
        if (restrictedUntil == null) {
            return false;
        }
        return now.isBefore(restrictedUntil);
    }

    public Instant getCustomerRestrictedUntil(String customerId) {
        return customerRestrictions.get(customerId);
    }

    public void clearCustomerRestriction(String customerId) {
        if (customerId != null) {
            customerRestrictions.remove(customerId);
        }
    }

    @Override
    public void validateTransferInitiation(String customerId) {
        Instant now = getCurrentTime();
        if (isCustomerRestricted(customerId, now)) {
            throw new OtpRateLimitException("Transfer initiation temporarily restricted for 15 minutes due to exceeding maximum incorrect OTP attempts");
        }
    }

    @Override
    public OtpChallengeResult createChallenge(String customerId, String transferId) {
        Instant now = getCurrentTime();
        if (isCustomerRestricted(customerId, now)) {
            throw new OtpRateLimitException("OTP challenge creation temporarily restricted for 15 minutes due to exceeding maximum incorrect attempts");
        }
        String challengeId = "OTP-" + UUID.randomUUID().toString().substring(0, 8);
        ChallengeState state = new ChallengeState(challengeId, customerId, transferId, "123456", now);
        challenges.put(challengeId, state);
        return new OtpChallengeResult(challengeId, state.expiresAt);
    }

    @Override
    public boolean verifyOtp(String challengeId, String otpCode) {
        ChallengeState state = challenges.get(challengeId);
        if (state == null) {
            throw new OtpVerificationException("OTP challenge not found or expired");
        }

        Instant now = getCurrentTime();

        // Check if customer is under active 15-minute temporary restriction (ADR-020)
        if (isCustomerRestricted(state.customerId, now)) {
            throw new OtpRateLimitException("OTP verification temporarily restricted for 15 minutes due to exceeding maximum incorrect attempts");
        }

        // Check if explicit testing shortcut was provided, strictly isolated to configured test environments
        if (allowTestShortcuts && "999999".equals(otpCode)) {
            state.locked = true;
            Instant restrictedUntil = now.plus(RESTRICTION_DURATION);
            state.restrictedUntil = restrictedUntil;
            customerRestrictions.put(state.customerId, restrictedUntil);
            throw new OtpLockoutException("OTP verification temporarily restricted for 15 minutes due to test shortcut");
        }

        // Check if challenge is under active 15-minute temporary restriction (Section 5.5)
        if (state.restrictedUntil != null && now.isBefore(state.restrictedUntil)) {
            throw new OtpRateLimitException("OTP verification temporarily restricted for 15 minutes due to exceeding maximum incorrect attempts");
        }

        // Check if challenge is locked (Section 5.2 & 5.5: A locked challenge remains unusable)
        if (state.locked) {
            throw new OtpVerificationException("OTP challenge is locked and unusable");
        }

        // Check expiry (Section 5.1)
        if (now.isAfter(state.expiresAt)) {
            throw new OtpVerificationException("OTP has expired");
        }

        // Check matching active OTP
        if (state.currentOtp != null && state.currentOtp.equals(otpCode)) {
            return true;
        } else {
            // Increment incorrect-attempt count (Section 5.2)
            state.failedAttempts++;
            if (state.failedAttempts >= MAX_FAILED_ATTEMPTS) {
                state.locked = true;
                Instant restrictedUntil = now.plus(RESTRICTION_DURATION);
                state.restrictedUntil = restrictedUntil;
                customerRestrictions.put(state.customerId, restrictedUntil);
                throw new OtpLockoutException("OTP verification locked for 15 minutes after " + MAX_FAILED_ATTEMPTS + " incorrect attempts");
            }
            throw new OtpVerificationException("Invalid OTP code. Attempt " + state.failedAttempts + " of " + MAX_FAILED_ATTEMPTS);
        }
    }

    @Override
    public OtpChallengeResult resendOtp(String challengeId) {
        ChallengeState state = challenges.get(challengeId);
        if (state == null) {
            throw new OtpVerificationException("OTP challenge not found");
        }

        Instant now = getCurrentTime();

        // Check if customer is under active 15-minute temporary restriction (ADR-020)
        if (isCustomerRestricted(state.customerId, now)) {
            throw new OtpRateLimitException("OTP challenge is locked due to temporary verification restriction");
        }

        // Check if challenge is under active 15-minute temporary restriction (Section 5.5)
        if (state.restrictedUntil != null && now.isBefore(state.restrictedUntil)) {
            throw new OtpRateLimitException("OTP challenge is locked due to temporary verification restriction");
        }

        // Check if challenge is locked (Section 5.2 & 5.5: A locked challenge remains unusable)
        if (state.locked) {
            throw new OtpVerificationException("OTP challenge is locked and cannot be resent");
        }

        // Check resend cooldown of 30 seconds (Section 5.3)
        if (Duration.between(state.lastSentAt, now).compareTo(RESEND_COOLDOWN) < 0) {
            throw new OtpRateLimitException("OTP resend cooldown active. Please wait 30 seconds.");
        }

        // Check maximum 3 resends per challenge (Section 5.3)
        if (state.resendCount >= MAX_RESENDS) {
            throw new OtpRateLimitException("Maximum OTP resends reached for this challenge");
        }

        state.resendCount++;
        state.lastSentAt = now;
        // Fresh 5-minute validity period (Section 5.1 & 5.3)
        state.expiresAt = now.plus(OTP_VALIDITY);

        // Immediately invalidate previous OTP and issue replacement OTP without resetting failed-attempt count (Section 5.2 & 5.3)
        state.currentOtp = "654321";

        return new OtpChallengeResult(challengeId, state.expiresAt);
    }

    public ChallengeState getChallengeState(String challengeId) {
        return challenges.get(challengeId);
    }
}
