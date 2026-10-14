package com.neobank.transfer.domain;

/**
 * Five finalized nextAction hints (Section 6.2.5).
 */
public enum NextAction {
    CONFIRM_TRANSFER,
    COMPLETE_OTP_VERIFICATION,
    WAIT,
    CHECK_STATUS,
    NONE
}
