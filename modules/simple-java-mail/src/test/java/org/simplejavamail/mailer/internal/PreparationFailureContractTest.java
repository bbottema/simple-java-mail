package org.simplejavamail.mailer.internal;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.Provider;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.URLName;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.MailException;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.mailer.MailFailureKind;
import org.simplejavamail.api.mailer.MailSendHandlers;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSubmissionException;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.MailSubmissionStatus;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.ConnectionConfig;
import org.simplejavamail.api.mailer.config.ConnectionPoolConfig;
import org.simplejavamail.api.mailer.config.FailoverPolicy;
import org.simplejavamail.api.mailer.config.MailSendRetryPolicy;
import org.simplejavamail.api.mailer.spi.MailTransportCompatibilityException;
import testutil.ConfigLoaderTestHelper;
import testutil.smtp.ScriptedSmtpServer;
import testutil.smtp.SmtpConversation;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.NEVER;
import static org.simplejavamail.mailer.internal.SmtpDsnCharacterizationTest.expectCommand;
import static org.simplejavamail.mailer.internal.SmtpDsnCharacterizationTest.finishConnection;
import static org.simplejavamail.mailer.internal.SmtpDsnCharacterizationTest.readGeneratedEnvelopeId;
import static org.simplejavamail.mailer.internal.SmtpDsnCharacterizationTest.readMessage;

/** Keeps local envelope preparation separate from the selected provider's submission and compatibility facts. */
@Timeout(30)
class PreparationFailureContractTest {
    private static final String ADDRESS = "receiver@example.test";
    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @ParameterizedTest
    @CsvSource({"sync, false", "sync, true", "async, false", "async, true",
            "batch, false", "batch, true", "scope, false", "scope, true"})
    void localEnvelopeMismatchIsPreparationWithoutSubmissionFacts(final String execution, final boolean retry) throws Exception {
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        try (ScriptedSmtpServer server = new ScriptedSmtpServer(1, peer -> {
            peer.greet("250-localhost\r\n250 DSN");
            if (execution.equals("sync") || execution.equals("async")) {
                // Only the corrected follow-up email reaches SMTP; the earlier preparation failure preserves this healthy lease.
                readGeneratedEnvelopeId(peer, "MAIL FROM:<sender@example.test>");
                acceptEmail(peer, true);
            }
            // Shared scopes have no follow-up email, so their setup is immediately followed by cleanup without MAIL/RCPT/DATA.
            finishConnection(peer);
        }); Mailer mailer = builder(server, retry, outcomes).buildMailer()) {
            final Email mismatch = mail.emailBuilder().copying(email(true))
                    .withHeader("To", "additional@example.test").buildEmail();
            final Throwable failure = sendFailure(mailer, mismatch, execution);
            final MailSendOutcome outcome = outcomes.get(0);
            if (execution.equals("sync") || execution.equals("async")) {
                assertThat(mailer.sync().sendMail(email(false)).getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED);
            }

            assertThat(failure).isInstanceOf(MailException.class)
                    .hasCauseInstanceOf(MailTransportCompatibilityException.class);
            assertThat(failure.getCause()).hasMessageContaining("final SMTP recipient list has 2 entries")
                    .hasMessageContaining("Email recipient list has 1");
            assertThat(((MailException) failure).getKind()).isEqualTo(MailFailureKind.PREPARATION);
            assertThat(((MailException) failure).getSubmissionReceipt()).isEmpty();
            assertThat(outcome.getFailure()).containsSame(failure);
            assertThat(outcome.getSubmissionReceipt()).isEmpty();
            assertThat(outcome.getDiagnostics().orElseThrow().getMimePreparation().isFailureObservedHere()).isTrue();
            assertThat(outcome.getDiagnostics().orElseThrow().getSubmission().getElapsed()).isNull();
            assertThat(outcomes).hasSize(execution.equals("sync") || execution.equals("async") ? 2 : 1);
        }
    }

