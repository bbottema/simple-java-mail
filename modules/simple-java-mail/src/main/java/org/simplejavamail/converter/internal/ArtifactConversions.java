package org.simplejavamail.converter.internal;

import org.simplejavamail.internal.smime.FactorySmimeRuntime;

import jakarta.activation.DataSource;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.simplejavamail.MailException;
import org.simplejavamail.api.converter.EmailConversionResult;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EmailProblem;
import org.simplejavamail.api.email.EmailSourceContent;
import org.simplejavamail.api.email.EmailSourceData;
import org.simplejavamail.api.email.EmailSourceIdentity;
import org.simplejavamail.api.email.EmailSourcePart;
import org.simplejavamail.api.email.EmailSourceProperty;
import org.simplejavamail.api.mailer.config.EmailGovernance;
import org.simplejavamail.api.mailer.MailFailureKind;
import org.simplejavamail.converter.internal.mimemessage.InputInspection;
import org.simplejavamail.converter.internal.mimemessage.MimeMessageProducerHelper;
import org.simplejavamail.email.internal.InternalEmail;
import org.simplejavamail.email.internal.InternalEmailPopulatingBuilder;
import org.simplejavamail.internal.email.EmailInspection;
import org.simplejavamail.internal.util.FinalizedMimeMessage;
import org.simplejavamail.internal.util.InterruptedIoGuard;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;

/** Captures artifact-operation observations without turning immutable input Email history into a mutable operation log. */
final class ArtifactConversions {

    private ArtifactConversions() { }

    static <T> T value(final EmailConversionResult<T> result) {
        if (result.hasValue()) return result.getValue();
        throw result.getFailure();
    }

    static final class Operation {
        private EmailSourceIdentity origin;
        private final List<EmailProblem> problems = new ArrayList<>();
        private final List<EmailSourceData> sources = new ArrayList<>();
        private final Set<MailException> recordedFailures = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<MailException> protectionFailures = Collections.newSetFromMap(new IdentityHashMap<>());

        Operation(final String messageId) {
            origin = EmailInspection.origin(messageId);
        }

        void includeEmail(final Email email, final boolean importedInThisOperation) {
            includeSource(email.getSourceData());
            if (importedInThisOperation) {
                problems.addAll(email.getProblems());
                if (email.getSourceData().getOrigin() != null) origin = email.getSourceData().getOrigin();
            }
        }

        void includeMime(final MimeMessage message) {
            final EmailSourceData source = InputInspection.capture(message).getSourceData();
            if (source.getOrigin() != null) origin = source.getOrigin();
            includeSource(source);
        }

        private void includeSource(final EmailSourceData source) {
            if (source.getOrigin() == null && source.getHeaders().isEmpty() && source.getProperties().isEmpty()
                    && source.getParts().isEmpty() && source.getDerivedSources().isEmpty()) return;
            for (EmailSourceData present : sources) if (present == source) return;
            sources.add(source);
        }

        void noteInputCloseFailure(final IOException failure) {
            InterruptedIoGuard.rethrowIfInterrupted(failure);
            final EmailSourcePart evidence = part("source/stream", emptyList(), null);
            includeSource(EmailInspection.source(origin, EmailSourceData.Format.MIME, EmailSourceData.Representation.RECEIVED,
                    emptyList(), emptyList(), singletonList(evidence), emptyList()));
            problems.add(EmailInspection.problem(origin, EmailProblem.Operation.IMPORT, EmailProblem.Code.RESOURCE_CLOSE_FAILED,
                    evidence.getLocation(), null, failure.getMessage() == null ? "Owned input stream could not be closed" : failure.getMessage(),
                    singletonList(evidence)));
        }

