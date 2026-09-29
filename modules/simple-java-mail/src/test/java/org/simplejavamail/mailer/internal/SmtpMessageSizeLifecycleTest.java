package org.simplejavamail.mailer.internal;

import jakarta.mail.Address;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.OriginalOpenPgpDetails.SignatureStatus;
import org.simplejavamail.api.email.OriginalSmimeDetails.VerificationStatus;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.email.config.OpenPgpReceiveConfig;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.spi.ContentRequirement;
import org.simplejavamail.api.mailer.spi.DeliveryEnvelope;
import org.simplejavamail.api.mailer.spi.MailTransportResult;
import org.simplejavamail.api.mailer.spi.PreparedMail;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.internal.mailprovider.angus.AngusMailTransportAdapter;
import org.simplejavamail.internal.mailprovider.angus.AngusMailTransportLifecycleAdapter;
import org.simplejavamail.internal.util.FinalizedMimeMessage;
import testutil.smtp.ScriptedSmtpServer;
import org.simplejavamail.recipient.RecipientBuilder;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.simplejavamail.mailer.internal.SmtpContentNegotiationCharacterizationTest.exactEmail;
import static org.simplejavamail.mailer.internal.SmtpDsnCharacterizationTest.acceptMessage;
import static org.simplejavamail.mailer.internal.SmtpDsnCharacterizationTest.acceptMessageAfterMailFrom;
import static org.simplejavamail.mailer.internal.SmtpDsnCharacterizationTest.finishConnection;

@Timeout(30)
class SmtpMessageSizeLifecycleTest {
    private static final byte[] CONTENT = "From: sender@example.test\r\nTo: receiver@example.test\r\n\r\nSynthetic content.\r\n".getBytes(US_ASCII);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void interruptedMeasurementNeverSubmitsOrPublishesAPartialCount(final boolean cancel) throws Exception {
        final Session session = session();
        final AngusMailTransportLifecycleAdapter lifecycle = new AngusMailTransportLifecycleAdapter();
        lifecycle.configureOwnedSession(session, "smtp");
        try (ScriptedSmtpServer server = new ScriptedSmtpServer(1, peer -> {
            peer.greet("250-localhost\r\n250 SIZE 99999");
            peer.expectClosed();
        }); Transport transport = session.getTransport("smtp")) {
            transport.connect("localhost", server.port(), null, null);
            final Runnable abort = lifecycle.createAbortAction(transport).orElseThrow();
            final MimeMessage message = new MimeMessage(session) {
                @Override
                public void writeTo(final OutputStream output, final String[] ignored) throws IOException {
                    output.write("From: sender@example.test\r\n".getBytes(US_ASCII));
                    if (cancel) {
                        abort.run();
                        output.write("\r\nnever submitted".getBytes(US_ASCII));
                    } else {
                        throw new IOException("synthetic content read failure");
                    }
                }
            };
            message.setFrom("sender@example.test");
            message.setText("Synthetic content");
            message.saveChanges();
            final MailTransportResult result = send(transport, message, new Address[]{new InternetAddress("receiver@example.test")});
            assertThat(result.getMessageSize()).isNull();
            assertThat(result.getServerMaximumMessageSize()).isEqualTo(99999L);
            assertThat(result.getSmtpResponse()).isEmpty();
            assertThat(result.getEnvelopeId()).isNull();
            assertThat(result.getFailure()).isPresent();
        }
    }

    @Test
    void localRecipientFailureNeverPretendsContentWasMeasured() throws Exception {
        final Session session = session();
        new AngusMailTransportLifecycleAdapter().configureOwnedSession(session, "smtp");
        try (ScriptedSmtpServer server = new ScriptedSmtpServer(1, peer -> {
            peer.greet("250-localhost\r\n250 SIZE 99999");
            finishConnection(peer);
        }); Transport transport = session.getTransport("smtp")) {
            transport.connect("localhost", server.port(), null, null);
            final MimeMessage message = new MimeMessage(session);
            message.setFrom("sender@example.test");
            message.setText("Nothing to submit");
            message.saveChanges();
            final MailTransportResult result = send(transport, message, new Address[0]);
            assertThat(result.getMessageSize()).isNull();
            assertThat(result.getServerMaximumMessageSize()).isEqualTo(99999L);
            assertThat(result.getSmtpResponse()).isEmpty();
        }
    }

