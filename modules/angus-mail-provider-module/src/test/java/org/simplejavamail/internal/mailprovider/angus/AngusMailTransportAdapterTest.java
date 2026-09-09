package org.simplejavamail.internal.mailprovider.angus;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.URLName;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.angus.mail.smtp.SMTPMessage;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.smtp.SMTPTransport;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;
import org.simplejavamail.api.mailer.MailSubmissionStatus;
import org.simplejavamail.api.mailer.spi.ContentRequirement;
import org.simplejavamail.api.mailer.spi.DeliveryEnvelope;
import org.simplejavamail.api.mailer.spi.MailTransportResult;
import org.simplejavamail.api.mailer.spi.PreparedMail;
import org.simplejavamail.internal.util.FinalizedMimeMessage;

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
                new DeliveryEnvelope("bounce@example.com", dsn), ContentRequirement.PRESERVE_PROTECTED_CONTENT);

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
                new DeliveryEnvelope(null, null), ContentRequirement.PRESERVE_PROTECTED_CONTENT);
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
    void allBytesFacadeRetainsHeadersNormallyIgnoredByAngus() throws Exception {
        final byte[] exactEml = ("From: sender@example.com\r\n"
                + "To: visible@example.com\r\n"
                + "Bcc: hidden@example.com\r\n"
                + "Content-Length: 4\r\n"
                + "\r\nbody\r\n").getBytes(StandardCharsets.US_ASCII);
        final FinalizedMimeMessage message = FinalizedMimeMessage.fromExactMessageBytes(
                Session.getInstance(new Properties()), exactEml);
        final PreparedMail preparedMail = new PreparedMail(message, recipients(),
                new DeliveryEnvelope(null, null), ContentRequirement.PRESERVE_ALL_BYTES);
        final AngusMailTransportAdapter.AngusSmtpMessage facade =
                new AngusMailTransportAdapter.AngusSmtpMessage(preparedMail);
        final ByteArrayOutputStream output = new ByteArrayOutputStream();

        facade.writeTo(output, new String[] {"Bcc", "Content-Length"});

        assertThat(output.toByteArray()).containsExactly(exactEml);
    }

    @Test
    void failedSendDoesNotReuseThePreviousSubmissionResponse() throws Exception {
        final MessagingException failure = new MessagingException("connection dropped after DATA");
        final StaleResponseTransport transport = new StaleResponseTransport(failure);
        final PreparedMail preparedMail = new PreparedMail(message("body"), recipients(),
                new DeliveryEnvelope(null, null), ContentRequirement.NORMAL);

        final MailTransportResult result = new AngusMailTransportAdapter().sendMessage(transport, preparedMail);

        assertThat(result.getStatus()).isEqualTo(MailSubmissionStatus.UNKNOWN);
        assertThat(result.getSmtpResponse()).isEmpty();
        assertThat(result.getFailure()).containsSame(failure);
    }

    @Test
    void normalReturnWithoutFreshProviderFactsDoesNotReuseThePreviousResponse() throws Exception {
        final MailTransportResult result = new AngusMailTransportAdapter().sendMessage(new StaleResponseTransport(null), preparedMail());
        assertThat(result.getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED);
        assertThat(result.getSmtpResponse()).isEmpty();
        assertThat(result.getRecipientResults()).allSatisfy(recipient -> assertThat(recipient.getRcptResponse()).isEmpty());
    }

    @Test
    void emptyProviderResponseIsNotReportedAsAnSmtpRejection() throws Exception {
        final MessagingException failure = new MessagingException("Exception reading response");
        final ChangedResponseTransport transport = new ChangedResponseTransport(failure, 0, "");

        final MailTransportResult result = new AngusMailTransportAdapter().sendMessage(transport, preparedMail());

        assertThat(result.getStatus()).isEqualTo(MailSubmissionStatus.UNKNOWN);
        assertThat(result.getSmtpResponse()).isEmpty();
        assertThat(result.getFailure()).containsSame(failure);
    }

    @Test
    void missingFinalDataReplyClearsAmbiguousRecipientsButRetainsKnownInvalidRecipients() throws Exception {
        final Address ambiguousRecipient = new InternetAddress("ambiguous@example.com");
        final Address invalidRecipient = new InternetAddress("invalid@example.com");
        final SMTPSendFailedException failure = new SMTPSendFailedException(
                ".", -1, "[EOF]", null, null, new Address[]{ambiguousRecipient}, new Address[]{invalidRecipient});
        final ChangedResponseTransport transport = new ChangedResponseTransport(failure, -1, "[EOF]");

        final MailTransportResult result = new AngusMailTransportAdapter().sendMessage(transport, preparedMail(ambiguousRecipient, invalidRecipient));

        assertThat(result.getStatus()).isEqualTo(MailSubmissionStatus.UNKNOWN);
        assertThat(result.getSmtpResponse()).isEmpty();
        assertThat(result.getAcceptedRecipients()).isEmpty();
        assertThat(result.getValidUnsentRecipients()).isEmpty();
        assertThat(result.getInvalidRecipients()).containsExactly(invalidRecipient);
        assertThat(result.getFailure()).containsSame(failure);
    }

    @Test
    void explicitFinalDataRejectionRemainsRejected() throws Exception {
        final Address unsentRecipient = new InternetAddress("unsent@example.com");
        final SMTPSendFailedException failure = new SMTPSendFailedException(
                ".", 550, "550 message rejected", null, null, new Address[]{unsentRecipient}, null);
        final ChangedResponseTransport transport = new ChangedResponseTransport(failure, 550, "550 message rejected");

        final MailTransportResult result = new AngusMailTransportAdapter().sendMessage(transport, preparedMail(unsentRecipient));

        assertThat(result.getStatus()).isEqualTo(MailSubmissionStatus.REJECTED);
        assertThat(result.getSmtpResponse()).hasValueSatisfying(response -> {
            assertThat(response.getReturnCode()).isEqualTo(550);
            assertThat(response.getResponse()).isEqualTo("550 message rejected");
        });
        assertThat(result.getValidUnsentRecipients()).containsExactly(unsentRecipient);
        assertThat(result.getFailure()).containsSame(failure);
    }

    @Test
    void commandSpecificEofBeforeDataRemainsRejected() throws Exception {
        final Address unsentRecipient = recipients()[0];
        final SMTPSendFailedException failure = new SMTPSendFailedException(
                "MAIL FROM:<sender@example.com>", -1, "[EOF]", null, null, new Address[]{unsentRecipient}, null);
        final ChangedResponseTransport transport = new ChangedResponseTransport(failure, -1, "[EOF]");

        final MailTransportResult result = new AngusMailTransportAdapter().sendMessage(transport, preparedMail());

        assertThat(result.getStatus()).isEqualTo(MailSubmissionStatus.REJECTED);
        assertThat(result.getSmtpResponse()).isEmpty();
        assertThat(result.getValidUnsentRecipients()).containsExactly(unsentRecipient);
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

    private static PreparedMail preparedMail(final Address... envelope) throws Exception {
        return new PreparedMail(message("body"), envelope.length == 0 ? recipients() : envelope,
                new DeliveryEnvelope(null, null), ContentRequirement.NORMAL);
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
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static final class ChangedResponseTransport extends SMTPTransport {
        private final MessagingException failure;
        private final int failureReturnCode;
        private final String failureResponse;
        private int returnCode = 250;
        private String serverResponse = "250 previous message accepted";

        private ChangedResponseTransport(final MessagingException failure,
                                         final int failureReturnCode,
                                         final String failureResponse) {
            super(Session.getInstance(new Properties()), new URLName("smtp", null, -1, null, null, null));
            this.failure = failure;
            this.failureReturnCode = failureReturnCode;
            this.failureResponse = failureResponse;
        }

        @Override
        public synchronized int getLastReturnCode() {
            return returnCode;
        }

        @Override
        public synchronized String getLastServerResponse() {
            return serverResponse;
        }

        @Override
        public synchronized void sendMessage(final Message message, final Address[] addresses) throws MessagingException {
            returnCode = failureReturnCode;
            serverResponse = failureResponse;
            throw failure;
        }
    }
}
