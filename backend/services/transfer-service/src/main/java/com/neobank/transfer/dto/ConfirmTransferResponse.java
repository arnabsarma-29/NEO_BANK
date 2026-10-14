package com.neobank.transfer.dto;

import com.neobank.transfer.domain.NextAction;
import com.neobank.transfer.domain.TransferStatus;

import java.time.Instant;

public class ConfirmTransferResponse {

    private String transferId;
    private String referenceNumber;
    private TransferStatus status;
    private NextAction nextAction;
    private String challengeId;
    private Instant otpExpiresAt;

    public ConfirmTransferResponse() {
    }

    public ConfirmTransferResponse(String transferId, String referenceNumber,
                                  TransferStatus status, NextAction nextAction,
                                  String challengeId, Instant otpExpiresAt) {
        this.transferId = transferId;
        this.referenceNumber = referenceNumber;
        this.status = status;
        this.nextAction = nextAction;
        this.challengeId = challengeId;
        this.otpExpiresAt = otpExpiresAt;
    }

    // Getters and Setters
    public String getTransferId() {
        return transferId;
    }

    public void setTransferId(String transferId) {
        this.transferId = transferId;
    }

    public String getReferenceNumber() {
        return referenceNumber;
    }

    public void setReferenceNumber(String referenceNumber) {
        this.referenceNumber = referenceNumber;
    }

    public TransferStatus getStatus() {
        return status;
    }

    public void setStatus(TransferStatus status) {
        this.status = status;
    }

    public NextAction getNextAction() {
        return nextAction;
    }

    public void setNextAction(NextAction nextAction) {
        this.nextAction = nextAction;
    }

    public String getChallengeId() {
        return challengeId;
    }

    public void setChallengeId(String challengeId) {
        this.challengeId = challengeId;
    }

    public Instant getOtpExpiresAt() {
        return otpExpiresAt;
    }

    public void setOtpExpiresAt(Instant otpExpiresAt) {
        this.otpExpiresAt = otpExpiresAt;
    }
}
