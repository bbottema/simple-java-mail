package org.simplejavamail.internal.mailprovider.angus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.mailer.SmtpCapabilities;
import org.simplejavamail.internal.util.SmtpSizeSupport;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AngusSmtpCapabilitiesTest {

    @ParameterizedTest
    @ValueSource(strings = {"", "0", "-1", "+1", "1.5", "123 bytes", "9223372036854775808", "123\r\n250-SIZE 456"})
    void unknownOrContradictoryLimitsDoNotBecomeARejectionThreshold(final String value) {
        final String response = "250-localhost\r\n250-SIZE " + value + "\r\n250 DSN\r\n";
        final SmtpCapabilities capabilities = AngusSmtpCapabilities.parse(response, unused -> { });
        assertThat(capabilities.supports("SIZE")).isTrue();
        assertThat(capabilities.getMaximumMessageSize()).isEmpty();
        assertThat(AngusSmtpCapabilities.parseSizeSupport(response).isAdvertised()).isTrue();
        assertThat(AngusSmtpCapabilities.parseSizeSupport(response).getMaximumMessageSize()).isNull();
    }

    @Test
    void identicalDuplicatesRetainOneReliablePositiveLimit() {
        final SmtpCapabilities capabilities = AngusSmtpCapabilities.parse("250-localhost\r\n250-size 00123\r\n250 SIZE 123\r\n", unused -> { });
        assertThat(capabilities.getMaximumMessageSize()).hasValue(123);
        assertThat(capabilities.getExtensions().get("SIZE")).containsExactly("00123", "123");
    }

    @ParameterizedTest
    @ValueSource(strings = {"123\rbroken", "123\u0085broken", "123\u2028broken", "123\u2029broken"})
    void malformedControlCharactersInvalidateRatherThanHideASizeDuplicate(final String malformed) {
        final String response = "250-localhost\r\n250-SIZE 123\r\n250-SIZE " + malformed + "\r\n250 SIZE 123\r\n";
        final List<String> warnings = new ArrayList<>();
        final SmtpCapabilities capabilities = AngusSmtpCapabilities.parse(response, warnings::add);

        assertThat(capabilities.getExtensions().get("SIZE")).hasSize(3);
        assertThat(capabilities.getMaximumMessageSize()).isEmpty();
        assertThat(capabilities.toString()).doesNotContain(malformed);
        assertThat(AngusSmtpCapabilities.parseSizeSupport(response).getMaximumMessageSize()).isNull();
        assertThat(warnings).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void acceptsHundredsOfShortExtensionsWithOrWithoutATrailingNewline(final boolean trailingNewline) {
        final List<String> warnings = new ArrayList<>();
        final String response = "250-localhost\r\n" + "250-X-test\r\n".repeat(400) + "250 SIZE 123" + (trailingNewline ? "\r\n" : "");
        final SmtpCapabilities capabilities = AngusSmtpCapabilities.parse(response, warnings::add);

        assertThat(capabilities.getMaximumMessageSize()).hasValue(123);
        assertThat(capabilities.getExtensions().get("X-TEST")).hasSize(400);
        assertThat(warnings).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anOversizedDiagnosticDoesNotHideALaterSizeAdvertisement(final boolean trailingNewline) {
        final List<String> warnings = new ArrayList<>();
        final String response = "250-localhost\r\n250-X-TEST " + "x".repeat(65536) + "\r\n250 SIZE 123"
                + (trailingNewline ? "\r\n" : "");

        assertThat(AngusSmtpCapabilities.parse(response, warnings::add)).isNull();
        assertThat(warnings).singleElement().asString().contains("64 KiB");
        final SmtpSizeSupport size = AngusSmtpCapabilities.parseSizeSupport(response);
        assertThat(size.isAdvertised()).isTrue();
        assertThat(size.getMaximumMessageSize()).isEqualTo(123L);
    }

    @Test
    void aLongParameterIsRetainedWithoutTheOldPerFieldTruncation() {
        final List<String> warnings = new ArrayList<>();
        final String prefix = "250 X-TEST ";
        final String parameters = "x".repeat(4096);
        final SmtpCapabilities capabilities = AngusSmtpCapabilities.parse("250-localhost\r\n" + prefix + parameters + "\r\n", warnings::add);

        assertThat(capabilities.getExtensions().get("X-TEST")).containsExactly(parameters);
        assertThat(warnings).isEmpty();
    }

    @Test
    void lateConflictingDuplicatesCannotLeaveAnEarlierLimitActive() {
        final List<String> warnings = new ArrayList<>();
        final String prefix = "250 X-TEST ";
        final String response = "250-localhost\r\n250-SIZE 123\r\n" + prefix + "x".repeat(65536) + "\r\n250 SIZE 456\r\n";

        assertThat(AngusSmtpCapabilities.parse(response, warnings::add)).isNull();
        assertThat(warnings).singleElement().asString().contains("64 KiB");
        assertThat(AngusSmtpCapabilities.parseSizeSupport(response).getMaximumMessageSize()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"250 hello", "250-hello\n250 DSN\n", "250-hello\n250-X-SIZE 1\n250 SIZE-WRONG 1\n"})
    void doesNotInventSizeFromTheGreetingOrUnrelatedKeywords(final String response) {
        assertThat(AngusSmtpCapabilities.parseSizeSupport(response).isAdvertised()).isFalse();
        assertThat(AngusSmtpCapabilities.parseSizeSupport(response).getMaximumMessageSize()).isNull();
    }

    @Test
    void longZeroPrefixedNumbersAreInterpretedBeforeDisplayAndWithoutALineLimit() {
        final String response = "250-hello\n250-size " + "0".repeat(70000) + "123\n250 SIZE 123\n";
        assertThat(AngusSmtpCapabilities.parse(response, unused -> { })).isNull();
        assertThat(AngusSmtpCapabilities.parseSizeSupport(response).getMaximumMessageSize()).isEqualTo(123L);
    }

    @Test
    void malformedLineWarningsAreAggregatedWithoutLosingTheValidSize() {
        final List<String> warnings = new ArrayList<>();
        final String response = "250-hello\n" + "250-\n".repeat(10000) + "250 SIZE 123\n";
        assertThat(AngusSmtpCapabilities.parse(response, warnings::add).getMaximumMessageSize()).hasValue(123);
        assertThat(warnings).containsExactly("Ignored 10000 invalid EHLO extension lines.");
        assertThat(AngusSmtpCapabilities.parseSizeSupport(response).getMaximumMessageSize()).isEqualTo(123L);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // Published examples, not claims that every deployment of these products uses these advertisements.
            // https://learn.microsoft.com/en-us/exchange/mail-flow/test-smtp-telnet
            "250-mail1.fabrikam.com Hello [172.16.0.5]\n250-SIZE 37748736\n250-PIPELINING\n250-DSN\n250-ENHANCEDSTATUSCODES\n"
                    + "250-STARTTLS\n250-X-ANONYMOUSTLS\n250-AUTH NTLM\n250-X-EXPS GSSAPI NTLM\n250-8BITMIME\n250-BINARYMIME\n250-CHUNKING\n250 XRDST",
            // https://www.exim.org/exim-html-4.66/doc/html/spec_html/ch-smtp_authentication.html
            "250-server.example Hello client.example [10.8.4.5]\n250-SIZE 52428800\n250-PIPELINING\n250-AUTH PLAIN\n250 HELP",
            // https://www.postfix.org/SASL_README.html (includes the compatibility AUTH= advertisement).
            "250-server.example.com\n250-PIPELINING\n250-SIZE 10240000\n250-AUTH DIGEST-MD5 PLAIN CRAM-MD5\n250 AUTH=DIGEST-MD5 PLAIN CRAM-MD5"
    })
    void retainsSizeFromPublishedLegacyStyleReplies(final String response) {
        final SmtpCapabilities capabilities = AngusSmtpCapabilities.parse(response, unused -> { });
        assertThat(capabilities.supports("SIZE")).isTrue();
        assertThat(capabilities.getMaximumMessageSize()).hasValue(AngusSmtpCapabilities.parseSizeSupport(response).getMaximumMessageSize());
    }
}
