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
import org.eclipse.angus.mail.util.PropUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;
import org.simplejavamail.api.mailer.SmtpServerResponse;
import org.simplejavamail.api.mailer.spi.ContentRequirement;
import org.simplejavamail.api.mailer.spi.DeliveryEnvelope;
import org.simplejavamail.api.mailer.spi.MailTransportAdapter;
import org.simplejavamail.api.mailer.spi.MailTransportProgress;
import org.simplejavamail.api.mailer.spi.MailTransportCompatibilityException;
import org.simplejavamail.api.mailer.spi.MailTransportResult;
import org.simplejavamail.api.mailer.spi.PreparedMail;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Angus Mail implementation of Simple Java Mail's provider adapter SPI. */
public final class AngusMailTransportAdapter implements MailTransportAdapter {

    /** @see MailTransportAdapter#prepareForRepeatedSubmission(Transport, PreparedMail, MimeMessage) */
    @Override
    @NotNull
    public PreparedMail prepareForRepeatedSubmission(final Transport transport, final PreparedMail original, final MimeMessage frozen)
            throws MessagingException {
        final String protocol = transport.getURLName().getProtocol();
        final AngusReplayableMessage replay = new AngusReplayableMessage(original.getMimeMessage(), frozen);
        replay.retainEnvelopeOptions(original, protocol);
        replay.disableMimeRewriting();
        replay.retainSessionOptions(original.getMimeMessage().getSession(), protocol);
        return PreparedMail.builder()
            .withMimeMessage(replay)
            .withRecipients(original.getRecipients())
            .withDeliveryEnvelope(original.getDeliveryEnvelope())
            .withContentRequirement(replayContentRequirement(original))
            .withLegacySmtpContentSupportEnabled(original.isLegacySmtpContentSupportEnabled())
            .build();
    }

    private static ContentRequirement replayContentRequirement(final PreparedMail original) {
        // Even unsigned mail now has a byte-preservation requirement. Reuse the existing preservation path to prevent rewriting,
        // not to imply that the content is cryptographically protected; exact EML keeps its stronger complete-byte requirement.
        return original.getContentRequirement() == ContentRequirement.PRESERVE_ALL_BYTES ? ContentRequirement.PRESERVE_ALL_BYTES
                : ContentRequirement.PRESERVE_PROTECTED_CONTENT;
    }

    /** Freezes provider options separately from bytes; each send still creates its own ordinary AngusSmtpMessage facade. */
    private static final class AngusReplayableMessage extends SMTPMessage {
        private final MimeMessage frozen;

        private AngusReplayableMessage(final MimeMessage original, final MimeMessage frozen) throws MessagingException {
            super(frozen.getSession());
            this.frozen = frozen;
            // Angus still reads headers for sender lookup and preflight; copying them does not replace the frozen serialization.
            copyHeaders(frozen, this);
            retainMessageOptions(original);
        }

        private void retainMessageOptions(final MimeMessage original) {
            if (original instanceof SMTPMessage) {
                final SMTPMessage options = (SMTPMessage) original;
                setNotifyOptions(options.getNotifyOptions());
                setReturnOption(options.getReturnOption());
                setSendPartial(options.getSendPartial());
                setSubmitter(options.getSubmitter());
            }
        }

        private void retainEnvelopeOptions(final PreparedMail original, final String protocol) throws MessagingException {
            setEnvelopeFrom(AngusContentNegotiation.resolveEnvelopeSender(original, protocol));
            setMailExtension(mailExtensionOf(original.getMimeMessage(), protocol).value);
        }

        private void disableMimeRewriting() {
            // Disable Angus's optional MIME conversion so every attempt sends the frozen bytes.
            // Already-8-bit content is still allowed; the usual content/capability checks apply on each connection.
            setAllow8bitMIME(false);
        }

        /** Resolve Angus's Session fallbacks once. Do not let a later attempt accidentally lose its original per-message choices. */
        private void retainSessionOptions(final Session session, final String protocol) {
            final String prefix = "mail." + protocol;
            // Match Angus's precedence: a per-message false does not override a Session-wide sendpartial=true.
            setSendPartial(getSendPartial() || PropUtil.getBooleanProperty(session.getProperties(), prefix + ".sendpartial", false));
            retainSubmitterDefault(session, prefix);
            retainNotificationDefault(session, prefix);
            retainReturnOptionDefault(session, prefix);
        }

