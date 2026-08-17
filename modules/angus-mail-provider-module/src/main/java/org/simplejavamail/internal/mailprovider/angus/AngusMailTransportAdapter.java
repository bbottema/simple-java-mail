package org.simplejavamail.internal.mailprovider.angus;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.Header;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.angus.mail.smtp.SMTPMessage;
import org.eclipse.angus.mail.smtp.SMTPTransport;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;
import org.simplejavamail.api.mailer.SmtpServerResponse;
import org.simplejavamail.api.mailer.spi.DeliveryEnvelope;
import org.simplejavamail.api.mailer.spi.MailTransportAdapter;
import org.simplejavamail.api.mailer.spi.PreparedMail;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.Properties;

/** Angus Mail implementation of Simple Java Mail's provider adapter SPI. */
public final class AngusMailTransportAdapter implements MailTransportAdapter {

    @Override
    public boolean supports(@NotNull final Transport transport) {
        return transport instanceof SMTPTransport;
    }

    @Nullable
    @Override
    public SmtpServerResponse sendMessage(@NotNull final Transport transport,
                                          @NotNull final PreparedMail preparedMail)
            throws MessagingException {
        final SMTPTransport smtpTransport = (SMTPTransport) transport;
        final DeliveryEnvelope envelope = preparedMail.getDeliveryEnvelope();
        final MimeMessage message = envelope.hasProviderSpecificOptions() || preparedMail.requiresStableContent()
                ? new AngusSmtpMessage(preparedMail)
                : preparedMail.getMimeMessage();

        smtpTransport.sendMessage(message, preparedMail.getRecipients());
        return new SmtpServerResponse(smtpTransport.getLastReturnCode(), smtpTransport.getLastServerResponse());
    }

    /**
     * The only provider-specific message facade in the pipeline. It exposes Angus envelope/DSN options while
     * retaining the delegate's final serialization (including late DKIM output).
     */
    static final class AngusSmtpMessage extends SMTPMessage {

        private final MimeMessage delegate;
        private final boolean stableContentRequired;

        AngusSmtpMessage(@NotNull final PreparedMail preparedMail) throws MessagingException {
            super(sessionOf(preparedMail.getMimeMessage()));
            this.delegate = preparedMail.getMimeMessage();
            this.stableContentRequired = preparedMail.requiresStableContent();
            copyHeaders(delegate, this);

            final DeliveryEnvelope envelope = preparedMail.getDeliveryEnvelope();
            if (envelope.getEnvelopeFrom() != null) {
                super.setEnvelopeFrom(envelope.getEnvelopeFrom());
            }
            if (envelope.getDeliveryStatusNotification() != null) {
                configureDsn(envelope.getDeliveryStatusNotification());
            }
            if (stableContentRequired) {
                super.setAllow8bitMIME(false);
            }
        }

        @NotNull
        private static Session sessionOf(@NotNull final MimeMessage message) {
            return message.getSession() != null ? message.getSession() : Session.getInstance(new Properties());
        }

        private static void copyHeaders(@NotNull final MimeMessage from, @NotNull final MimeMessage to)
                throws MessagingException {
            final Enumeration<Header> headers = from.getAllHeaders();
            while (headers.hasMoreElements()) {
                final Header header = headers.nextElement();
                to.addHeader(header.getName(), header.getValue());
            }
        }

        private void configureDsn(@NotNull final DeliveryStatusNotification dsn) {
            int notifyOptions = 0;
            for (DeliveryStatusNotification.NotifyOption notifyOption : dsn.getNotifyOptions()) {
                switch (notifyOption) {
                    case SUCCESS:
                        notifyOptions |= SMTPMessage.NOTIFY_SUCCESS;
                        break;
                    case FAILURE:
                        notifyOptions |= SMTPMessage.NOTIFY_FAILURE;
                        break;
                    case DELAY:
                        notifyOptions |= SMTPMessage.NOTIFY_DELAY;
                        break;
                    case NEVER:
                        notifyOptions = SMTPMessage.NOTIFY_NEVER;
                        break;
                    default:
                        throw new AssertionError("Unsupported DSN notify option: " + notifyOption);
                }
            }
            if (!dsn.getNotifyOptions().isEmpty()) {
                super.setNotifyOptions(notifyOptions);
            }
            if (dsn.getReturnOption() != null) {
                super.setReturnOption(dsn.getReturnOption() == DeliveryStatusNotification.ReturnOption.FULL_MESSAGE
                        ? SMTPMessage.RETURN_FULL
                        : SMTPMessage.RETURN_HDRS);
            }
        }

        @Override
        public boolean isMimeType(final String mimeType) throws MessagingException {
            if (stableContentRequired && ("text/*".equalsIgnoreCase(mimeType)
                    || "multipart/*".equalsIgnoreCase(mimeType))) {
                return false;
            }
            return delegate.isMimeType(mimeType);
        }

        @Override
        public void saveChanges() throws MessagingException {
            if (!stableContentRequired) {
                delegate.saveChanges();
            }
        }

        @Override
        public void writeTo(final OutputStream outputStream) throws IOException, MessagingException {
            delegate.writeTo(outputStream);
        }

        @Override
        public void writeTo(final OutputStream outputStream, final String[] ignoreList)
                throws IOException, MessagingException {
            delegate.writeTo(outputStream, ignoreList);
        }
    }
}
