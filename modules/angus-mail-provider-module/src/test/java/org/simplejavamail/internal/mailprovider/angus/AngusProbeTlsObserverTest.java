package org.simplejavamail.internal.mailprovider.angus;

import jakarta.mail.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.mailer.SmtpConnectionReport;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.security.auth.x500.X500Principal;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AngusProbeTlsObserverTest {
    private static final String CERTIFICATE_SUBJECT = "CN=smtp.example.org";
    private static final String TRUNCATION_WARNING = "Only the first 32 certificate identities are shown; additional names were omitted.";

    @ParameterizedTest(name = "alternative names={0}, reported identities={1}, truncated={2}")
    @CsvSource({"0, 1, false", "30, 31, false", "31, 32, false", "32, 32, true", "80, 32, true"})
    void reportsTruncationOnlyWhenAnIdentityWasOmitted(final int alternativeNameCount, final int expectedIdentityCount,
            final boolean truncated) throws Exception {
        final SmtpConnectionReport report = captureReport(tlsSession(alternativeNameCount));
        final List<String> identities = report.getTlsDetails().orElseThrow().getPeerIdentities();

        assertThat(identities).hasSize(expectedIdentityCount).startsWith(CERTIFICATE_SUBJECT);
        if (expectedIdentityCount > 1) {
            assertThat(identities).endsWith("smtp-" + (expectedIdentityCount - 2) + ".example.org");
        }
        if (truncated) {
            assertThat(report.getWarnings()).containsExactly(TRUNCATION_WARNING);
        } else {
            assertThat(report.getWarnings()).isEmpty();
        }
        assertThat(report.toString().contains(TRUNCATION_WARNING)).isEqualTo(truncated);
    }

    @Test
    void nonIdentityAlternativeNamesDoNotTriggerTruncationAtTheLimit() throws Exception {
        final Collection<List<?>> alternatives = new ArrayList<>();
        for (int index = 0; index < 30; index++) {
            alternatives.add(List.of(2, "smtp-" + index + ".example.org"));
        }
        alternatives.add(List.of(7, "192.0.2.1"));
        alternatives.add(List.of(1, "admin@example.org"));
        alternatives.add(List.of(6, "https://example.org"));
        alternatives.add(List.of(2));
        alternatives.add(List.of(7, new byte[]{127, 0, 0, 1}));
        final SmtpConnectionReport report = captureReport(tlsSession(alternatives));

        assertThat(report.getTlsDetails().orElseThrow().getPeerIdentities()).hasSize(32).endsWith("192.0.2.1");
        assertThat(report.getWarnings()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aLaterCompleteCaptureDoesNotRetainTheTruncationWarning(final boolean resetBetweenCaptures) throws Exception {
        final Session session = Session.getInstance(new Properties());
        final AngusProbeTlsObserver observer = new AngusProbeTlsObserver(session, "smtp");
        assertThat(verifyHostname(session, tlsSession(32))).isTrue();
        assertThat(observer.getWarnings()).containsExactly(TRUNCATION_WARNING);

        if (resetBetweenCaptures) {
            observer.reset();
            assertThat(observer.getDetails()).isNull();
            assertThat(observer.getWarnings()).isEmpty();
        }
        assertThat(verifyHostname(session, tlsSession(1))).isTrue();
        assertThat(observer.getDetails().getPeerIdentities()).containsExactly(CERTIFICATE_SUBJECT, "smtp-0.example.org");
        assertThat(observer.getWarnings()).isEmpty();
    }

    @Test
    void unavailableMetadataClearsEarlierDetailsAndTruncationWarning() throws Exception {
        final Session session = Session.getInstance(new Properties());
        final AngusProbeTlsObserver observer = new AngusProbeTlsObserver(session, "smtp");
        final SSLSession tlsSession = tlsSession(32);
        assertThat(verifyHostname(session, tlsSession)).isTrue();
        assertThat(observer.getWarnings()).containsExactly(TRUNCATION_WARNING);

        when(tlsSession.getPeerCertificates()).thenThrow(new SSLPeerUnverifiedException("No peer certificate available"));
        assertThat(verifyHostname(session, tlsSession)).isTrue();
        assertThat(observer.getDetails()).isNull();
        assertThat(observer.getWarnings()).isEmpty();
    }

    @Test
    void truncatedIdentitiesDoNotChangeTheCallerVerifiersRejection() throws Exception {
        final Session session = Session.getInstance(new Properties());
        session.getProperties().put("mail.smtp.ssl.hostnameverifier", (HostnameVerifier) (host, tlsSession) -> false);
        final AngusProbeTlsObserver observer = new AngusProbeTlsObserver(session, "smtp");

        assertThat(verifyHostname(session, tlsSession(32))).isFalse();
        assertThat(observer.getDetails().getCustomHostnameVerifierAccepted()).contains(false);
        assertThat(observer.getWarnings()).containsExactly(TRUNCATION_WARNING);
    }

    @Test
    void truncatedIdentitiesDoNotReplaceTheCallerVerifiersException() throws Exception {
        final IllegalStateException rejection = new IllegalStateException("Caller verifier failed");
        final Session session = Session.getInstance(new Properties());
        session.getProperties().put("mail.smtp.ssl.hostnameverifier", (HostnameVerifier) (host, tlsSession) -> { throw rejection; });
        final AngusProbeTlsObserver observer = new AngusProbeTlsObserver(session, "smtp");
        final SSLSession tlsSession = tlsSession(32);

        assertThatThrownBy(() -> verifyHostname(session, tlsSession)).isSameAs(rejection);
        assertThat(observer.getDetails().getCustomHostnameVerifierAccepted()).isEmpty();
        assertThat(observer.getWarnings()).containsExactly(TRUNCATION_WARNING);
    }

    @Test
    void aClassConfiguredVerifierRetainsItsUnavailableMetadataWarningAfterReset() {
        final Session session = Session.getInstance(new Properties());
        session.getProperties().setProperty("mail.smtp.ssl.hostnameverifier.class", "application.CustomHostnameVerifier");
        final AngusProbeTlsObserver observer = new AngusProbeTlsObserver(session, "smtp");
        observer.reset();

        assertThat(session.getProperties()).doesNotContainKey("mail.smtp.ssl.hostnameverifier");
        assertThat(observer.getWarnings()).containsExactly("TLS metadata is unavailable because the configured hostname-verifier class was left unchanged.");
    }

    private static SmtpConnectionReport captureReport(final SSLSession tlsSession) {
        final Session session = Session.getInstance(new Properties());
        final AngusProbeTransport transport = new AngusProbeTransport(session, "smtp", false);
        try {
            assertThat(verifyHostname(session, tlsSession)).isTrue();
            return transport.report();
        } finally {
            transport.closeProbe();
        }
    }

    private static boolean verifyHostname(final Session session, final SSLSession tlsSession) {
        final HostnameVerifier verifier = (HostnameVerifier) session.getProperties().get("mail.smtp.ssl.hostnameverifier");
        return verifier.verify("smtp.example.org", tlsSession);
    }

    private static SSLSession tlsSession(final int alternativeNameCount) throws Exception {
        final Collection<List<?>> alternatives = new ArrayList<>();
        for (int index = 0; index < alternativeNameCount; index++) {
            alternatives.add(List.of(2, "smtp-" + index + ".example.org"));
        }
        return tlsSession(alternatives);
    }

    private static SSLSession tlsSession(final Collection<List<?>> alternatives) throws Exception {
        final X509Certificate certificate = mock(X509Certificate.class);
        when(certificate.getSubjectX500Principal()).thenReturn(new X500Principal(CERTIFICATE_SUBJECT));
        when(certificate.getSubjectAlternativeNames()).thenReturn(alternatives);
        final SSLSession tlsSession = mock(SSLSession.class);
        when(tlsSession.getProtocol()).thenReturn("TLSv1.3");
        when(tlsSession.getCipherSuite()).thenReturn("TLS_AES_256_GCM_SHA384");
        when(tlsSession.getPeerCertificates()).thenReturn(new Certificate[]{certificate});
        return tlsSession;
    }
}
