package org.simplejavamail.mailer.failover;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.MailException;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSubmissionException;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.FailoverPolicy;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import testutil.smtp.RecordingSmtpServer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.simplejavamail.api.mailer.MailSubmissionStatus.ACCEPTED;
import static org.simplejavamail.api.mailer.MailSubmissionStatus.PARTIALLY_ACCEPTED;
import static org.simplejavamail.api.mailer.MailSubmissionStatus.UNKNOWN;
import static org.simplejavamail.mailer.failover.FailoverContractSupport.*;
import static org.simplejavamail.recipient.RecipientBuilder.to;

/** The same public send contracts run here with real pools and in a separate, pool-free JVM. */
@Timeout(40)
class FailoverSmtpContractTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void healthyPrimaryDoesNotContactFailover(final boolean asynchronous) throws Exception {
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        try (RecordingSmtpServer primary = new RecordingSmtpServer(); RecordingSmtpServer secondary = new RecordingSmtpServer();
             Mailer destination = builder(secondary.port()).buildMailer();
             Mailer initiating = builder(primary.port()).withFailoverMailer(destination, policy()).withMailSendObserver(outcomes::add).buildMailer()) {
            final MailSubmissionReceipt receipt = send(initiating, email(), asynchronous);
            assertThat(receipt.getStatus()).isEqualTo(ACCEPTED);
            assertSuccessfulOutcome(outcomes, receipt);
            assertThat(primary.messages()).hasSize(1);
            assertThat(secondary.connectionCount()).isZero();
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void failedSetupSelectsAnotherFactoryWithoutReplaying(final boolean asynchronous, final boolean observed) throws Exception {
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        try (RecordingSmtpServer primary = unavailableServer(); RecordingSmtpServer secondary = new RecordingSmtpServer();
             Mailer destination = builder(factory(), secondary.port()).buildMailer()) {
            final var configured = builder(primary.port()).withFailoverMailer(destination, policy());
            if (observed) {
                configured.withMailSendObserver(outcomes::add);
            }
            try (Mailer initiating = configured.buildMailer()) {
                final MailSubmissionReceipt receipt = send(initiating, email(), asynchronous);
                assertThat(receipt.getStatus()).isEqualTo(ACCEPTED);
                assertThat(primary.connectionCount()).isEqualTo(1);
                assertThat(primary.messages()).isEmpty();
                assertThat(secondary.messages()).hasSize(1);
                if (observed) {
                    assertSuccessfulOutcome(outcomes, receipt);
                    final var history = outcomes.get(0).getDiagnostics().orElseThrow().getConnectionHistory();
                    assertThat(history).hasSize(2);
                    assertThat(history.get(0).isConnectionAttempted()).isTrue();
                    assertThat(history.get(0).isConnectionAcquired()).isFalse();
                    assertThat(history.get(1).isConnectionAcquired()).isTrue();
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void explicitAttemptsAreBoundedBeforeVisitingNestedFailover(final boolean asynchronous) throws Exception {
        try (RecordingSmtpServer primary = unavailableServer(); RecordingSmtpServer secondary = unavailableServer();
             RecordingSmtpServer tertiary = new RecordingSmtpServer(); Mailer last = builder(tertiary.port()).buildMailer();
             Mailer middle = builder(secondary.port()).withFailoverMailer(last, policy()).buildMailer();
             Mailer first = builder(primary.port()).withFailoverMailer(middle,
                     FailoverPolicy.builder().withPrimaryConnectionAttempts(2).build()).buildMailer()) {
            assertThat(send(first, email(), asynchronous).getStatus()).isEqualTo(ACCEPTED);
            assertThat(primary.connectionCount()).isEqualTo(2);
            assertThat(secondary.connectionCount()).isEqualTo(1);
            assertThat(tertiary.messages()).hasSize(1);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void exhaustionHasOneFailureOutcomeButNoSubmissionReceipt(final boolean asynchronous) throws Exception {
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        try (RecordingSmtpServer primary = unavailableServer(); RecordingSmtpServer secondary = unavailableServer();
             Mailer destination = builder(secondary.port()).buildMailer();
             Mailer initiating = builder(primary.port()).withFailoverMailer(destination, policy()).withMailSendObserver(outcomes::add).buildMailer()) {
            final Throwable failure = catchThrowable(() -> send(initiating, email(), asynchronous));
            assertThat(failure).isInstanceOf(MailException.class);
            assertThat(outcomes).hasSize(1);
            assertThat(outcomes.get(0).getFailure()).containsSame(failure);
            assertThat(outcomes.get(0).getSubmissionReceipt()).isEmpty();
            assertThat(primary.messages()).isEmpty();
            assertThat(secondary.messages()).isEmpty();
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void finalDataFailureNeverReplaysEvenWithoutDiagnostics(final boolean asynchronous, final boolean loseReply) throws Exception {
        try (RecordingSmtpServer primary = new RecordingSmtpServer((peer, connection) -> {
            connection.greet(peer, "250 localhost");
            connection.acceptMessages(peer, loseReply ? null : "554 content rejected");
        }); RecordingSmtpServer secondary = new RecordingSmtpServer(); Mailer destination = builder(secondary.port()).buildMailer();
             Mailer initiating = builder(primary.port()).withFailoverMailer(destination, policy()).buildMailer()) {
            final Throwable failure = catchThrowable(() -> send(initiating, email(), asynchronous));
            assertThat(failure).isInstanceOf(MailSubmissionException.class);
            if (loseReply) {
                assertThat(((MailSubmissionException) failure).getStatus()).isEqualTo(UNKNOWN);
            }
            assertThat(primary.messages()).hasSize(1);
            assertThat(secondary.connectionCount()).isZero();
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void recipientRejectionKeepsReceiptIdentityAndNeverUsesFailover(final boolean asynchronous, final boolean partial) throws Exception {
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        try (RecordingSmtpServer primary = new RecordingSmtpServer((peer, connection) -> {
            connection.greet(peer, "250 localhost");
            assertThat(connection.readCommand(peer)).startsWith("MAIL FROM:");
            peer.reply("250 ok");
            connection.expect(peer, "RCPT TO:<receiver@example.test>");
            peer.reply("250 ok");
            connection.expect(peer, "RCPT TO:<rejected@example.test>");
            peer.reply("550 no such mailbox");
            connection.acceptMessages(peer);
        }); RecordingSmtpServer secondary = new RecordingSmtpServer(); Mailer destination = builder(secondary.port()).buildMailer();
             Mailer initiating = builder(primary.port()).withFailoverMailer(destination, policy()).withMailSendObserver(outcomes::add).buildMailer()) {
            final Email message = MAIL.emailBuilder().copying(email()).withRecipients(to(null, "rejected@example.test"))
                    .withSendingToAcceptedRecipients(partial).buildEmail();
            final Throwable failure = catchThrowable(() -> send(initiating, message, asynchronous));
            assertThat(failure).isInstanceOf(MailSubmissionException.class);
            final MailSubmissionReceipt receipt = ((MailSubmissionException) failure).getSubmissionReceipt();
            assertThat(outcomes).hasSize(1);
            assertThat(outcomes.get(0).getFailure()).containsSame(failure);
            assertThat(outcomes.get(0).getSubmissionReceipt()).containsSame(receipt);
            assertThat(outcomes.get(0).isSuccessful()).isFalse();
            assertThat(primary.messages()).hasSize(partial ? 1 : 0);
            if (partial) {
                assertThat(receipt.getStatus()).isEqualTo(PARTIALLY_ACCEPTED);
                assertThat(receipt.getAcceptedRecipients()).containsExactly("receiver@example.test");
            }
            assertThat(secondary.connectionCount()).isZero();
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void authenticationPermissionDoesNotFollowCertificatePermission(final boolean allowAuthentication, final boolean allowCertificate) throws Exception {
        try (RecordingSmtpServer primary = new RecordingSmtpServer((peer, connection) -> {
            connection.greet(peer, "250-localhost\r\n250 AUTH LOGIN");
            connection.expect(peer, "AUTH LOGIN");
            peer.reply("535 invalid credentials");
            connection.acceptMessages(peer);
        }); RecordingSmtpServer secondary = new RecordingSmtpServer(); Mailer destination = builder(secondary.port()).buildMailer();
             Mailer initiating = builder(primary.port()).withSMTPServerUsername("account").withSMTPServerPassword("password")
                     .withFailoverMailer(destination, FailoverPolicy.builder().withFailoverOnAuthenticationFailure(allowAuthentication)
                             .withFailoverOnCertificateVerificationFailure(allowCertificate).build()).buildMailer()) {
            final Throwable failure = catchThrowable(() -> initiating.sync().sendMail(email()));
            assertThat(failure == null).isEqualTo(allowAuthentication);
            assertThat(primary.messages()).isEmpty();
            assertThat(secondary.messages()).hasSize(allowAuthentication ? 1 : 0);
            if (!allowAuthentication) {
                assertThat(secondary.connectionCount()).isZero();
            }
        }
    }

    @Test
    void requiredTlsStopsBeforeTryingAnInsecureDestination() throws Exception {
        try (RecordingSmtpServer primary = new RecordingSmtpServer(); RecordingSmtpServer secondary = new RecordingSmtpServer();
             Mailer destination = builder(secondary.port()).buildMailer();
             Mailer initiating = builder(primary.port()).withTransportStrategy(TransportStrategy.SMTP_TLS)
                     .withFailoverMailer(destination, policy()).buildMailer()) {
            assertThat(catchThrowable(() -> initiating.sync().sendMail(email()))).isInstanceOf(MailException.class);
            assertThat(primary.messages()).isEmpty();
            assertThat(secondary.connectionCount()).isZero();
        }
    }

    @Test
    void missingSubmissionCapabilityDoesNotBecomeConnectionFailover() throws Exception {
        try (RecordingSmtpServer primary = new RecordingSmtpServer(); RecordingSmtpServer secondary = new RecordingSmtpServer();
             Mailer destination = builder(secondary.port()).buildMailer();
             Mailer initiating = builder(primary.port()).withFailoverMailer(destination, policy()).buildMailer()) {
            final Email message = MAIL.emailBuilder().copying(email()).fixingEnvelopeId("explicit-id").buildEmail();
            assertThat(catchThrowable(() -> initiating.sync().sendMail(message))).isInstanceOf(MailException.class);
            assertThat(primary.messages()).isEmpty();
            assertThat(secondary.connectionCount()).isZero();
            assertThat(initiating.sync().sendMail(email()).getStatus()).isEqualTo(ACCEPTED);
            assertThat(primary.messages()).hasSize(1);
        }
    }

    static RecordingSmtpServer unavailableServer() throws Exception {
        return new RecordingSmtpServer((peer, connection) -> peer.reply("421 temporarily unavailable"));
    }
}