        <T> EmailConversionResult<T> execute(final Supplier<T> conversion) {
            try {
                return new Result<>(conversion.get(), problems, sources, null);
            } catch (final MailException failure) {
                if (!(failure instanceof EmailConverterException) && !InputInspection.isParsingFailure(failure)
                        && !protectionFailures.contains(failure)) throw failure;
                InterruptedIoGuard.rethrowIfInterrupted(failure);
                if (failure instanceof SourceReadException) {
                    final SourceReadException readFailure = (SourceReadException) failure;
                    includeSource(readFailure.getSourceData());
                    problems.add(readFailure.getProblem());
                } else if (recordedFailures.add(failure)) {
                    problems.add(EmailInspection.problem(origin, EmailProblem.Operation.CONVERSION,
                            protectionFailures.contains(failure) ? EmailProblem.Code.PROTECTION_FAILED : EmailProblem.Code.CONVERSION_FAILED,
                            "output", null, failure.getMessage(), emptyList()));
                }
                return new Result<>(null, problems, sources, failure);
            }
        }

        MimeMessage render(final Email input, final Session session, final EmailGovernance governance,
                final Function<Email, EmailPopulatingBuilder> copy, final boolean writingContent, final FactorySmimeRuntime smimeRuntime) {
            try {
                final Email effective = InternalEmail.requireInternalEmail(input).prepareForConversion(governance);
                final InternalEmail internal = InternalEmail.requireInternalEmail(effective);
                final boolean protecting = !internal.isExactEml() && needsContentForProtection(effective);
                // Exact sources already own their authoritative artifact; never rebuild their represented resources.
                final Email prepared = !internal.isExactEml() && (writingContent || protecting)
                        ? stageContent(effective, session, copy) : effective;
                if (!protecting) return InternalEmail.requireInternalEmail(prepared).renderMimeMessage(session, true, smimeRuntime);
                // Metadata and caller-owned getters remain outside the narrowly recognized protection-failure boundary.
                final MimeMessage base = InternalEmail.requireInternalEmail(prepared).renderMimeMessage(session, false, smimeRuntime);
                try {
                    return MimeMessageProducerHelper.applyMessageProtection(prepared, session, base, smimeRuntime);
                } catch (final MailException failure) {
                    if (failure.getKind() == MailFailureKind.PREPARATION) {
                        InterruptedIoGuard.rethrowIfInterrupted(failure);
                        protectionFailures.add(failure);
                    }
                    throw failure;
                } catch (final MessagingException cause) {
                    InterruptedIoGuard.rethrowIfInterrupted(cause);
                    final EmailConverterException failure = new EmailConverterException(EmailConverterException.ERROR_CREATING_MIME_MESSAGE, cause);
                    protectionFailures.add(failure);
                    throw failure;
                }
            } catch (UnsupportedEncodingException | MessagingException failure) {
                InterruptedIoGuard.rethrowIfInterrupted(failure);
                throw new EmailConverterException(EmailConverterException.ERROR_CREATING_MIME_MESSAGE, failure);
            }
        }

        byte[] write(final MimeMessage message) {
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            try {
                message.writeTo(output);
                return output.toByteArray();
            } catch (IOException | MessagingException cause) {
                InterruptedIoGuard.rethrowIfInterrupted(cause);
                final EmailConverterException failure = new EmailConverterException(EmailConverterException.ERROR_WRITING_EML, cause);
                final EmailSourcePart prefix = output.size() == 0 ? null : part("output", emptyList(),
                        EmailInspection.content(output.toByteArray(), EmailSourceContent.Representation.SOURCE_BYTES, false));
                if (prefix != null) includePart(prefix);
                problems.add(EmailInspection.problem(origin, EmailProblem.Operation.CONVERSION,
                        EmailProblem.Code.CONVERSION_FAILED, "output", null, failure.getMessage(),
                        prefix == null ? emptyList() : singletonList(prefix)));
                recordedFailures.add(failure);
                throw failure;
            }
        }

        String writeText(final MimeMessage message) {
            final byte[] bytes = write(message);
            try {
                return UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            } catch (final CharacterCodingException notUtf8Text) {
                // The existing text target uses UTF-8 replacement semantics. Keep the otherwise unavailable original bytes.
                final EmailSourcePart original = part("output/eml-bytes", emptyList(),
                        EmailInspection.content(bytes, EmailSourceContent.Representation.SOURCE_BYTES, true));
                includePart(original);
                problems.add(EmailInspection.problem(origin, EmailProblem.Operation.CONVERSION, EmailProblem.Code.VALUE_NOT_DECODED,
                        "output/eml-text", null, "Serialized EML contains bytes that cannot be decoded as UTF-8; replacement text is available",
                        singletonList(original)));
                return new String(bytes, UTF_8);
            }
        }

