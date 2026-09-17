package org.simplejavamail.mailer.internal;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.URLName;
import jakarta.mail.internet.InternetAddress;
import org.eclipse.angus.mail.smtp.SMTPMessage;
import org.eclipse.angus.mail.smtp.SMTPTransport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.MailRecipientResult;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.mailer.internal.SmtpCapabilityProbeCharacterizationTest.Conversation;
import testutil.ConfigLoaderTestHelper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static jakarta.mail.Message.RecipientType.TO;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.DELAY;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.FAILURE;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.ReturnOption.HEADERS_ONLY;
import static org.simplejavamail.api.mailer.MailSubmissionStatus.ACCEPTED;

/**
 * Checks the existing DSN wire behavior before expanding the public envelope model in #736.
 * Raw Angus extension cases describe provider hooks, not an approved workaround or a new Simple Java Mail API.
 */
@Timeout(30)
class SmtpDsnCharacterizationTest {

    private static final String DSN_EHLO = "250-localhost\r\n250 DSN";
    private static final String PLAIN_EHLO = "250 localhost";
    private static final String SENDER_COMMAND = "MAIL FROM:<sender@example.test>";
    private static final String FIRST_RECIPIENT = "first@example.test";
    private static final String SECOND_RECIPIENT = "second@example.test";

