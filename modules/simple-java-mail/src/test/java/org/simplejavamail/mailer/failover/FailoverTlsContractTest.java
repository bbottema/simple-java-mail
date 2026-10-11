package org.simplejavamail.mailer.failover;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.FailoverPolicy;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import testutil.smtp.RecordingSmtpServer;

import java.net.SocketException;
import javax.net.ssl.SSLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.simplejavamail.mailer.failover.FailoverContractSupport.*;

/** Real rejected TLS setup; endpoint protection that intercepts TLS must be handled by the isolated verification lane, not weakened checks. */
@Timeout(40)
class FailoverTlsContractTest {
    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void untrustedCertificateRequiresItsOwnFailoverPermission(final boolean allowCertificate, final boolean allowAuthentication) throws Exception {
        try (RecordingSmtpServer primary = new RecordingSmtpServer((peer, connection) -> {
            try {
                peer.startTls("250 localhost");
                throw new AssertionError("The test certificate must not be trusted by the client");
            } catch (SSLException | SocketException expectedClientRejection) {
                // TLS alerts or a closed socket are the two wire outcomes of client-side certificate rejection.
            }
        }); RecordingSmtpServer secondary = new RecordingSmtpServer(); Mailer selected = builder(secondary.port()).buildMailer();
             Mailer initiating = builder(primary.port()).withTransportStrategy(TransportStrategy.SMTP_TLS)
                     .withFailoverMailer(selected, FailoverPolicy.builder().withFailoverOnCertificateVerificationFailure(allowCertificate)
                             .withFailoverOnAuthenticationFailure(allowAuthentication).build()).buildMailer()) {
            final Throwable failure = catchThrowable(() -> initiating.sync().sendMail(email()));
            assertThat(failure == null).isEqualTo(allowCertificate);
            assertThat(primary.messages()).isEmpty();
            assertThat(secondary.messages()).hasSize(allowCertificate ? 1 : 0);
        }
    }
}
