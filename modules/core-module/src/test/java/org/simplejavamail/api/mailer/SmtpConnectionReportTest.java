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

    private static SmtpConnectionReport.SmtpConnectionReportBuilder report() {
        return SmtpConnectionReport.builder().host("localhost").port(25).protocol("smtp").supported(true)
                .startedAt(Instant.EPOCH).completedAt(Instant.EPOCH.plusSeconds(1)).warnings(List.of());
    }
}