        private void retainSubmitterDefault(final Session session, final String prefix) {
            if (getSubmitter() == null) {
                setSubmitter(session.getProperty(prefix + ".submitter"));
            }
        }

        private void retainNotificationDefault(final Session session, final String prefix) {
            final String notify = session.getProperty(prefix + ".dsn.notify");
            if (getNotifyOptions() == 0 && notify != null) {
                setNotifyOptions(toAngusNotifyOptions(DeliveryStatusNotification.parseNotifyOptions(notify)));
            }
        }

        private void retainReturnOptionDefault(final Session session, final String prefix) {
            final String returned = session.getProperty(prefix + ".dsn.ret");
            if (getReturnOption() == 0 && returned != null) {
                setReturnOption(DeliveryStatusNotification.parseReturnOption(returned) == DeliveryStatusNotification.ReturnOption.FULL_MESSAGE
                        ? RETURN_FULL : RETURN_HDRS);
            }
        }

        @Override public void saveChanges() { /* Frozen bytes, including Message-ID and signatures, are authoritative. */ }
        @Override public void writeTo(final OutputStream output) throws IOException, MessagingException { frozen.writeTo(output); }
        @Override public void writeTo(final OutputStream output, final String[] omittedHeaders) throws IOException, MessagingException {
            frozen.writeTo(output, omittedHeaders);
        }
        @Override public boolean isMimeType(final String type) throws MessagingException { return frozen.isMimeType(type); }
        @Override public Object getContent() throws IOException, MessagingException { return frozen.getContent(); }
        @Override public InputStream getInputStream() throws IOException, MessagingException { return frozen.getInputStream(); }
    }

    private static void copyHeaders(@NotNull final MimeMessage sourceMessage, @NotNull final MimeMessage targetMessage) throws MessagingException {
        final Enumeration<Header> headers = sourceMessage.getAllHeaders();
        while (headers.hasMoreElements()) {
            final Header header = headers.nextElement();
            targetMessage.addHeader(header.getName(), header.getValue());
        }
    }

    /** Shared by typed Email preferences and frozen advanced Session defaults; Angus needs the same notification bit flags in both paths. */
    private static int toAngusNotifyOptions(final Collection<DeliveryStatusNotification.NotifyOption> options) {
        int flags = 0;
        for (final DeliveryStatusNotification.NotifyOption option : options) {
            switch (option) {
                case SUCCESS:
                    flags |= SMTPMessage.NOTIFY_SUCCESS;
                    break;
                case FAILURE:
                    flags |= SMTPMessage.NOTIFY_FAILURE;
                    break;
                case DELAY:
                    flags |= SMTPMessage.NOTIFY_DELAY;
                    break;
                case NEVER:
                    flags = SMTPMessage.NOTIFY_NEVER;
                    break;
                default:
                    throw new AssertionError(option);
            }
        }
        return flags;
    }

    @Override
    public boolean supports(@NotNull final Transport transport) {
        return transport instanceof SMTPTransport;
    }

    /** @see MailTransportAdapter#supportsDeliveryEnvelope(DeliveryEnvelope) */
    @Override
    public boolean supportsDeliveryEnvelope(@NotNull final DeliveryEnvelope envelope) {
        return true;
    }

    /** @see MailTransportAdapter#supportsSendingToAcceptedRecipients() */
    @Override
    public boolean supportsSendingToAcceptedRecipients() {
        return true;
    }

    /** @see MailTransportAdapter#supportsRequiredDeliveryStatusNotification() */
    @Override
    public boolean supportsRequiredDeliveryStatusNotification() {
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
        return sendMessage(transport, preparedMail, null);
    }

