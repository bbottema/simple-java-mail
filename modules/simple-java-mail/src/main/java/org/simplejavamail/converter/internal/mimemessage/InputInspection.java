package org.simplejavamail.converter.internal.mimemessage;

import jakarta.mail.Header;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimePart;
import org.simplejavamail.api.email.*;
import org.simplejavamail.api.outlook.OutlookMessageData;
import org.simplejavamail.internal.email.EmailInspection;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.simplejavamail.internal.util.InterruptedIoGuard.rethrowIfInterrupted;

/** Captures one input's source evidence and observations during its selected conversion path. */
public final class InputInspection {
    private final EmailSourceIdentity origin;
    private final EmailSourceData.Format format;
    private final EmailSourceData.Representation representation;
    private final List<EmailSourceHeader> headers;
    private final List<EmailSourceProperty> properties;
    private final List<PendingProblem> problems = new ArrayList<>();
    private final List<MimePartCapture> parts = new ArrayList<>();
    private final List<EmailSourcePart> originalHtmlParts = new ArrayList<>();
    private boolean contentTypeReadUnavailable;
    private EmailSourceData source;

    private InputInspection(final List<Header> receivedHeaders, final String messageId,
            final EmailSourceData.Format format, final EmailSourceData.Representation representation,
            final OutlookMessageData outlook) {
        this.origin = EmailInspection.origin(messageId);
        this.format = format;
        this.representation = representation;
        this.headers = captureHeaders(receivedHeaders, "source/headers");
        this.properties = captureOutlookProperties(outlook);
        if (format == EmailSourceData.Format.MIME) {
            // The wrapper still has a content entity when the selected path interprets an extracted view instead.
            parts.add(new MimePartCapture(this, "source/parts[0]", headers));
        }
    }

    public static InputInspection capture(final MimeMessage message) {
        return capture(message, false);
    }

    public static InputInspection capture(final MimeMessage message, final boolean generatedArtifact) {
        return capture(message, generatedArtifact, generatedArtifact
                ? EmailSourceData.Representation.GENERATED : EmailSourceData.Representation.RECEIVED);
    }

    /** The generated-ID policy and the provenance of the selected content view are independent. */
    public static InputInspection capture(final MimeMessage message, final boolean generatedArtifact,
            final EmailSourceData.Representation representation) {
        final List<Header> values;
        MimeMessageParseException failure = null;
        List<Header> captured = Collections.emptyList();
        try {
            captured = MimeMessageParser.retrieveAllHeaders(message);
        } catch (final MimeMessageParseException unreadable) {
            rethrowIfInterrupted(unreadable);
            failure = unreadable;
        }
        values = captured;
        String messageId = firstHeader(values, "Message-ID");
        if (generatedArtifact && org.simplejavamail.api.internal.smimesupport.builder.SmimeParseResult.GENERATED_MESSAGE_ID.equals(messageId)) {
            messageId = null;
        }
        final InputInspection inspection = new InputInspection(values, messageId, EmailSourceData.Format.MIME,
                Objects.requireNonNull(representation, "representation"), null);
        if (failure != null) {
            inspection.record(EmailProblem.Code.CONTENT_READ_FAILED, "source/headers", null, describe(failure));
        }
        return inspection;
    }

    public static InputInspection capture(final OutlookMessageData data, final String messageId) {
        final List<Header> headers = new ArrayList<>();
        if (data.getRawHeaders() != null) {
            final Matcher matcher = Pattern.compile("(?m)^([^\\s:\\r\\n][^:\\r\\n]*):[ \\t]*(.*(?:\\r?\\n[ \\t].*)*)").matcher(data.getRawHeaders());
            while (matcher.find()) {
                headers.add(new Header(matcher.group(1), matcher.group(2).replaceFirst("\\r$", "")));
            }
        }
        final Set<String> capturedNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (final Header header : headers) {
            capturedNames.add(header.getName());
        }
        data.getHeaders().forEach((name, values) -> {
            if (name != null && !capturedNames.contains(name) && values != null) {
                values.forEach(value -> headers.add(new Header(name, value)));
            }
        });
        return new InputInspection(headers, messageId, EmailSourceData.Format.OUTLOOK_MSG,
                EmailSourceData.Representation.RECEIVED, data);
    }

    public EmailSourceData getSourceData() {
        if (source == null) {
            final List<EmailSourcePart> captured = new ArrayList<>();
            for (final MimePartCapture part : parts) {
                captured.add(part.snapshot());
            }
            source = EmailInspection.source(origin, format, representation, headers, properties, captured, Collections.emptyList());
        }
        return source;
    }

