package org.simplejavamail.internal.mailprovider.angus;

import jakarta.mail.Address;
import jakarta.mail.Header;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.URLName;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.angus.mail.smtp.SMTPMessage;
import org.eclipse.angus.mail.smtp.SMTPSSLTransport;
import org.eclipse.angus.mail.smtp.SMTPTransport;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;
import org.simplejavamail.api.mailer.SmtpServerResponse;
import org.simplejavamail.api.mailer.spi.ContentRequirement;
import org.simplejavamail.api.mailer.spi.DeliveryEnvelope;
import org.simplejavamail.api.mailer.spi.MailTransportAdapter;
import org.simplejavamail.api.mailer.spi.MailTransportCompatibilityException;
import org.simplejavamail.api.mailer.spi.MailTransportResult;
import org.simplejavamail.api.mailer.spi.PreparedMail;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;

/** Angus Mail implementation of Simple Java Mail's provider adapter SPI. */
public final class AngusMailTransportAdapter implements MailTransportAdapter {

    @Override
    public boolean supports(@NotNull final Transport transport) {
        return transport instanceof SMTPTransport;
    }

    /** @see MailTransportAdapter#supportsDeliveryEnvelope(DeliveryEnvelope) */
    @Override
    public boolean supportsDeliveryEnvelope(@NotNull final DeliveryEnvelope envelope) {
        return true;
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
        // Use Angus's own monitor to keep reporting changes, sending and response capture atomic.
        //noinspection SynchronizationOnLocalVariableOrMethodParameter
        synchronized (smtpTransport) {
            final Address[] expandedEnvelopeRecipients = AngusRecipientCommands.expandRecipients(preparedMail.getRecipients());
            try {
                final boolean supportsDsn = supportsUsableDsn(smtpTransport);
                final AngusRecipientCommands recipientCommands = AngusRecipientCommands.prepare(smtpTransport, preparedMail, supportsDsn);
                final ConfiguredMailExtension existingMailExtension = mailExtensionOf(preparedMail.getMimeMessage(), protocolOf(smtpTransport));
                final String envelopeId = resolveEnvelopeIdentifier(supportsDsn, preparedMail.getDeliveryEnvelope(), existingMailExtension,
                        expandedEnvelopeRecipients);
                final MimeMessage message = resolveMessageForTransport(preparedMail, existingMailExtension.value, envelopeId, recipientCommands);
                final MailTransportResult result = sendWithRecipientReporting(smtpTransport, message, preparedMail, expandedEnvelopeRecipients);
                return result.withEnvelopeId(message instanceof AngusSmtpMessage ? ((AngusSmtpMessage) message).getEnvelopeIdUsed() : null);
            } catch (final MessagingException preparationFailure) {
                // Nothing was submitted: do not reuse an earlier SMTP response or imply duplicate risk.
                return MailTransportResult.failed(preparationFailure, null, null, expandedEnvelopeRecipients, null)
                        .withEnvelopeRecipients(expandedEnvelopeRecipients);
            }
        }
    }

    @Nullable
    private static String resolveEnvelopeIdentifier(final boolean supportsDsn, final DeliveryEnvelope deliveryEnvelope,
            final ConfiguredMailExtension existingMailExtension, final Address[] recipients)
            throws MessagingException {
        final DeliveryStatusNotification notification = deliveryEnvelope.getDeliveryStatusNotification();
        final String fixedIdentifier = notification == null ? null : notification.getEnvelopeId();
        final boolean rawIdentifierPresent = containsEnvelopeIdentifier(existingMailExtension.value);
        if (fixedIdentifier != null) {
            if (!supportsDsn) {
                throw new MailTransportCompatibilityException("This SMTP connection does not advertise usable DSN support, so the envelope identifier (ENVID) "
                        + "you fixed cannot be sent. Remove fixingEnvelopeId(...) to allow sending without ENVID, "
                        + "or use a DSN-capable SMTP server. No message was submitted.", recipients);
            }
            if (rawIdentifierPresent) {
                throw new MailTransportCompatibilityException("An envelope identifier is configured twice: on this email and in "
                        + existingMailExtension.sourceDescription + ". Remove the ENVID=... entry from that setting and keep fixingEnvelopeId(...). "
                        + "Leave any other entries unchanged. No message was submitted.", recipients);
            }
            return fixedIdentifier;
        }
        // Older raw MAIL configuration remains caller-owned; do not append a second identifier or claim to have validated it.
        return supportsDsn && !rawIdentifierPresent ? UUID.randomUUID().toString() : null;
    }

