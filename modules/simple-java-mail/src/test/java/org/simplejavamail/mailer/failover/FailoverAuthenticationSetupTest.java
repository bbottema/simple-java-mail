package org.simplejavamail.mailer.failover;

import jakarta.mail.Authenticator;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.MailException;
import org.simplejavamail.api.mailer.MailSendHandlers;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.ConnectionConfig;
import org.simplejavamail.api.mailer.config.FailoverPolicy;
import testutil.smtp.RecordingSmtpServer;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.simplejavamail.api.mailer.MailSubmissionStatus.ACCEPTED;
import static org.simplejavamail.mailer.failover.FailoverContractSupport.builder;
import static org.simplejavamail.mailer.failover.FailoverContractSupport.email;
import static org.simplejavamail.mailer.failover.FailoverContractSupport.factory;
import static org.simplejavamail.mailer.failover.FailoverContractSupport.policy;
import static org.simplejavamail.mailer.failover.FailoverContractSupport.send;

/** The public failover route must retain the physical reason hidden by Angus's AUTH wrapper. */
@Timeout(40)
class FailoverAuthenticationSetupTest {

    @ParameterizedTest
    @CsvSource({"UNAVAILABLE,false,false", "UNAVAILABLE,false,true", "UNAVAILABLE,true,false", "UNAVAILABLE,true,true",
            "EOF,false,false", "EOF,false,true", "EOF,true,false", "EOF,true,true",
            "TIMEOUT,false,false", "TIMEOUT,false,true", "TIMEOUT,true,false", "TIMEOUT,true,true"})
    void authenticationOutageUsesDefaultFailover(final String outage, final boolean afterChallenge,
            final boolean asynchronous) throws Exception {
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        try (RecordingSmtpServer primary = new RecordingSmtpServer((peer, connection) -> {
            connection.greet(peer, "250-localhost\r\n250 AUTH LOGIN");
            connection.expect(peer, "AUTH LOGIN");
            if (afterChallenge) {
                peer.reply("334 VXNlcm5hbWU6");
                assertThat(connection.readCommand(peer)).isNotBlank();
            }
            if (outage.equals("UNAVAILABLE")) {
                peer.reply("421 authentication service unavailable");
                peer.expectClosed();
            } else if (outage.equals("EOF")) {
                peer.close();
            } else {
                // No sleep: wait for the client's bounded read to fail and close its physical connection.
                peer.expectClosed();
            }
        }); RecordingSmtpServer secondary = new RecordingSmtpServer();
             Mailer destination = builder(secondary.port()).buildMailer();
             Mailer initiating = builder(primary.port()).withSMTPServerUsername("account").withSMTPServerPassword("password")
                     .withProperty("mail.smtp.auth.mechanisms", "LOGIN")
                     .withConnectionConfig(ConnectionConfig.builder().withSmtpClientHostname("probe.example.test")
                             .withSessionTimeout(Duration.ofSeconds(5)).withReadTimeout(Duration.ofMillis(750)).build())
                     .withFailoverMailer(destination, policy())
                     .withSendHandlers(MailSendHandlers.builder().withResultObserver(outcomes::add).build()).buildMailer()) {
            assertThat(catchThrowable(() -> assertThat(send(initiating, email(), asynchronous).getStatus()).isEqualTo(ACCEPTED)))
                    .as("an AUTH outage permits ordinary setup failover").isNull();
            assertThat(primary.messages()).isEmpty();
            assertThat(secondary.messages()).hasSize(1);
            assertThat(outcomes).hasSize(1);
            assertThat(outcomes.get(0).isSuccessful()).isTrue();
            final var history = outcomes.get(0).getDiagnostics().orElseThrow().getConnectionHistory();
            assertThat(history).hasSize(2);
            assertThat(history.get(0).getExplanation()).contains("Connection unavailable");
            assertThat(history.get(0).isConnectionAcquired()).isFalse();
            assertThat(history.get(1).isConnectionAcquired()).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void suppliedAngusSessionsRetainTheirProviderAndConservativeAuthenticationPermission(final boolean allowAuthentication) throws Exception {
        try (RecordingSmtpServer primary = new RecordingSmtpServer((peer, connection) -> {
            connection.greet(peer, "250-localhost\r\n250 AUTH LOGIN");
            connection.expect(peer, "AUTH LOGIN");
            peer.reply("421 authentication service unavailable");
            peer.expectClosed();
        }); RecordingSmtpServer secondary = new RecordingSmtpServer(); Mailer destination = builder(secondary.port()).buildMailer()) {
            final Properties properties = new Properties();
            properties.setProperty("mail.transport.protocol", "smtp");
            properties.setProperty("mail.smtp.host", "localhost");
            properties.setProperty("mail.smtp.port", Integer.toString(primary.port()));
            properties.setProperty("mail.smtp.localhost", "probe.example.test");
            properties.setProperty("mail.smtp.user", "account");
            properties.setProperty("mail.smtp.auth", "true");
            properties.setProperty("mail.smtp.auth.mechanisms", "LOGIN");
            properties.setProperty("mail.smtp.timeout", "5000");
            final Properties original = new Properties();
            original.putAll(properties);
            final Session session = Session.getInstance(properties, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication("account", "password");
                }
            });
            assertThat(session.getProvider("smtp").getClassName()).isEqualTo("org.eclipse.angus.mail.smtp.SMTPTransport");
            try (Mailer initiating = factory().mailerBuilder(session).withFailoverMailer(destination,
                    FailoverPolicy.builder().withFailoverOnAuthenticationFailure(allowAuthentication).build()).buildMailer()) {
                final Throwable failure = catchThrowable(() -> initiating.sync().sendMail(email()));
                if (allowAuthentication) {
                    assertThat(failure).isNull();
                } else {
                    assertThat(failure).isInstanceOf(MailException.class);
                }
                assertThat(primary.messages()).isEmpty();
                assertThat(secondary.messages()).hasSize(allowAuthentication ? 1 : 0);
                assertThat(session.getProvider("smtp").getClassName()).isEqualTo("org.eclipse.angus.mail.smtp.SMTPTransport");
                // Existing internal owner/converter slots are not caller configuration and may be installed independently.
                assertThat(properties).containsAllEntriesOf(original);
                assertThat(properties.stringPropertyNames()).containsExactlyInAnyOrderElementsOf(original.stringPropertyNames());
            }
        }
    }
}
