package com.neobank.transfer.dto;

import jakarta.validation.constraints.NotBlank;

public class ResendTransferOtpRequest {

    @NotBlank(message = "challengeId is required")
    private String challengeId;

    public ResendTransferOtpRequest() {
    }

    public ResendTransferOtpRequest(String challengeId) {
        this.challengeId = challengeId;
    }

    public String getChallengeId() {
        return challengeId;
    }

    public void setChallengeId(String challengeId) {
        this.challengeId = challengeId;
    }
}
