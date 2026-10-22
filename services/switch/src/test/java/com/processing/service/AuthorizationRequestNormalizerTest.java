package com.processing.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import static org.assertj.core.api.Assertions.assertThat;

class AuthorizationRequestNormalizerTest {
    @ParameterizedTest
    @CsvSource({"TERM, TERM0000", "TERM0001, TERM0001", "TERM000123, TERM0001", "' ', ' '"})
    void shouldNormalizeTerminalWhenLengthVaries(String input, String expected) {
        // Act
        String result = AuthorizationRequestNormalizer.normalizeTerminalId(input);
        // Assert
        assertThat(result).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"SHOP, SHOP00000000000",
        "MERCH000000002, MERCH0000000020",
        "MERCH0000000002, MERCH0000000002",
        "MERCH00000000002, MERCH0000000002",
        "OTHER00000000000, OTHER0000000000",
        "MERCH10000000002, MERCH1000000000",
        "' ', ' '"})
    void shouldNormalizeMerchantWhenLengthVaries(String input, String expected) {
        // Act
        String result = AuthorizationRequestNormalizer.normalizeMerchantId(input);
        // Assert
        assertThat(result).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void shouldPreserveMissingTerminalWhenAbsent(String input) {
        // Act
        String result = AuthorizationRequestNormalizer.normalizeTerminalId(input);
        // Assert
        assertThat(result).isEqualTo(input);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void shouldPreserveMissingMerchantWhenAbsent(String input) {
        // Act
        String result = AuthorizationRequestNormalizer.normalizeMerchantId(input);
        // Assert
        assertThat(result).isEqualTo(input);
    }
}
