package com.neobank.transfer.domain;

/**
 * Eight finalized transfer workflow statuses (Section 6.2.3).
 */
public enum TransferStatus {
    PENDING_CONFIRMATION,
    AWAITING_OTP,
    READY_FOR_EXECUTION,
    PROCESSING,
    OUTCOME_UNKNOWN,
    SUCCESS,
    REJECTED,
    FAILED
}