    /** RFC 3461 advertises DSN without EHLO parameters; reject parameterized variants instead of guessing at non-standard semantics. */
    private static boolean supportsUsableDsn(final SMTPTransport transport) {
        final String parameters = transport.getExtensionParameter("DSN");
        return transport.supportsExtension("DSN") && (parameters == null || parameters.trim().isEmpty());
    }

    private static boolean containsEnvelopeIdentifier(@Nullable final String extension) {
        if (extension != null) {
            for (final String parameter : extension.split("\\s+")) {
                if (parameter.equalsIgnoreCase("ENVID") || parameter.regionMatches(true, 0, "ENVID=", 0, 6)) {
                    return true;
                }
            }
        }
        return false;
    }

    @NotNull
    private static ConfiguredMailExtension mailExtensionOf(final MimeMessage message, final String protocol) {
        final String extension = message instanceof SMTPMessage ? ((SMTPMessage) message).getMailExtension() : null;
        if (extension != null) {
            return new ConfiguredMailExtension(extension, "SMTPMessage.setMailExtension(...)");
        }
        final String propertyName = "mail." + protocol + ".mailextension";
        return new ConfiguredMailExtension(message.getSession() == null ? null : message.getSession().getProperty(propertyName),
                "the Mailer property '" + propertyName + "'");
    }

    /** Keeps the selected value and its source together so a conflict points to the setting that actually supplied it. */
    private static final class ConfiguredMailExtension {
        @Nullable private final String value;
        private final String sourceDescription;

        private ConfiguredMailExtension(@Nullable final String value, final String sourceDescription) {
            this.value = value;
            this.sourceDescription = sourceDescription;
        }
    }

    @NotNull
    private static MailTransportResult sendWithRecipientReporting(@NotNull final SMTPTransport smtpTransport,
            @NotNull final MimeMessage message, @NotNull final PreparedMail preparedMail,
            @NotNull final Address[] expandedEnvelopeRecipients) {
        final boolean originalReportSuccess = smtpTransport.getReportSuccess();
        final SmtpResponseSnapshot responseBeforeSend = captureResponseSnapshot(smtpTransport);
        smtpTransport.setReportSuccess(true);
        try {
            smtpTransport.sendMessage(message, preparedMail.getRecipients());
            return MailTransportResult.accepted(expandedEnvelopeRecipients,
                    captureNewResponse(smtpTransport, responseBeforeSend)).withEnvelopeRecipients(expandedEnvelopeRecipients);
        } catch (final MessagingException failure) {
            return AngusSubmissionResult.fromFailure(failure, expandedEnvelopeRecipients,
                    captureNewResponse(smtpTransport, responseBeforeSend),
                    smtpTransport instanceof ManagedAngusTransport ? (ManagedAngusTransport) smtpTransport : null);
        } finally {
            // Restore transport-local state before its lease can be released, including on unchecked failures.
            smtpTransport.setReportSuccess(originalReportSuccess);
        }
    }