    public List<Header> headers() {
        final List<Header> values = new ArrayList<>();
        for (final EmailSourceHeader header : headers) {
            values.add(new Header(header.getName(), header.getRawValue()));
        }
        return values;
    }

    public EmailSourceIdentity getOrigin() {
        return origin;
    }

    /** Transient import candidates; the normal source graph retains them only for an actual automatic rewrite. */
    public List<EmailSourcePart> originalHtmlParts() {
        return Collections.unmodifiableList(new ArrayList<>(originalHtmlParts));
    }

    void originalHtml(final MimePartCapture capture) {
        final EmailSourcePart original = capture.originalSnapshot();
        if (original != null) originalHtmlParts.add(original);
    }

    public List<EmailProblem> getProblems() {
        getSourceData();
        final List<EmailProblem> captured = new ArrayList<>();
        for (final PendingProblem problem : problems) {
            final List<? extends EmailSourceElement> affected = problem.part != null
                    ? Collections.singletonList(problem.part.snapshot()) : problem.sources;
            captured.add(EmailInspection.problem(origin, EmailProblem.Operation.IMPORT, problem.code,
                    problem.location, problem.value, problem.message, affected));
        }
        return EmailInspection.problems(captured);
    }

    public String raw(final String field) {
        final List<String> values = values(field);
        return values.isEmpty() ? null : values.get(0);
    }

    public String joined(final String field) {
        final List<String> values = values(field);
        return values.isEmpty() ? null : values.stream().filter(Objects::nonNull).collect(java.util.stream.Collectors.joining(","));
    }

    private List<String> values(final String field) {
        final List<String> values = new ArrayList<>();
        for (final EmailSourceHeader header : headers) {
            if (header.getName().equalsIgnoreCase(field)) {
                values.add(header.getRawValue());
            }
        }
        return values;
    }

    public <T> T recover(final String field, final Supplier<T> read, final Supplier<T> fallback) {
        return recover(field, raw(field), read, fallback);
    }

    public <T> T recover(final String field, final String value, final Supplier<T> read, final Supplier<T> fallback) {
        try {
            return read.get();
        } catch (final MimeMessageParseException failure) {
            rethrowIfInterrupted(failure);
            if (causedBy(failure, UnsupportedEncodingException.class)) {
                record(EmailProblem.Code.VALUE_NOT_DECODED, field, value, describe(failure));
            } else if (recognizedFailure(failure)) {
                record(EmailProblem.Code.CONTENT_READ_FAILED, field, value, describe(failure));
            } else {
                throw failure;
            }
            return fallback.get();
        }
    }

    public static boolean unavailableCharset(final String value) {
        if (value == null || !value.contains("=?")) {
            return false;
        }
        try {
            jakarta.mail.internet.MimeUtility.decodeText(value);
            return false;
        } catch (final UnsupportedEncodingException failure) {
            return DecodedHeader.canDecodeWithAvailableCharsets(value);
        }
    }

    public boolean decodingFailed(final String field, final String value) {
        return problems.stream().anyMatch(problem -> problem.code == EmailProblem.Code.VALUE_NOT_DECODED
                && problem.location.equals(field) && Objects.equals(problem.value, value));
    }

    /** Retains one root declaration-read observation without retrying an unavailable metadata reader. */
    public void noteUnreadableContentType(final Throwable failure) {
        rethrowIfInterrupted(failure);
        if (!contentTypeReadUnavailable) {
            contentTypeReadUnavailable = true;
            record(EmailProblem.Code.CONTENT_READ_FAILED, "Content-Type", raw("Content-Type"), describe(failure));
        }
    }

    boolean isContentTypeReadUnavailable() {
        return contentTypeReadUnavailable;
    }

    public void record(final EmailProblem.Code code, final String field, final String value, final String message) {
        final List<EmailSourceElement> affected = new ArrayList<>();
        for (final EmailSourceHeader header : headers) {
            if (header.getName().equalsIgnoreCase(field) || ("header-name".equals(field) && Objects.equals(header.getName(), value))) {
                affected.add(header);
            }
        }
        problems.add(new PendingProblem(code, field, value, message, affected, null));
    }

    private void recordOccurrence(final EmailProblem.Code code, final String field, final int index,
            final String value, final String message) {
        final List<EmailSourceElement> occurrences = new ArrayList<>();
        for (final EmailSourceHeader header : headers) {
            if (header.getName().equalsIgnoreCase(field)) occurrences.add(header);
        }
        final List<EmailSourceElement> affected = index < occurrences.size()
                ? Collections.singletonList(occurrences.get(index)) : Collections.emptyList();
        problems.add(new PendingProblem(code, field + "[" + index + "]", value, message, affected, null));
    }