    /** @see MailTransportAdapter#sendMessage(Transport, PreparedMail, MailTransportProgress) */
    @Override
    @NotNull
    public MailTransportResult sendMessage(@NotNull final Transport transport, @NotNull final PreparedMail preparedMail,
            @Nullable final MailTransportProgress progress) {
        final SMTPTransport smtpTransport = (SMTPTransport) transport;
        // Use Angus's own monitor to keep reporting changes, sending and response capture atomic.
        //noinspection SynchronizationOnLocalVariableOrMethodParameter
        synchronized (smtpTransport) {
            final Address[] expandedEnvelopeRecipients = AngusRecipientCommands.expandRecipients(preparedMail.getRecipients());
            final Long serverMaximumMessageSize = smtpTransport instanceof ManagedAngusTransport
                    ? ((ManagedAngusTransport) smtpTransport).getServerMaximumMessageSize() : null;
            try {
                final String protocol = protocolOf(smtpTransport);
                verifyRecipientRejectionChoice(smtpTransport, preparedMail, protocol, expandedEnvelopeRecipients);
                final boolean supportsDsn = supportsUsableDsn(smtpTransport);
                if (preparedMail.getDeliveryEnvelope().isDeliveryStatusNotificationRequired() && !supportsDsn) {
                    throw new MailTransportCompatibilityException("This send requires its delivery-notification settings to be honored, "
                            + "but the connected SMTP server does not advertise DSN support. Simple Java Mail cannot submit the email with that requirement. "
                            + "Use a server that advertises DSN, or remove the mandatory notification requirement, "
                            + "including any notification settings fixed by simplejavamail.locked.* properties. "
                            + "No message was submitted.",
                            expandedEnvelopeRecipients);
                }
                final AngusRecipientCommands recipientCommands = AngusRecipientCommands.prepare(smtpTransport, preparedMail, supportsDsn);
                final ConfiguredMailExtension existingMailExtension = mailExtensionOf(preparedMail.getMimeMessage(), protocol);
                verifyLockedNotificationExtension(preparedMail.getDeliveryEnvelope(), existingMailExtension, expandedEnvelopeRecipients);
                if (smtpTransport instanceof ManagedAngusTransport) {
                    AngusMessageSize.validateDeclaration(existingMailExtension.value, existingMailExtension.sourceDescription, expandedEnvelopeRecipients);
                }
                final boolean requireTlsSelected = resolveRequireTls(smtpTransport, preparedMail, protocol, existingMailExtension,
                        expandedEnvelopeRecipients);
                final String envelopeId = resolveEnvelopeIdentifier(supportsDsn, preparedMail.getDeliveryEnvelope(), existingMailExtension,
                        expandedEnvelopeRecipients);
                final String mailExtensionWithRequireTls = mailExtensionWithRequireTls(existingMailExtension.value, requireTlsSelected);
                final AngusMailFromParameters mailFromParameters = AngusContentNegotiation.resolveMailParameters(smtpTransport, preparedMail, protocol,
                        mailExtensionWithRequireTls, existingMailExtension.sourceDescription, expandedEnvelopeRecipients);
                final MimeMessage message = resolveMessageForTransport(preparedMail, mailFromParameters, envelopeId, recipientCommands,
                        requireTlsSelected, smtpTransport instanceof ManagedAngusTransport);
                if (message instanceof AngusSmtpMessage && smtpTransport instanceof ManagedAngusTransport) {
                    ((AngusSmtpMessage) message).progress = progress;
                }
                final MailTransportResult result = sendWithRecipientReporting(smtpTransport, message, preparedMail, expandedEnvelopeRecipients);
                if (!(message instanceof AngusSmtpMessage)) {
                    return result;
                }
                final AngusSmtpMessage angusMessage = (AngusSmtpMessage) message;
                return result.withEnvelopeId(angusMessage.getEnvelopeIdUsed())
                        .withRequireTlsUsed(angusMessage.isRequireTlsUsed())
                        .withMessageSizeFacts(angusMessage.getMessageSize(), serverMaximumMessageSize);
            } catch (final MessagingException preparationFailure) {
                // Nothing was submitted: do not reuse an earlier SMTP response or imply duplicate risk.
                return MailTransportResult.failed(preparationFailure, null, null, expandedEnvelopeRecipients, null)
                        .withEnvelopeRecipients(expandedEnvelopeRecipients).withMessageSizeFacts(null, serverMaximumMessageSize);
            }
        }
    }

