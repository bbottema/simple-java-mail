package org.simplejavamail.mailer.internal;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSubmissionException;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.api.mailer.spi.MailTransportCompatibilityException;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.mailer.internal.SmtpCapabilityProbeCharacterizationTest.Conversation;
import org.simplejavamail.mailer.internal.SmtpEnvelopeIdTest.PeerServer;
import testutil.ConfigLoaderTestHelper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.simplejavamail.api.mailer.MailSubmissionStatus.ACCEPTED;
import static org.simplejavamail.api.mailer.MailSubmissionStatus.REJECTED;
import static org.simplejavamail.api.mailer.MailSubmissionStatus.UNKNOWN;
import static org.simplejavamail.mailer.internal.SmtpDsnCharacterizationTest.acceptMessage;
import static org.simplejavamail.mailer.internal.SmtpDsnCharacterizationTest.finishConnection;
import static org.simplejavamail.mailer.internal.SmtpDsnCharacterizationTest.readMessage;

/** Exercises per-message REQUIRETLS through the ordinary Mailer and a real loopback STARTTLS connection. */
@Timeout(40)
class SmtpRequireTlsTest {

    private static final String MAIL_FROM = "MAIL FROM:<sender@example.test>";
    private static final String RECIPIENT = "receiver@example.test";
    private static final String REQUIRE_TLS = "250-localhost\r\n250 REQUIRETLS";
    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void composedAndExactEmailsUseRequireTlsAndReturnTheSameReceiptToTheObserver(final boolean exact, final boolean asynchronous) throws Exception {
        final byte[] eml = ("From: sender@example.test\r\nTo: visible@example.test\r\n"
                + "Message-ID: <requiretls@example.test>\r\nContent-Type: text/plain\r\n\r\nExact content.\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        final Email email = exact
                ? mail.emailBuilder().startingFromExactEml(eml).withEnvelopeSender("sender@example.test")
                        .withEnvelopeRecipients(RECIPIENT).withTlsRequiredForOnwardDelivery().buildEmail()
                : email(true);
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        try (PeerServer server = new PeerServer(1, peer -> {
            peer.startTls(REQUIRE_TLS);
            final String content = acceptMessage(peer, MAIL_FROM + " REQUIRETLS", "", RECIPIENT);
            if (exact) {
                assertThat(content.getBytes(StandardCharsets.US_ASCII)).containsExactly(eml);
            }
            finishConnection(peer);
        }); Mailer mailer = builder(server).withMailSendObserver(outcomes::add).buildMailer()) {
            final MailSubmissionReceipt receipt = send(mailer, email, asynchronous);

            assertThat(receipt.getStatus()).isEqualTo(ACCEPTED);
            assertThat(receipt.isRequireTlsUsed()).isTrue();
            assertThat(outcomes).singleElement().satisfies(outcome ->
                    assertThat(outcome.getSubmissionReceipt()).containsSame(receipt));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingPostTlsCapabilityRejectsBeforeMailFrom(final boolean asynchronous) throws Exception {
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        try (PeerServer server = new PeerServer(1, peer -> {
            peer.startTls("250 localhost");
            finishConnection(peer);
        }); Mailer mailer = builder(server).withMailSendObserver(outcomes::add).buildMailer()) {
            final Throwable thrown = catchThrowable(() -> send(mailer, email(true), asynchronous));
            final Throwable failure = asynchronous ? thrown.getCause() : thrown;

            assertThat(failure).isInstanceOf(MailSubmissionException.class)
                    .hasCauseInstanceOf(MailTransportCompatibilityException.class);
            assertThat(failure.getCause()).hasMessageContaining("does not advertise usable REQUIRETLS")
                    .hasMessageContaining("No message was submitted");
            final MailSubmissionReceipt receipt = ((MailSubmissionException) failure).getSubmissionReceipt();
            assertThat(receipt.isRequireTlsUsed()).isFalse();
            assertThat(receipt.getSmtpResponse()).isEmpty();
            assertThat(outcomes).singleElement().satisfies(outcome -> {
                assertThat(outcome.getFailure()).containsSame(failure);
                assertThat(outcome.getSubmissionReceipt()).containsSame(receipt);
            });
        }
    }

    @Test
    void rejectedMailFromStillReportsThatRequireTlsWasIssued() throws Exception {
        try (PeerServer server = new PeerServer(1, peer -> {
            peer.startTls(REQUIRE_TLS);
            peer.expect(MAIL_FROM + " REQUIRETLS");
            peer.reply("550 5.7.1 REQUIRETLS rejected by test server");
            peer.expect("RSET");
            peer.reply("250 reset");
            peer.expectClosed();
        }); Mailer mailer = builder(server).buildMailer()) {
            final Throwable failure = catchThrowable(() -> mailer.sync().sendMail(email(true)));

            assertThat(failure).isInstanceOf(MailSubmissionException.class);
            final MailSubmissionReceipt receipt = ((MailSubmissionException) failure).getSubmissionReceipt();
            assertThat(receipt.getStatus()).isEqualTo(REJECTED);
            assertThat(receipt.isRequireTlsUsed()).isTrue();
            assertThat(receipt.getSmtpResponse()).hasValueSatisfying(response ->
                    assertThat(response.getResponse()).contains("REQUIRETLS rejected"));
        }
    }

    @Test
    void missingFinalReplyKeepsRequireTlsSeparateFromUnknownAcceptance() throws Exception {
        try (PeerServer server = new PeerServer(1, peer -> {
            peer.startTls(REQUIRE_TLS);
            peer.expect(MAIL_FROM + " REQUIRETLS");
            peer.reply("250 sender accepted");
            peer.expect("RCPT TO:<" + RECIPIENT + ">");
            peer.reply("250 recipient accepted");
            peer.expect("DATA");
            peer.reply("354 send synthetic message");
            readMessage(peer);
            // Close without the final acceptance reply: REQUIRETLS use is known, message acceptance is not.
        }); Mailer mailer = builder(server).buildMailer()) {
            final Throwable failure = catchThrowable(() -> mailer.sync().sendMail(email(true)));

            assertThat(failure).isInstanceOf(MailSubmissionException.class);
            final MailSubmissionReceipt receipt = ((MailSubmissionException) failure).getSubmissionReceipt();
            assertThat(receipt.getStatus()).isEqualTo(UNKNOWN);
            assertThat(receipt.isRequireTlsUsed()).isTrue();
        }
    }

    @Test
    void openConnectionDoesNotLeakRequireTlsToTheNextEmail() throws Exception {
        try (PeerServer server = new PeerServer(1, peer -> {
            peer.startTls(REQUIRE_TLS);
            acceptMessage(peer, MAIL_FROM + " REQUIRETLS", "", RECIPIENT);
            acceptMessage(peer, MAIL_FROM, "", RECIPIENT);
            finishConnection(peer);
        }); Mailer mailer = builder(server).buildMailer()) {
            mailer.withOpenConnection(sender -> {
                assertThat(sender.sendMailAndGetReceipt(email(true)).isRequireTlsUsed()).isTrue();
                assertThat(sender.sendMailAndGetReceipt(email(false)).isRequireTlsUsed()).isFalse();
            });
        }
    }

    @Test
    void loggingOnlyAndCustomMailerNeverClaimTheSmtpFlagWasUsed() throws Exception {
        final Email email = email(true);
        try (Mailer loggingOnly = mail.mailerBuilder().withTransportModeLoggingOnly(true).buildMailer()) {
            assertThat(loggingOnly.sync().sendMail(email).isRequireTlsUsed()).isFalse();
        }

        final CustomMailer customMailer = new CustomMailer() {
            @Override
            public void testConnection(final OperationalConfig config, final Session session) {
            }

            @Override
            public void sendMessage(final OperationalConfig config, final Session session, final Email deliveredEmail,
                                    final MimeMessage message) {
                assertThat(deliveredEmail.isTlsRequiredForOnwardDelivery()).isTrue();
            }
        };
        try (Mailer mailer = mail.mailerBuilder().withCustomMailer(customMailer).buildMailer()) {
            final MailSubmissionReceipt receipt = mailer.sync().sendMail(email);
            assertThat(receipt.getStatus()).isEqualTo(UNKNOWN);
            assertThat(receipt.isRequireTlsUsed()).isFalse();
        }
    }

    private MailerRegularBuilder<?> builder(final PeerServer server) {
        return mail.mailerBuilder().withSMTPServer("localhost", server.port()).withTransportStrategy(TransportStrategy.SMTP_TLS)
                .withSmtpClientHostname("probe.example.test").trustingSSLHosts("localhost")
                .withSessionTimeout(5000).withConnectionPoolCoreSize(0).withConnectionPoolMaxSize(1)
                .withConnectionPoolClaimTimeoutMillis(10000).withProperty("mail.smtp.ssl.protocols", "TLSv1.2");
    }

    private Email email(final boolean requireTls) {
        final EmailPopulatingBuilder builder = mail.emailBuilder().startingBlank()
                .from("sender@example.test").withRecipients(new Recipient(null, RECIPIENT, Message.RecipientType.TO, null))
                .withSubject("Synthetic REQUIRETLS test").withPlainText("Synthetic loopback content.");
        if (requireTls) {
            builder.withTlsRequiredForOnwardDelivery();
        }
        return builder.buildEmail();
    }

    private static MailSubmissionReceipt send(final Mailer mailer, final Email email, final boolean asynchronous) throws Exception {
        return asynchronous ? mailer.async().sendMail(email).getCompletion().get(10, SECONDS) : mailer.sync().sendMail(email);
    }
}
