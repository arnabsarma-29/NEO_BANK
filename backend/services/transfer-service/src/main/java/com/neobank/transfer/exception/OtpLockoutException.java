package com.neobank.transfer.exception;

/**
 * Dedicated exception representing the fifth incorrect OTP attempt lockout under ADR-011 and ADR-019.
 * Subclasses OtpRateLimitException so existing HTTP 429 error mapping is preserved,
 * while allowing TransferWorkflowService to explicitly distinguish lockout from transient cooldown/quota errors.
 */
public class OtpLockoutException extends OtpRateLimitException {

    public static final String DEFAULT_LOCKOUT_REASON = "OTP_ATTEMPTS_EXCEEDED";

    public OtpLockoutException(String message) {
        super(message);
    }
}
