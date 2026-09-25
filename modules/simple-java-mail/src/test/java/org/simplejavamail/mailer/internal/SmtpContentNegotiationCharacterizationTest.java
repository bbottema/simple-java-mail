package org.simplejavamail.mailer.internal;

import jakarta.mail.Message;
import jakarta.mail.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.ContentTransferEncoding;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.mailer.MailSubmissionException;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.api.mailer.spi.MailTransportCompatibilityException;
import org.simplejavamail.mailer.internal.SmtpEnvelopeIdTest.PeerServer;
import org.simplejavamail.recipient.RecipientBuilder;
import testutil.ConfigLoaderTestHelper;

import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.simplejavamail.mailer.internal.SmtpDsnCharacterizationTest.acceptMessage;
import static org.simplejavamail.mailer.internal.SmtpDsnCharacterizationTest.finishConnection;

/**
 * Pins down Angus 2.0.5 SMTPUTF8 and 8BITMIME behavior and the narrow compatibility guardrails applied by Simple Java Mail in #742.
 */
@Timeout(30)
class SmtpContentNegotiationCharacterizationTest {

    private static final String MAIL_FROM = "MAIL FROM:<sender@example.test>";
    private static final String RECIPIENT = "receiver@example.test";
    private static final String EIGHT_BIT_TEXT = "Synthetic café content.";

    @Test
    void enabledUtf8DoesNotDeclareSmtpUtf8ForAsciiMail() throws Exception {
        try (PeerServer server = new PeerServer(1, UTF_8, peer -> {
            peer.greet("250-localhost\r\n250 SMTPUTF8");
            acceptMessage(peer, MAIL_FROM, "", RECIPIENT);
            finishConnection(peer);
        }); Mailer mailer = builder(server).withProperty("mail.mime.allowutf8", true).buildMailer()) {
            mailer.sync().sendMail(composedEmail("sender@example.test", RECIPIENT, "ASCII subject", "ASCII body"));
        }
    }

    @Test
    void defaultUtf8SendsAnInternationalizedRecipientWhenSmtpUtf8IsAvailable() throws Exception {
        final String internationalizedRecipient = "müller@example.test";
        try (PeerServer server = new PeerServer(1, UTF_8, peer -> {
            peer.greet("250-localhost\r\n250 SMTPUTF8");
            final String content = acceptMessage(peer, MAIL_FROM + " SMTPUTF8", "", internationalizedRecipient);
            assertThat(content).contains("To: " + internationalizedRecipient);
            finishConnection(peer);
        }); Mailer mailer = builder(server).buildMailer()) {
            mailer.sync().sendMail(composedEmail("sender@example.test", internationalizedRecipient, "ASCII subject", "ASCII body"));
        }
    }

    @Test
    void defaultUtf8RejectsAnInternationalizedRecipientWithoutNegotiatedSupport() throws Exception {
        final String internationalizedRecipient = "müller@example.test";
        try (PeerServer server = new PeerServer(1, UTF_8, peer -> {
            peer.greet("250 localhost");
            finishConnection(peer);
        }); Mailer mailer = builder(server).buildMailer()) {
            assertCompatibilityFailure(catchThrowable(() -> mailer.sync().sendMail(
                    composedEmail("sender@example.test", internationalizedRecipient, "ASCII subject", "ASCII body"))),
                    "does not advertise SMTPUTF8");
        }
    }

    @Test
    void defaultUtf8RejectsAnInternationalizedSenderWithoutNegotiatedSupport() throws Exception {
        final String internationalizedSender = "séndér@example.test";
        try (PeerServer server = new PeerServer(1, UTF_8, peer -> {
            peer.greet("250 localhost");
            finishConnection(peer);
        }); Mailer mailer = builder(server).buildMailer()) {
            assertCompatibilityFailure(catchThrowable(() -> mailer.sync().sendMail(
                    composedEmail(internationalizedSender, RECIPIENT, "ASCII subject", "ASCII body"))),
                    "does not advertise SMTPUTF8");
        }
    }

