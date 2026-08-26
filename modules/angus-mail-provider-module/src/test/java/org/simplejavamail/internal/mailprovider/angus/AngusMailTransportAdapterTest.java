package org.simplejavamail.internal.mailprovider.angus;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.URLName;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.angus.mail.smtp.SMTPTransport;
import org.eclipse.angus.mail.smtp.SMTPMessage;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;
import org.simplejavamail.api.mailer.MailSubmissionStatus;
import org.simplejavamail.api.mailer.spi.DeliveryEnvelope;
import org.simplejavamail.api.mailer.spi.MailTransportResult;
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

    @Test
    void failedSendDoesNotReuseThePreviousSubmissionResponse() throws Exception {
        final MessagingException failure = new MessagingException("connection dropped after DATA");
        final StaleResponseTransport transport = new StaleResponseTransport(failure);
        final PreparedMail preparedMail = new PreparedMail(message("body"), recipients(),
                new DeliveryEnvelope(null, null), false);

        final MailTransportResult result = new AngusMailTransportAdapter().sendMessage(transport, preparedMail);

        assertThat(result.getStatus()).isEqualTo(MailSubmissionStatus.UNKNOWN);
        assertThat(result.getSmtpResponse()).isEmpty();
        assertThat(result.getFailure()).containsSame(failure);
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

    private static final class StaleResponseTransport extends SMTPTransport {
        private final MessagingException failure;

        private StaleResponseTransport(final MessagingException failure) {
            super(Session.getInstance(new Properties()), new URLName("smtp", null, -1, null, null, null));
            this.failure = failure;
        }

        @Override
        public synchronized int getLastReturnCode() {
            return 250;
        }

        @Override
        public synchronized String getLastServerResponse() {
            return "250 previous message accepted";
        }

        @Override
        public synchronized void sendMessage(final Message message, final Address[] addresses) throws MessagingException {
            throw failure;
        }
    }
}
