package org.simplejavamail.internal.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.mailer.SmtpCapabilities;

import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

class SmtpCapabilityDiagnosticsTest {
    @Test
    void byteAccountingMatchesTheJdkAcrossBmpCharactersAndSurrogatePairs() {
        final StringBuilder text = new StringBuilder();
        for (int character = 0; character <= Character.MAX_VALUE; character++) {
            text.append((char) character);
        }
        text.append("😀𐀀\ud800x\udc00");
        final int encodedBytes = SmtpDiagnosticText.escape(text).getBytes(UTF_8).length;
        assertThat(SmtpDiagnosticText.escapedUtf8Length(text, encodedBytes)).isEqualTo(encodedBytes);
        assertThat(SmtpDiagnosticText.escapedUtf8Length(text, 100)).isGreaterThan(100);
    }

    @ParameterizedTest
    @ValueSource(ints = {1016, 1017, 1018, 1019, 1020, 2037})
    void chunkBoundariesPreserveSurrogatePairsEscapesAndMalformedCharacterReplacement(final int prefixLength) {
        final String text = "x".repeat(prefixLength) + "😀\t\u202e\ud800x\udc00" + "tail".repeat(1200) + "\ud800";
        final int encodedBytes = SmtpDiagnosticText.escape(text).getBytes(UTF_8).length;
        assertThat(SmtpDiagnosticText.escapedUtf8Length(text, encodedBytes)).isEqualTo(encodedBytes);
        assertThat(SmtpDiagnosticText.escapedUtf8Length(text, encodedBytes - 1)).isGreaterThan(encodedBytes - 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a", "é", "漢", "😀", "\ud800", "\udc00", "\ud800\ud800\udc00", "\n", "\u0000"})
    void handlesEmptyInputAndBudgetsSmallerThanOneEncodedCharacter(final String text) {
        final int encodedBytes = SmtpDiagnosticText.escape(text).getBytes(UTF_8).length;
        for (int remainingBytes = 0; remainingBytes <= encodedBytes; remainingBytes++) {
            assertThat(SmtpDiagnosticText.escapedUtf8Length(text, remainingBytes)).isEqualTo(Math.min(encodedBytes, remainingBytes + 1));
        }
    }

    @Test
    void renderingPreservesTheExistingEscapingAndDisplayTruncation() {
        assertThat(SmtpDiagnosticText.escape("line\n\r\t\u0000\u007f\u202e\u2028\u2029😀\\né"))
                .isEqualTo("line\\n\\r\\t\\u0000\\u007f\\u202e\\u2028\\u2029😀\\né");
        assertThat(SmtpDiagnosticText.display("x".repeat(2048))).isEqualTo("x".repeat(2048));
        assertThat(SmtpDiagnosticText.display("x".repeat(2049))).isEqualTo("x".repeat(2048) + "...");
        assertThat(SmtpDiagnosticText.display("x".repeat(2047) + "\u0000tail")).isEqualTo("x".repeat(2047) + "\\u0000...");
    }

    @Test
    void stopsAfterABoundedPrefixWithoutCopyingAnOversizedValue() {
        final CharSequence oversized = new CharSequence() {
            @Override
            public int length() {
                return Integer.MAX_VALUE;
            }

            @Override
            public char charAt(final int index) {
                assertThat(index).isLessThan(2048);
                return 'x';
            }

            @Override
            public CharSequence subSequence(final int start, final int end) {
                throw new AssertionError("The counter must not copy the input");
            }

            @Override
            public String toString() {
                throw new AssertionError("The counter must not render the complete input");
            }
        };
        assertThat(SmtpDiagnosticText.escapedUtf8Length(oversized, 10)).isGreaterThan(10);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ascii", "josé", "漢字", "😀", "\t\r\n\u0000\u007f\u2028\u2029\u202e", "\\n", "\ud800", "\udc00"})
    void countsExactlyTheEscapedUtf8RenderingIncludingDuplicateAndMapFraming(final String text) {
        final Map<String, List<String>> sample = Map.of("X-TEST", List.of(text, ""), "SIZE", List.of("123"), "DSN", List.of(""));
        final int framingBytes = new SmtpCapabilities(sample).toString().getBytes(UTF_8).length;
        final String filled = "x".repeat(65536 - framingBytes);
        final SmtpCapabilityDiagnostics.Collector collector = new SmtpCapabilityDiagnostics.Collector();
        assertThat(collector.add("x-test", text)).isTrue();
        assertThat(collector.add("X-TEST", filled)).isTrue();
        assertThat(collector.add("SIZE", "123")).isTrue();
        assertThat(collector.add("DSN", "")).isTrue();
        final SmtpCapabilities exact = collector.snapshot();
        assertThat(exact.toString().getBytes(UTF_8)).hasSize(65536);
        assertThat(SmtpCapabilityDiagnostics.fits(exact)).isTrue();

        final SmtpCapabilityDiagnostics.Collector overflow = new SmtpCapabilityDiagnostics.Collector();
        assertThat(overflow.add("SIZE", "123")).isTrue();
        assertThat(overflow.add("DSN", "")).isTrue();
        assertThat(overflow.add("X-TEST", text)).isTrue();
        assertThat(overflow.add("x-test", filled + "x")).isFalse();
        assertThat(overflow.snapshot()).isNull();
        assertThat(overflow.add("HELP", "")).isFalse();
        assertThat(SmtpCapabilityDiagnostics.fits(new SmtpCapabilities(
                Map.of("X-TEST", List.of(text, filled + "x"), "SIZE", List.of("123"), "DSN", List.of(""))))).isFalse();
    }

    @Test
    void emptyParametersAndLongNamesAlsoConsumeBudget() {
        final SmtpCapabilityDiagnostics.Collector collector = new SmtpCapabilityDiagnostics.Collector();
        assertThat(collector.add("X".repeat(65531), "")).isTrue();
        assertThat(collector.snapshot().toString().getBytes(UTF_8)).hasSize(65536);
        assertThat(collector.add("X".repeat(65531), "")).isFalse();
        assertThat(collector.snapshot()).isNull();
        assertThat(SmtpCapabilityDiagnostics.fits(new SmtpCapabilities(Map.of("X".repeat(65532), List.of(""))))).isFalse();
    }

    @Test
    void sizeInterpretationIsIndependentOfAnyReportingBudget() {
        final String longNumber = "0".repeat(70000) + "123";
        final SmtpCapabilities capabilities = new SmtpCapabilities(Map.of("size", List.of(longNumber), "SIZE", List.of("123")));
        assertThat(capabilities.getMaximumMessageSize()).hasValue(123);
        assertThat(SmtpCapabilityDiagnostics.fits(capabilities)).isFalse();
        assertThat(capabilities.getExtensions().get("SIZE")).contains(longNumber, "123");
    }
}
