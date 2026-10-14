package com.neobank.transfer.service;

import com.neobank.transfer.dto.CreateTransferRequest;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Service
public class TransferFingerprintService {

    public String computeFingerprint(CreateTransferRequest request) {
        String canonicalAmount = request.getAmount() != null
                ? request.getAmount().stripTrailingZeros().toPlainString()
                : "";
        String canonicalCurrency = request.getCurrency() != null
                ? request.getCurrency().trim().toUpperCase()
                : "";
        String sourceAccountId = request.getSourceAccountId() != null ? request.getSourceAccountId().trim() : "";
        String transferType = request.getTransferType() != null ? request.getTransferType().name() : "";
        String destAccountId = request.getDestinationAccountId() != null ? request.getDestinationAccountId().trim() : "";
        String beneficiaryId = request.getBeneficiaryId() != null ? request.getBeneficiaryId().trim() : "";
        String remarks = request.getRemarks() != null ? request.getRemarks().trim() : "";

        String canonical = String.join("|",
                sourceAccountId,
                transferType,
                destAccountId,
                beneficiaryId,
                canonicalAmount,
                canonicalCurrency,
                remarks
        );

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    public String computeScheduledFingerprint(com.neobank.transfer.dto.ExecuteScheduledTransferRequest request) {
        String canonicalAmount = request.getAmount() != null
                ? request.getAmount().stripTrailingZeros().toPlainString()
                : "";
        String canonicalCurrency = request.getCurrency() != null
                ? request.getCurrency().trim().toUpperCase()
                : "";
        String transferId = request.getTransferId() != null ? request.getTransferId().trim() : "";
        String customerId = request.getCustomerId() != null ? request.getCustomerId().trim() : "";
        String sourceAccountId = request.getSourceAccountId() != null ? request.getSourceAccountId().trim() : "";
        String transferType = request.getTransferType() != null ? request.getTransferType().trim().toUpperCase() : "";
        String destAccountId = request.getDestinationAccountId() != null ? request.getDestinationAccountId().trim() : "";
        String beneficiaryId = request.getBeneficiaryId() != null ? request.getBeneficiaryId().trim() : "";

        String canonical = String.join("|",
                transferId,
                customerId,
                sourceAccountId,
                transferType,
                destAccountId,
                beneficiaryId,
                canonicalAmount,
                canonicalCurrency
        );

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