    private static void verifyRecipientRejectionChoice(final SMTPTransport transport, final PreparedMail preparedMail,
            final String protocol, final Address[] recipients) throws MailTransportCompatibilityException {
        if (!Boolean.FALSE.equals(preparedMail.getDeliveryEnvelope().getSendingToAcceptedRecipients())) {
            return;
        }
        final String propertyName = "mail." + protocol + ".sendpartial";
        // Composed and exact messages use the selected transport's Session, including caller-owned and clustered sends.
        final Session selectedSession = preparedMail.getMimeMessage().getSession();
        final boolean sessionAllowsPartialSending = transport instanceof ManagedAngusTransport
                ? ((ManagedAngusTransport) transport).isPartialSendingEnabled()
                : selectedSession != null && PropUtil.getBooleanProperty(selectedSession.getProperties(), propertyName, false);
        // Angus treats a false message flag as "fall back to the Session", not as an override of Session-wide true.
        if (sessionAllowsPartialSending) {
            throw new MailTransportCompatibilityException("This email uses withSendingToAcceptedRecipients(false) "
                    + "to stop sending when a recipient is rejected, "
                    + "but the selected SMTP connection has '" + propertyName + "=true', which tells Angus to continue anyway. "
                    + "Angus cannot override that connection-wide setting for one email. Remove '" + propertyName
                    + "' and use an Email default with withSendingToAcceptedRecipients(true), or "
                    + "'simplejavamail.defaults.sendtoacceptedrecipients=true', for emails that should continue. "
                    + "Then this email's explicit false can stop sending. No message was submitted.", recipients);
        }
    }

    private static void verifyLockedNotificationExtension(final DeliveryEnvelope envelope, final ConfiguredMailExtension extension,
            final Address[] recipients) throws MailTransportCompatibilityException {
        if (envelope.isDeliveryStatusNotificationRequired() && containsMailExtension(extension.value, "RET", true)) {
            throw new MailTransportCompatibilityException("This send requires its delivery-notification settings to be honored, but "
                    + extension.sourceDescription
                    + " supplies a separate return-content preference (RET) that could replace them. "
                    + "Remove the RET parameter from that advanced configuration and let Simple Java Mail apply the required notification preference, "
                    + "or remove the mandatory notification requirement, including any corresponding simplejavamail.locked.* properties. "
                    + "No message was submitted.", recipients);
        }
    }

    private static boolean resolveRequireTls(@NotNull final SMTPTransport smtpTransport, @NotNull final PreparedMail preparedMail,
            @NotNull final String protocol, @NotNull final ConfiguredMailExtension existingMailExtension,
            @NotNull final Address[] recipients) throws MessagingException {
        final boolean requiredByEmail = preparedMail.getDeliveryEnvelope().isTlsRequiredForOnwardDelivery();
        final boolean rawRequireTlsPresent = containsBareRequireTls(existingMailExtension.value);
        if (!requiredByEmail) {
            return rawRequireTlsPresent;
        }
        if (containsParameterizedRequireTls(existingMailExtension.value)) {
            throw new MailTransportCompatibilityException("This email requires REQUIRETLS, but " + existingMailExtension.sourceDescription
                    + " also contains an invalid REQUIRETLS=... parameter. Remove that raw parameter; Simple Java Mail will add the valid "
                    + "REQUIRETLS flag for this email. No message was submitted.", recipients);
        }
        verifyRequireTlsConnection(smtpTransport, preparedMail.getMimeMessage().getSession(), protocol, recipients);
        return true;
    }