    MimePartCapture beginRootPart() {
        source = null;
        parts.clear();
        originalHtmlParts.clear();
        final MimePartCapture root = new MimePartCapture(this, "source/parts[0]", headers);
        parts.add(root);
        return root;
    }

    MimePartCapture beginPart(final MimePart part, final String location) {
        final List<Header> headers;
        try {
            headers = MimeMessageParser.retrieveAllHeaders(part);
        } catch (final MimeMessageParseException failure) {
            rethrowIfInterrupted(failure);
            final MimePartCapture captured = new MimePartCapture(this, location, Collections.emptyList());
            captured.problem(EmailProblem.Code.CONTENT_READ_FAILED, null, describe(failure));
            return captured;
        }
        return new MimePartCapture(this, location, captureHeaders(headers, location + "/headers"));
    }

    void recordPart(final EmailProblem.Code code, final MimePartCapture part, final String value, final String message) {
        problems.add(new PendingProblem(code, part.getLocation(), value, message, Collections.emptyList(), part));
    }

    void recordProperty(final EmailProblem.Code code, final EmailSourceProperty property, final String message) {
        problems.add(new PendingProblem(code, property.getLocation(), property.getValue(), message, Collections.singletonList(property), null));
    }

    void recordHeader(final EmailProblem.Code code, final MimePartCapture part, final String field,
            final String value, final String message) {
        final String location = part.getLocation() + "/" + field;
        if (problems.stream().anyMatch(problem -> problem.code == code && problem.location.equals(location)
                && Objects.equals(problem.value, value))) {
            return;
        }
        final List<EmailSourceElement> affected = new ArrayList<>();
        for (final EmailSourceHeader header : part.getHeaders()) {
            if (header.getName().equalsIgnoreCase(field)) {
                affected.add(header);
            }
        }
        problems.add(new PendingProblem(code, location, value, message, affected, null));
    }

    public void noteUninterpretedDate(final Date date) {
        final String raw = raw("Date");
        if (date == null && raw != null && !raw.trim().isEmpty()) {
            record(EmailProblem.Code.FIELD_NOT_INTERPRETED, "Date", raw, "No unambiguous received date could be established");
        }
    }

    public void noteAddressRecovery() {
        noteAddressRecovery("From", "To", "Cc", "Bcc", "Reply-To", "Disposition-Notification-To", "Return-Receipt-To", "Return-Path");
    }

    /** Observe only the received address fields interpreted by the selected conversion route. */
    public void noteAddressRecovery(final String... fields) {
        for (final String field : fields) {
            final List<String> values = values(field);
            for (int i = 0; i < values.size(); i++) {
                final String value = values.get(i);
                if (value == null || value.trim().isEmpty() || "<>".equals(value.trim())) {
                    continue;
                }
                if (unavailableCharset(value) && problems.stream().noneMatch(problem -> problem.code == EmailProblem.Code.VALUE_NOT_DECODED
                        && (problem.location.equals(field) || problem.location.startsWith(field + "[")))) {
                    recordOccurrence(EmailProblem.Code.VALUE_NOT_DECODED, field, i, value, "The received address text uses an unavailable charset");
                }
                try {
                    final InternetAddress[] parsed = InternetAddress.parseHeader(value, false);
                    if (parsed.length == 0 && !value.replaceAll("\\([^)]*\\)", "").trim().isEmpty()) {
                        recordOccurrence(EmailProblem.Code.FIELD_NOT_INTERPRETED, field, i, value, "No mailbox could be interpreted");
                    }
                } catch (final AddressException malformed) {
                    recordOccurrence(EmailProblem.Code.FIELD_NOT_INTERPRETED, field, i, value, malformed.getMessage());
                }
            }
        }
    }

    /** One selected Sender source can supply both From and Reply-To without becoming two recovery observations. */
    void noteSenderRecovery(final String value, final String message) {
        if (problems.stream().noneMatch(problem -> problem.code == EmailProblem.Code.FIELD_NOT_INTERPRETED
                && problem.location.equals("Sender") && Objects.equals(problem.value, value))) {
            record(EmailProblem.Code.FIELD_NOT_INTERPRETED, "Sender", value, message);
        }
    }

