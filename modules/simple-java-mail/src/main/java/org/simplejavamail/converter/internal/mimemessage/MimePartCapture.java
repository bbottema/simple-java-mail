package org.simplejavamail.converter.internal.mimemessage;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimePart;
import org.simplejavamail.api.email.EmailProblem;
import org.simplejavamail.api.email.EmailSourceContent;
import org.simplejavamail.api.email.EmailSourceHeader;
import org.simplejavamail.api.email.EmailSourcePart;
import org.simplejavamail.api.email.EmailSourceProperty;
import org.simplejavamail.internal.email.EmailInspection;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.CharacterCodingException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.simplejavamail.internal.util.InterruptedIoGuard.rethrowIfInterrupted;

/** Temporary capture while one MIME entity is interpreted; only immutable evidence leaves this object. */
final class MimePartCapture {
    private final InputInspection inspection;
    private final String location;
    private final List<EmailSourceHeader> headers;
    private final List<MimePartCapture> children = new ArrayList<>();
    private final List<EmailSourceProperty> properties = new ArrayList<>();
    private EmailSourceContent content;
    private String text;
    private byte[] acquiredBytes;
    private String readerText;
    private String canonicalBody;
    private EmailSourcePart snapshot;
    private EmailSourcePart originalSnapshot;
    private EmailSourceContent originalContent;
    private boolean deferredRepresentation;
    private String transferEncoding;

    MimePartCapture(final InputInspection inspection, final String location, final List<EmailSourceHeader> headers) {
        this.inspection = inspection;
        this.location = location;
        this.headers = headers;
    }

    String getLocation() {
        return location;
    }

    List<EmailSourceHeader> getHeaders() {
        return headers;
    }

    String raw(final String name) {
        for (final EmailSourceHeader header : headers) {
            if (header.getName().equalsIgnoreCase(name)) {
                return header.getRawValue();
            }
        }
        return null;
    }

    boolean hasMultipleHeaders(final String name) {
        int count = 0;
        for (final EmailSourceHeader header : headers) {
            if (header.getName().equalsIgnoreCase(name) && ++count > 1) return true;
        }
        return false;
    }

    void add(final MimePartCapture child) {
        children.add(child);
    }

    void readerText(final Object value) {
        if (value instanceof String) {
            readerText = (String) value;
        }
    }

    /** Read and close the already selected stream, retaining only a prefix if reading/closing fails. */
    byte[] read(final InputStream stream) throws IOException {
        try {
            acquiredBytes = CapturedMimeContent.read(stream);
        } catch (final CapturedMimeContent.Failure failure) {
            if (failure.isFullyCaptured() && !failure.isInterpretationFailure()) {
                return closedAfterCompleteRead(failure);
            }
            content = EmailInspection.content(failure.getCaptured(), EmailSourceContent.Representation.READER_BYTES, failure.isFullyCaptured());
            throw failure;
        }
        return acquiredBytes;
    }

    boolean hasAcquiredBytes() {
        return acquiredBytes != null;
    }

    byte[] closedAfterCompleteRead(final CapturedMimeContent.Failure failure) {
        acquiredBytes = failure.getCaptured();
        problem(EmailProblem.Code.RESOURCE_CLOSE_FAILED, null, InputInspection.describe(failure));
        return acquiredBytes;
    }

    /** Capture through a stream whose closing scope is owned by the calling interpreter. */
    byte[] readOpen(final InputStream stream) throws IOException {
        return read(new FilterInputStream(stream) {
            @Override public void close() { /* The interpreter's enclosing scope closes the acquired stream. */ }
        });
    }

    void represented() {
        if (deferredRepresentation) {
            return;
        }
        // Neither the source metadata nor a later problem list should keep healthy payload buffers alive.
        acquiredBytes = null;
        readerText = null;
        canonicalBody = null;
    }

    void bodyValue(final String value) {
        canonicalBody = value;
    }

    void transferEncoding(final String value) { transferEncoding = value; }
    String transferEncoding() { return transferEncoding; }

    boolean isBodyValue(final String value) {
        return java.util.Objects.equals(canonicalBody, value);
    }

    void deferRepresentation() {
        deferredRepresentation = true;
    }

    void finishRepresentation(final boolean retainOriginal) {
        deferredRepresentation = false;
        if (retainOriginal) {
            retainAcquired();
        }
        represented();
    }

    void unrepresented(final String message) {
        retainAcquired();
        problem(EmailProblem.Code.CONTENT_NOT_REPRESENTED, null, message);
    }

    void invalidTransferEncoding(final MimePart part, final boolean externalReadSelected, final String message) {
        final jakarta.mail.internet.ParseException issue = new jakarta.mail.internet.ParseException(message);
        captureRaw(part, issue, externalReadSelected);
        problem(EmailProblem.Code.CONTENT_NOT_REPRESENTED, null, InputInspection.describe(issue));
    }

    void failed(final MimePart part, final MimeMessageParseException failure) {
        failed(part, failure, true);
    }