    @ParameterizedTest
    @CsvSource({"true, false", "true, true", "false, false", "false, true"})
    void existingDsnOptionsAreMessageWideAndDoNotLeakToTheNextPooledSend(final boolean advertised, final boolean asynchronous)
            throws Exception {
        try (ScriptedPeer server = new ScriptedPeer(peer -> {
            peer.greet(advertised ? DSN_EHLO : PLAIN_EHLO);
            final String firstMessage;
            if (advertised) {
                readGeneratedEnvelopeId(peer, SENDER_COMMAND + " RET=HDRS");
                firstMessage = acceptMessageAfterMailFrom(peer, " NOTIFY=FAILURE,DELAY", FIRST_RECIPIENT, SECOND_RECIPIENT);
            } else {
                firstMessage = acceptMessage(peer, SENDER_COMMAND, "", FIRST_RECIPIENT, SECOND_RECIPIENT);
            }
            assertThat(firstMessage).doesNotContain("NOTIFY=", "RET=", "ENVID=", "ORCPT=");
            if (advertised) {
                readGeneratedEnvelopeId(peer, SENDER_COMMAND);
                acceptMessageAfterMailFrom(peer, "", FIRST_RECIPIENT, SECOND_RECIPIENT);
            } else {
                acceptMessage(peer, SENDER_COMMAND, "", FIRST_RECIPIENT, SECOND_RECIPIENT);
            }
            finishConnection(peer);
        }); Mailer mailer = mailerBuilder(server.port()).buildMailer()) {
            assertAccepted(send(mailer, email(true), asynchronous), FIRST_RECIPIENT, SECOND_RECIPIENT);
            assertAccepted(send(mailer, email(false), asynchronous), FIRST_RECIPIENT, SECOND_RECIPIENT);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rawMailExtensionSendsEnvelopeIdEvenWhenTheServerDoesNotAdvertiseDsn(final boolean advertised) throws Exception {
        final Session session = SmtpCapabilityProbeCharacterizationTest.session(false);
        try (ScriptedPeer server = new ScriptedPeer(peer -> {
            peer.greet(advertised ? DSN_EHLO : PLAIN_EHLO);
            acceptMessage(peer, SENDER_COMMAND + (advertised ? " RET=HDRS" : "") + " ENVID=invoice+2B42",
                    advertised ? " NOTIFY=FAILURE,DELAY" : "", FIRST_RECIPIENT, SECOND_RECIPIENT);
            finishConnection(peer);
        }); SMTPTransport transport = transport(session)) {
            transport.connect("localhost", server.port(), null, null);
            assertThat(transport.supportsExtension("DSN")).isEqualTo(advertised);
            final SMTPMessage message = message(session);
            message.setNotifyOptions(SMTPMessage.NOTIFY_FAILURE | SMTPMessage.NOTIFY_DELAY);
            message.setReturnOption(SMTPMessage.RETURN_HDRS);
            message.setMailExtension("ENVID=invoice+2B42");
            transport.sendMessage(message, message.getAllRecipients());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"invoice42", "invoice+42", "invoice+2B42", "invoice=42"})
    void rawEnvelopeIdentifierIsNotValidatedOrXtextEncodedByAngus(final String envelopeId) throws Exception {
        final Session session = SmtpCapabilityProbeCharacterizationTest.session(false);
        try (ScriptedPeer server = new ScriptedPeer(peer -> {
            peer.greet(DSN_EHLO);
            // The peer deliberately accepts the raw string: this pins down transmission, not RFC validity.
            acceptMessage(peer, SENDER_COMMAND + " ENVID=" + envelopeId, "", FIRST_RECIPIENT, SECOND_RECIPIENT);
            finishConnection(peer);
        }); SMTPTransport transport = transport(session)) {
            transport.connect("localhost", server.port(), null, null);
            final SMTPMessage message = message(session);
            message.setMailExtension("ENVID=" + envelopeId);
            transport.sendMessage(message, message.getAllRecipients());
        }
    }

    @Test
    void mailExtensionCannotAttachOriginalRecipientMetadataToRcptCommands() throws Exception {
        final Session session = SmtpCapabilityProbeCharacterizationTest.session(false);
        try (ScriptedPeer server = new ScriptedPeer(peer -> {
            peer.greet(DSN_EHLO);
            peer.expect(SENDER_COMMAND + " ORCPT=rfc822;original@example.test");
            peer.reply("555 ORCPT belongs on RCPT TO, not MAIL FROM");
            peer.expect("RSET");
            peer.reply("250 reset");
            finishConnection(peer);
        }); SMTPTransport transport = transport(session)) {
            transport.connect("localhost", server.port(), null, null);
            final SMTPMessage message = message(session);
            message.setMailExtension("ORCPT=rfc822;original@example.test");
            assertThatThrownBy(() -> transport.sendMessage(message, message.getAllRecipients()))
                    .isInstanceOf(MessagingException.class)
                    .hasMessageContaining("ORCPT belongs on RCPT TO");
        }
    }

    @Test
    void sessionWideEnvelopeIdentifierIsRepeatedAcrossPooledTransactions() throws Exception {
        try (ScriptedPeer server = new ScriptedPeer(peer -> {
            peer.greet(DSN_EHLO);
            acceptMessage(peer, SENDER_COMMAND + " ENVID=shared-value", "", FIRST_RECIPIENT, SECOND_RECIPIENT);
            acceptMessage(peer, SENDER_COMMAND + " ENVID=shared-value", "", FIRST_RECIPIENT, SECOND_RECIPIENT);
            finishConnection(peer);
        }); Mailer mailer = mailerBuilder(server.port()).withProperty("mail.smtp.mailextension", "ENVID=shared-value").buildMailer()) {
            assertAccepted(mailer.sync().sendMail(email(false)), FIRST_RECIPIENT, SECOND_RECIPIENT);
            assertAccepted(mailer.async().sendMail(email(false)).getCompletion().get(10, SECONDS), FIRST_RECIPIENT, SECOND_RECIPIENT);
        }
    }

    @Test
    void exactEmailKeepsItsBytesAndDuplicateEnvelopeRecipientsWithExistingDsnOptions() throws Exception {
        final String exactEml = "From: Content Author <author@example.test>\r\n"
                + "To: visible@example.test\r\nSubject: Keep this exact source\r\n"
                + "Date: Tue, 15 Sep 2026 12:00:00 +0000\r\nMessage-ID: <dsn-exact@example.test>\r\n"
                + "MIME-Version: 1.0\r\nContent-Type: text/plain; charset=us-ascii\r\n"
                + "Content-Transfer-Encoding: 7bit\r\nX-Spacing:  preserved\r\n\r\nSynthetic exact content.\r\n";
        try (ScriptedPeer server = new ScriptedPeer(peer -> {
            peer.greet(DSN_EHLO);
            readGeneratedEnvelopeId(peer, SENDER_COMMAND + " RET=HDRS");
            final String transmitted = acceptMessageAfterMailFrom(peer, " NOTIFY=FAILURE,DELAY", FIRST_RECIPIENT, FIRST_RECIPIENT);
            assertThat(transmitted).isEqualTo(exactEml);
            finishConnection(peer);
        }); Mailer mailer = mailerBuilder(server.port()).buildMailer()) {
            final Email email = factory().emailBuilder().startingFromExactEml(exactEml.getBytes(US_ASCII))
                    .withEnvelopeSender("sender@example.test")
                    .withEnvelopeRecipients(FIRST_RECIPIENT, FIRST_RECIPIENT)
                    .withDeliveryStatusNotification(DeliveryStatusNotification.of(HEADERS_ONLY, FAILURE, DELAY))
                    .buildEmail();
            assertAccepted(mailer.sync().sendMail(email), FIRST_RECIPIENT, FIRST_RECIPIENT);
        }
    }

    private static SimpleJavaMail factory() {
        return SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
    }

    private static MailerRegularBuilder<?> mailerBuilder(final int port) {
        return factory().mailerBuilder().withSMTPServer("localhost", port)
                .withSmtpClientHostname("probe.example.test").withSessionTimeout(5000)
                .withConnectionPoolCoreSize(0).withConnectionPoolMaxSize(1).withConnectionPoolClaimTimeoutMillis(5000);
    }

    private static Email email(final boolean requestNotifications) {
        final EmailPopulatingBuilder builder = factory().emailBuilder().startingBlank().from("sender@example.test")
                .withRecipients(new Recipient(null, FIRST_RECIPIENT, TO, null), new Recipient(null, SECOND_RECIPIENT, TO, null))
                .withSubject("Local DSN characterization").withPlainText("Synthetic loopback test only.");
        if (requestNotifications) {
            builder.withDeliveryStatusNotification(HEADERS_ONLY, FAILURE, DELAY);
        }
        return builder.buildEmail();
    }

    private static MailSubmissionReceipt send(final Mailer mailer, final Email email, final boolean asynchronous) throws Exception {
        return asynchronous ? mailer.async().sendMail(email).getCompletion().get(10, SECONDS) : mailer.sync().sendMail(email);
    }

    private static void assertAccepted(final MailSubmissionReceipt receipt, final String... recipients) {
        assertThat(receipt.getStatus()).isEqualTo(ACCEPTED);
        assertThat(receipt.getAcceptedRecipients()).containsExactly(recipients);
        assertThat(receipt.getRecipientResults()).extracting(MailRecipientResult::getOriginalAddress).containsExactly(recipients);
    }

    private static SMTPTransport transport(final Session session) {
        return new SMTPTransport(session, new URLName("smtp", null, -1, null, null, null));
    }

    private static SMTPMessage message(final Session session) throws Exception {
        final SMTPMessage message = new SMTPMessage(session);
        message.setFrom(new InternetAddress("sender@example.test"));
        message.setRecipients(TO, new InternetAddress[]{new InternetAddress(FIRST_RECIPIENT), new InternetAddress(SECOND_RECIPIENT)});
        message.setSubject("Local provider DSN characterization");
        message.setText("Synthetic loopback test only.", US_ASCII.name());
        message.saveChanges();
        return message;
    }

    static String acceptMessage(final Conversation peer, final String senderCommand, final String recipientParameters,
                                        final String... recipients) throws IOException {
        expectCommand(peer, senderCommand);
        return acceptMessageAfterMailFrom(peer, recipientParameters, recipients);
    }

    static String readGeneratedEnvelopeId(final Conversation peer, final String senderCommand) throws IOException {
        final String command = readCommand(peer);
        final String prefix = senderCommand + " ENVID=";
        assertThat(command).startsWith(prefix);
        final String identifier = command.substring(prefix.length());
        assertThat(identifier).matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
        return identifier;
    }

    static String acceptMessageAfterMailFrom(final Conversation peer, final String recipientParameters, final String... recipients) throws IOException {
        peer.reply("250 sender accepted");
        for (final String recipient : recipients) {
            final String expected = "RCPT TO:<" + recipient + ">" + recipientParameters;
            // This legacy fixture covers both raw Angus and managed sends. RecipientDsnSubmissionTest pins down ORCPT strictly.
            assertThat(peer.readLine()).isIn(expected, expected + " ORCPT=rfc822;" + recipient);
            peer.reply("250 recipient accepted");
        }
        peer.expect("DATA");
        peer.reply("354 send synthetic message");
        final String content = readMessage(peer);
        peer.reply("250 2.0.0 accepted synthetic message");
        return content;
    }

    static String readMessage(final Conversation peer) throws IOException {
        final StringBuilder content = new StringBuilder();
        String line = peer.readLine();
        int lines = 0;
        while (line != null && !".".equals(line)) {
            assertThat(++lines).as("bounded synthetic message").isLessThan(100);
            content.append(line.startsWith("..") ? line.substring(1) : line).append("\r\n");
            line = peer.readLine();
        }
        assertThat(line).isEqualTo(".");
        assertThat(lines).isPositive();
        return content.toString();
    }

    static void finishConnection(final Conversation peer) throws IOException {
        expectCommand(peer, "QUIT");
        peer.reply("221 bye");
        peer.expectClosed();
    }

    static void expectCommand(final Conversation peer, final String expected) throws IOException {
        assertThat(readCommand(peer)).isEqualTo(expected);
    }

    private static String readCommand(final Conversation peer) throws IOException {
        String command = peer.readLine();
        int checks = 0;
        // Pool leasing and disposal can validate this same physical connection between messages and before QUIT.
        while ("NOOP".equals(command)) {
            assertThat(++checks).as("bounded pool connection checks").isLessThan(20);
            peer.reply("250 still connected");
            command = peer.readLine();
        }
        return command;
    }

    @FunctionalInterface
    private interface ServerScript {
        void run(Conversation peer) throws Exception;
    }

    private static final class ScriptedPeer implements AutoCloseable {
        private final ServerSocket server;
        private final ExecutorService worker = Executors.newSingleThreadExecutor();
        private final CompletableFuture<Void> serving;

        private ScriptedPeer(final ServerScript script) throws IOException {
            server = new ServerSocket(0, 1, InetAddress.getByName("localhost"));
            server.setSoTimeout(10000);
            serving = CompletableFuture.runAsync(() -> {
                try (Conversation peer = new Conversation(server.accept())) {
                    script.run(peer);
                } catch (Exception failure) {
                    throw new AssertionError("Scripted DSN peer failed", failure);
                }
            }, worker);
        }

        private int port() {
            return server.getLocalPort();
        }

        @Override
        public void close() throws Exception {
            try {
                serving.get(15, SECONDS);
            } finally {
                server.close();
                worker.shutdownNow();
                assertThat(worker.awaitTermination(5, SECONDS)).isTrue();
            }
        }
    }
}