    @ParameterizedTest
    @CsvSource({"sync, false", "sync, true", "async, false", "async, true"})
    void providerCompatibilityStillReturnsItsReceiptAndAReusableConnection(final String execution, final boolean retry) throws Exception {
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        try (ScriptedSmtpServer server = new ScriptedSmtpServer(1, peer -> {
            peer.greet("250 localhost"); // The provider must reject recipient DSN options without DSN support.
            // The follow-up ordinary email reuses the same healthy connection and has no generated ENVID.
            expectCommand(peer, "MAIL FROM:<sender@example.test>");
            acceptEmail(peer, false);
            finishConnection(peer);
        }); Mailer mailer = builder(server, retry, outcomes).buildMailer()) {
            final Throwable failure = sendFailure(mailer, email(true), execution);
            assertThat(failure).isInstanceOf(MailSubmissionException.class)
                    .hasCauseInstanceOf(MailTransportCompatibilityException.class);
            final MailException mailFailure = (MailException) failure;
            assertThat(mailFailure.getKind()).isEqualTo(MailFailureKind.SUBMISSION);
            final MailSubmissionReceipt receipt = mailFailure.getSubmissionReceipt().orElseThrow();
            assertThat(receipt.getStatus()).isEqualTo(MailSubmissionStatus.REJECTED);
            assertThat(receipt.getValidUnsentRecipients()).containsExactly(ADDRESS);
            assertThat(outcomes).singleElement().satisfies(outcome -> {
                assertThat(outcome.getFailure()).containsSame(failure);
                assertThat(outcome.getSubmissionReceipt()).containsSame(receipt);
                assertThat(outcome.getDiagnostics().orElseThrow().getSubmission().getElapsed()).isNotNull();
            });
            assertThat(mailer.sync().sendMail(email(false)).getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aProviderRuntimeFailureCannotClaimLocalPreparationProvenance(final boolean async) throws Exception {
        final ProviderState state = new ProviderState();
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        try (Mailer mailer = providerMailer(state, outcomes)) {
            final Throwable failure = sendFailure(mailer, email(false), async ? "async" : "sync");
            assertThat(failure).isSameAs(state.failure);
            assertThat(outcomes).singleElement().satisfies(outcome -> {
                assertThat(outcome.getFailure()).containsSame(failure);
                assertThat(outcome.getDiagnostics().orElseThrow().getSubmission().getElapsed()).isNotNull();
            });
            assertThat(state.closed.get()).isEqualTo(1);
            assertThat(mailer.sync().sendMail(email(false)).getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED);
            assertThat(state.connected.get()).isEqualTo(2);
            assertThat(state.submitted.get()).isEqualTo(2);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rethrowingAnEarlierRealPreparationFailureDoesNotMakeTheCurrentProviderLeaseHealthy(final boolean observed) throws Exception {
        final RuntimeException earlierFailure;
        try (ScriptedSmtpServer server = new ScriptedSmtpServer(1, peer -> {
            peer.greet("250-localhost\r\n250 DSN");
            finishConnection(peer);
        }); Mailer mailer = builder(server, false, new CopyOnWriteArrayList<>()).buildMailer()) {
            earlierFailure = (RuntimeException) sendFailure(mailer, mail.emailBuilder().copying(email(true))
                    .withHeader("To", "additional@example.test").buildEmail(), "sync");
            assertThat(((MailException) earlierFailure).getKind()).isEqualTo(MailFailureKind.PREPARATION);
        }
        final ProviderState state = new ProviderState(earlierFailure);
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        try (Mailer mailer = providerMailer(state, observed ? outcomes : null)) {
            assertThat(sendFailure(mailer, email(false), "sync")).isSameAs(earlierFailure);
            assertThat(state.closed.get()).isEqualTo(1);
            assertThat(mailer.sync().sendMail(email(false)).getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED);
            assertThat(state.connected.get()).isEqualTo(2);
        }
    }

    @org.junit.jupiter.api.Test
    void aSuccessfulRetainedSendPreservesTheEarlierLocalFailuresHealthyLeaseEvidence() throws Exception {
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        try (ScriptedSmtpServer server = new ScriptedSmtpServer(1, peer -> {
            peer.greet("250-localhost\r\n250 DSN");
            for (int index = 0; index < 2; index++) {
                readGeneratedEnvelopeId(peer, "MAIL FROM:<sender@example.test>");
                acceptEmail(peer, true);
            }
            finishConnection(peer);
        }); Mailer fallback = mail.mailerBuilder().withSMTPServer("localhost", 1)
                .withConnectionPoolConfig(ConnectionPoolConfig.builder().withCoreSize(0).build()).buildMailer();
                Mailer mailer = builder(server, false, outcomes).withFailoverMailer(fallback, FailoverPolicy.builder().build()).buildMailer()) {
            final Email mismatch = mail.emailBuilder().copying(email(true)).withHeader("To", "additional@example.test").buildEmail();
            mailer.withOpenConnection(sender -> {
                final Throwable failure = catchThrowable(() -> sender.sendMail(mismatch));
                assertThat(((MailException) failure).getKind()).isEqualTo(MailFailureKind.PREPARATION);
                assertThat(sender.sendMail(email(false)).getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED);
            });
            // The callback releases its retained lease; this independent send must still reuse that same physical connection.
            assertThat(mailer.sync().sendMail(email(false)).getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED);
        }
    }

    private Mailer providerMailer(final ProviderState state, final List<MailSendOutcome> outcomes) throws Exception {
        final Properties properties = new Properties();
        properties.put(ProviderState.class.getName(), state);
        properties.setProperty("mail.transport.protocol", "smtp");
        properties.setProperty("mail.smtp.host", "fixture.example.test");
        properties.setProperty("mail.smtp.localhost", "probe.example.test");
        final Session session = Session.getInstance(properties);
        session.setProvider(new Provider(Provider.Type.TRANSPORT, "smtp", RuntimeFailingTransport.class.getName(), "fixture", "1"));
        final org.simplejavamail.api.mailer.MailerFromSessionBuilder builder = mail.mailerBuilder(session)
                .withConnectionPoolConfig(ConnectionPoolConfig.builder().withCoreSize(0).withMaxSize(1).build());
        return (outcomes == null ? builder : builder.withSendHandlers(MailSendHandlers.builder().withResultObserver(outcomes::add).build())).buildMailer();
    }

    private static void acceptEmail(final SmtpConversation peer, final boolean dsn) throws Exception {
        peer.reply("250 sender accepted");
        peer.expect("RCPT TO:<" + ADDRESS + ">" + (dsn ? " ORCPT=rfc822;" + ADDRESS : ""));
        peer.reply("250 recipient accepted");
        peer.expect("DATA");
        peer.reply("354 send message");
        assertThat(readMessage(peer)).contains("Example");
        peer.reply("250 message accepted");
    }

    private MailerRegularBuilder<?> builder(final ScriptedSmtpServer server, final boolean retry, final List<MailSendOutcome> outcomes) {
        final MailerRegularBuilder<?> builder = mail.mailerBuilder().withSMTPServer("localhost", server.port())
                .withConnectionConfig(ConnectionConfig.builder().withSmtpClientHostname("probe.example.test")
                        .withSessionTimeout(Duration.ofSeconds(5)).build())
                .withConnectionPoolConfig(ConnectionPoolConfig.builder().withCoreSize(0).withMaxSize(1)
                        .withClaimTimeout(Duration.ofSeconds(5)).build())
                .withSendHandlers(MailSendHandlers.builder().withResultObserver(outcomes::add).build());
        return retry ? builder.withMailSendRetryPolicy(MailSendRetryPolicy.builder().withMaximumAttempts(2)
                .withRetryDelay(Duration.ofMillis(1)).build()) : builder;
    }

    private Email email(final boolean recipientDsn) {
        final Recipient recipient = (recipientDsn ? mail.recipientBuilder().withDeliveryStatusNotificationNotifyOptions(NEVER)
                : mail.recipientBuilder()).withAddress(ADDRESS).withType(Message.RecipientType.TO).build();
        return mail.emailBuilder().startingBlank().from("sender@example.test").withRecipients(recipient)
                .fixingMessageId("<preparation@example.test>").fixingSentDate(Instant.EPOCH)
                .withPlainText("Example").buildEmail();
    }

    private static Throwable sendFailure(final Mailer mailer, final Email email, final String execution) {
        final Throwable thrown = catchThrowable(() -> {
            switch (execution) {
                case "sync": mailer.sync().sendMail(email); break;
                case "async": mailer.async().sendMail(email).getCompletion().get(5, SECONDS); break;
                case "batch": mailer.sync().sendMailsInSimpleBatch(Collections.singletonList(email)); break;
                case "scope": mailer.withOpenConnection(sender -> sender.sendMail(email)); break;
                default: throw new AssertionError("Unknown execution mode: " + execution);
            }
        });
        return thrown instanceof ExecutionException ? thrown.getCause() : thrown;
    }

    private static final class ProviderState {
        private final AtomicInteger connected = new AtomicInteger();
        private final AtomicInteger submitted = new AtomicInteger();
        private final AtomicInteger closed = new AtomicInteger();
        private final RuntimeException failure;

        private ProviderState() {
            this(new MailerException(MailFailureKind.PREPARATION, "Application provider failure after entry",
                    new MailTransportCompatibilityException("Original application cause")));
        }

        private ProviderState(final RuntimeException failure) { this.failure = failure; }
    }

    /** A third-party provider can throw an arbitrary RuntimeException even when its kind resembles a local preparation failure. */
    public static final class RuntimeFailingTransport extends Transport {
        private final ProviderState state;

        public RuntimeFailingTransport(final Session session, final URLName url) {
            super(session, url);
            state = (ProviderState) session.getProperties().get(ProviderState.class.getName());
        }

        @Override protected boolean protocolConnect(final String host, final int port, final String user, final String password) {
            state.connected.incrementAndGet();
            return true;
        }

        @Override public void sendMessage(final Message message, final Address[] recipients) {
            if (state.submitted.incrementAndGet() == 1) { throw state.failure; }
        }

        @Override public void close() {
            state.closed.incrementAndGet();
            setConnected(false);
        }
    }
}