    @NotNull
    private static MimeMessage resolveMessageForTransport(@NotNull final PreparedMail preparedMail,
            @Nullable final String existingMailExtension, @Nullable final String envelopeId, @Nullable final AngusRecipientCommands recipientCommands)
            throws MessagingException {
        final DeliveryEnvelope envelope = preparedMail.getDeliveryEnvelope();
        return recipientCommands != null || envelopeId != null || envelope.hasProviderSpecificOptions()
                || preparedMail.getContentRequirement() != ContentRequirement.NORMAL
                ? new AngusSmtpMessage(preparedMail, existingMailExtension, envelopeId, recipientCommands)
                : preparedMail.getMimeMessage();
    }

    private static String protocolOf(final SMTPTransport transport) {
        final URLName url = transport.getURLName();
        if (url != null && url.getProtocol() != null) {
            return url.getProtocol();
        }
        // Directly constructed Angus transports may have no URL; use their standard protocol defaults.
        return transport instanceof SMTPSSLTransport ? "smtps" : "smtp";
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
        @Nullable private final AngusRecipientCommands recipientCommands;
        @Nullable private final String selectedEnvelopeId;
        @Nullable private String envelopeIdUsed;

        AngusSmtpMessage(@NotNull final PreparedMail preparedMail, @Nullable final String existingMailExtension,
                @Nullable final String envelopeId, @Nullable final AngusRecipientCommands recipientCommands) throws MessagingException {
            super(sessionOf(preparedMail.getMimeMessage()));
            this.delegate = preparedMail.getMimeMessage();
            this.contentRequirement = preparedMail.getContentRequirement();
            this.recipientCommands = recipientCommands;
            this.selectedEnvelopeId = envelopeId;
            copyHeaders(delegate, this);
            retainProviderOptions(delegate);
            super.setMailExtension(envelopeId == null ? existingMailExtension : mailExtensionWithEnvelopeId(envelopeId, existingMailExtension));

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

        @Nullable
        AngusRecipientCommands getRecipientCommands() {
            return recipientCommands;
        }

        /**
         * Angus reads this extension immediately before issuing MAIL FROM, after its local sender/recipient checks.
         * Record consumption here because ordinary caller-owned Angus transports have no managed command hook.
         * The state belongs to this attempt's facade, never to the reusable Email or pooled transport.
         */
        @Override
        @Nullable
        public String getMailExtension() {
            envelopeIdUsed = selectedEnvelopeId;
            return super.getMailExtension();
        }

        @Nullable
        String getEnvelopeIdUsed() {
            return envelopeIdUsed;
        }

        /** Automatic ENVID may introduce this facade around an otherwise untouched provider message. Retain its caller-supplied options. */
        private void retainProviderOptions(final MimeMessage message) {
            if (message instanceof SMTPMessage) {
                final SMTPMessage original = (SMTPMessage) message;
                super.setEnvelopeFrom(original.getEnvelopeFrom());
                super.setNotifyOptions(original.getNotifyOptions());
                super.setReturnOption(original.getReturnOption());
                super.setAllow8bitMIME(original.getAllow8bitMIME());
                super.setSendPartial(original.getSendPartial());
                super.setSubmitter(original.getSubmitter());
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

        // Angus may convert ordinary MIME to 8bit. Its reads and updates must reach the message that writeTo serializes.
        @Override
        public InputStream getInputStream() throws IOException, MessagingException {
            return delegate.getInputStream();
        }

        @Override
        public Object getContent() throws IOException, MessagingException {
            return delegate.getContent();
        }

        @Override
        public void setContent(final Object content, final String type) throws MessagingException {
            delegate.setContent(content, type);
        }

        @Override
        public void setHeader(final String name, final String value) throws MessagingException {
            delegate.setHeader(name, value);
            super.setHeader(name, value);
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

        private static String mailExtensionWithEnvelopeId(final String envelopeId, @Nullable final String existingExtension) {
            final String identifierParameter = "ENVID=" + ManagedAngusTransport.encodeXtext(envelopeId);
            return existingExtension == null || existingExtension.isEmpty()
                    ? identifierParameter : existingExtension + " " + identifierParameter;
        }
    }
}