        private Email stageContent(final Email effective, final Session session,
                final Function<Email, EmailPopulatingBuilder> copy) {
            if (effective.getAttachments().isEmpty() && effective.getEmbeddedImages().isEmpty()
                    && effective.getEmailToForward() == null) return effective;
            final List<AttachmentResource> attachments = stageResources(effective.getAttachments(), "output/attachments");
            final List<AttachmentResource> images = stageResources(effective.getEmbeddedImages(), "output/embedded-images");
            final EmailPopulatingBuilder builder = copy.apply(effective).clearAttachments().clearEmbeddedImages()
                    .withAttachments(attachments).withEmbeddedImages(images);
            if (effective.getEmailToForward() != null) {
                ((InternalEmailPopulatingBuilder) builder).withForward(stageForward(effective.getEmailToForward(), session));
            }
            final Email prepared = builder.buildEmail();
            InternalEmail.requireInternalEmail(prepared).markAsDefaultsAndOverridesApplied();
            return prepared;
        }

        private List<AttachmentResource> stageResources(final List<AttachmentResource> resources, final String location) {
            final List<AttachmentResource> readable = new ArrayList<>();
            for (int index = 0; index < resources.size(); index++) {
                final AttachmentResource resource = resources.get(index);
                final DataSource source = resource.getDataSource();
                final String type = source.getContentType();
                final String name = source.getName();
                final String encoding = source instanceof jakarta.mail.EncodingAware
                        ? ((jakarta.mail.EncodingAware) source).getEncoding() : null;
                final ByteArrayOutputStream captured = new ByteArrayOutputStream();
                boolean opened = false;
                boolean complete = false;
                try {
                    try (InputStream input = source.getInputStream()) {
                        opened = true;
                        acquire(input, captured);
                        complete = true;
                    }
                    readable.add(resource.toBuilder().withDataSource(
                            new CapturedDataSource(captured.toByteArray(), name, type, encoding)).build());
                } catch (final IOException failure) {
                    InterruptedIoGuard.rethrowIfInterrupted(failure);
                    if (complete) {
                        // Cleanup failed after all bytes were acquired; the content itself remains usable.
                        readable.add(resource.toBuilder().withDataSource(
                                new CapturedDataSource(captured.toByteArray(), name, type, encoding)).build());
                    }
                    final String partLocation = location + "[" + index + "]";
                    final List<EmailSourceProperty> metadata = new ArrayList<>();
                    metadata.add(property(partLocation, "name", resource.getName()));
                    metadata.add(property(partLocation, "content-type", type));
                    metadata.add(property(partLocation, "content-id", resource.getContentId()));
                    metadata.add(property(partLocation, "description", resource.getDescription()));
                    if (resource.getPreEncodedContentTransferEncoding() != null) {
                        metadata.add(property(partLocation, "pre-encoded-content-transfer-encoding",
                                resource.getPreEncodedContentTransferEncoding().getEncoder()));
                    }
                    final EmailSourcePart evidence = part(partLocation, metadata, opened && !complete
                            ? EmailInspection.content(captured.toByteArray(), EmailSourceContent.Representation.READER_BYTES, complete)
                            : null);
                    componentFailure(evidence, failure, complete ? EmailProblem.Code.RESOURCE_CLOSE_FAILED
                            : EmailProblem.Code.CONTENT_READ_FAILED);
                }
            }
            return readable;
        }

