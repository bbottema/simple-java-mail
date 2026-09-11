package org.simplejavamail.internal.mailprovider.angus;

import jakarta.mail.Address;
import jakarta.mail.Header;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.ParseException;
import org.eclipse.angus.mail.smtp.SMTPMessage;
import org.eclipse.angus.mail.smtp.SMTPTransport;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;
import org.simplejavamail.api.mailer.SmtpServerResponse;
import org.simplejavamail.api.mailer.spi.ContentRequirement;
import org.simplejavamail.api.mailer.spi.DeliveryEnvelope;
import org.simplejavamail.api.mailer.spi.MailTransportAdapter;
import org.simplejavamail.api.mailer.spi.MailTransportResult;
import org.simplejavamail.api.mailer.spi.PreparedMail;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/** Angus Mail implementation of Simple Java Mail's provider adapter SPI. */
public final class AngusMailTransportAdapter implements MailTransportAdapter {

    @Override
    public boolean supports(@NotNull final Transport transport) {
        return transport instanceof SMTPTransport;
    }

    @Override
    public boolean supportsContentRequirement(@NotNull final ContentRequirement contentRequirement) {
        switch (contentRequirement) {
            case NORMAL:
            case PRESERVE_PROTECTED_CONTENT:
            case PRESERVE_ALL_BYTES:
                return true;
            default:
                return false;
        }
    }

    @Override
    @NotNull
    public MailTransportResult sendMessage(@NotNull final Transport transport,
                                           @NotNull final PreparedMail preparedMail) {
        final SMTPTransport smtpTransport = (SMTPTransport) transport;
        final MimeMessage message;
        try {
            message = resolveMessageForTransport(preparedMail);
        } catch (final MessagingException preparationFailure) {
            return MailTransportResult.failed(preparationFailure, null);
        }
        // Use Angus's own monitor to keep reporting changes, sending and response capture atomic.
        //noinspection SynchronizationOnLocalVariableOrMethodParameter
        synchronized (smtpTransport) {
            return sendWithRecipientReporting(smtpTransport, message, preparedMail);
        }
    }

    @NotNull
    private static MailTransportResult sendWithRecipientReporting(@NotNull final SMTPTransport smtpTransport,
            @NotNull final MimeMessage message, @NotNull final PreparedMail preparedMail) {
        final Address[] envelope = resolveEnvelopeForReceipt(preparedMail.getRecipients());
        final boolean originalReportSuccess = smtpTransport.getReportSuccess();
        final SmtpResponseSnapshot responseBeforeSend = captureResponseSnapshot(smtpTransport);
        smtpTransport.setReportSuccess(true);
        try {
            smtpTransport.sendMessage(message, preparedMail.getRecipients());
            return MailTransportResult.accepted(envelope,
                    captureNewResponse(smtpTransport, responseBeforeSend)).withEnvelopeRecipients(envelope);
        } catch (final MessagingException failure) {
            return AngusSubmissionResult.fromFailure(failure, envelope,
                    captureNewResponse(smtpTransport, responseBeforeSend),
                    smtpTransport instanceof ManagedAngusTransport ? (ManagedAngusTransport) smtpTransport : null);
        } finally {
            // Restore transport-local state before its lease can be released, including on unchecked failures.
            smtpTransport.setReportSuccess(originalReportSuccess);
        }
    }

    /** Mirrors Angus group expansion for reporting only; the original addresses and MIME headers still go to the transport. */
    @NotNull
    private static Address[] resolveEnvelopeForReceipt(final Address[] addresses) {
        final List<Address> envelope = new ArrayList<>();
        for (final Address address : addresses) {
            if (address instanceof InternetAddress && ((InternetAddress) address).isGroup()) {
                try {
                    final InternetAddress[] members = ((InternetAddress) address).getGroup(true);
                    if (members != null) {
                        Collections.addAll(envelope, members);
                        continue;
                    }
                } catch (final ParseException ignored) {
                    // Angus also retains an unparseable group and lets the original send report any failure.
                }
            }
            envelope.add(address);
        }
        return envelope.toArray(new Address[0]);
    }

    @NotNull
    private static MimeMessage resolveMessageForTransport(@NotNull final PreparedMail preparedMail) throws MessagingException {
        final DeliveryEnvelope envelope = preparedMail.getDeliveryEnvelope();
        return envelope.hasProviderSpecificOptions()
                || preparedMail.getContentRequirement() != ContentRequirement.NORMAL
                ? new AngusSmtpMessage(preparedMail)
                : preparedMail.getMimeMessage();
    }

