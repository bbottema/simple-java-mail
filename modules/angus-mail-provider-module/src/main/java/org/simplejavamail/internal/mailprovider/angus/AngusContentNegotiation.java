package org.simplejavamail.internal.mailprovider.angus;

import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.Header;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimePart;
import lombok.Builder;
import org.eclipse.angus.mail.smtp.SMTPMessage;
import org.eclipse.angus.mail.smtp.SMTPTransport;
import org.eclipse.angus.mail.util.PropUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.spi.ContentRequirement;
import org.simplejavamail.api.mailer.spi.MailTransportCompatibilityException;
import org.simplejavamail.api.mailer.spi.PreparedMail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.stream.IntStream;

/** Derives the SMTP extensions needed by the prepared envelope and the bytes Angus will submit. */
final class AngusContentNegotiation {

    private static final String SMTP_UTF8 = "SMTPUTF8";
    private static final String EIGHT_BIT_MIME = "8BITMIME";
    private static final String BODY_EIGHT_BIT_MIME = "BODY=8BITMIME";
    // SMTP DATA allows 1000 octets per line, including CRLF but excluding any transparency dot added by the transport.
    private static final int MAX_BODY_LINE_BYTES = 998;
    // Match the BufferedInputStream buffer this replaces, but inspect blocks instead of invoking read() once per byte.
    private static final int BODY_READ_BUFFER_BYTES = 8192;
    private static final String[] OMITTED_TRANSPORT_HEADERS = {"Bcc", "Content-Length"};

    private AngusContentNegotiation() {
    }

    @NotNull
    static AngusMailFromParameters resolveMailParameters(@NotNull final SMTPTransport transport, @NotNull final PreparedMail preparedMail,
            @NotNull final String protocol, @Nullable final String configuredMailExtension,
            @NotNull final String configuredMailExtensionSource, @NotNull final Address[] envelopeRecipients)
            throws MessagingException {
        final SubmittedContent submittedContent = inspectSubmittedContent(preparedMail);
        final boolean internationalizedEnvelope = hasInternationalizedEnvelope(preparedMail, protocol, envelopeRecipients);
        final boolean internationalizedSubmitter = requiresUtf8Submitter(transport, preparedMail.getMimeMessage(), protocol, envelopeRecipients);
        final boolean smtpUtf8Required = internationalizedEnvelope || submittedContent.hasRawNonAsciiHeaderBytes || internationalizedSubmitter;
        final boolean eightBitMimeRequired = submittedContent.hasRawEightBitBodyBytes;

        requireSmtpUtf8Support(transport, preparedMail, submittedContent, internationalizedEnvelope, smtpUtf8Required, envelopeRecipients);
        requireSupportedBodyTransport(transport, submittedContent, preparedMail.isLegacySmtpContentSupportEnabled(), envelopeRecipients);

        String resolvedExtension = configuredMailExtension;
        final boolean declareSmtpUtf8 = containsExtension(configuredMailExtension, SMTP_UTF8)
                || smtpUtf8Required && transport.supportsExtension(SMTP_UTF8);
        if (declareSmtpUtf8 && !transportAddsSmtpUtf8(transport, preparedMail.getMimeMessage())) {
            resolvedExtension = appendExtension(resolvedExtension, SMTP_UTF8);
        }
        if (eightBitMimeRequired) {
            requireCompatibleBodyDeclaration(configuredMailExtension, configuredMailExtensionSource, envelopeRecipients);
        }
        if ((eightBitMimeRequired && transport.supportsExtension(EIGHT_BIT_MIME)) || angusMayConvertBodyToEightBit(transport, preparedMail, protocol)) {
            resolvedExtension = appendEightBitBodyDeclaration(resolvedExtension, configuredMailExtensionSource, envelopeRecipients);
        }
        return new AngusMailFromParameters(declareSmtpUtf8, resolvedExtension);
    }

