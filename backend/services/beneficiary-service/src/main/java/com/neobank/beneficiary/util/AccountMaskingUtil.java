package com.neobank.beneficiary.util;

/**
 * Utility for masking sensitive account numbers and identifiers
 * in accordance with banking data protection standards.
 */
public final class AccountMaskingUtil {

    private AccountMaskingUtil() {
    }

    /**
     * Masks an account number, retaining only the last 4 digits.
     * Example: "123456789012" -> "********9012"
     * For strings of length 4 or fewer, returns "****".
     *
     * @param accountNumber the raw account number
     * @return the masked account number or null if input is null
     */
    public static String mask(String accountNumber) {
        if (accountNumber == null) {
            return null;
        }
        String trimmed = accountNumber.trim();
        if (trimmed.length() <= 4) {
            return "****";
        }
        int len = trimmed.length();
        String last4 = trimmed.substring(len - 4);
        return "*".repeat(len - 4) + last4;
    }
}