    @NotNull
    private static SmtpResponseSnapshot captureResponseSnapshot(@NotNull final SMTPTransport smtpTransport) {
        return new SmtpResponseSnapshot(smtpTransport.getLastReturnCode(), smtpTransport.getLastServerResponse());
    }

    @Nullable
    private static SmtpServerResponse captureNewResponse(@NotNull final SMTPTransport smtpTransport,
                                                          @NotNull final SmtpResponseSnapshot responseBeforeSend) {
        final SmtpResponseSnapshot responseAfterFailure = captureResponseSnapshot(smtpTransport);
        return responseAfterFailure.differsFrom(responseBeforeSend)
                ? responseAfterFailure.toSmtpServerResponse()
                : null;
    }

    private static final class SmtpResponseSnapshot {

        private final int returnCode;
        @Nullable private final String serverResponse;

        private SmtpResponseSnapshot(final int returnCode, @Nullable final String serverResponse) {
            this.returnCode = returnCode;
            this.serverResponse = serverResponse;
        }

        private boolean differsFrom(@NotNull final SmtpResponseSnapshot previousResponse) {
            return returnCode != previousResponse.returnCode || !Objects.equals(serverResponse, previousResponse.serverResponse);
        }

        @Nullable
        private SmtpServerResponse toSmtpServerResponse() {
            return AngusSubmissionResult.smtpResponse(returnCode, serverResponse);
        }
    }

    /**
     * The only provider-specific message facade in the pipeline. It exposes Angus envelope/DSN options while
     * retaining the delegate's final serialization (including late DKIM output).
     */
    static final class AngusSmtpMessage extends SMTPMessage {

        private final MimeMessage delegate;
        private final ContentRequirement contentRequirement;

        AngusSmtpMessage(@NotNull final PreparedMail preparedMail) throws MessagingException {
            super(sessionOf(preparedMail.getMimeMessage()));
            this.delegate = preparedMail.getMimeMessage();
            this.contentRequirement = preparedMail.getContentRequirement();
            copyHeaders(delegate, this);

            final DeliveryEnvelope envelope = preparedMail.getDeliveryEnvelope();
            if (envelope.getEnvelopeFrom() != null) {
                super.setEnvelopeFrom(envelope.getEnvelopeFrom());
            }
            if (envelope.getDeliveryStatusNotification() != null) {
                configureDeliveryStatusNotification(envelope.getDeliveryStatusNotification());
            }
            if (contentRequirement != ContentRequirement.NORMAL) {
                super.setAllow8bitMIME(false);
            }
        }

        @NotNull
        private static Session sessionOf(@NotNull final MimeMessage message) {
            return message.getSession() != null ? message.getSession() : Session.getInstance(new Properties());
        }

        private static void copyHeaders(@NotNull final MimeMessage sourceMessage, @NotNull final MimeMessage targetMessage)
                throws MessagingException {
            final Enumeration<Header> headers = sourceMessage.getAllHeaders();
            while (headers.hasMoreElements()) {
                final Header header = headers.nextElement();
                targetMessage.addHeader(header.getName(), header.getValue());
            }
        }

        private void configureDeliveryStatusNotification(@NotNull final DeliveryStatusNotification deliveryStatusNotification) {
            int notifyOptions = 0;
            for (final DeliveryStatusNotification.NotifyOption notifyOption : deliveryStatusNotification.getNotifyOptions()) {
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
            if (!deliveryStatusNotification.getNotifyOptions().isEmpty()) {
                super.setNotifyOptions(notifyOptions);
            }
            if (deliveryStatusNotification.getReturnOption() != null) {
                super.setReturnOption(deliveryStatusNotification.getReturnOption() == DeliveryStatusNotification.ReturnOption.FULL_MESSAGE
                        ? SMTPMessage.RETURN_FULL
                        : SMTPMessage.RETURN_HDRS);
            }
        }

        @Override
        public boolean isMimeType(final String mimeType) throws MessagingException {
            if (contentRequirement != ContentRequirement.NORMAL && ("text/*".equalsIgnoreCase(mimeType)
                    || "multipart/*".equalsIgnoreCase(mimeType))) {
                return false;
            }
            return delegate.isMimeType(mimeType);
        }

        @Override
        public void saveChanges() throws MessagingException {
            if (contentRequirement == ContentRequirement.NORMAL) {
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
            if (contentRequirement == ContentRequirement.PRESERVE_ALL_BYTES) {
                delegate.writeTo(outputStream);
            } else {
                delegate.writeTo(outputStream, ignoreList);
            }
        }
    }
}