    @Test
    void reconnectAndHeloDiscardTheEarlierLimit() throws Exception {
        final Session session = session();
        new AngusMailTransportLifecycleAdapter().configureOwnedSession(session, "smtp");
        final AtomicInteger connections = new AtomicInteger();
        try (ScriptedSmtpServer server = new ScriptedSmtpServer(2, peer -> {
            if (connections.incrementAndGet() == 1) {
                peer.greet("250-localhost\r\n250 SIZE 1");
            } else {
                peer.greet("500 EHLO unavailable");
                peer.expect("HELO probe.example.test");
                peer.reply("250 hello");
                acceptMessage(peer, "MAIL FROM:<sender@example.test>", "", "receiver@example.test");
            }
            finishConnection(peer);
        }); Transport transport = session.getTransport("smtp")) {
            transport.connect("localhost", server.port(), null, null);
            transport.close();
            transport.connect("localhost", server.port(), null, null);
            final MimeMessage message = new MimeMessage(session);
            message.setFrom("sender@example.test");
            message.setText("Larger than the old limit");
            message.saveChanges();
            final MailTransportResult result = send(transport, message, new Address[]{new InternetAddress("receiver@example.test")});
            assertThat(result.getMessageSize()).isPositive();
            assertThat(result.getServerMaximumMessageSize()).isNull();
            assertThat(result.getFailure()).isEmpty();
        }
    }

    @Test
    void callerOwnedOrdinaryAngusSessionKeepsItsBehaviorAndDoesNotInventSizes() throws Exception {
        try (ScriptedSmtpServer server = new ScriptedSmtpServer(1, peer -> {
            peer.greet("250-localhost\r\n250 SIZE 1");
            acceptMessage(peer, "MAIL FROM:<sender@example.test>", "", "receiver@example.test");
            finishConnection(peer);
        })) {
            final Session session = session();
            session.getProperties().setProperty("mail.smtp.host", "localhost");
            session.getProperties().setProperty("mail.smtp.port", Integer.toString(server.port()));
            try (Mailer mailer = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder(session).buildMailer()) {
                assertUnknownSizes(mailer.sync().sendMail(exactEmail(CONTENT)));
            }
        }
    }

    @Test
    void loggingAndCustomMailersDoNotInventSizes() throws Exception {
        final SimpleJavaMail factory = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
        try (Mailer logging = factory.mailerBuilder().withTransportModeLoggingOnly(true).buildMailer();
             Mailer custom = factory.mailerBuilder().withCustomMailer(mock(CustomMailer.class)).buildMailer()) {
            assertUnknownSizes(logging.sync().sendMail(exactEmail(CONTENT)));
            assertUnknownSizes(custom.sync().sendMail(exactEmail(CONTENT)));
        }
    }

    private static void assertUnknownSizes(final MailSubmissionReceipt receipt) {
        assertThat(receipt.getMessageSize()).isNull();
        assertThat(receipt.getServerMaximumMessageSize()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"dkim", "smime", "openpgp"})
    void countsAndSubmitsProtectedRepresentationsWithoutRebuildingOrChangingSignatures(final String protection) throws Exception {
        final byte[] original = protectedContent(protection);
        verifyProtectedContent(protection, original);
        final CompletableFuture<byte[]> received = new CompletableFuture<>();
        final Session session = session();
        new AngusMailTransportLifecycleAdapter().configureOwnedSession(session, "smtp");
        try (ScriptedSmtpServer server = new ScriptedSmtpServer(1, ISO_8859_1, peer -> {
            peer.greet("250-localhost\r\n250-8BITMIME\r\n250-SMTPUTF8\r\n250 SIZE 999999");
            final String command = peer.readLine();
            assertThat(command).startsWith("MAIL FROM:<sender@example.test>");
            final byte[] captured = acceptMessageAfterMailFrom(peer, "", "receiver@example.test").getBytes(ISO_8859_1);
            assertThat(command).endsWith(" SIZE=" + captured.length);
            received.complete(captured);
            finishConnection(peer);
        }); Transport transport = session.getTransport("smtp")) {
            transport.connect("localhost", server.port(), null, null);
            final MimeMessage message = FinalizedMimeMessage.fromMessageBytes(session, original, FinalizedMimeMessage.ProtectionState.CONTENT_PROTECTED);
            final MailTransportResult result = new AngusMailTransportAdapter().sendMessage(transport, new PreparedMail(message,
                    new Address[]{new InternetAddress("receiver@example.test")}, new DeliveryEnvelope("sender@example.test", null),
                    ContentRequirement.PRESERVE_PROTECTED_CONTENT));
            assertThat(result.getFailure()).isEmpty();
            final byte[] captured = received.get(10, SECONDS);
            assertThat(result.getMessageSize()).isEqualTo((long) captured.length);
            assertThat(captured).containsExactly(original);
            assertThat(EmailConverter.mimeMessageToEMLByteArray(message)).containsExactly(original);
            verifyProtectedContent(protection, captured);
        }
    }