    private static void requireSmtpUtf8Support(@NotNull final SMTPTransport transport, @NotNull final PreparedMail preparedMail,
            @NotNull final SubmittedContent submittedContent, final boolean internationalizedEnvelope, final boolean smtpUtf8Required,
            @NotNull final Address[] recipients)
            throws MailTransportCompatibilityException {
        if (submittedContent.hasMalformedInternationalizedHeaderBytes) {
            throw new MailTransportCompatibilityException("This email's stored headers contain text that is not valid UTF-8. "
                    + "Regenerate the original email with valid UTF-8 headers, or recreate it with the regular Email builder, which encodes header "
                    + "text for you. Simple Java Mail cannot repair exact or signed email without changing it. No message was submitted.", recipients);
        }
        if (!smtpUtf8Required) {
            return;
        }
        if (!transport.supportsExtension(SMTP_UTF8) && !preparedMail.isLegacySmtpContentSupportEnabled()) {
            final String remedy = internationalizedEnvelope
                    ? "This email has an internationalized sender or recipient address, but your SMTP server does not advertise SMTPUTF8. "
                            + "Use a server with SMTPUTF8 support, or supply a valid ASCII-only alternative address. "
                    : "This email has raw UTF-8 headers, but your SMTP server does not advertise SMTPUTF8. Use a server with SMTPUTF8 support, "
                            + "or recreate the email with the regular Email builder so ordinary header text can be MIME encoded. ";
            throw new MailTransportCompatibilityException(remedy + legacySupportRemedy(), recipients);
        }
        if ((internationalizedEnvelope || !submittedContent.headersAreFinalized)
                && !utf8CommandEncodingEnabled(transport, preparedMail.getMimeMessage())) {
            throw new MailTransportCompatibilityException("This email needs UTF-8 output, but it is disabled for this SMTP transport. "
                    + "If you set mail.mime.allowutf8 to false, remove that override or set it to true. For a caller-supplied Session, set this "
                    + "property to true before creating the Mailer and its transports. No message was submitted.", recipients);
        }
    }

    private static String legacySupportRemedy() {
        return "If you have verified that this legacy server and its onward route preserve the data without advertising support, "
                + "enable withLegacySmtpContentSupport(true), or set simplejavamail.smtp.legacycontentsupport=true. No message was submitted.";
    }

    private static void requireSupportedBodyTransport(@NotNull final SMTPTransport transport, @NotNull final SubmittedContent submittedContent,
            final boolean legacySupportEnabled, @NotNull final Address[] recipients) throws MailTransportCompatibilityException {
        if (submittedContent.unsupportedBodyReason != null) {
            throw new MailTransportCompatibilityException("This email's body cannot be sent in its current format because it "
                    + submittedContent.unsupportedBodyReason + ". "
                    + (submittedContent.headersAreFinalized
                            ? "Regenerate the source email using Base64 or quoted-printable body encoding before loading it as exact EML or signing it. "
                            : "Use ContentTransferEncoding.QUOTED_PRINTABLE or BASE_64 for the Email builder's text encoding. ")
                    + "No message was submitted.", recipients);
        }
        if (submittedContent.hasRawEightBitBodyBytes && !transport.supportsExtension(EIGHT_BIT_MIME) && !legacySupportEnabled) {
            throw new MailTransportCompatibilityException("This email has a raw 8-bit body, but your SMTP server does not advertise 8BITMIME. "
                    + "Use a server with 8BITMIME support, or "
                    + (submittedContent.headersAreFinalized
                            ? "regenerate the source email using Base64 or quoted-printable before loading it as exact EML or signing it. "
                            : "replace ContentTransferEncoding.BIT8 with QUOTED_PRINTABLE or BASE_64 in the Email builder. ")
                    + legacySupportRemedy(), recipients);
        }
    }

    private static boolean hasInternationalizedEnvelope(@NotNull final PreparedMail preparedMail, @NotNull final String protocol,
            @NotNull final Address[] envelopeRecipients) throws MessagingException {
        boolean internationalized = containsInternationalizedMailbox(resolveEnvelopeSender(preparedMail, protocol), envelopeRecipients);
        for (final Address recipient : envelopeRecipients) {
            // Validate every address even after finding an international one: UTF-8 encoding must not replace an unpaired surrogate.
            internationalized |= containsInternationalizedMailbox(mailboxOf(recipient), envelopeRecipients);
        }
        return internationalized;
    }

    private static boolean containsInternationalizedMailbox(@Nullable final String mailbox, final Address[] recipients)
            throws MailTransportCompatibilityException {
        if (mailbox != null && !StandardCharsets.UTF_8.newEncoder().canEncode(mailbox)) {
            throw new MailTransportCompatibilityException("A sender or recipient address contains malformed Unicode text. Correct that address "
                    + "before sending; replacing its characters could select a different mailbox. No message was submitted.", recipients);
        }
        return containsNonAscii(mailbox);
    }

