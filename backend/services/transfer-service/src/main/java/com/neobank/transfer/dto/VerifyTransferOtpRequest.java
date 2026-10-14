package com.neobank.transfer.dto;

import jakarta.validation.constraints.NotBlank;

public class VerifyTransferOtpRequest {

    @NotBlank(message = "challengeId is required")
    private String challengeId;

    @NotBlank(message = "otpCode is required")
    private String otpCode;

    public VerifyTransferOtpRequest() {
    }

    public VerifyTransferOtpRequest(String challengeId, String otpCode) {
        this.challengeId = challengeId;
        this.otpCode = otpCode;
    }

    // Getters and Setters
    public String getChallengeId() {
        return challengeId;
    }

    public void setChallengeId(String challengeId) {
        this.challengeId = challengeId;
    }

    public String getOtpCode() {
        return otpCode;
    }

    public void setOtpCode(String otpCode) {
        this.otpCode = otpCode;
    }
}
