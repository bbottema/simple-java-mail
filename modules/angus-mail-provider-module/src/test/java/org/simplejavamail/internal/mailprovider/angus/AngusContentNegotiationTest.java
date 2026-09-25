package org.simplejavamail.internal.mailprovider.angus;

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.angus.mail.smtp.SMTPTransport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.mailer.spi.ContentRequirement;
import org.simplejavamail.api.mailer.spi.DeliveryEnvelope;
import org.simplejavamail.api.mailer.spi.MailTransportCompatibilityException;
import org.simplejavamail.api.mailer.spi.PreparedMail;
import org.simplejavamail.internal.util.FinalizedMimeMessage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AngusContentNegotiationTest {

    private static final Address[] RECIPIENTS = recipients();

    @Test
    void legacyPermissionPreservesProtectedBytesAndOriginalConstructorsStayStrict() throws Exception {
        final byte[] signedBytes = ("From: sender@example.test\r\nTo: receiver@example.test\r\nSubject: Café\r\n"
                + "DKIM-Signature: v=1; b=unchanged-signature\r\nContent-Transfer-Encoding: 8bit\r\n\r\nCafé\r\n")
                .getBytes(StandardCharsets.UTF_8);
        final PreparedMail strict = protectedMail(signedBytes);
        final PreparedMail legacy = new PreparedMail(strict.getMimeMessage(), RECIPIENTS, strict.getDeliveryEnvelope(),
                strict.getContentRequirement(), true);
        assertThat(strict.isLegacySmtpContentSupportEnabled()).isFalse();
        assertThat(new PreparedMail(strict.getMimeMessage(), RECIPIENTS, strict.getDeliveryEnvelope(), true)
                .isLegacySmtpContentSupportEnabled()).isFalse();
        assertThatThrownBy(() -> AngusContentNegotiation.resolveMailParameters(transportSupporting(), strict,
                "smtp", null, "test", RECIPIENTS)).isInstanceOf(MailTransportCompatibilityException.class);
        final AngusMailFromParameters parameters = AngusContentNegotiation.resolveMailParameters(transportSupporting(), legacy,
                "smtp", null, "test", RECIPIENTS);
        assertThat(parameters.isSmtpUtf8()).isFalse();
        assertThat(parameters.getMailExtension()).isNull();
        assertThat(((FinalizedMimeMessage) legacy.getMimeMessage()).getSerializedBytes()).containsExactly(signedBytes);
    }

    @Test
    void malformedMailboxIsRejectedEvenAfterAnEarlierInternationalizedAddress() throws Exception {
        final PreparedMail original = protectedMail("From: sender@example.test\r\n\r\nBody\r\n".getBytes(StandardCharsets.US_ASCII));
        final Address[] recipients = {new InternetAddress("müller@example.test"), new InternetAddress("bad\uD800@example.test")};
        final PreparedMail legacy = new PreparedMail(original.getMimeMessage(), recipients, original.getDeliveryEnvelope(),
                original.getContentRequirement(), true);
        assertThatThrownBy(() -> AngusContentNegotiation.resolveMailParameters(transportSupporting(), legacy, "smtp", null, "test", recipients))
                .isInstanceOf(MailTransportCompatibilityException.class).hasMessageContaining("malformed Unicode", "No message was submitted");
    }

    @Test
    void malformedHeaderIsRejectedEvenAfterAnEarlierInternationalizedHeader() throws Exception {
        final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setText("ASCII body");
        message.setHeader("Subject", "Café");
        message.setHeader("X-Malformed", "bad\uD800");
        final PreparedMail preparedMail = new PreparedMail(message, RECIPIENTS, new DeliveryEnvelope(null, null), ContentRequirement.NORMAL);

        assertThatThrownBy(() -> AngusContentNegotiation.resolveMailParameters(transportSupporting("SMTPUTF8", "8BITMIME"), preparedMail,
                "smtp", null, "test", RECIPIENTS))
                .isInstanceOf(MailTransportCompatibilityException.class).hasMessageContaining("not valid UTF-8", "No message was submitted");
    }

    @Test
    void bodyReadFailureKeepsItsOriginalCause() throws Exception {
        final IOException readFailure = new IOException("Synthetic body-read failure");
        final MimeMessage message = new MimeMessage(Session.getInstance(new Properties())) {
            @Override
            public InputStream getInputStream() throws IOException {
                throw readFailure;
            }
        };
        message.setText("ASCII body");
        final PreparedMail preparedMail = new PreparedMail(message, RECIPIENTS, new DeliveryEnvelope(null, null), ContentRequirement.NORMAL);

        assertThatThrownBy(() -> AngusContentNegotiation.resolveMailParameters(transportSupporting(), preparedMail,
                "smtp", null, "test", RECIPIENTS))
                .isInstanceOf(MessagingException.class).hasMessageContaining("Could not read this email's content", "No message was submitted")
                .cause().isSameAs(readFailure);
    }

    @Test
    void protectedUtf8HeadersAcquireSmtpUtf8WithoutChangingTheFinalizedMessage() throws Exception {
        final byte[] finalizedBytes = ("From: sender@example.test\r\nTo: receiver@example.test\r\nSubject: Café\r\n\r\n"
                + "ASCII body\r\n").getBytes(StandardCharsets.UTF_8);
        final PreparedMail preparedMail = protectedMail(finalizedBytes);

        final String extension = AngusContentNegotiation.resolveMailParameters(transportSupporting("SMTPUTF8"), preparedMail,
                "smtp", null, "the test setting", RECIPIENTS).getMailExtension();

        assertThat(extension).isEqualTo("SMTPUTF8");
        assertThat(((FinalizedMimeMessage) preparedMail.getMimeMessage()).getSerializedBytes()).containsExactly(finalizedBytes);
    }

    @Test
    void protectedNestedUtf8HeadersAlsoRequireSmtpUtf8WithoutRewritingTheContent() throws Exception {
        final byte[] finalizedBytes = ("From: sender@example.test\r\nTo: receiver@example.test\r\n"
                + "Content-Type: multipart/mixed; boundary=parts\r\n\r\n--parts\r\n"
                + "Content-Type: text/plain\r\nContent-Description: Café\r\n\r\nASCII body\r\n--parts--\r\n").getBytes(StandardCharsets.UTF_8);
        final PreparedMail strict = protectedMail(finalizedBytes);
        assertThatThrownBy(() -> AngusContentNegotiation.resolveMailParameters(transportSupporting("8BITMIME"), strict,
                "smtp", null, "test", RECIPIENTS)).isInstanceOf(MailTransportCompatibilityException.class).hasMessageContaining("SMTPUTF8");
        assertThat(AngusContentNegotiation.resolveMailParameters(transportSupporting("SMTPUTF8", "8BITMIME"), strict,
                "smtp", null, "test", RECIPIENTS).getMailExtension()).isEqualTo("SMTPUTF8 BODY=8BITMIME");

        final PreparedMail legacy = new PreparedMail(strict.getMimeMessage(), RECIPIENTS, strict.getDeliveryEnvelope(),
                strict.getContentRequirement(), true);
        assertThat(AngusContentNegotiation.resolveMailParameters(transportSupporting(), legacy,
                "smtp", null, "test", RECIPIENTS).getMailExtension()).isNull();
        assertThat(((FinalizedMimeMessage) strict.getMimeMessage()).getSerializedBytes()).containsExactly(finalizedBytes);
    }

    @Test
    void unencodedAttachedMessageRequiresBothExtensionsWithoutChangingTheFinalizedMessage() throws Exception {
        final byte[] finalizedBytes = ("From: sender@example.test\r\nTo: receiver@example.test\r\n"
                + "Content-Type: message/rfc822\r\n\r\nFrom: attached@example.test\r\nSubject: Café\r\n\r\nASCII body\r\n")
                .getBytes(StandardCharsets.UTF_8);
        final PreparedMail preparedMail = protectedMail(finalizedBytes);

        assertThatThrownBy(() -> AngusContentNegotiation.resolveMailParameters(transportSupporting("8BITMIME"), preparedMail,
                "smtp", null, "test", RECIPIENTS)).isInstanceOf(MailTransportCompatibilityException.class).hasMessageContaining("SMTPUTF8");
        assertThat(AngusContentNegotiation.resolveMailParameters(transportSupporting("SMTPUTF8", "8BITMIME"), preparedMail,
                "smtp", null, "test", RECIPIENTS).getMailExtension()).isEqualTo("SMTPUTF8 BODY=8BITMIME");
        assertThat(((FinalizedMimeMessage) preparedMail.getMimeMessage()).getSerializedBytes()).containsExactly(finalizedBytes);
    }

    @Test
    void protectedBccHeadersOmittedByTheTransportDoNotRequireSmtpUtf8() throws Exception {
        final PreparedMail preparedMail = protectedMail(("From: sender@example.test\r\nTo: receiver@example.test\r\n"
                + "Bcc: müller@example.test\r\n\r\nASCII body\r\n").getBytes(StandardCharsets.UTF_8));
        assertThat(AngusContentNegotiation.resolveMailParameters(transportSupporting(), preparedMail,
                "smtp", null, "test", RECIPIENTS).getMailExtension()).isNull();
    }

    @Test
    void protectedEightBitBodiesAcquireTheBodyDeclarationWithoutChangingTheFinalizedMessage() throws Exception {
        final byte[] finalizedBytes = ("From: sender@example.test\r\nTo: receiver@example.test\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: 8bit\r\n\r\n"
                + "Café\r\n").getBytes(StandardCharsets.UTF_8);
        final PreparedMail preparedMail = protectedMail(finalizedBytes);

        final String extension = AngusContentNegotiation.resolveMailParameters(transportSupporting("8BITMIME"), preparedMail,
                "smtp", "XTRACE=keep", "the test setting", RECIPIENTS).getMailExtension();

        assertThat(extension).isEqualTo("XTRACE=keep BODY=8BITMIME");
        assertThat(((FinalizedMimeMessage) preparedMail.getMimeMessage()).getSerializedBytes()).containsExactly(finalizedBytes);
    }

    @Test
    void protectedAsciiContentCannotUseABinaryTransferDeclaration() throws Exception {
        final byte[] finalizedBytes = ("From: sender@example.test\r\nTo: receiver@example.test\r\n"
                + "Content-Type: text/plain\r\nContent-Transfer-Encoding: binary\r\n\r\nASCII body\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        final PreparedMail preparedMail = protectedMail(finalizedBytes);

        assertThatThrownBy(() -> AngusContentNegotiation.resolveMailParameters(transportSupporting("8BITMIME", "BINARYMIME"), preparedMail,
                "smtp", null, "the test setting", RECIPIENTS))
                .isInstanceOf(MailTransportCompatibilityException.class).hasMessageContaining("unsupported binary body encoding");
        assertThat(((FinalizedMimeMessage) preparedMail.getMimeMessage()).getSerializedBytes()).containsExactly(finalizedBytes);
    }

    @ParameterizedTest
    @ValueSource(strings = {"base64", "quoted-printable"})
    void encodedAttachedMessageDoesNotImposeItsInternalBinaryDeclarationOnSmtp(final String encoding) throws Exception {
        final String nestedMessage = "From: attached@example.test\r\nContent-Transfer-Encoding: binary\r\n\r\nBinary\0body\r\n";
        final String encodedMessage = encoding.equals("base64")
                ? Base64.getMimeEncoder().encodeToString(nestedMessage.getBytes(StandardCharsets.US_ASCII)) : nestedMessage.replace("\0", "=00");
        final byte[] finalizedBytes = ("From: sender@example.test\r\nTo: receiver@example.test\r\n"
                + "Content-Type: multipart/mixed; boundary=parts\r\n\r\n--parts\r\n"
                + "Content-Type: message/rfc822\r\nContent-Transfer-Encoding: " + encoding + "\r\n\r\n"
                + encodedMessage + "\r\n--parts--\r\n").getBytes(StandardCharsets.US_ASCII);
        final PreparedMail preparedMail = protectedMail(finalizedBytes);

        assertThat(AngusContentNegotiation.resolveMailParameters(transportSupporting(), preparedMail,
                "smtp", null, "the test setting", RECIPIENTS).getMailExtension()).isNull();
        assertThat(((FinalizedMimeMessage) preparedMail.getMimeMessage()).getSerializedBytes()).containsExactly(finalizedBytes);
    }

    @ParameterizedTest
    @ValueSource(strings = {"BODY=7BIT", "BODY=8BITMIME BODY=7BIT"})
    void conflictingCustomMessageOptionsIdentifyTheIntegrationSettingWithoutProtocolInstructions(final String extension) throws Exception {
        final byte[] finalizedBytes = ("From: sender@example.test\r\nTo: receiver@example.test\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: 8bit\r\n\r\n"
                + "Café\r\n").getBytes(StandardCharsets.UTF_8);
        final PreparedMail preparedMail = protectedMail(finalizedBytes);

        assertThatThrownBy(() -> AngusContentNegotiation.resolveMailParameters(transportSupporting("8BITMIME"), preparedMail,
                "smtp", extension, "SMTPMessage.setMailExtension(...)", RECIPIENTS))
                .isInstanceOf(MailTransportCompatibilityException.class)
                .hasMessageContaining("a custom SMTP setting forces an incompatible body format")
                .hasMessageContaining("SMTPMessage.setMailExtension(...)")
                .hasMessageContaining("update the integration supplying it")
                .hasMessageNotContaining("BODY=");
        assertThat(((FinalizedMimeMessage) preparedMail.getMimeMessage()).getSerializedBytes()).containsExactly(finalizedBytes);
    }

    private static PreparedMail protectedMail(final byte[] finalizedBytes) throws Exception {
        final Session session = Session.getInstance(new Properties());
        return new PreparedMail(FinalizedMimeMessage.fromMessageBytes(session, finalizedBytes,
                FinalizedMimeMessage.ProtectionState.CONTENT_PROTECTED), RECIPIENTS,
                new DeliveryEnvelope(null, null), ContentRequirement.PRESERVE_PROTECTED_CONTENT);
    }

    private static SMTPTransport transportSupporting(final String... extensions) {
        final Set<String> supported = Set.of(extensions);
        return new SMTPTransport(Session.getInstance(new Properties()), null) {
            @Override
            public boolean supportsExtension(final String extension) {
                return supported.contains(extension);
            }
        };
    }

    private static Address[] recipients() {
        try {
            return new Address[] {new InternetAddress("receiver@example.test")};
        } catch (final AddressException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