    @Nullable
    private static String resolveEnvelopeSender(@NotNull final PreparedMail preparedMail, @NotNull final String protocol)
            throws MessagingException {
        final String envelopeFrom = preparedMail.getDeliveryEnvelope().getEnvelopeFrom();
        if (envelopeFrom != null && !envelopeFrom.isEmpty()) {
            return envelopeFrom;
        }
        final MimeMessage message = preparedMail.getMimeMessage();
        if (envelopeFrom == null && message instanceof SMTPMessage && ((SMTPMessage) message).getEnvelopeFrom() != null
                && !((SMTPMessage) message).getEnvelopeFrom().isEmpty()) {
            return ((SMTPMessage) message).getEnvelopeFrom();
        }
        final Session session = message.getSession();
        if (session != null && session.getProperty("mail." + protocol + ".from") != null
                && !session.getProperty("mail." + protocol + ".from").isEmpty()) {
            return session.getProperty("mail." + protocol + ".from");
        }
        final Address[] headerFrom = message.getFrom();
        return headerFrom == null || headerFrom.length == 0
                ? mailboxOf(session == null ? null : InternetAddress.getLocalAddress(session)) : mailboxOf(headerFrom[0]);
    }

    @Nullable
    private static String mailboxOf(@Nullable final Address address) {
        return address instanceof InternetAddress ? ((InternetAddress) address).getAddress() : address == null ? null : address.toString();
    }

    private static boolean containsNonAscii(@Nullable final String value) {
        return value != null && value.chars().anyMatch(character -> character > 0x7f);
    }

    private static boolean transportAddsSmtpUtf8(@NotNull final SMTPTransport transport, @NotNull final MimeMessage message) {
        return transport.supportsExtension(SMTP_UTF8) && utf8CommandEncodingEnabled(transport, message);
    }

    private static boolean utf8CommandEncodingEnabled(final SMTPTransport transport, final MimeMessage message) {
        if (transport instanceof ManagedAngusTransport) {
            return ((ManagedAngusTransport) transport).isUtf8CommandEncodingEnabled();
        }
        final Session session = message.getSession();
        return session != null && PropUtil.getBooleanProperty(session.getProperties(), "mail.mime.allowutf8", false);
    }

    private static boolean requiresUtf8Submitter(final SMTPTransport transport, final MimeMessage message, final String protocol,
            final Address[] recipients) throws MailTransportCompatibilityException {
        if (!transport.supportsExtension("AUTH")) {
            return false;
        }
        String submitter = message instanceof SMTPMessage ? ((SMTPMessage) message).getSubmitter() : null;
        if (submitter == null && message.getSession() != null) {
            submitter = message.getSession().getProperty("mail." + protocol + ".submitter");
        }
        if (!containsInternationalizedMailbox(submitter, recipients)) {
            return false;
        }
        // Without negotiated UTF-8 Angus drops this advanced AUTH parameter. Legacy content permission cannot make it safe to lose that identity.
        if (!transportAddsSmtpUtf8(transport, message)) {
            throw new MailTransportCompatibilityException("A custom SMTP submitter identity contains non-ASCII characters that this connection cannot "
                    + "preserve. Enable UTF-8 encoding and use a server advertising SMTPUTF8, or correct/remove SMTPMessage.setSubmitter(...) or "
                    + "mail." + protocol + ".submitter in the integration supplying it. No message was submitted.", recipients);
        }
        return true;
    }

    private static boolean angusMayConvertBodyToEightBit(@NotNull final SMTPTransport transport, @NotNull final PreparedMail preparedMail,
            @NotNull final String protocol) {
        if (preparedMail.getContentRequirement() != ContentRequirement.NORMAL || !transport.supportsExtension(EIGHT_BIT_MIME)) {
            return false;
        }
        final MimeMessage message = preparedMail.getMimeMessage();
        if (message instanceof SMTPMessage && ((SMTPMessage) message).getAllow8bitMIME()) {
            return true;
        }
        final Session session = message.getSession();
        return session != null && PropUtil.getBooleanProperty(session.getProperties(), "mail." + protocol + ".allow8bitmime", false);
    }

    @Nullable
    private static String appendEightBitBodyDeclaration(@Nullable final String configuredExtension,
            @NotNull final String configuredExtensionSource, @NotNull final Address[] recipients)
            throws MailTransportCompatibilityException {
        requireCompatibleBodyDeclaration(configuredExtension, configuredExtensionSource, recipients);
        return findBodyDeclaration(configuredExtension) == null ? appendExtension(configuredExtension, BODY_EIGHT_BIT_MIME) : configuredExtension;
    }

