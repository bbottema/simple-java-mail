package org.simplejavamail.internal.mailprovider.angus;

import jakarta.mail.Address;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.angus.mail.smtp.SMTPMessage;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;
import org.simplejavamail.api.mailer.spi.DeliveryEnvelope;
import org.simplejavamail.api.mailer.spi.PreparedMail;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.DELAY;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.FAILURE;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.ReturnOption.HEADERS_ONLY;

class AngusMailTransportAdapterTest {

    @Test
    void facadeMapsEnvelopeAndDsnWithoutChangingWireBytes() throws Exception {
        final MimeMessage message = message("body");
        final byte[] expected = bytes(message);
        final DeliveryStatusNotification dsn = DeliveryStatusNotification.of(HEADERS_ONLY, FAILURE, DELAY);
        final PreparedMail preparedMail = new PreparedMail(message, recipients(),
                new DeliveryEnvelope("bounce@example.com", dsn), true);

        final AngusMailTransportAdapter.AngusSmtpMessage facade =
                new AngusMailTransportAdapter.AngusSmtpMessage(preparedMail);

        assertThat(facade.getEnvelopeFrom()).isEqualTo("bounce@example.com");
        assertThat(facade.getNotifyOptions()).isEqualTo(SMTPMessage.NOTIFY_FAILURE | SMTPMessage.NOTIFY_DELAY);
        assertThat(facade.getReturnOption()).isEqualTo(SMTPMessage.RETURN_HDRS);
        assertThat(bytes(facade)).containsExactly(expected);
    }

    @Test
    void stableFacadeSuppressesAngusEightBitTraversalAndSaveChanges() throws Exception {
        final MimeMessage message = message("body");
        final PreparedMail preparedMail = new PreparedMail(message, recipients(),
                new DeliveryEnvelope(null, null), true);
        final AngusMailTransportAdapter.AngusSmtpMessage facade =
                new AngusMailTransportAdapter.AngusSmtpMessage(preparedMail);
        final byte[] before = bytes(facade);

        facade.saveChanges();

        assertThat(facade.getAllow8bitMIME()).isFalse();
        assertThat(facade.isMimeType("text/*")).isFalse();
        assertThat(facade.isMimeType("multipart/*")).isFalse();
        assertThat(bytes(facade)).containsExactly(before);
    }

    private static MimeMessage message(final String body) throws Exception {
        final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom(new InternetAddress("sender@example.com"));
        message.setRecipients(jakarta.mail.Message.RecipientType.TO, recipients());
        message.setSubject("subject", StandardCharsets.UTF_8.name());
        message.setText(body, StandardCharsets.UTF_8.name());
        message.saveChanges();
        return message;
    }

    private static Address[] recipients() throws Exception {
        return new Address[]{new InternetAddress("receiver@example.com")};
    }

    private static byte[] bytes(final MimeMessage message) throws Exception {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        message.writeTo(output);
        return output.toByteArray();
    }
}