        private MimeMessage stageForward(final MimeMessage forward, final Session session) {
            final ByteArrayOutputStream captured = new ByteArrayOutputStream();
            boolean complete = false;
            try {
                forward.writeTo(captured);
                complete = true;
                return FinalizedMimeMessage.fromOwnedBytes(session, captured.toByteArray(),
                        FinalizedMimeMessage.ProtectionState.NONE, true);
            } catch (final IOException | MessagingException failure) {
                InterruptedIoGuard.rethrowIfInterrupted(failure);
                componentFailure(part("output/forward", emptyList(), EmailInspection.content(captured.toByteArray(),
                        EmailSourceContent.Representation.SOURCE_BYTES, complete)), failure);
                return null;
            }
        }

        private void componentFailure(final EmailSourcePart evidence, final Exception failure) {
            componentFailure(evidence, failure, EmailProblem.Code.CONTENT_READ_FAILED);
        }

        private void componentFailure(final EmailSourcePart evidence, final Exception failure, final EmailProblem.Code code) {
            includePart(evidence);
            problems.add(EmailInspection.problem(origin, EmailProblem.Operation.CONVERSION, code,
                    evidence.getLocation(), null, failure.getMessage() == null ? "Selected content could not be acquired" : failure.getMessage(),
                    singletonList(evidence)));
        }

        private EmailSourceProperty property(final String location, final String name, final String value) {
            return EmailInspection.property(origin, location + "/" + name, name, "String", value, null);
        }

        private EmailSourcePart part(final String location, final List<EmailSourceProperty> properties,
                final EmailSourceContent content) {
            return EmailInspection.part(origin, location, emptyList(), properties, emptyList(), null, content);
        }

        private void includePart(final EmailSourcePart part) {
            includeSource(EmailInspection.source(origin, EmailSourceData.Format.MIME, EmailSourceData.Representation.GENERATED,
                    emptyList(), emptyList(), singletonList(part), emptyList()));
        }
    }

    private static boolean needsContentForProtection(final Email email) {
        return email.getDkimConfig() != null || email.getSmimeSigningConfig() != null || email.getSmimeEncryptionConfig() != null
                || email.getOpenPgpSigningConfig() != null || email.getOpenPgpEncryptionConfig() != null
                || email.getRecipients().stream().anyMatch(recipient -> recipient.getSmimeCertificate() != null);
    }

    private static void acquire(final InputStream input, final ByteArrayOutputStream captured) throws IOException {
        final byte[] buffer = new byte[8192];
        for (int count; (count = input.read(buffer)) != -1;) {
            if (count == 0) {
                final int next = input.read();
                if (next == -1) break;
                captured.write(next);
            } else {
                captured.write(buffer, 0, count);
            }
        }
    }

    private static final class Result<T> implements EmailConversionResult<T> {
        private final T value;
        private final List<EmailProblem> problems;
        private final List<EmailSourceData> sources;
        private final MailException failure;

        Result(final T value, final List<EmailProblem> problems, final List<EmailSourceData> sources, final MailException failure) {
            if ((value == null) == (failure == null)) throw new IllegalArgumentException("An artifact result needs a value or a failure");
            this.value = value;
            this.problems = Collections.unmodifiableList(new ArrayList<>(problems));
            this.sources = Collections.unmodifiableList(new ArrayList<>(sources));
            this.failure = failure;
        }

        @Override public boolean hasValue() { return value != null; }
        @Override public T getValue() { return value; }
        @Override public List<EmailProblem> getProblems() { return problems; }
        @Override public List<EmailSourceData> getSources() { return sources; }
        @Override public MailException getFailure() { return failure; }
    }

    /** Owned successful output content, not another diagnostic payload or a live external callback. */
    private static final class CapturedDataSource implements DataSource, jakarta.mail.EncodingAware {
        private final byte[] bytes;
        private final String name;
        private final String type;
        private final String encoding;

        CapturedDataSource(final byte[] bytes, final String name, final String type, final String encoding) {
            this.bytes = bytes;
            this.name = name;
            this.type = type;
            this.encoding = encoding;
        }

        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        @Override public OutputStream getOutputStream() throws IOException { throw new IOException("Captured output content is read-only"); }
        @Override public String getContentType() { return type; }
        @Override public String getName() { return name; }
        @Override public String getEncoding() { return encoding; }
    }
}
