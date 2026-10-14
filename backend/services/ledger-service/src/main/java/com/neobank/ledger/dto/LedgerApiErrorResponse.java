package com.neobank.ledger.dto;

import java.time.Instant;

public class LedgerApiErrorResponse {

    private Instant timestamp;
    private int status;
    private String code;
    private String message;
    private String transferId;

    public LedgerApiErrorResponse() {
    }

    public LedgerApiErrorResponse(Instant timestamp, int status, String code, String message, String transferId) {
        this.timestamp = timestamp != null ? timestamp : Instant.now();
        this.status = status;
        this.code = code;
        this.message = message;
        this.transferId = transferId;
    }

    // Getters and Setters
    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getTransferId() {
        return transferId;
    }

    public void setTransferId(String transferId) {
        this.transferId = transferId;
    }
}
