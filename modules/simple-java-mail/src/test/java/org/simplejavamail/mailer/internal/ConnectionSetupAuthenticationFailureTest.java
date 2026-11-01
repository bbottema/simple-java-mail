package org.simplejavamail.mailer.internal;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.mailer.config.FailoverPolicy;
import org.simplejavamail.internal.util.SmtpAuthenticationAvailabilityException;

import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.cert.CertificateException;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionSetupAuthenticationFailureTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void arbitraryAuthenticationWrappersKeepTheirSecurityPermission(final boolean allowAuthentication) {
        final AuthenticationFailedException original = new AuthenticationFailedException("rejected",
                new SocketException("a nested I/O symptom is not observed provider outage evidence"));
        final ConnectionSetupFailure failure = new ConnectionSetupFailure(original);
        assertThat(failure.allowsFailover(FailoverPolicy.builder().withFailoverOnAuthenticationFailure(allowAuthentication).build()))
                .isEqualTo(allowAuthentication);
        assertThat(failure.explanation()).isEqualTo("The server rejected authentication");
        assertThat(failure).hasCause(original);
    }

    @Test
    void observedAuthenticationOutageRetainsTheOriginalProviderFailure() {
        final AuthenticationFailedException original = new AuthenticationFailedException("provider wrapper",
                new MessagingException("response timed out", new SocketTimeoutException("read timeout")));
        final SmtpAuthenticationAvailabilityException observed = new SmtpAuthenticationAvailabilityException(original);
        final ConnectionSetupFailure failure = new ConnectionSetupFailure(observed);
        assertThat(failure.allowsFailover(FailoverPolicy.builder().build())).isTrue();
        assertThat(failure.explanation()).isEqualTo("Connection unavailable");
        assertThat(observed).hasCause(original);
        assertThat(failure).hasCause(observed);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void certificateEvidenceStillControlsObservedOutages(final boolean allowCertificate) {
        final AuthenticationFailedException original = new AuthenticationFailedException("provider wrapper",
                new CertificateException("untrusted"));
        final ConnectionSetupFailure failure = new ConnectionSetupFailure(new SmtpAuthenticationAvailabilityException(original));
        assertThat(failure.allowsFailover(FailoverPolicy.builder().withFailoverOnAuthenticationFailure(true)
                .withFailoverOnCertificateVerificationFailure(allowCertificate).build())).isEqualTo(allowCertificate);
        assertThat(failure.explanation()).isEqualTo("Certificate or server identity verification failed");
    }

    @Test
    void incompleteDisposalStillStopsAnObservedAuthenticationOutage() {
        final AuthenticationFailedException original = new AuthenticationFailedException("provider wrapper",
                new SocketTimeoutException("read timeout"));
        original.addSuppressed(new MessagingException("disposal failed"));
        final ConnectionSetupFailure failure = new ConnectionSetupFailure(new SmtpAuthenticationAvailabilityException(original));
        assertThat(failure.hasUnresolvedCleanup()).isTrue();
        assertThat(failure.allowsFailover(FailoverPolicy.builder().withFailoverOnAuthenticationFailure(true)
                .withFailoverOnCertificateVerificationFailure(true).build())).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anObservedWrapperCannotHideAnotherAuthenticationRejection(final boolean allowAuthentication) {
        final AuthenticationFailedException rejected = new AuthenticationFailedException("genuine rejection");
        final AuthenticationFailedException interrupted = new AuthenticationFailedException("provider wrapper",
                new SocketTimeoutException("read timeout"));
        final MessagingException aggregate = new MessagingException("combined setup failures", rejected);
        aggregate.setNextException(new SmtpAuthenticationAvailabilityException(interrupted));
        final ConnectionSetupFailure failure = new ConnectionSetupFailure(aggregate);
        assertThat(failure.allowsFailover(FailoverPolicy.builder().withFailoverOnAuthenticationFailure(allowAuthentication).build()))
                .isEqualTo(allowAuthentication);
        assertThat(failure.explanation()).isEqualTo("The server rejected authentication");
    }
}
