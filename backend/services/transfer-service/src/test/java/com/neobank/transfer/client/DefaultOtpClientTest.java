package com.neobank.transfer.client;

import com.neobank.transfer.exception.OtpLockoutException;
import com.neobank.transfer.exception.OtpRateLimitException;
import com.neobank.transfer.exception.OtpVerificationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultOtpClientTest {

    private DefaultOtpClient otpClient;

    @BeforeEach
    void setUp() {
        otpClient = new DefaultOtpClient();
    }

    @Test
    @DisplayName("ADR-011 & ADR-019: Allow up to 5 incorrect attempts and lock with temporary restriction on 5th attempt")
    void shouldAllowUpToFiveAttemptsAndLockOnFifth() {
        var challenge = otpClient.createChallenge("C1001", "TRF-100");
        String challengeId = challenge.challengeId();

        // Attempts 1 to 4: Should throw OtpVerificationException (422 equivalent)
        for (int i = 1; i <= 4; i++) {
            final int attempt = i;
            assertThatThrownBy(() -> otpClient.verifyOtp(challengeId, "WRONG"))
                    .isInstanceOf(OtpVerificationException.class)
                    .hasMessageContaining("Attempt " + attempt + " of 5");
        }

        // 5th attempt: Reaches threshold, locks challenge, and throws OtpLockoutException (429 equivalent)
        assertThatThrownBy(() -> otpClient.verifyOtp(challengeId, "WRONG"))
                .isInstanceOf(OtpLockoutException.class)
                .hasMessageContaining("locked for 15 minutes after 5 incorrect attempts");

        // 6th attempt: Already locked, blocked by 15-minute temporary restriction
        assertThatThrownBy(() -> otpClient.verifyOtp(challengeId, "123456"))
                .isInstanceOf(OtpRateLimitException.class)
                .hasMessageContaining("temporarily restricted for 15 minutes");

        DefaultOtpClient.ChallengeState state = otpClient.getChallengeState(challengeId);
        assertThat(state.isLocked()).isTrue();
        assertThat(state.getFailedAttempts()).isEqualTo(5);
        assertThat(state.getRestrictedUntil()).isAfter(Instant.now().plus(Duration.ofMinutes(14)));
    }

    @Test
    @DisplayName("ADR-011: Resending OTP invalidates previous OTP without resetting failed-attempt count")
    void shouldInvalidatePreviousOtpOnResendWithoutResettingFailedAttempts() {
        var challenge = otpClient.createChallenge("C1001", "TRF-100");
        String challengeId = challenge.challengeId();

        // 2 failed attempts
        assertThatThrownBy(() -> otpClient.verifyOtp(challengeId, "000000"))
                .isInstanceOf(OtpVerificationException.class);
        assertThatThrownBy(() -> otpClient.verifyOtp(challengeId, "000000"))
                .isInstanceOf(OtpVerificationException.class);

        DefaultOtpClient.ChallengeState state = otpClient.getChallengeState(challengeId);
        assertThat(state.getFailedAttempts()).isEqualTo(2);

        // Advance cooldown timer to simulate 31 seconds passing
        state.setLastSentAt(Instant.now().minusSeconds(31));

        // Resend OTP
        var resendResult = otpClient.resendOtp(challengeId);
        assertThat(resendResult.challengeId()).isEqualTo(challengeId);

        // Verify failed-attempt count was NOT reset by resending
        assertThat(state.getFailedAttempts()).isEqualTo(2);

        // Verify previous OTP ("123456") is now invalidated and rejected
        assertThatThrownBy(() -> otpClient.verifyOtp(challengeId, "123456"))
                .isInstanceOf(OtpVerificationException.class)
                .hasMessageContaining("Attempt 3 of 5");
        assertThat(state.getFailedAttempts()).isEqualTo(3);

        // Verify replacement OTP ("654321") is accepted
        boolean verified = otpClient.verifyOtp(challengeId, "654321");
        assertThat(verified).isTrue();
    }

    @Test
    @DisplayName("ADR-011: Cumulative failures across resends reach 5-attempt limit")
    void shouldAccumulateFailuresAcrossResendToLockout() {
        var challenge = otpClient.createChallenge("C1001", "TRF-100");
        String challengeId = challenge.challengeId();

        // 3 failed attempts on initial OTP
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> otpClient.verifyOtp(challengeId, "WRONG"))
                    .isInstanceOf(OtpVerificationException.class);
        }

        // Resend OTP after cooldown
        DefaultOtpClient.ChallengeState state = otpClient.getChallengeState(challengeId);
        state.setLastSentAt(Instant.now().minusSeconds(31));
        otpClient.resendOtp(challengeId);

        // Attempt 4 on new OTP
        assertThatThrownBy(() -> otpClient.verifyOtp(challengeId, "WRONG"))
                .isInstanceOf(OtpVerificationException.class)
                .hasMessageContaining("Attempt 4 of 5");

        // Attempt 5 locks the challenge under 15-minute restriction
        assertThatThrownBy(() -> otpClient.verifyOtp(challengeId, "WRONG"))
                .isInstanceOf(OtpRateLimitException.class);

        assertThat(state.isLocked()).isTrue();
    }

    @Test
    @DisplayName("ADR-011: Enforce 30-second cooldown and maximum 3 resends per challenge")
    void shouldEnforceCooldownAndMaxResends() {
        var challenge = otpClient.createChallenge("C1001", "TRF-100");
        String challengeId = challenge.challengeId();
        DefaultOtpClient.ChallengeState state = otpClient.getChallengeState(challengeId);

        // Immediate resend triggers cooldown restriction
        assertThatThrownBy(() -> otpClient.resendOtp(challengeId))
                .isInstanceOf(OtpRateLimitException.class)
                .hasMessageContaining("cooldown active");

        // 3 successful resends after each cooldown
        for (int r = 1; r <= 3; r++) {
            state.setLastSentAt(Instant.now().minusSeconds(31));
            var result = otpClient.resendOtp(challengeId);
            assertThat(result.challengeId()).isEqualTo(challengeId);
        }
        assertThat(state.getResendCount()).isEqualTo(3);

        // 4th resend exceeds maximum resends per challenge
        state.setLastSentAt(Instant.now().minusSeconds(31));
        assertThatThrownBy(() -> otpClient.resendOtp(challengeId))
                .isInstanceOf(OtpRateLimitException.class)
                .hasMessageContaining("Maximum OTP resends reached");
    }

    @Test
    @DisplayName("ADR-011: Expired OTP is rejected")
    void shouldRejectExpiredOtp() {
        var challenge = otpClient.createChallenge("C1001", "TRF-100");
        String challengeId = challenge.challengeId();
        DefaultOtpClient.ChallengeState state = otpClient.getChallengeState(challengeId);

        // Simulate OTP expiry (past 5 minutes)
        state.setExpiresAt(Instant.now().minusSeconds(1));

        assertThatThrownBy(() -> otpClient.verifyOtp(challengeId, "123456"))
                .isInstanceOf(OtpVerificationException.class)
                .hasMessageContaining("OTP has expired");
    }

    @Test
    @DisplayName("ADR-011: Cannot resend OTP when challenge is locked under restriction")
    void shouldPreventResendWhenLocked() {
        var challenge = otpClient.createChallenge("C1001", "TRF-100");
        String challengeId = challenge.challengeId();
        DefaultOtpClient.ChallengeState state = otpClient.getChallengeState(challengeId);

        state.setLocked(true);
        state.setRestrictedUntil(Instant.now().plus(Duration.ofMinutes(15)));

        assertThatThrownBy(() -> otpClient.resendOtp(challengeId))
                .isInstanceOf(OtpRateLimitException.class)
                .hasMessageContaining("locked");
    }

    @Test
    @DisplayName("ADR-011: Restriction expiry transitions locked challenge from 429 rate-limit to 422 terminal locked")
    void shouldHandleRestrictionExpiryConsistentlyWithoutPermanentRateLimit() {
        var challenge = otpClient.createChallenge("C1001", "TRF-100");
        String challengeId = challenge.challengeId();

        // Accumulate 5 failed attempts
        for (int i = 1; i <= 4; i++) {
            assertThatThrownBy(() -> otpClient.verifyOtp(challengeId, "WRONG"))
                    .isInstanceOf(OtpVerificationException.class);
        }
        assertThatThrownBy(() -> otpClient.verifyOtp(challengeId, "WRONG"))
                .isInstanceOf(OtpRateLimitException.class);

        DefaultOtpClient.ChallengeState state = otpClient.getChallengeState(challengeId);
        assertThat(state.isLocked()).isTrue();

        // 1. During 15-minute restriction: verify throws OtpRateLimitException (429)
        assertThatThrownBy(() -> otpClient.verifyOtp(challengeId, "123456"))
                .isInstanceOf(OtpRateLimitException.class)
                .hasMessageContaining("temporarily restricted for 15 minutes");

        // 2. Simulate restriction expiry (15 minutes elapse)
        state.setRestrictedUntil(Instant.now().minusSeconds(1));
        otpClient.clearCustomerRestriction("C1001");

        // After restriction expires, rate-limit restriction has ended, but challenge remains locked and unusable per ADR-011
        // It must NOT throw OtpRateLimitException (which would falsely indicate an active 15-minute restriction),
        // but OtpVerificationException (422 terminal failure).
        assertThatThrownBy(() -> otpClient.verifyOtp(challengeId, "123456"))
                .isInstanceOf(OtpVerificationException.class)
                .hasMessageContaining("locked and unusable");

        // Resend after restriction expiry is also rejected as locked and unusable (422), not rate-limited (429)
        assertThatThrownBy(() -> otpClient.resendOtp(challengeId))
                .isInstanceOf(OtpVerificationException.class)
                .hasMessageContaining("locked and cannot be resent");
    }

    @Test
    @DisplayName("Shortcut isolation: Special code 999999 does not cause lockout when shortcuts are disabled (default/prod)")
    void shouldNotTriggerLockoutOnSpecialCodeWhenShortcutsDisabled() {
        var challenge = otpClient.createChallenge("C1001", "TRF-100");
        String challengeId = challenge.challengeId();

        // Ensure shortcuts are disabled (default)
        assertThat(otpClient.isAllowTestShortcuts()).isFalse();

        // Attempt 1 with 999999 must NOT trigger immediate lockout; it is treated as a regular incorrect attempt
        assertThatThrownBy(() -> otpClient.verifyOtp(challengeId, "999999"))
                .isInstanceOf(OtpVerificationException.class)
                .hasMessageContaining("Attempt 1 of 5");

        DefaultOtpClient.ChallengeState state = otpClient.getChallengeState(challengeId);
        assertThat(state.isLocked()).isFalse();
        assertThat(state.getFailedAttempts()).isEqualTo(1);
        assertThat(state.getRestrictedUntil()).isNull();
    }

    @Test
    @DisplayName("Shortcut isolation: Special code 999999 triggers lockout only when explicitly enabled for test fixture")
    void shouldTriggerLockoutOnSpecialCodeOnlyWhenExplicitlyEnabled() {
        var challenge = otpClient.createChallenge("C1001", "TRF-100");
        String challengeId = challenge.challengeId();

        otpClient.setAllowTestShortcuts(true);
        assertThat(otpClient.isAllowTestShortcuts()).isTrue();

        assertThatThrownBy(() -> otpClient.verifyOtp(challengeId, "999999"))
                .isInstanceOf(OtpLockoutException.class)
                .hasMessageContaining("temporarily restricted for 15 minutes due to test shortcut");

        DefaultOtpClient.ChallengeState state = otpClient.getChallengeState(challengeId);
        assertThat(state.isLocked()).isTrue();
        assertThat(state.getRestrictedUntil()).isNotNull();
        assertThat(otpClient.isCustomerRestricted("C1001")).isTrue();
    }

    @Test
    @DisplayName("ADR-020: 5th incorrect attempt restricts customer from new transfer initiation and fresh challenges")
    void shouldTrackCustomerLevelRestrictionOnFifthAttempt() {
        var challenge = otpClient.createChallenge("C1001", "TRF-100");
        String challengeId = challenge.challengeId();

        for (int i = 1; i <= 4; i++) {
            assertThatThrownBy(() -> otpClient.verifyOtp(challengeId, "WRONG"))
                    .isInstanceOf(OtpVerificationException.class);
        }

        assertThatThrownBy(() -> otpClient.verifyOtp(challengeId, "WRONG"))
                .isInstanceOf(OtpLockoutException.class);

        // Customer C1001 is now restricted
        assertThat(otpClient.isCustomerRestricted("C1001")).isTrue();
        assertThat(otpClient.getCustomerRestrictedUntil("C1001")).isNotNull();

        // Customer cannot initiate a new transfer
        assertThatThrownBy(() -> otpClient.validateTransferInitiation("C1001"))
                .isInstanceOf(OtpRateLimitException.class)
                .hasMessageContaining("Transfer initiation temporarily restricted for 15 minutes");

        // Customer cannot create a fresh challenge
        assertThatThrownBy(() -> otpClient.createChallenge("C1001", "TRF-NEW"))
                .isInstanceOf(OtpRateLimitException.class)
                .hasMessageContaining("OTP challenge creation temporarily restricted for 15 minutes");
    }

    @Test
    @DisplayName("ADR-020: Different customer is not restricted when one customer is locked out")
    void shouldNotRestrictDifferentCustomer() {
        var challenge1 = otpClient.createChallenge("C1001", "TRF-100");
        // Lock out customer C1001
        for (int i = 1; i <= 4; i++) {
            assertThatThrownBy(() -> otpClient.verifyOtp(challenge1.challengeId(), "WRONG"))
                    .isInstanceOf(OtpVerificationException.class);
        }
        assertThatThrownBy(() -> otpClient.verifyOtp(challenge1.challengeId(), "WRONG"))
                .isInstanceOf(OtpLockoutException.class);

        assertThat(otpClient.isCustomerRestricted("C1001")).isTrue();

        // Different customer C1002 is completely unaffected
        assertThat(otpClient.isCustomerRestricted("C1002")).isFalse();
        otpClient.validateTransferInitiation("C1002"); // Does not throw

        var challenge2 = otpClient.createChallenge("C1002", "TRF-200");
        assertThat(challenge2.challengeId()).isNotNull();

        boolean verified = otpClient.verifyOtp(challenge2.challengeId(), "123456");
        assertThat(verified).isTrue();
    }

    @Test
    @DisplayName("ADR-020: Restricted customer cannot verify or resend existing alternate challenges")
    void shouldBlockVerifyAndResendAcrossChallengesForRestrictedCustomer() {
        var challenge1 = otpClient.createChallenge("C1001", "TRF-100");
        var challenge2 = otpClient.createChallenge("C1001", "TRF-101");

        // Lock out challenge1
        for (int i = 1; i <= 5; i++) {
            assertThatThrownBy(() -> otpClient.verifyOtp(challenge1.challengeId(), "WRONG"))
                    .isInstanceOf(Exception.class);
        }

        // Challenge2 is also blocked for verification
        assertThatThrownBy(() -> otpClient.verifyOtp(challenge2.challengeId(), "123456"))
                .isInstanceOf(OtpRateLimitException.class)
                .hasMessageContaining("OTP verification temporarily restricted for 15 minutes");

        // Challenge2 is also blocked for resend
        assertThatThrownBy(() -> otpClient.resendOtp(challenge2.challengeId()))
                .isInstanceOf(OtpRateLimitException.class)
                .hasMessageContaining("OTP challenge is locked due to temporary verification restriction");
    }

    @Test
    @DisplayName("ADR-020: Precise boundary: restricted while now < restrictedUntil, released when now >= restrictedUntil")
    void shouldEnforcePreciseExpiryBoundaryWithClock() {
        Instant t0 = Instant.parse("2026-10-13T10:00:00Z");
        otpClient.setClock(Clock.fixed(t0, ZoneOffset.UTC));

        var challenge = otpClient.createChallenge("C1001", "TRF-100");
        for (int i = 1; i <= 5; i++) {
            assertThatThrownBy(() -> otpClient.verifyOtp(challenge.challengeId(), "WRONG"))
                    .isInstanceOf(Exception.class);
        }

        Instant restrictedUntil = otpClient.getCustomerRestrictedUntil("C1001");
        assertThat(restrictedUntil).isEqualTo(t0.plus(Duration.ofMinutes(15)));

        // 1. One millisecond before expiry (now < restrictedUntil): restricted
        otpClient.setClock(Clock.fixed(restrictedUntil.minusMillis(1), ZoneOffset.UTC));
        assertThat(otpClient.isCustomerRestricted("C1001")).isTrue();
        assertThatThrownBy(() -> otpClient.validateTransferInitiation("C1001"))
                .isInstanceOf(OtpRateLimitException.class);
        assertThatThrownBy(() -> otpClient.createChallenge("C1001", "TRF-NEW"))
                .isInstanceOf(OtpRateLimitException.class);

        // 2. Exactly at expiry (now >= restrictedUntil): restriction expired
        otpClient.setClock(Clock.fixed(restrictedUntil, ZoneOffset.UTC));
        assertThat(otpClient.isCustomerRestricted("C1001")).isFalse();
        otpClient.validateTransferInitiation("C1001"); // Succeeds without exception

        // Fresh challenge can be created and verified
        var freshChallenge = otpClient.createChallenge("C1001", "TRF-NEW");
        assertThat(freshChallenge.challengeId()).isNotNull();
        boolean verified = otpClient.verifyOtp(freshChallenge.challengeId(), "123456");
        assertThat(verified).isTrue();

        // Original locked challenge remains permanently unusable (422)
        assertThatThrownBy(() -> otpClient.verifyOtp(challenge.challengeId(), "123456"))
                .isInstanceOf(OtpVerificationException.class)
                .hasMessageContaining("locked and unusable");
        assertThatThrownBy(() -> otpClient.resendOtp(challenge.challengeId()))
                .isInstanceOf(OtpVerificationException.class)
                .hasMessageContaining("locked and cannot be resent");
    }

    @Test
    @DisplayName("ADR-020: Ordinary resend cooldown and quota violations do not trigger customer lockout")
    void shouldNotTriggerCustomerRestrictionOnOrdinaryResendCooldownOrQuota() {
        var challenge = otpClient.createChallenge("C1001", "TRF-100");
        DefaultOtpClient.ChallengeState state = otpClient.getChallengeState(challenge.challengeId());

        // Cooldown violation
        assertThatThrownBy(() -> otpClient.resendOtp(challenge.challengeId()))
                .isInstanceOf(OtpRateLimitException.class)
                .hasMessageContaining("cooldown active");
        assertThat(otpClient.isCustomerRestricted("C1001")).isFalse();

        // Exhaust resends
        for (int i = 1; i <= 3; i++) {
            state.setLastSentAt(Instant.now().minusSeconds(31));
            otpClient.resendOtp(challenge.challengeId());
        }
        state.setLastSentAt(Instant.now().minusSeconds(31));
        assertThatThrownBy(() -> otpClient.resendOtp(challenge.challengeId()))
                .isInstanceOf(OtpRateLimitException.class)
                .hasMessageContaining("Maximum OTP resends reached");

        // Customer remains unrestricted for transfers and other challenges
        assertThat(otpClient.isCustomerRestricted("C1001")).isFalse();
        otpClient.validateTransferInitiation("C1001");
    }
}