    private static void verifyRequireTlsConnection(@NotNull final SMTPTransport transport, @Nullable final Session session,
            @NotNull final String protocol, @NotNull final Address[] recipients) throws MailTransportCompatibilityException {
        if (!transport.isSSL()) {
            throw new MailTransportCompatibilityException("This email requires REQUIRETLS, but the SMTP connection is not using TLS. "
                    + "Use SMTP_TLS or SMTPS, or make sure opportunistic SMTP successfully negotiates STARTTLS. No message was submitted.", recipients);
        }
        final Properties properties = session == null ? new Properties() : session.getProperties();
        final String prefix = "mail." + protocol;
        if (!PropUtil.getBooleanProperty(properties, prefix + ".ssl.checkserveridentity", false)) {
            throw new MailTransportCompatibilityException("This email requires REQUIRETLS, but server identity verification is disabled. "
                    + "Enable verifyingServerIdentity(true), or set '" + prefix
                    + ".ssl.checkserveridentity=true'. No message was submitted.", recipients);
        }
        if (trustsEveryCertificate(properties, prefix)) {
            throw new MailTransportCompatibilityException("This email requires REQUIRETLS, but the Mailer is configured to trust every TLS certificate. "
                    + "Remove trustingAllHosts(true) and use the JVM trust store or a specific trusted host. No message was submitted.", recipients);
        }
        if (hasOpaqueSocketFactory(properties, prefix)) {
            throw new MailTransportCompatibilityException("This email requires REQUIRETLS, but a custom socket factory owns TLS certificate handling, "
                    + "so Simple Java Mail cannot confirm that the server certificate was trusted. Use the default TLS setup, or clear the email's "
                    + "REQUIRETLS requirement. No message was submitted.", recipients);
        }
        if (!supportsUsableRequireTls(transport)) {
            throw new MailTransportCompatibilityException("This email requires REQUIRETLS, but the SMTP server does not advertise usable REQUIRETLS "
                    + "support after TLS negotiation. Use a server that supports RFC 8689, or clear the requirement for this email. "
                    + "No message was submitted.", recipients);
        }
    }

    private static boolean hasOpaqueSocketFactory(final Properties properties, final String prefix) {
        final Object socketFactory = properties.get(prefix + ".socketFactory");
        return (socketFactory != null && !(socketFactory instanceof AngusSocketFactory))
                || properties.getProperty(prefix + ".socketFactory.class") != null
                || properties.get(prefix + ".ssl.socketFactory") != null
                || properties.getProperty(prefix + ".ssl.socketFactory.class") != null;
    }

    private static boolean trustsEveryCertificate(final Properties properties, final String prefix) {
        final String trustedHosts = properties.getProperty(prefix + ".ssl.trust");
        return trustedHosts != null && "*".equals(trustedHosts.trim());
    }

    /** RFC 8689 advertises REQUIRETLS without EHLO parameters; reject parameterized variants instead of guessing. */
    private static boolean supportsUsableRequireTls(final SMTPTransport transport) {
        final String parameters = transport.getExtensionParameter("REQUIRETLS");
        return transport.supportsExtension("REQUIRETLS") && (parameters == null || parameters.trim().isEmpty());
    }

    @Nullable
    private static String mailExtensionWithRequireTls(@Nullable final String existingExtension, final boolean requireTlsSelected) {
        if (!requireTlsSelected || containsBareRequireTls(existingExtension)) {
            return existingExtension;
        }
        return existingExtension == null || existingExtension.trim().isEmpty()
                ? "REQUIRETLS" : existingExtension + " REQUIRETLS";
    }

    private static boolean containsBareRequireTls(@Nullable final String extension) {
        return containsMailExtension(extension, "REQUIRETLS", false);
    }

    private static boolean containsParameterizedRequireTls(@Nullable final String extension) {
        return containsMailExtension(extension, "REQUIRETLS", true);
    }