    private static void requireCompatibleBodyDeclaration(@Nullable final String configuredExtension,
            final String configuredExtensionSource, final Address[] recipients) throws MailTransportCompatibilityException {
        if (configuredExtension == null) {
            return;
        }
        for (final String parameter : configuredExtension.trim().split("\\s+")) {
            if (!isBodyDeclaration(parameter) || BODY_EIGHT_BIT_MIME.equalsIgnoreCase(parameter)) {
                continue;
            }
            throw new MailTransportCompatibilityException("This email cannot be sent because a custom SMTP setting forces an incompatible body format. "
                    + "The setting comes from " + configuredExtensionSource + ". Remove that setting if you do not need custom SMTP options; otherwise, "
                    + "update the integration supplying it to let Simple Java Mail choose the body format. No message was submitted.", recipients);
        }
    }

    @Nullable
    private static String findBodyDeclaration(@Nullable final String extension) {
        return extension == null ? null : Arrays.stream(extension.trim().split("\\s+"))
                .filter(AngusContentNegotiation::isBodyDeclaration).findFirst().orElse(null);
    }

    private static boolean isBodyDeclaration(final String parameter) {
        return parameter.regionMatches(true, 0, "BODY=", 0, "BODY=".length());
    }

    @NotNull
    private static String appendExtension(@Nullable final String existingExtension, @NotNull final String extension) {
        if (containsExtension(existingExtension, extension)) {
            return existingExtension;
        }
        return existingExtension == null || existingExtension.trim().isEmpty()
                ? extension : existingExtension + " " + extension;
    }

    private static boolean containsExtension(@Nullable final String configuredExtension, @NotNull final String extension) {
        return configuredExtension != null && Arrays.stream(configuredExtension.trim().split("\\s+")).anyMatch(extension::equalsIgnoreCase);
    }

    @NotNull
    private static SubmittedContent inspectSubmittedContent(@NotNull final PreparedMail preparedMail) throws MessagingException {
        try {
            if (preparedMail.getContentRequirement() == ContentRequirement.NORMAL) {
                return inspectMimePart(preparedMail.getMimeMessage(), true, OMITTED_TRANSPORT_HEADERS);
            }
            return inspectFinalizedContent(preparedMail);
        } catch (final IOException failure) {
            throw new MessagingException("Could not read this email's content before sending. Check the nested exception for the cause. "
                    + "No message was submitted.", failure);
        }
    }

    @NotNull
    private static SubmittedContent inspectFinalizedContent(final PreparedMail preparedMail) throws MessagingException, IOException {
        final SubmissionBuffer serializedMessage = serializeForSubmission(preparedMail);
        final String[] omittedHeaders = preparedMail.getContentRequirement() == ContentRequirement.PRESERVE_ALL_BYTES
                ? null : OMITTED_TRANSPORT_HEADERS;
        // Even an ASCII-only body may declare binary encoding. Inspect MIME structure too, without scanning decoded body bytes.
        final SubmittedContent mimeContent = inspectMimePart(preparedMail.getMimeMessage(), false, omittedHeaders);
        return serializedMessage.inspect(mimeContent);
    }

    @NotNull
    private static SubmissionBuffer serializeForSubmission(final PreparedMail preparedMail) throws MessagingException, IOException {
        final SubmissionBuffer output = new SubmissionBuffer();
        if (preparedMail.getContentRequirement() == ContentRequirement.PRESERVE_ALL_BYTES) {
            preparedMail.getMimeMessage().writeTo(output);
        } else {
            preparedMail.getMimeMessage().writeTo(output, OMITTED_TRANSPORT_HEADERS);
        }
        return output;
    }

    @NotNull
    private static SubmittedContent inspectMimePart(@NotNull final MimePart part, final boolean inspectBodyBytes,
            @Nullable final String[] omittedHeaders) throws MessagingException, IOException {
        final String encoding = part.getEncoding();
        final SubmittedContent headers = inspectMimeHeaders(part, encoding, omittedHeaders);
        if (part.isMimeType("multipart/*")) {
            return inspectMultipartBody(part, inspectBodyBytes, headers);
        }
        if (part.isMimeType("message/*")) {
            // Encoded attachments do not expose their inner message's transport requirements to this SMTP transaction.
            return hasAsciiTransferEncoding(encoding) ? headers : inspectAttachedMessage(part, inspectBodyBytes, headers);
        }
        if (inspectBodyBytes && headers.unsupportedBodyReason == null && !hasAsciiTransferEncoding(encoding)) {
            return headers.includingBodyContent(inspectUnencodedBody(part));
        }
        return headers;
    }

