package org.simplejavamail.api.mailer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmtpConnectionReportTest {
    @Test
    void copiesAndSortsCapabilitiesWithoutLosingDuplicates() {
        final List<String> sizes = new ArrayList<>(List.of("123", "123"));
        final Map<String, List<String>> input = new LinkedHashMap<>();
        input.put("size", sizes);
        input.put("X-UNKNOWN", List.of("one\ntwo"));
        input.put("dsn", List.of(""));
        final SmtpCapabilities capabilities = new SmtpCapabilities(input);
        sizes.clear();
        input.clear();
        assertThat(capabilities.getExtensions().keySet()).containsExactly("DSN", "SIZE", "X-UNKNOWN");
        assertThat(capabilities.getExtensions().get("SIZE")).containsExactly("123", "123");
        assertThat(capabilities.getMaximumMessageSize()).hasValue(123);
        assertThat(capabilities.supports("dSn")).isTrue();
        assertThat(capabilities.toString()).contains("one\\ntwo").doesNotContain("\n");
        assertThatThrownBy(() -> capabilities.getExtensions().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> capabilities.getExtensions().get("SIZE").clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0", "-1", "+1", "1 2", "9223372036854775808", "no limit"})
    void doesNotInventAFixedSizeLimit(final String parameter) {
        final SmtpCapabilities capabilities = new SmtpCapabilities(Map.of("SIZE", List.of(parameter)));
        assertThat(capabilities.supports("SIZE")).isTrue();
        assertThat(capabilities.getMaximumMessageSize()).isEmpty();
    }

    @Test
    void contradictorySizeAndAbsentSizeAreNotReportedAsLimits() {
        assertThat(new SmtpCapabilities(Map.of("SIZE", List.of("1", "2"))).getMaximumMessageSize()).isEmpty();
        assertThat(new SmtpCapabilities(Map.of()).getMaximumMessageSize()).isEmpty();
        assertThat(new SmtpCapabilities(Map.of("SIZE", List.of("9223372036854775807"))).getMaximumMessageSize()).hasValue(Long.MAX_VALUE);
    }

    @Test
    void reportKeepsOnlyTheApplicableCapabilities() {
        final SmtpCapabilities before = new SmtpCapabilities(Map.of("STARTTLS", List.of("")));
        final SmtpCapabilities after = new SmtpCapabilities(Map.of("DSN", List.of("")));
        final SmtpConnectionReport plain = report().connected(true).beforeTls(before).build();
        assertThat(plain.getEffectiveCapabilities()).contains(before);
        assertThat(plain.toBuilder().tlsActive(true).afterTls(after).build().getEffectiveCapabilities()).contains(after);
        assertThat(plain.toBuilder().tlsActive(true).build().getEffectiveCapabilities()).isEmpty();
        assertThat(plain.toBuilder().startTlsAttempted(true).build().getEffectiveCapabilities()).isEmpty();
        assertThat(plain.toBuilder().connected(false).build().getEffectiveCapabilities()).isEmpty();
    }

    @Test
    void authenticationAndCleanupArePartOfSuccessWithoutErasingEarlierFacts() {
        final SmtpConnectionReport connected = report().connected(true).build();
        assertThat(connected.isSuccessful()).isTrue();
        assertThat(connected.toBuilder().authenticationRequested(true).build().isSuccessful()).isFalse();
        assertThat(connected.toBuilder().authenticationRequested(true).authenticated(true).build().isSuccessful()).isTrue();
        final SmtpConnectionReport cleanupFailed = connected.toBuilder().failurePhase(SmtpConnectionPhase.CLOSE)
                .failureDescription("Could not close").build();
        assertThat(cleanupFailed.isSuccessful()).isFalse();
        assertThat(cleanupFailed.isConnected()).isTrue();
    }

    @Test
    void escapesAllDisplayFieldsAndCopiesWarningsAndTlsIdentities() {
        final List<String> identities = new ArrayList<>(List.of("CN=peer\nforged log line"));
        final SmtpTlsDetails tls = new SmtpTlsDetails("TLSv1.3", "cipher\u001b[31m", identities, true, null);
        identities.clear();
        final List<String> warnings = new ArrayList<>(List.of("note\r\nforged\u202e"));
        final SmtpConnectionReport report = report().host("host\nforged").greeting("220 hello\tworld\u2028")
                .tlsDetails(tls).warnings(warnings).failurePhase(SmtpConnectionPhase.EHLO)
                .failureDescription("bad\u0000reply").build();
        warnings.clear();
        assertThat(report.getHost()).isEqualTo("host\\nforged");
        assertThat(report.getGreeting()).contains("220 hello\\tworld\\u2028");
        assertThat(report.getWarnings()).containsExactly("note\\r\\nforged\\u202e");
        assertThat(report.getFailureDescription()).contains("bad\\u0000reply");
        assertThat(report.toString()).doesNotContain("\u001b", "\u0000", "\u2028", "\u202e", "\r");
        assertThat(tls.getPeerIdentities()).containsExactly("CN=peer\\nforged log line");
        assertThatThrownBy(() -> report.getWarnings().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> tls.getPeerIdentities().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void boundsDisplayedServerTextAndSurvivesSerialization() throws Exception {
        final SmtpConnectionReport original = report().greeting("A".repeat(10000)).beforeTls(new SmtpCapabilities(Map.of("DSN", List.of("")))).build();
        assertThat(original.getGreeting().orElseThrow()).hasSizeLessThanOrEqualTo(2055);
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(original);
        }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            final SmtpConnectionReport copy = (SmtpConnectionReport) input.readObject();
            assertThat(copy.toString()).isEqualTo(original.toString());
            assertThatThrownBy(() -> copy.getBeforeTls().orElseThrow().getExtensions().clear()).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Test
    void oversizedThirdPartySnapshotsAreOmittedWithoutTurningSuccessfulSetupIntoAFailure() throws Exception {
        final SmtpCapabilities oversized = new SmtpCapabilities(Map.of("X-REMOTE", List.of("é".repeat(40000))));
        final SmtpCapabilities small = new SmtpCapabilities(Map.of("SIZE", List.of("123")));
        final SmtpConnectionReport report = report().connected(true).beforeTls(oversized).afterTls(small).tlsActive(true).build();
        assertThat(report.isSuccessful()).isTrue();
        assertThat(report.getBeforeTls()).isEmpty();
        assertThat(report.getEffectiveCapabilities()).containsSame(small);
        assertThat(report.getWarnings()).singleElement().asString().contains("Before TLS", "64 KiB");
        final SmtpConnectionReport copy = (SmtpConnectionReport) roundTrip(report);
        assertThat(copy.toString()).isEqualTo(report.toString());
        assertThat(copy.toBuilder().build().getWarnings()).containsExactlyElementsOf(report.getWarnings());
        assertThat(report().connected(true).beforeTls(oversized).afterTls(oversized).build().getWarnings()).hasSize(2);
    }

    @Test
    void beforeAndAfterTlsHaveIndependentBudgets() {
        final SmtpCapabilities capabilities = new SmtpCapabilities(Map.of("X", List.of("x".repeat(65530))));
        final SmtpConnectionReport report = report().connected(true).tlsActive(true).beforeTls(capabilities).afterTls(capabilities).build();
        assertThat(report.getBeforeTls()).containsSame(capabilities);
        assertThat(report.getAfterTls()).containsSame(capabilities);
        assertThat(report.getWarnings()).isEmpty();
    }

    @Test
    void typedSizeAndCompleteEscapedParametersSurviveSerialization() throws Exception {
        final String longNumber = "0".repeat(5000) + "123";
        final SmtpCapabilities original = new SmtpCapabilities(Map.of("SIZE", List.of(longNumber), "X-TEXT", List.of("one\ntwo\u202e😀")));
        final SmtpCapabilities copy = (SmtpCapabilities) roundTrip(original);
        assertThat(copy.getMaximumMessageSize()).hasValue(123);
        assertThat(copy.getExtensions()).isEqualTo(original.getExtensions());
        assertThat(copy.getExtensions().get("SIZE")).containsExactly(longNumber);
        assertThat(((SmtpCapabilities) roundTrip(copy)).toString()).isEqualTo(original.toString());
        assertThatThrownBy(() -> copy.getExtensions().get("SIZE").clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void readsSnapshotsWrittenBeforeTypedSizeFactsWereStored() throws Exception {
        // Written with the original serialVersionUID=1 class, whose only field was the escaped extensions map.
        final String fixture = "rO0ABXNyAC5vcmcuc2ltcGxlamF2YW1haWwuYXBpLm1haWxlci5TbXRwQ2FwYWJpbGl0aWVzAAAAAAAAAAECAAFMAApleHRlbnNpb25z"
                + "dAAPTGphdmEvdXRpbC9NYXA7eHBzcgAlamF2YS51dGlsLkNvbGxlY3Rpb25zJFVubW9kaWZpYWJsZU1hcPGlqP509QdCAgABTAABbXEAfgAB"
                + "eHBzcgARamF2YS51dGlsLlRyZWVNYXAMwfY+LSVq5gMAAUwACmNvbXBhcmF0b3J0ABZMamF2YS91dGlsL0NvbXBhcmF0b3I7eHBwdwQAAAAC"
                + "dAAEU0laRXNyABFqYXZhLnV0aWwuQ29sbFNlcleOq7Y6G6gRAwABSQADdGFneHAAAAABdwQAAAACdAAFMDAxMjN0AAMxMjN4dAAFWC1PTERz"
                + "cQB+AAkAAAABdwQAAAABdAAIb25lXG50d294eA==";
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(fixture)))) {
            final SmtpCapabilities capabilities = (SmtpCapabilities) input.readObject();
            assertThat(capabilities.getMaximumMessageSize()).hasValue(123);
            assertThat(capabilities.getExtensions().get("X-OLD")).containsExactly("one\\ntwo");
            assertThatThrownBy(() -> capabilities.getExtensions().clear()).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    private static Object roundTrip(final Object value) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(value);
        }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return input.readObject();
        }
    }

    private static SmtpConnectionReport.SmtpConnectionReportBuilder report() {
        return SmtpConnectionReport.builder().host("localhost").port(25).protocol("smtp").supported(true)
                .startedAt(Instant.EPOCH).completedAt(Instant.EPOCH.plusSeconds(1)).warnings(List.of());
    }
}