    private static boolean containsMailExtension(@Nullable final String extension, final String name, final boolean parameterized) {
        if (extension != null) {
            for (final String parameter : extension.trim().split("\\s+")) {
                if (parameterized ? parameter.regionMatches(true, 0, name + "=", 0, name.length() + 1)
                        : parameter.equalsIgnoreCase(name)) {
                    return true;
                }
            }
        }
        return false;
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
            @NotNull final AngusMailFromParameters mailFromParameters, @Nullable final String envelopeId,
            @Nullable final AngusRecipientCommands recipientCommands, final boolean requireTlsSelected, final boolean managedTransport)
            throws MessagingException {
        final DeliveryEnvelope envelope = preparedMail.getDeliveryEnvelope();
        return managedTransport || recipientCommands != null || envelopeId != null || requireTlsSelected
                || mailFromParameters.getMailExtension() != null || envelope.hasProviderSpecificOptions()
                || preparedMail.getContentRequirement() != ContentRequirement.NORMAL
                ? new AngusSmtpMessage(preparedMail, mailFromParameters, envelopeId, recipientCommands, requireTlsSelected)
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
     * The per-submission Angus facade exposes envelope/DSN options while retaining the delegate's final serialization (including late DKIM output).
     * Retry-enabled sends delegate to AngusReplayableMessage for stable content and provider options; this facade is fresh for every invocation,
     * so selected/used identifiers and other transaction facts cannot leak into the next attempt.
     */
    static final class AngusSmtpMessage extends SMTPMessage {

        private final MimeMessage delegate;
        private final ContentRequirement contentRequirement;
        private final AngusMailFromParameters mailFromParameters;
        @Nullable private final AngusRecipientCommands recipientCommands;
        @Nullable private final String selectedEnvelopeId;
        @Nullable private String envelopeIdUsed;
        private final boolean requireTlsSelected;
        private boolean requireTlsUsed;
        private final Address[] recipients;
        @Nullable private Long messageSize;
        @Nullable private Long progressContentSize;
        @Nullable private MailTransportProgress progress;

        AngusSmtpMessage(@NotNull final PreparedMail preparedMail, @NotNull final AngusMailFromParameters mailFromParameters,
                @Nullable final String envelopeId, @Nullable final AngusRecipientCommands recipientCommands,
                final boolean requireTlsSelected) throws MessagingException {
            super(sessionOf(preparedMail.getMimeMessage()));
            this.delegate = preparedMail.getMimeMessage();
            this.contentRequirement = preparedMail.getContentRequirement();
            this.mailFromParameters = mailFromParameters;
            this.recipientCommands = recipientCommands;
            this.selectedEnvelopeId = envelopeId;
            this.requireTlsSelected = requireTlsSelected;
            this.recipients = preparedMail.getRecipients();
            copyHeaders(delegate, this);
            retainProviderOptions(delegate);
            super.setMailExtension(envelopeId == null ? mailFromParameters.getMailExtension()
                    : mailExtensionWithEnvelopeId(envelopeId, mailFromParameters.getMailExtension()));

            final DeliveryEnvelope envelope = preparedMail.getDeliveryEnvelope();
            if (envelope.getSendingToAcceptedRecipients() != null) {
                super.setSendPartial(envelope.getSendingToAcceptedRecipients());
            }
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

        @Nullable
        AngusRecipientCommands getRecipientCommands() {
            return recipientCommands;
        }

        AngusMailFromParameters getMailFromParameters() {
            return mailFromParameters;
        }

        /** Publish only a complete count. A rejection retains that count, but does not consume the selected submission options. */
        void prepareMessageSize(@Nullable final Long serverMaximum, final boolean sizeAdvertised, final BooleanSupplier stopped)
                throws MessagingException {
            messageSize = AngusMessageSize.measure(this, stopped, progress == null ? null : count -> progressContentSize = count < 0 ? null : count);
            if (serverMaximum != null && messageSize > serverMaximum) {
                throw new MailTransportCompatibilityException(String.format(Locale.ROOT,
                        "This email is %,d bytes, but the SMTP server advertises a limit of %,d bytes. "
                                + "Reduce its content or attachments, or use a server with a higher limit. No message was submitted.",
                        messageSize, serverMaximum), AngusRecipientCommands.expandRecipients(recipients));
            }
            if (sizeAdvertised) {
                super.setMailExtension(AngusMessageSize.withDeclaration(super.getMailExtension(), messageSize));
            }
        }

        @Nullable
        Long getMessageSize() {
            return messageSize;
        }

        @Nullable
        MailTransportProgress getProgress() {
            return progress;
        }

        @Nullable
        Long getProgressContentSize() {
            return progressContentSize;
        }

        void clearProgress() {
            progress = null;
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
            final String mailExtension = super.getMailExtension();
            requireTlsUsed = requireTlsSelected && containsBareRequireTls(mailExtension);
            return mailExtension;
        }

        @Nullable
        String getEnvelopeIdUsed() {
            return envelopeIdUsed;
        }

        boolean isRequireTlsUsed() {
            return requireTlsUsed;
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
            if (!deliveryStatusNotification.getNotifyOptions().isEmpty()) {
                super.setNotifyOptions(toAngusNotifyOptions(deliveryStatusNotification.getNotifyOptions()));
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