    @NotNull
    private static SubmittedContent inspectMultipartBody(final MimePart part, final boolean inspectBodyBytes, final SubmittedContent headers)
            throws MessagingException, IOException {
        final Object content = part.getContent();
        if (!(content instanceof MimeMultipart)) {
            return headers;
        }
        final MimeMultipart multipart = (MimeMultipart) content;
        SubmittedContent inspected = headers;
        for (int index = 0; index < multipart.getCount(); index++) {
            final BodyPart child = multipart.getBodyPart(index);
            if (child instanceof MimePart) {
                inspected = inspected.includingBodyContent(inspectMimePart((MimePart) child, inspectBodyBytes, null));
            }
        }
        return inspected;
    }

    @NotNull
    private static SubmittedContent inspectAttachedMessage(final MimePart part, final boolean inspectBodyBytes, final SubmittedContent headers)
            throws MessagingException, IOException {
        final Object content = part.getContent();
        return content instanceof MimePart ? headers.includingBodyContent(inspectMimePart((MimePart) content, inspectBodyBytes, null)) : headers;
    }

    @NotNull
    private static SubmittedContent inspectUnencodedBody(final MimePart part) throws MessagingException, IOException {
        // A declared 8bit body may still be entirely ASCII. Only scan unencoded bodies, so encoded attachments stay streaming.
        try (final InputStream body = part.getInputStream()) {
            return SubmittedContent.inspectBody(body);
        }
    }

    private static boolean hasAsciiTransferEncoding(@Nullable final String encoding) {
        return "base64".equalsIgnoreCase(encoding) || "quoted-printable".equalsIgnoreCase(encoding);
    }

    @NotNull
    private static SubmittedContent inspectMimeHeaders(final MimePart part, @Nullable final String encoding, @Nullable final String[] omittedHeaders)
            throws MessagingException {
        boolean hasNonAsciiText = false;
        boolean hasMalformedText = false;
        final Enumeration<Header> headers = part.getNonMatchingHeaders(omittedHeaders);
        while (headers.hasMoreElements()) {
            final Header header = headers.nextElement();
            hasNonAsciiText |= containsNonAscii(header.getName()) || containsNonAscii(header.getValue());
            hasMalformedText |= !StandardCharsets.UTF_8.newEncoder().canEncode(header.getName())
                    || !StandardCharsets.UTF_8.newEncoder().canEncode(header.getValue());
        }
        return SubmittedContent.builder()
                .hasRawNonAsciiHeaderBytes(hasNonAsciiText)
                .hasMalformedInternationalizedHeaderBytes(hasMalformedText)
                .unsupportedBodyReason("binary".equalsIgnoreCase(encoding) ? "uses unsupported binary body encoding" : null)
                .build();
    }

    /** Keeps the serialization buffer private to this inspection, avoiding a second whole-message copy via toByteArray(). */
    private static final class SubmissionBuffer extends ByteArrayOutputStream {
        @NotNull
        private SubmittedContent inspect(final SubmittedContent mimeContent) {
            // Only count written bytes: unused capacity contains zeros, which would otherwise look like invalid body content.
            return SubmittedContent.inspectSerializedContent(buf, count, mimeContent);
        }
    }

    /** Carries line length across read boundaries; each body gets its own accumulator. */
    private static final class BodyInspection {
        private int lineLength;
        private boolean eightBit;
        @Nullable private String unsupportedReason;

        private void inspect(final byte[] bytes, final int start, final int end) {
            for (int index = start; index < end && isSupported(); index++) {
                final int nextByte = bytes[index] & 0xff;
                eightBit |= nextByte > 0x7f;
                if (nextByte == 0) {
                    unsupportedReason = "contains an unencoded zero byte";
                } else if (nextByte == '\r' || nextByte == '\n') {
                    // Angus normalizes each form of line break to CRLF when writing SMTP DATA.
                    lineLength = 0;
                } else if (++lineLength > MAX_BODY_LINE_BYTES) {
                    unsupportedReason = "contains a body line longer than the supported limit of " + MAX_BODY_LINE_BYTES + " bytes";
                }
            }
        }

