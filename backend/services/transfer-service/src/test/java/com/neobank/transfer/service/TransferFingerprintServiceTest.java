package com.neobank.transfer.service;

import com.neobank.transfer.domain.TransferType;
import com.neobank.transfer.dto.CreateTransferRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class TransferFingerprintServiceTest {

    private TransferFingerprintService fingerprintService;

    @BeforeEach
    void setUp() {
        fingerprintService = new TransferFingerprintService();
    }

    @Test
    void shouldProduceSameFingerprintForIdenticalRequests() {
        CreateTransferRequest req1 = new CreateTransferRequest(
                "ACC-1001", TransferType.OWN_ACCOUNT, "ACC-1002", null,
                new BigDecimal("1000.00"), "INR", "Test remarks");
        CreateTransferRequest req2 = new CreateTransferRequest(
                "ACC-1001", TransferType.OWN_ACCOUNT, "ACC-1002", null,
                new BigDecimal("1000.0000"), "INR", "Test remarks");

        String fp1 = fingerprintService.computeFingerprint(req1);
        String fp2 = fingerprintService.computeFingerprint(req2);

        assertNotNull(fp1);
        assertEquals(64, fp1.length());
        assertEquals(fp1, fp2);
    }

    @Test
    void shouldProduceDifferentFingerprintWhenAmountDiffers() {
        CreateTransferRequest req1 = new CreateTransferRequest(
                "ACC-1001", TransferType.OWN_ACCOUNT, "ACC-1002", null,
                new BigDecimal("1000.00"), "INR", "Test remarks");
        CreateTransferRequest req2 = new CreateTransferRequest(
                "ACC-1001", TransferType.OWN_ACCOUNT, "ACC-1002", null,
                new BigDecimal("1000.01"), "INR", "Test remarks");

        String fp1 = fingerprintService.computeFingerprint(req1);
        String fp2 = fingerprintService.computeFingerprint(req2);

        assertNotEquals(fp1, fp2);
    }

    @Test
    void shouldProduceDifferentFingerprintWhenDestinationDiffers() {
        CreateTransferRequest req1 = new CreateTransferRequest(
                "ACC-1001", TransferType.OWN_ACCOUNT, "ACC-1002", null,
                new BigDecimal("1000.00"), "INR", "Test remarks");
        CreateTransferRequest req2 = new CreateTransferRequest(
                "ACC-1001", TransferType.OWN_ACCOUNT, "ACC-1003", null,
                new BigDecimal("1000.00"), "INR", "Test remarks");

        String fp1 = fingerprintService.computeFingerprint(req1);
        String fp2 = fingerprintService.computeFingerprint(req2);

        assertNotEquals(fp1, fp2);
    }
}