    void noteReportWarnings(final DeliveryStatusReport report, final List<MimePartCapture> affectedParts) {
        if (report != null) {
            final List<EmailSourcePart> affected = new ArrayList<>();
            for (final MimePartCapture part : affectedParts) affected.add(part.snapshot());
            for (int i = 0; i < report.getWarnings().size(); i++) {
                problems.add(new PendingProblem(EmailProblem.Code.INTERPRETATION_WARNING, "delivery-status[" + i + "]",
                        null, report.getWarnings().get(i), affected, null));
            }
        }
    }

    static boolean recognizedFailure(final Throwable failure) {
        rethrowIfInterrupted(failure);
        return failure instanceof MimeMessageParseException
                && (causedBy(failure, IOException.class) || causedBy(failure, jakarta.mail.MessagingException.class));
    }

    /** Classifies this library's parser boundary without exposing its private exception implementation. */
    public static boolean isParsingFailure(final org.simplejavamail.MailException failure) {
        return failure instanceof MimeMessageParseException;
    }

    static boolean causedBy(final Throwable failure, final Class<? extends Throwable> type) {
        final Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }

    static String describe(final Throwable failure) {
        Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
        if (cause instanceof CapturedMimeContent.Failure && cause.getCause() != null) cause = cause.getCause();
        final StringBuilder description = new StringBuilder(failureDescription(cause));
        final Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<Throwable> reported = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && visited.add(current); current = current.getCause()) {
            for (final Throwable suppressed : current.getSuppressed()) {
                final Throwable actual = suppressed instanceof CapturedMimeContent.Failure && suppressed.getCause() != null
                        ? suppressed.getCause() : suppressed;
                if (reported.add(actual)) description.append("; suppressed ").append(failureDescription(actual));
            }
        }
        return description.toString();
    }

    private static String failureDescription(final Throwable failure) {
        return failure.getClass().getSimpleName() + (failure.getMessage() != null ? ": " + failure.getMessage() : "");
    }

    private List<EmailSourceHeader> captureHeaders(final List<Header> received, final String location) {
        final List<EmailSourceHeader> captured = new ArrayList<>();
        for (int index = 0; index < received.size(); index++) {
            final Header header = received.get(index);
            captured.add(EmailInspection.header(origin, location + "[" + index + "]", header.getName(), header.getValue()));
        }
        return Collections.unmodifiableList(captured);
    }

    private List<EmailSourceProperty> captureOutlookProperties(final OutlookMessageData data) {
        if (data == null) {
            return Collections.emptyList();
        }
        final List<EmailSourceProperty> captured = new ArrayList<>();
        addProperty(captured, "messageClass", data.getMessageClass());
        addProperty(captured, "displayTo", data.getDisplayTo());
        addProperty(captured, "displayCc", data.getDisplayCc());
        addProperty(captured, "displayBcc", data.getDisplayBcc());
        addProperty(captured, "date", data.getDate());
        addProperty(captured, "clientSubmitTime", data.getClientSubmitTime());
        addProperty(captured, "creationDate", data.getCreationDate());
        addProperty(captured, "lastModificationDate", data.getLastModificationDate());
        addProperty(captured, "lastModifierName", data.getLastModifierName());
        for (final Integer code : data.getPropertyCodes()) {
            addProperty(captured, "propertyCode", code);
        }
        for (final String value : data.getPropertiesAsHex()) {
            addProperty(captured, "property", value);
        }
        return Collections.unmodifiableList(captured);
    }

    private void addProperty(final List<EmailSourceProperty> properties, final String name, final Object value) {
        if (value != null) {
            final String represented = value instanceof Date ? Long.toString(((Date) value).getTime()) : String.valueOf(value);
            properties.add(EmailInspection.property(origin, "source/properties[" + properties.size() + "]", name,
                    value.getClass().getSimpleName(), represented, null));
        }
    }

    private static String firstHeader(final List<Header> headers, final String name) {
        for (final Header header : headers) {
            if (header.getName().equalsIgnoreCase(name)) {
                return header.getValue();
            }
        }
        return null;
    }

    private static final class PendingProblem {
        private final EmailProblem.Code code;
        private final String location;
        private final String value;
        private final String message;
        private final List<? extends EmailSourceElement> sources;
        private final MimePartCapture part;

        private PendingProblem(final EmailProblem.Code code, final String location, final String value, final String message,
                final List<? extends EmailSourceElement> sources, final MimePartCapture part) {
            this.code = code;
            this.location = location;
            this.value = value;
            this.message = message != null ? message : "Unable to interpret received content";
            this.sources = sources;
            this.part = part;
        }
    }
}