        private boolean isSupported() {
            return unsupportedReason == null;
        }

        @NotNull
        private SubmittedContent toSubmittedContent() {
            return SubmittedContent.builder().hasRawEightBitBodyBytes(eightBit).unsupportedBodyReason(unsupportedReason).build();
        }
    }

    @Builder
    private static final class SubmittedContent {
        private static final SubmittedContent EMPTY = SubmittedContent.builder().build();

        private final boolean hasRawNonAsciiHeaderBytes;
        private final boolean hasRawEightBitBodyBytes;
        @Nullable private final String unsupportedBodyReason;
        private final boolean headersAreFinalized;
        private final boolean hasMalformedInternationalizedHeaderBytes;

        @NotNull
        private SubmittedContent includingBodyContent(final SubmittedContent nested) {
            // Nested MIME headers travel in the outer body: non-ASCII text there requires both SMTPUTF8 and 8BITMIME.
            return builder()
                    .hasRawNonAsciiHeaderBytes(hasRawNonAsciiHeaderBytes || nested.hasRawNonAsciiHeaderBytes)
                    .hasRawEightBitBodyBytes(hasRawEightBitBodyBytes || nested.hasRawEightBitBodyBytes || nested.hasRawNonAsciiHeaderBytes)
                    .unsupportedBodyReason(unsupportedBodyReason != null ? unsupportedBodyReason : nested.unsupportedBodyReason)
                    .hasMalformedInternationalizedHeaderBytes(hasMalformedInternationalizedHeaderBytes || nested.hasMalformedInternationalizedHeaderBytes)
                    .build();
        }

        @NotNull
        private static SubmittedContent inspectSerializedContent(final byte[] messageBytes, final int length, final SubmittedContent mimeContent) {
            final int bodyOffset = findBodyOffset(messageBytes, length);
            final int headerEnd = bodyOffset < 0 ? length : bodyOffset;
            final boolean hasRawNonAsciiHeaderBytes = containsEightBitByte(messageBytes, 0, headerEnd);
            final SubmittedContent body = bodyOffset < 0 ? EMPTY
                    : inspectBody(messageBytes, bodyOffset, length);
            // MIME-part headers live among the outer body's bytes, but still require SMTPUTF8 rather than only 8BITMIME.
            return builder()
                    .hasRawNonAsciiHeaderBytes(hasRawNonAsciiHeaderBytes || mimeContent.hasRawNonAsciiHeaderBytes)
                    .hasRawEightBitBodyBytes(body.hasRawEightBitBodyBytes)
                    .unsupportedBodyReason(mimeContent.unsupportedBodyReason != null ? mimeContent.unsupportedBodyReason : body.unsupportedBodyReason)
                    .headersAreFinalized(true)
                    .hasMalformedInternationalizedHeaderBytes(mimeContent.hasMalformedInternationalizedHeaderBytes
                            || (hasRawNonAsciiHeaderBytes && !isValidUtf8(messageBytes, headerEnd)))
                    .build();
        }

        private static int findBodyOffset(final byte[] bytes, final int length) {
            for (int index = 0; index <= length - 4; index++) {
                if (bytes[index] == '\r' && bytes[index + 1] == '\n' && bytes[index + 2] == '\r' && bytes[index + 3] == '\n') {
                    return index + 4;
                }
            }
            return -1;
        }

        private static boolean containsEightBitByte(final byte[] bytes, final int start, final int end) {
            return IntStream.range(start, end).anyMatch(index -> (bytes[index] & 0x80) != 0);
        }

        @NotNull
        private static SubmittedContent inspectBody(final InputStream body) throws IOException {
            final BodyInspection inspection = new BodyInspection();
            final byte[] buffer = new byte[BODY_READ_BUFFER_BYTES];
            int count;
            while (inspection.isSupported() && (count = body.read(buffer)) != -1) {
                inspection.inspect(buffer, 0, count);
            }
            return inspection.toSubmittedContent();
        }

        @NotNull
        private static SubmittedContent inspectBody(final byte[] bytes, final int start, final int end) {
            final BodyInspection inspection = new BodyInspection();
            inspection.inspect(bytes, start, end);
            return inspection.toSubmittedContent();
        }

        private static boolean isValidUtf8(final byte[] bytes, final int end) {
            try {
                StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes, 0, end));
                return true;
            } catch (final CharacterCodingException invalidUtf8) {
                return false;
            }
        }
    }
}