    @Test
    void disabledUtf8RejectsAnInternationalizedRecipientEvenWhenTheServerSupportsIt() throws Exception {
        final String internationalizedRecipient = "müller@example.test";
        try (PeerServer server = new PeerServer(1, ISO_8859_1, peer -> {
            peer.greet("250-localhost\r\n250 SMTPUTF8");
            finishConnection(peer);
        }); Mailer mailer = builder(server).withProperty("mail.mime.allowutf8", false).buildMailer()) {
            assertCompatibilityFailure(catchThrowable(() -> mailer.sync().sendMail(
                    composedEmail("sender@example.test", internationalizedRecipient, "ASCII subject", "ASCII body"))),
                    "it is disabled for this SMTP transport");
        }
    }

    @Test
    void defaultUtf8SendsInternationalizedSenderAndReplyToHeadersWithoutCorruptingThem() throws Exception {
        final Email email = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).emailBuilder().startingBlank()
                .from("séndér@example.test").withReplyTo("réply@example.test")
                .withRecipients(new Recipient(null, RECIPIENT, Message.RecipientType.TO, null)).withPlainText("ASCII body").buildEmail();
        try (PeerServer server = new PeerServer(1, UTF_8, peer -> {
            peer.greet("250-localhost\r\n250 SMTPUTF8");
            final String content = acceptMessage(peer, "MAIL FROM:<séndér@example.test> SMTPUTF8", "", RECIPIENT);
            assertThat(content).contains("From: séndér@example.test", "Reply-To: réply@example.test");
            finishConnection(peer);
        }); Mailer mailer = builder(server).buildMailer()) {
            mailer.sync().sendMail(email);
        }
    }

    @Test
    void defaultUtf8KeepsOrdinaryDisplayNamesCompatibleWithLegacyServers() throws Exception {
        final Email email = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).emailBuilder().startingBlank()
                .from("Séndér", "sender@example.test").withRecipients(new Recipient("José", RECIPIENT, Message.RecipientType.TO, null))
                .withReplyTo("Réply", "reply@example.test").withPlainText("ASCII body").buildEmail();
        try (PeerServer server = new PeerServer(1, UTF_8, peer -> {
            peer.greet("250 localhost");
            final String content = acceptMessage(peer, MAIL_FROM, "", RECIPIENT);
            assertThat(content).contains("From: =?UTF-8?", "To: =?UTF-8?", "Reply-To: =?UTF-8?")
                    .doesNotContain("Séndér", "José", "Réply");
            finishConnection(peer);
        }); Mailer mailer = builder(server).buildMailer()) {
            mailer.sync().sendMail(email);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void callerOwnedSessionRetainsItsUtf8Setting(final boolean enabled) throws Exception {
        final String internationalizedRecipient = "müller@example.test";
        final Properties properties = new Properties();
        properties.setProperty("mail.transport.protocol", "smtp");
        properties.setProperty("mail.smtp.host", "localhost");
        properties.setProperty("mail.smtp.localhost", "probe.example.test");
        if (enabled) {
            properties.setProperty("mail.mime.allowutf8", "true");
        }
        try (PeerServer server = new PeerServer(1, UTF_8, peer -> {
            peer.greet("250-localhost\r\n250 SMTPUTF8");
            if (enabled) {
                acceptMessage(peer, MAIL_FROM + " SMTPUTF8", "", internationalizedRecipient);
            }
            finishConnection(peer);
        })) {
            properties.setProperty("mail.smtp.port", String.valueOf(server.port()));
            final Session session = Session.getInstance(properties);
            try (Mailer mailer = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder(session)
                    .withSessionTimeout(5000).withConnectionPoolCoreSize(0).withConnectionPoolMaxSize(1).buildMailer()) {
                final Email email = composedEmail("sender@example.test", internationalizedRecipient, "ASCII subject", "ASCII body");
                if (enabled) {
                    mailer.sync().sendMail(email);
                } else {
                    assertCompatibilityFailure(catchThrowable(() -> mailer.sync().sendMail(email)), "For a caller-supplied Session");
                    assertThat(session.getProperty("mail.mime.allowutf8")).isNull();
                }
                assertThat(mailer.getSession()).isSameAs(session);
            }
        }
    }

    @Test
    void ordinaryComposedMailStaysSevenBitByDefaultWhenEightBitMimeIsAvailable() throws Exception {
        try (PeerServer server = new PeerServer(1, UTF_8, peer -> {
            peer.greet("250-localhost\r\n250 8BITMIME");
            final String content = acceptMessage(peer, MAIL_FROM, "", RECIPIENT);
            assertThat(content).contains("Content-Transfer-Encoding: quoted-printable")
                    .contains("Synthetic caf=C3=A9 content.")
                    .doesNotContain("Content-Transfer-Encoding: 8bit");
            finishConnection(peer);
        }); Mailer mailer = builder(server).buildMailer()) {
            mailer.sync().sendMail(eightBitCandidate());
        }
    }

    @Test
    void ordinaryUnicodeHeadersRemainAsciiEncodedWithoutSmtpUtf8() throws Exception {
        try (PeerServer server = new PeerServer(1, UTF_8, peer -> {
            peer.greet("250 localhost");
            final String content = acceptMessage(peer, MAIL_FROM, "", RECIPIENT);
            assertThat(content).contains("Subject: =?UTF-8?").doesNotContain("Subject: Café rendez-vous");
            finishConnection(peer);
        }); Mailer mailer = builder(server).buildMailer()) {
            mailer.sync().sendMail(composedEmail("sender@example.test", RECIPIENT, "Café rendez-vous", "ASCII body"));
        }
    }

    @Test
    void customComposedUnicodeHeaderRemainsAsciiEncodedWithoutSmtpUtf8() throws Exception {
        final Email email = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).emailBuilder().startingBlank()
                .from("sender@example.test").withRecipients(new Recipient(null, RECIPIENT, Message.RecipientType.TO, null))
                .withSubject("ASCII subject").withPlainText("ASCII body")
                .withHeader("X-Internationalized", "Café rendez-vous").buildEmail();
        try (PeerServer server = new PeerServer(1, UTF_8, peer -> {
            peer.greet("250 localhost");
            final String content = acceptMessage(peer, MAIL_FROM, "", RECIPIENT);
            assertThat(content).contains("X-Internationalized: =?UTF-8?").doesNotContain("X-Internationalized: Café rendez-vous");
            finishConnection(peer);
        }); Mailer mailer = builder(server).buildMailer()) {
            mailer.sync().sendMail(email);
        }
    }

    @Test
    void enabledEightBitMimeConvertsEligibleTextAndDeclaresTheBodyParameter() throws Exception {
        try (PeerServer server = new PeerServer(1, UTF_8, peer -> {
            peer.greet("250-localhost\r\n250 8BITMIME");
            final String content = acceptMessage(peer, MAIL_FROM + " BODY=8BITMIME", "", RECIPIENT);
            assertThat(content).contains("Content-Transfer-Encoding: 8bit")
                    .contains(EIGHT_BIT_TEXT)
                    .doesNotContain("Content-Transfer-Encoding: quoted-printable");
            finishConnection(peer);
        }); Mailer mailer = builder(server).withProperty("mail.smtp.allow8bitmime", true).buildMailer()) {
            mailer.sync().sendMail(eightBitCandidate());
        }
    }

    @Test
    void enabledEightBitMimeKeepsSevenBitTransferEncodingWhenTheServerDoesNotAdvertiseSupport() throws Exception {
        try (PeerServer server = new PeerServer(1, UTF_8, peer -> {
            peer.greet("250 localhost");
            final String content = acceptMessage(peer, MAIL_FROM, "", RECIPIENT);
            assertThat(content).contains("Content-Transfer-Encoding: quoted-printable")
                    .contains("Synthetic caf=C3=A9 content.")
                    .doesNotContain("Content-Transfer-Encoding: 8bit");
            finishConnection(peer);
        }); Mailer mailer = builder(server).withProperty("mail.smtp.allow8bitmime", true).buildMailer()) {
            mailer.sync().sendMail(eightBitCandidate());
        }
    }

    @Test
    void explicitlyEightBitComposedBodyUsesTheEightBitMimeDeclaration() throws Exception {
        final Email email = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).emailBuilder().startingBlank()
                .from("sender@example.test").withRecipients(new Recipient(null, RECIPIENT, Message.RecipientType.TO, null))
                .withSubject("8BITMIME declaration").withPlainText(EIGHT_BIT_TEXT)
                .withPlainTextContentTransferEncoding(ContentTransferEncoding.BIT8).buildEmail();
        try (PeerServer server = new PeerServer(1, UTF_8, peer -> {
            peer.greet("250-localhost\r\n250 8BITMIME");
            final String content = acceptMessage(peer, MAIL_FROM + " BODY=8BITMIME", "", RECIPIENT);
            assertThat(content).contains("Content-Transfer-Encoding: 8bit").contains(EIGHT_BIT_TEXT);
            finishConnection(peer);
        }); Mailer mailer = builder(server).buildMailer()) {
            mailer.sync().sendMail(email);
        }
    }

    @Test
    void unsupportedEightBitComposedBodyPointsToTheEmailBuilderEncoding() throws Exception {
        final Email email = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).emailBuilder().startingBlank()
                .from("sender@example.test").withRecipients(new Recipient(null, RECIPIENT, Message.RecipientType.TO, null))
                .withPlainText(EIGHT_BIT_TEXT).withPlainTextContentTransferEncoding(ContentTransferEncoding.BIT8).buildEmail();
        try (PeerServer server = new PeerServer(1, UTF_8, peer -> {
            peer.greet("250 localhost");
            finishConnection(peer);
        }); Mailer mailer = builder(server).buildMailer()) {
            final Throwable failure = catchThrowable(() -> mailer.sync().sendMail(email));
            assertCompatibilityFailure(failure, "your SMTP server does not advertise 8BITMIME");
            assertThat(failure.getCause()).hasMessageContaining("replace ContentTransferEncoding.BIT8 with QUOTED_PRINTABLE or BASE_64")
                    .hasMessageNotContaining("exact EML");
        }
    }

    @Test
    void composedBinaryTransferEncodingIsRejectedBeforeMailFrom() throws Exception {
        final Email email = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).emailBuilder().startingBlank()
                .from("sender@example.test").withRecipients(new Recipient(null, RECIPIENT, Message.RecipientType.TO, null))
                .withSubject("Binary transport is unavailable").withPlainText("ASCII bytes with an unsafe transfer declaration")
                .withPlainTextContentTransferEncoding(ContentTransferEncoding.BINARY).buildEmail();
        try (PeerServer server = new PeerServer(1, UTF_8, peer -> {
            peer.greet("250-localhost\r\n250-8BITMIME\r\n250 BINARYMIME");
            finishConnection(peer);
        }); Mailer mailer = builder(server).buildMailer()) {
            final Throwable failure = catchThrowable(() -> mailer.sync().sendMail(email));
            assertCompatibilityFailure(failure, "body cannot be sent in its current format");
            assertThat(failure.getCause()).hasMessageContaining("uses unsupported binary body encoding")
                    .hasMessageContaining("ContentTransferEncoding.QUOTED_PRINTABLE or BASE_64");
        }
    }

    @ParameterizedTest
    @CsvSource({"false,nul", "true,nul", "false,long-ascii", "true,long-ascii", "false,long-utf8", "true,long-utf8"})
    void unsafeComposedEightBitBodiesAreRejectedBeforeMailFrom(final boolean multipart, final String invalidContent) throws Exception {
        final String body = "nul".equals(invalidContent) ? "before\0after"
                : "long-utf8".equals(invalidContent) ? "é".repeat(500) : "x".repeat(999);
        final Email email = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).emailBuilder().startingBlank()
                .from("sender@example.test").withRecipients(new Recipient(null, RECIPIENT, Message.RecipientType.TO, null)).withPlainText(body)
                .withPlainTextContentTransferEncoding(ContentTransferEncoding.BIT8)
                .withHTMLText(multipart ? "<p>Safe alternative</p>" : null).buildEmail();
        try (PeerServer server = new PeerServer(1, UTF_8, peer -> {
            peer.greet("250-localhost\r\n250 8BITMIME");
            finishConnection(peer);
        }); Mailer mailer = builder(server).buildMailer()) {
            final Throwable failure = catchThrowable(() -> mailer.sync().sendMail(email));
            assertCompatibilityFailure(failure, "nul".equals(invalidContent) ? "unencoded zero byte" : "limit of 998 bytes");
            assertThat(failure.getCause()).hasMessageContaining("ContentTransferEncoding.QUOTED_PRINTABLE or BASE_64")
                    .hasMessageNotContaining("instead of BINARY");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n", "\r"})
    void composedEightBitBodiesAcceptTheByteLimitAndNormalizeLineBreaks(final String lineBreak) throws Exception {
        final String boundaryLine = "é".repeat(499);
        final Email email = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).emailBuilder().startingBlank()
                .from("sender@example.test").withRecipients(new Recipient(null, RECIPIENT, Message.RecipientType.TO, null))
                .withPlainText(boundaryLine + lineBreak + boundaryLine)
                .withPlainTextContentTransferEncoding(ContentTransferEncoding.BIT8).buildEmail();
        try (PeerServer server = new PeerServer(1, UTF_8, peer -> {
            peer.greet("250-localhost\r\n250 8BITMIME");
            final String content = acceptMessage(peer, MAIL_FROM + " BODY=8BITMIME", "", RECIPIENT);
            assertThat(content).contains(boundaryLine + "\r\n" + boundaryLine);
            finishConnection(peer);
        }); Mailer mailer = builder(server).buildMailer()) {
            mailer.sync().sendMail(email);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void exactBinaryDeclarationIsRejectedEvenForAsciiContent(final boolean multipart) throws Exception {
        final String binaryPart = "Content-Type: text/plain\r\nContent-Transfer-Encoding: binary\r\n\r\nASCII body\r\n";
        final byte[] exactEml = ("From: sender@example.test\r\nTo: receiver@example.test\r\n"
                + (multipart ? "Content-Type: multipart/mixed; boundary=parts\r\n\r\n--parts\r\n" + binaryPart + "--parts--\r\n"
                : binaryPart)).getBytes(US_ASCII);
        try (PeerServer server = new PeerServer(1, UTF_8, peer -> {
            peer.greet("250-localhost\r\n250-8BITMIME\r\n250 BINARYMIME");
            finishConnection(peer);
        }); Mailer mailer = builder(server).buildMailer()) {
            assertCompatibilityFailure(catchThrowable(() -> mailer.sync().sendMail(exactEmail(exactEml))), "unsupported binary body encoding");
        }
    }

    @Test
    void pooledUtf8RejectionDoesNotPreventTheNextAsciiSend() throws Exception {
        try (PeerServer server = new PeerServer(1, UTF_8, peer -> {
            peer.greet("250 localhost");
            acceptMessage(peer, MAIL_FROM, "", RECIPIENT);
            finishConnection(peer);
        }); Mailer mailer = builder(server).buildMailer()) {
            assertCompatibilityFailure(catchThrowable(() -> mailer.sync().sendMail(
                    composedEmail("sender@example.test", "müller@example.test", "ASCII subject", "ASCII body"))),
                    "does not advertise SMTPUTF8");
            mailer.async().sendMail(composedEmail("sender@example.test", RECIPIENT, "ASCII subject", "ASCII body")).getCompletion().join();
        }
    }

    @Test
    void concurrentDefaultUtf8SendsKeepEnvelopeAndHeaderAddressesAligned() throws Exception {
        final CountDownLatch connectionsReady = new CountDownLatch(2);
        try (PeerServer server = new PeerServer(2, UTF_8, peer -> {
            peer.greet("250-localhost\r\n250 SMTPUTF8");
            connectionsReady.countDown();
            assertThat(connectionsReady.await(5, SECONDS)).isTrue();
            final String senderCommand = peer.readLine();
            assertThat(senderCommand).isIn(MAIL_FROM, MAIL_FROM + " SMTPUTF8");
            peer.reply("250 sender accepted");
            final String recipientCommand = peer.readLine();
            assertThat(recipientCommand).isIn("RCPT TO:<" + RECIPIENT + ">", "RCPT TO:<müller@example.test>");
            final String recipient = recipientCommand.substring("RCPT TO:<".length(), recipientCommand.length() - 1);
            assertThat(senderCommand).isEqualTo(MAIL_FROM + (recipient.equals(RECIPIENT) ? "" : " SMTPUTF8"));
            peer.reply("250 recipient accepted");
            peer.expect("DATA");
            peer.reply("354 send synthetic message");
            assertThat(SmtpDsnCharacterizationTest.readMessage(peer)).contains("To: " + recipient);
            peer.reply("250 accepted");
            finishConnection(peer);
        }); Mailer mailer = builder(server).withConnectionPoolMaxSize(2).withThreadPoolSize(2).buildMailer()) {
            final var internationalized = mailer.async().sendMail(composedEmail("sender@example.test", "müller@example.test", "Subject", "Body"));
            final var ascii = mailer.async().sendMail(composedEmail("sender@example.test", RECIPIENT, "Subject", "Body"));
            internationalized.getCompletion().get(10, SECONDS);
            ascii.getCompletion().get(10, SECONDS);
        }
    }

    @Test
    void enabledEightBitMimeDoesNotRewriteExactEml() throws Exception {
        final byte[] exactEml = ("From: author@example.test\r\nTo: visible@example.test\r\n"
                + "Message-ID: <exact-eight-bit@example.test>\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: quoted-printable\r\n\r\n"
                + "Synthetic caf=C3=A9 content.\r\n").getBytes(US_ASCII);
        try (PeerServer server = new PeerServer(1, UTF_8, peer -> {
            peer.greet("250-localhost\r\n250 8BITMIME");
            final String content = acceptMessage(peer, MAIL_FROM, "", RECIPIENT);
            assertThat(content.getBytes(US_ASCII)).containsExactly(exactEml);
            finishConnection(peer);
        }); Mailer mailer = builder(server).withProperty("mail.smtp.allow8bitmime", true).buildMailer()) {
            mailer.sync().sendMail(exactEmail(exactEml));
        }
    }

    @Test
    void exactEmlWithRawUtf8HeaderIsRejectedWithoutSmtpUtf8Support() throws Exception {
        final byte[] exactEml = ("From: author@example.test\r\nTo: visible@example.test\r\nSubject: Café rendez-vous\r\n"
                + "Content-Type: text/plain; charset=us-ascii\r\n\r\nASCII body.\r\n").getBytes(UTF_8);
        try (PeerServer server = new PeerServer(1, UTF_8, peer -> {
            peer.greet("250 localhost");
            finishConnection(peer);
        }); Mailer mailer = builder(server).buildMailer()) {
            assertCompatibilityFailure(catchThrowable(() -> mailer.sync().sendMail(exactEmail(exactEml))),
                    "does not advertise SMTPUTF8");
        }
    }

    @Test
    void exactEmlWithRawUtf8HeaderUsesSmtpUtf8WithoutChangingItsBytes() throws Exception {
        final byte[] exactEml = ("From: author@example.test\r\nTo: visible@example.test\r\nSubject: Café rendez-vous\r\n"
                + "Content-Type: text/plain; charset=us-ascii\r\n\r\nASCII body.\r\n").getBytes(UTF_8);
        try (PeerServer server = new PeerServer(1, UTF_8, peer -> {
            peer.greet("250-localhost\r\n250 SMTPUTF8");
            final String content = acceptMessage(peer, MAIL_FROM + " SMTPUTF8", "", RECIPIENT);
            assertThat(content.getBytes(UTF_8)).containsExactly(exactEml);
            finishConnection(peer);
        }); Mailer mailer = builder(server).buildMailer()) {
            mailer.sync().sendMail(exactEmail(exactEml));
        }
    }

    @Test
    void exactEmlRejectsNonAsciiHeaderBytesThatAreNotUtf8() throws Exception {
        final byte[] exactEml = ("From: author@example.test\r\nTo: visible@example.test\r\nSubject: Café rendez-vous\r\n"
                + "Content-Type: text/plain; charset=us-ascii\r\n\r\nASCII body.\r\n").getBytes(ISO_8859_1);
        try (PeerServer server = new PeerServer(1, UTF_8, peer -> {
            peer.greet("250-localhost\r\n250 SMTPUTF8");
            finishConnection(peer);
        }); Mailer mailer = builder(server).buildMailer()) {
            assertCompatibilityFailure(catchThrowable(() -> mailer.sync().sendMail(exactEmail(exactEml))), "not valid UTF-8");
        }
    }

    @Test
    void exactEmlWithEightBitBodyIsRejectedWithoutEightBitMimeSupport() throws Exception {
        final byte[] exactEml = ("From: author@example.test\r\nTo: visible@example.test\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: 8bit\r\n\r\n"
                + EIGHT_BIT_TEXT + "\r\n").getBytes(UTF_8);
        try (PeerServer server = new PeerServer(1, UTF_8, peer -> {
            peer.greet("250 localhost");
            finishConnection(peer);
        }); Mailer mailer = builder(server).buildMailer()) {
            final Throwable failure = catchThrowable(() -> mailer.sync().sendMail(exactEmail(exactEml)));
            assertCompatibilityFailure(failure, "your SMTP server does not advertise 8BITMIME");
            assertThat(failure.getCause()).hasMessageContaining("regenerate the source email")
                    .hasMessageNotContaining("replace ContentTransferEncoding.BIT8");
        }
    }

    @Test
    void exactEmlWithEightBitBodyDeclaresEightBitMimeWithoutChangingItsBytes() throws Exception {
        final byte[] exactEml = ("From: author@example.test\r\nTo: visible@example.test\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: 8bit\r\n\r\n"
                + EIGHT_BIT_TEXT + "\r\n").getBytes(UTF_8);
        try (PeerServer server = new PeerServer(1, UTF_8, peer -> {
            peer.greet("250-localhost\r\n250 8BITMIME");
            final String content = acceptMessage(peer, MAIL_FROM + " BODY=8BITMIME", "", RECIPIENT);
            assertThat(content.getBytes(UTF_8)).containsExactly(exactEml);
            finishConnection(peer);
        }); Mailer mailer = builder(server).buildMailer()) {
            mailer.sync().sendMail(exactEmail(exactEml));
        }
    }

    @Test
    void exactEmlWithANullBodyByteIsRejectedEvenWhenTheServerAdvertisesEightBitMime() throws Exception {
        final byte[] prefix = ("From: author@example.test\r\nTo: visible@example.test\r\n"
                + "Content-Type: application/octet-stream\r\nContent-Transfer-Encoding: 8bit\r\n\r\n").getBytes(US_ASCII);
        final byte[] exactEml = Arrays.copyOf(prefix, prefix.length + 3);
        exactEml[prefix.length] = 0;
        exactEml[prefix.length + 1] = '\r';
        exactEml[prefix.length + 2] = '\n';
        try (PeerServer server = new PeerServer(1, UTF_8, peer -> {
            peer.greet("250-localhost\r\n250 8BITMIME");
            finishConnection(peer);
        }); Mailer mailer = builder(server).buildMailer()) {
            final Throwable failure = catchThrowable(() -> mailer.sync().sendMail(exactEmail(exactEml)));
            assertCompatibilityFailure(failure, "body cannot be sent in its current format");
            assertThat(failure.getCause()).hasMessageContaining("contains an unencoded zero byte")
                    .hasMessageContaining("Regenerate the source email");
        }
    }

    @Test
    void exactEmlWithAnOverlongBodyLineIsRejectedEvenWhenTheServerAdvertisesEightBitMime() throws Exception {
        final byte[] exactEml = ("From: author@example.test\r\nTo: visible@example.test\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: 8bit\r\n\r\n"
                + "é" + "x".repeat(998) + "\r\n").getBytes(UTF_8);
        try (PeerServer server = new PeerServer(1, UTF_8, peer -> {
            peer.greet("250-localhost\r\n250 8BITMIME");
            finishConnection(peer);
        }); Mailer mailer = builder(server).buildMailer()) {
            final Throwable failure = catchThrowable(() -> mailer.sync().sendMail(exactEmail(exactEml)));
            assertCompatibilityFailure(failure, "body cannot be sent in its current format");
            assertThat(failure.getCause()).hasMessageContaining("contains a body line longer than the supported limit of 998 bytes");
        }
    }

    @Test
    void conflictingRawBodyDeclarationExplainsWhichMailerPropertyToRemove() throws Exception {
        final byte[] exactEml = ("From: author@example.test\r\nTo: visible@example.test\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: 8bit\r\n\r\n"
                + EIGHT_BIT_TEXT + "\r\n").getBytes(UTF_8);
        try (PeerServer server = new PeerServer(1, UTF_8, peer -> {
            peer.greet("250-localhost\r\n250 8BITMIME");
            finishConnection(peer);
        }); Mailer mailer = builder(server).withProperty("mail.smtp.mailextension", "XTRACE=keep BODY=7BIT").buildMailer()) {
            final Throwable failure = catchThrowable(() -> mailer.sync().sendMail(exactEmail(exactEml)));
            assertCompatibilityFailure(failure, "a custom SMTP setting forces an incompatible body format");
            assertThat(failure.getCause()).hasMessageContaining("the Mailer property 'mail.smtp.mailextension'")
                    .hasMessageContaining("Remove that setting if you do not need custom SMTP options")
                    .hasMessageNotContaining("BODY=");
        }
    }

    @Test
    void negotiationUsesOnlyCapabilitiesAdvertisedAfterStartTls() throws Exception {
        try (PeerServer server = new PeerServer(1, UTF_8, peer -> {
            peer.greet("250-localhost\r\n250-SMTPUTF8\r\n250-8BITMIME\r\n250 STARTTLS");
            peer.expect("STARTTLS");
            peer.reply("220 begin TLS");
            peer.upgradeToTls();
            peer.expect("EHLO probe.example.test");
            peer.reply("250 localhost");
            final String content = acceptMessage(peer, MAIL_FROM, "", RECIPIENT);
            assertThat(content).contains("Content-Transfer-Encoding: quoted-printable")
                    .doesNotContain("Content-Transfer-Encoding: 8bit");
            finishConnection(peer);
        }); Mailer mailer = builder(server).withTransportStrategy(TransportStrategy.SMTP_TLS)
                .withProperties(SmtpCapabilityProbeCharacterizationTest.session(true).getProperties())
                .withProperty("mail.mime.allowutf8", true).withProperty("mail.smtp.allow8bitmime", true).buildMailer()) {
            mailer.sync().sendMail(eightBitCandidate());
        }
    }

    @Test
    void capabilitiesFromAClosedPooledConnectionDoNotAffectItsReplacement() throws Exception {
        final AtomicInteger connections = new AtomicInteger();
        try (PeerServer server = new PeerServer(2, UTF_8, peer -> {
            if (connections.getAndIncrement() == 0) {
                peer.greet("250-localhost\r\n250-SMTPUTF8\r\n250 8BITMIME");
                final String content = acceptMessage(peer, MAIL_FROM + " BODY=8BITMIME", "", RECIPIENT);
                assertThat(content).contains("Content-Transfer-Encoding: 8bit");
                return; // Closing the fixture socket makes the Mailer replace this pooled transport before the next send.
            }
            peer.greet("250 localhost");
            final String content = acceptMessage(peer, MAIL_FROM, "", RECIPIENT);
            assertThat(content).contains("Content-Transfer-Encoding: quoted-printable")
                    .doesNotContain("Content-Transfer-Encoding: 8bit");
            finishConnection(peer);
        }); Mailer mailer = builder(server).withProperty("mail.mime.allowutf8", true)
                .withProperty("mail.smtp.allow8bitmime", true).buildMailer()) {
            mailer.sync().sendMail(eightBitCandidate());
            mailer.sync().sendMail(eightBitCandidate());
        }
        assertThat(connections).hasValue(2);
    }

    static void assertCompatibilityFailure(final Throwable failure, final String messageFragment) {
        assertThat(failure).isInstanceOf(MailSubmissionException.class)
                .hasCauseInstanceOf(MailTransportCompatibilityException.class);
        assertThat(failure.getCause()).hasMessageContaining(messageFragment)
                .hasMessageContaining("No message was submitted");
    }

    static MailerRegularBuilder<?> builder(final PeerServer server) {
        return SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder().withSMTPServer("localhost", server.port())
                .withSmtpClientHostname("probe.example.test").withSessionTimeout(5000)
                .withConnectionPoolCoreSize(0).withConnectionPoolMaxSize(1).withConnectionPoolClaimTimeoutMillis(5000);
    }

    private static Email eightBitCandidate() {
        return SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).emailBuilder().startingBlank().from("sender@example.test")
                .withRecipients(new Recipient(null, RECIPIENT, Message.RecipientType.TO, null)).withSubject("8BITMIME characterization")
                .withPlainText(EIGHT_BIT_TEXT).withPlainTextContentTransferEncoding(ContentTransferEncoding.QUOTED_PRINTABLE).buildEmail();
    }

    static Email exactEmail(final byte[] exactEml) {
        return SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).emailBuilder().startingFromExactEml(exactEml)
                .withEnvelopeSender("sender@example.test").withEnvelopeRecipients(RECIPIENT).buildEmail();
    }

    static Email composedEmail(final String sender, final String recipient, final String subject, final String body) {
        return SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).emailBuilder().startingBlank().from(sender)
                .withRecipients(RecipientBuilder.to(null, recipient)).withSubject(subject).withPlainText(body).buildEmail();
    }
}