    private static byte[] protectedContent(final String protection) throws Exception {
        if (protection.equals("smime")) {
            // The external fixture uses LF outside its signed part. Prepare its SMTP line endings before testing byte preservation.
            final byte[] fixture = Base64.getMimeDecoder().decode(resource("smime/openssl-detached-signed.eml.b64"));
            return new String(fixture, US_ASCII).replace("\r\n", "\n").replace("\n", "\r\n").getBytes(US_ASCII);
        }
        if (protection.equals("openpgp")) {
            return resource("openpgpjs/signed-mixed.eml");
        }
        final KeyPairGenerator keys = KeyPairGenerator.getInstance("RSA");
        keys.initialize(2048);
        final Email email = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).emailBuilder().startingBlank()
                .from("sender@supersecret-testing-domain.com").withRecipients(RecipientBuilder.to(null, "receiver@example.test"))
                .withPlainText("A signed message with an attachment.\r\n")
                .withAttachment("sample.txt", "stable attachment".getBytes(US_ASCII), "text/plain")
                .signWithDomainKey(DkimConfig.builder().dkimPrivateKeyData(keys.generateKeyPair().getPrivate().getEncoded())
                        .dkimSigningDomain("supersecret-testing-domain.com").dkimSelector("test").build()).buildEmail();
        return EmailConverter.mimeMessageToEMLByteArray(EmailConverter.emailToMimeMessage(email));
    }

    private static void verifyProtectedContent(final String protection, final byte[] received) throws Exception {
        if (protection.equals("smime")) {
            assertThat(EmailConverter.emlToEmail(new ByteArrayInputStream(received)).getOriginalSmimeDetails().getVerificationStatus())
                    .isEqualTo(VerificationStatus.VALID);
        } else if (protection.equals("openpgp")) {
            final OpenPgpReceiveConfig config = OpenPgpReceiveConfig.builder().addVerificationKeyRing(resource("openpgpjs/public-key.asc")).build();
            assertThat(EmailConverter.emlToEmailWithOpenPgp(new ByteArrayInputStream(received), config)
                    .getOriginalOpenPgpDetails().getSignatureStatus()).isEqualTo(SignatureStatus.VALID);
        } else {
            assertThat(new String(received, US_ASCII)).contains("DKIM-Signature:");
        }
    }

    private static byte[] resource(final String name) throws Exception {
        try (InputStream input = SmtpMessageSizeLifecycleTest.class.getClassLoader().getResourceAsStream(name)) {
            assertThat(input).as(name).isNotNull();
            return input.readAllBytes();
        }
    }

    private static MailTransportResult send(final Transport transport, final MimeMessage message, final Address[] recipients) {
        return new AngusMailTransportAdapter().sendMessage(transport,
                new PreparedMail(message, recipients, new DeliveryEnvelope("sender@example.test", null), ContentRequirement.NORMAL));
    }

    private static Session session() {
        final Properties properties = new Properties();
        properties.setProperty("mail.smtp.localhost", "probe.example.test");
        properties.setProperty("mail.smtp.timeout", "5000");
        return Session.getInstance(properties);
    }
}
