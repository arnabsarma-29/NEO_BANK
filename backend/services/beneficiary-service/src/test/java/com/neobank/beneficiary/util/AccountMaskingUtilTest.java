package com.neobank.beneficiary.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AccountMaskingUtilTest {

    @Test
    @DisplayName("Should mask normal account number retaining last 4 digits")
    void shouldMaskNormalAccountNumber() {
        String input = "123456789012";
        String masked = AccountMaskingUtil.mask(input);
        assertThat(masked).isEqualTo("********9012");
    }

    @Test
    @DisplayName("Should return 4 asterisks for short account numbers")
    void shouldHandleShortAccountNumbers() {
        assertThat(AccountMaskingUtil.mask("1234")).isEqualTo("****");
        assertThat(AccountMaskingUtil.mask("123")).isEqualTo("****");
        assertThat(AccountMaskingUtil.mask("1")).isEqualTo("****");
    }

    @Test
    @DisplayName("Should return null for null input")
    void shouldHandleNullInput() {
        assertThat(AccountMaskingUtil.mask(null)).isNull();
    }

    @Test
    @DisplayName("Should trim and mask whitespace-padded account numbers")
    void shouldTrimAndMaskPaddedInput() {
        assertThat(AccountMaskingUtil.mask("  9876543210  ")).isEqualTo("******3210");
    }
}