    void failed(final MimePart part, final MimeMessageParseException failure, final boolean externalReadSelected) {
        retainAcquired();
        final CapturedMimeContent.Failure captured = CapturedMimeContent.failureIn(failure);
        if (content == null && captured != null) {
            content = EmailInspection.content(captured.getCaptured(), EmailSourceContent.Representation.READER_BYTES, captured.isFullyCaptured());
        }
        if (content != null && content.getRepresentation() == EmailSourceContent.Representation.READER_BYTES) {
            // A decoder prefix must not hide the original encoding when this MIME entity already owns cached wire bytes.
            captureRaw(part, failure, false);
        }
        if (content == null && text == null) {
            captureRaw(part, failure, externalReadSelected);
        }
        final EmailProblem.Code code = InputInspection.causedBy(failure, UnsupportedEncodingException.class)
                || InputInspection.causedBy(failure, CharacterCodingException.class)
                ? EmailProblem.Code.VALUE_NOT_DECODED
                : InputInspection.causedBy(failure, IOException.class) ? EmailProblem.Code.CONTENT_READ_FAILED
                : EmailProblem.Code.CONTENT_NOT_REPRESENTED;
        problem(code, null, InputInspection.describe(failure));
    }

    void problem(final EmailProblem.Code code, final String value, final String message) {
        inspection.recordPart(code, this, value, message);
    }

    /** Retain only bytes already consumed by an optional interpreter; never reopen the borrowed resource for diagnostics. */
    void optionalReadFailed(final byte[] captured, final boolean fullyRead, final Throwable failure) {
        retainAcquired();
        if (content == null && captured != null) {
            content = EmailInspection.content(captured, EmailSourceContent.Representation.READER_BYTES, fullyRead);
        }
        final EmailProblem.Code code = fullyRead ? EmailProblem.Code.RESOURCE_CLOSE_FAILED
                : InputInspection.causedBy(failure, IOException.class) ? EmailProblem.Code.CONTENT_READ_FAILED
                : EmailProblem.Code.CONTENT_NOT_REPRESENTED;
        problem(code, null, InputInspection.describe(failure));
    }

    void headerProblem(final EmailProblem.Code code, final String name, final String value, final String message) {
        inspection.recordHeader(code, this, name, value, message);
    }

    void propertyProblem(final EmailProblem.Code code, final String name, final String value, final String message) {
        final EmailSourceProperty property = EmailInspection.property(inspection.getOrigin(), location + "/properties[" + properties.size() + "]",
                name, "String", value, null);
        properties.add(property);
        inspection.recordProperty(code, property, message);
    }

    EmailSourcePart snapshot() {
        if (snapshot == null) {
            final List<EmailSourcePart> capturedChildren = new ArrayList<>();
            for (final MimePartCapture child : children) {
                capturedChildren.add(child.snapshot());
            }
            snapshot = EmailInspection.part(inspection.getOrigin(), location, headers, properties,
                    capturedChildren, text, content);
        }
        return snapshot;
    }

    EmailSourcePart originalSnapshot() {
        if (originalSnapshot == null) {
            if (content == null && acquiredBytes == null && text == null && readerText == null) {
                return null;
            }
            if (content == null && acquiredBytes != null && originalContent == null) {
                originalContent = EmailInspection.content(acquiredBytes, EmailSourceContent.Representation.READER_BYTES, true);
            }
            originalSnapshot = EmailInspection.part(inspection.getOrigin(), location, headers, properties,
                    Collections.emptyList(), text != null ? text : readerText, content != null ? content : originalContent);
        }
        return originalSnapshot;
    }

    private void retainAcquired() {
        if (content == null && acquiredBytes != null) {
            content = originalContent != null ? originalContent
                    : EmailInspection.content(acquiredBytes, EmailSourceContent.Representation.READER_BYTES, true);
        }
        if (content == null && readerText != null) {
            text = readerText;
        }
        acquiredBytes = null;
        readerText = null;
    }

    /** A raw MIME stream is original evidence; it is not another attempt to decode the failing content. */
    private void captureRaw(final MimePart part, final Throwable originalFailure) {
        captureRaw(part, originalFailure, true);
    }

    private void captureRaw(final MimePart part, final Throwable originalFailure, final boolean externalReadSelected) {
        if (!externalReadSelected && part.getClass() != MimeBodyPart.class && part.getClass() != MimeMessage.class
                && !(part instanceof org.simplejavamail.internal.util.FinalizedMimeMessage)) {
            return;
        }
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        boolean acquired = false;
        boolean fullyRead = false;
        try (InputStream raw = part instanceof MimeBodyPart ? ((MimeBodyPart) part).getRawInputStream()
                : part instanceof MimeMessage ? ((MimeMessage) part).getRawInputStream() : null) {
            if (raw == null) {
                return;
            }
            if (!externalReadSelected && raw.getClass() != ByteArrayInputStream.class
                    && raw.getClass() != jakarta.mail.util.SharedByteArrayInputStream.class) {
                return;
            }
            acquired = true;
            final byte[] buffer = new byte[8192];
            int count;
            while ((count = raw.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            fullyRead = true;
        } catch (final IOException | MessagingException unavailable) {
            rethrowIfInterrupted(unavailable);
            originalFailure.addSuppressed(unavailable);
        }
        if (acquired) {
            content = EmailInspection.content(output.toByteArray(), EmailSourceContent.Representation.SOURCE_BYTES, fullyRead);
        }
    }
}
