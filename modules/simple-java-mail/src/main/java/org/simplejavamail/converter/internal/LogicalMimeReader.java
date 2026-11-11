package org.simplejavamail.converter.internal;

import jakarta.activation.DataHandler;
import jakarta.mail.Header;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimePart;
import jakarta.mail.internet.ParseException;
import org.simplejavamail.api.email.*;
import org.simplejavamail.api.email.config.OpenPgpReceiveConfig;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.converter.internal.mimemessage.IncomingPartInterpreter;
import org.simplejavamail.converter.internal.mimemessage.InputInspection;
import org.simplejavamail.converter.internal.mimemessage.MimeDataSource;
import org.simplejavamail.converter.internal.mimemessage.MimeMessageParser;
import org.simplejavamail.converter.internal.mimemessage.ProtectionScope;
import org.simplejavamail.email.internal.InternalEmailPopulatingBuilder;
import org.simplejavamail.internal.converter.ImportTraversal;
import org.simplejavamail.internal.email.DecryptionInspectionImpl;
import org.simplejavamail.internal.email.EmailInspection;
import org.simplejavamail.internal.email.ParsedAttachmentResource;
import org.simplejavamail.internal.email.ProtectionInspectionImpl;
import org.simplejavamail.internal.email.ReceivedEmailInspection;
import org.simplejavamail.internal.email.SignatureObservationImpl;
import org.simplejavamail.internal.email.SignatureInspectionImpl;
import org.simplejavamail.internal.email.ValueInspectionImpl;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.internal.protection.ProtectionLayerResult;
import org.simplejavamail.internal.smime.FactorySmimeRuntime;
import org.simplejavamail.internal.util.FinalizedMimeMessage;
import org.simplejavamail.internal.util.InterruptedIoGuard;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/** Selects one logical received message while keeping independent resources and complete originals separately owned. */
final class LogicalMimeReader {
    private final ConfiguredEmailConverterImpl owner;
    private final EmailStartingBuilder starter;
    private final FactorySmimeRuntime smimeRuntime;
    private final Pkcs12Config smimeKeys;
    private final OpenPgpReceiveConfig pgpKeys;
    private final boolean eager;
    private final List<EmailProblem> layerProblems = new ArrayList<>();
    private final List<EmailSourceData> layerSources = new ArrayList<>();
    private boolean authoredHeadersSelected;
    private boolean generatedInput;

    LogicalMimeReader(final ConfiguredEmailConverterImpl owner, final EmailStartingBuilder starter,
            final FactorySmimeRuntime smimeRuntime, final Pkcs12Config smimeKeys,
            final OpenPgpReceiveConfig pgpKeys, final boolean eager) {
        this.owner = owner; this.starter = starter; this.smimeRuntime = smimeRuntime;
        this.smimeKeys = smimeKeys; this.pgpKeys = pgpKeys; this.eager = eager;
    }

    EmailPopulatingBuilder read(final MimeMessage message, final ImportContext context) {
        return read(message, context, ProtectionScope.plain(), null);
    }

    EmailPopulatingBuilder readGenerated(final MimeMessage message, final ImportContext context) {
        return readGenerated(message, context, ProtectionScope.plain());
    }

    EmailPopulatingBuilder readGenerated(final MimeMessage message, final ImportContext context, final ProtectionScope enclosing) {
        generatedInput = true;
        return read(message, context, enclosing, null);
    }

    List<EmailProblem> getProcessingProblems() { return List.copyOf(layerProblems); }

    /** Exact mail keeps its raw interpreted values; only the actual root's processing facts are observed at build time. */
    EmailPopulatingBuilder readExact(final MimeMessage message) {
        final InputInspection capture = InputInspection.capture(message);
        final MimeMessageParser.ParsedMimeMessageComponents parsed = MimeMessageParser.parseMimeMessage(message, true, capture);
        final InternalEmailPopulatingBuilder builder = (InternalEmailPopulatingBuilder) MimeMessageEmailMapper.populate(starter.startingBlank(), parsed);
        ProtectionScope scope = ProtectionScope.plain();
        boolean authoredUnavailable = false;
        try {
            if (recognizedFamily(message) != null) {
                final ProtectionLayerResult layer = openLayer(message);
                authoredUnavailable = layer.isAuthoredHeaders() && !layer.isOpened();
                final EmailSourcePart source = parsed.getSourceData().getParts().get(0);
                scope = scope.withLayer(layer, source);
                noteLayer(layer, source);
            } else if (message.isMimeType("multipart/signed") || message.isMimeType("multipart/encrypted")) {
                scope = scope.incomplete();
            }
        } catch (final MessagingException unavailable) {
            InterruptedIoGuard.rethrowIfInterrupted(unavailable);
            scope = scope.incomplete();
            note(parsed.getSourceData().getParts().get(0), EmailProblem.Code.FIELD_NOT_INTERPRETED, "The input protection declaration could not be classified");
        }
        final List<ValueInspection> attachments = new ArrayList<>();
        final List<AttachmentResource> originals = new ArrayList<>(builder.getAttachments());
        for (int index = 0; index < originals.size(); index++) {
            originals.set(index, withSourceContentId(originals.get(index), parsed, index));
            final ValueInspection value = deferredAttachment(scope, parsed.getAttachmentSource(index));
            if (parsed.isRootRepresentedAsResource() && !value.getSignatures().isEmpty()) {
                final List<SignatureInspection> partial = new ArrayList<>();
                value.getSignatures().forEach(signature -> partial.add(SignatureInspectionImpl.builder().observation(signature.getObservation()).coverage(ProtectionCoverage.PARTIAL).build()));
                attachments.add(((ValueInspectionImpl) value).toBuilder().signatures(partial).build());
            } else attachments.add(value);
        }
        final List<ValueInspection> images = new ArrayList<>();
        for (int index = 0; index < builder.getEmbeddedImages().size(); index++) {
            images.add(scope.inspect(parsed.getEmbeddedImageInspection(index).getSources(), ValueAvailability.PRESENT));
        }
        final List<EmailProblem> problems = new ArrayList<>(builder.getProblems()); problems.addAll(layerProblems);
        builder.retainInspection(builder.getSourceData(), problems);
        builder.retainSelectionInspection(ReceivedEmailInspection.builder()
                .plainText(scope.inspect(parsed.getPlainInspection().getSources(), parsed.getPlainInspection().getAvailability()))
                .htmlText(scope.inspect(parsed.getHtmlInspection().getSources(), parsed.getHtmlInspection().getAvailability()))
                .calendar(scope.inspect(parsed.getCalendarInspection().getSources(), parsed.getCalendarInspection().getAvailability()))
                .subject(parsed.getHeaderInspection("Subject", builder.getSubject())).fromRecipient(parsed.getHeaderInspection("From", builder.getFromRecipient()))
                .toRecipients(parsed.getHeaderInspection("To", parsed.getToAddresses())).ccRecipients(parsed.getHeaderInspection("Cc", parsed.getCcAddresses()))
                .bccRecipients(parsed.getHeaderInspection("Bcc", parsed.getBccAddresses())).replyToRecipients(parsed.getHeaderInspection("Reply-To", builder.getReplyToRecipients()))
                .bounceToRecipient(parsed.getHeaderInspection("Return-Path", builder.getBounceToRecipient()))
                .id(parsed.getHeaderInspection("Message-ID", builder.getId())).sentDate(parsed.getHeaderInspection("Date", builder.getSentDate()))
                .attachments(attachments).embeddedImages(images).originalAttachments(originals)
                .originalMessage(message instanceof FinalizedMimeMessage ? ((FinalizedMimeMessage) message).retainedInputResource() : null)
                .originalMessageProtection(message instanceof FinalizedMimeMessage ? org.simplejavamail.internal.email.OriginalMessageProtection.AS_RECEIVED
                        : org.simplejavamail.internal.email.OriginalMessageProtection.UNKNOWN)
                .authoredHeaderViewUnavailable(authoredUnavailable)
                .smime(family(ProtectionFamily.SMIME, List.of(scope), !capture.hasUnvisitedStructure()))
                .openPgp(family(ProtectionFamily.OPENPGP, List.of(scope), !capture.hasUnvisitedStructure())).build());
        return builder;
    }

    private EmailPopulatingBuilder read(final MimeMessage message, final ImportContext context,
            final ProtectionScope enclosing, final AttachmentResource suppliedOriginal) {
        return read(message, context, enclosing, suppliedOriginal, org.simplejavamail.internal.email.OriginalMessageProtection.AS_RECEIVED);
    }

    private EmailPopulatingBuilder read(final MimeMessage message, final ImportContext context,
            final ProtectionScope enclosing, final AttachmentResource suppliedOriginal,
            final org.simplejavamail.internal.email.OriginalMessageProtection originalProtection) {
        final InputInspection suppliedCapture = InputInspection.capture(message, generatedInput).withImportTraversal(context.traversal);
        final MimeMessage readable = pendingProtection(message, suppliedCapture);
        final InputInspection capture = readable == message ? suppliedCapture
                : InputInspection.capture(readable, true, EmailSourceData.Representation.GENERATED).withImportTraversal(context.traversal);
        final MimeMessageParser.ParsedMimeMessageComponents parsed = MimeMessageParser.parseMimeMessage(readable, eager, capture,
                this::openPart, enclosing);
        if ((readable != message || suppliedCapture.hasStoppedContent()) && !authoredHeadersSelected) parsed.useSuppliedOuterHeaders(suppliedCapture);
        context.noteProjection(capture.hasReadableBodyMaterialized(), capture.hasImportLimitStop());
        final InternalEmailPopulatingBuilder builder = (InternalEmailPopulatingBuilder) MimeMessageEmailMapper.populate(starter.startingBlank(), parsed);
        final List<AttachmentResource> incoming = builder.getAttachments();
        final List<AttachmentResource> selected = new ArrayList<>();
        final List<AttachmentResource> originals = new ArrayList<>();
        final List<ValueInspection> attachments = new ArrayList<>();
        final List<ProtectionScope> attachmentScopes = new ArrayList<>();
        final List<EmailProblem> problems = new ArrayList<>(parsed.getProblems());
        final List<EmailSourceData> children = new ArrayList<>();
        for (int index = 0; index < incoming.size(); index++) {
            final EmailSourcePart sourcePart = parsed.getAttachmentSource(index);
            final AttachmentResource original = eager ? withSourceContentId(incoming.get(index), parsed, index) : null;
            final SelectedAttachment attachment = eager || selectsProtectedRead(sourcePart)
                    ? readAttachment(incoming.get(index), original, parsed.getAttachmentProtection(index), sourcePart,
                            context.at(parsed.getAttachmentTraversal(index)))
                    : deferredResource(incoming.get(index), parsed.getAttachmentProtection(index), sourcePart);
            attachmentScopes.add(attachment.scope);
            if (attachment.resource == null) continue; // An actual read prefix is diagnostic evidence, never a normal resource.
            selected.add(attachment.resource);
            originals.add(!eager && attachment.original != null ? withSourceContentId(attachment.original, parsed, index) : attachment.original);
            attachments.add(attachment.inspection);
            if (attachment.resource.getEmail() != null) {
                final Email child = attachment.resource.getEmail();
                children.add(child.getSourceData());
                for (final EmailProblem problem : child.getProblems()) {
                    problems.add(EmailInspection.qualify(problem, parsed.getAttachmentSource(index).getLocation() + "/message/"));
                }
            }
        }
        builder.clearAttachments().withAttachments(selected);
        final List<ValueInspection> images = new ArrayList<>();
        for (int index = 0; index < builder.getEmbeddedImages().size(); index++) images.add(parsed.getEmbeddedImageInspection(index));
        problems.addAll(layerProblems);
        EmailSourceData source = parsed.getSourceData();
        final List<EmailSourceData> derived = new ArrayList<>(source.getDerivedSources());
        derived.addAll(layerSources); derived.addAll(children);
        source = EmailInspection.withDerivedSources(source, derived);
        if (readable != message) {
            final List<EmailSourceData> generated = new ArrayList<>(suppliedCapture.getSourceData().getDerivedSources()); generated.add(source);
            source = EmailInspection.withDerivedSources(suppliedCapture.getSourceData(), generated);
            problems.addAll(suppliedCapture.getProblems());
        }
        builder.retainInspection(source, problems);
        builder.retainConfidentialHeaderNames(parsed.getConfidentialHeaderNames());
        final AttachmentResource original = suppliedOriginal != null ? suppliedOriginal
                : context.rawSource && message instanceof FinalizedMimeMessage ? ((FinalizedMimeMessage) message).retainedInputResource() : null;
        final List<ProtectionScope> scopes = new ArrayList<>(parsed.getProtectionScopes());
        scopes.add(enclosing);
        scopes.addAll(attachmentScopes);
        builder.retainSelectionInspection(ReceivedEmailInspection.builder()
                .plainText(parsed.getPlainInspection()).htmlText(parsed.getHtmlInspection()).subject(parsed.getHeaderInspection("Subject", builder.getSubject()))
                .fromRecipient(parsed.getHeaderInspection("From", builder.getFromRecipient()))
                .toRecipients(parsed.getHeaderInspection("To", parsed.getToAddresses())).ccRecipients(parsed.getHeaderInspection("Cc", parsed.getCcAddresses()))
                .bccRecipients(parsed.getHeaderInspection("Bcc", parsed.getBccAddresses()))
                .replyToRecipients(parsed.getHeaderInspection("Reply-To", builder.getReplyToRecipients()))
                .bounceToRecipient(parsed.getHeaderInspection("Return-Path", builder.getBounceToRecipient()))
                .id(parsed.getHeaderInspection("Message-ID", builder.getId())).sentDate(parsed.getHeaderInspection("Date", builder.getSentDate()))
                .calendar(parsed.getCalendarInspection()).attachments(attachments).embeddedImages(images)
                .originalAttachments(originals).originalMessage(original)
                .authoredHeaderViewUnavailable(parsed.isAuthoredHeaderViewUnavailable())
                .smime(family(ProtectionFamily.SMIME, scopes, !capture.hasUnvisitedStructure()))
                .openPgp(family(ProtectionFamily.OPENPGP, scopes, !capture.hasUnvisitedStructure())).build());
        builder.retainOriginalMessageProtection(original == null ? org.simplejavamail.internal.email.OriginalMessageProtection.UNKNOWN : originalProtection);
        if (suppliedOriginal == null && suppliedCapture.raw("Subject") != null && enclosing.getOpenedDepth() == 0) {
            builder.retainPublicSubject(parsed.getNativeSubject());
        }
        return builder;
    }

    /** Finalization is necessary only for native protected content whose physical MIME declaration is still pending. */
    private MimeMessage pendingProtection(final MimeMessage supplied, final InputInspection capture) {
        try {
            final String[] declarations = supplied.getHeader("Content-Type");
            if (declarations != null && declarations.length > 0) return supplied;
            final ContentType pending = new ContentType(supplied.getDataHandler().getContentType());
            if (pending.match("multipart/signed") || pending.match("multipart/encrypted")
                    || pending.match("application/pkcs7-mime") || pending.match("application/x-pkcs7-mime")) {
                try {
                    return FinalizedMimeMessage.finalizeMessage(supplied, FinalizedMimeMessage.ProtectionState.NONE);
                } catch (final MessagingException failure) {
                    InterruptedIoGuard.rethrowIfInterrupted(failure);
                    capture.withoutContent(EmailProblem.Code.CONTENT_READ_FAILED, "The pending protected MIME representation could not be acquired: " + failure.getClass().getSimpleName());
                    return supplied;
                }
            }
            return supplied;
        } catch (final MessagingException unavailable) {
            InterruptedIoGuard.rethrowIfInterrupted(unavailable);
            // The normal source-aware parser retains this metadata/read limitation without another serialization attempt.
            return supplied;
        }
    }

    private IncomingPartInterpreter.PartSelection openPart(final MimePart original, final ProtectionScope enclosing,
            final EmailSourcePart source, final ImportTraversal traversal, final boolean messageBody) {
        try {
            final ProtectionFamily declaredFamily;
            final boolean protectedContainer;
            try {
                declaredFamily = recognizedFamily(original);
                protectedContainer = declaredFamily != null || original.isMimeType("multipart/signed") || original.isMimeType("multipart/encrypted");
            } catch (final MessagingException metadataFailure) {
                InterruptedIoGuard.rethrowIfInterrupted(metadataFailure);
                note(source, EmailProblem.Code.FIELD_NOT_INTERPRETED, "The input protection declaration could not be classified");
                return IncomingPartInterpreter.PartSelection.builder().content(original).protection(enclosing.incomplete()).build();
            }
            if (!messageBody || !protectedContainer) {
                return IncomingPartInterpreter.PartSelection.builder().content(original).protection(enclosing).protectedContainer(protectedContainer).build();
            }
            if (declaredFamily == null) {
                return IncomingPartInterpreter.PartSelection.builder().content(original).protection(enclosing.incomplete()).protectedContainer(true).build();
            }
            MimePart selected = original;
            MimeMessage message = null;
            MimeMessage authored = null;
            boolean authoredUnavailable = false;
            ProtectionScope authoredProtection = enclosing;
            ProtectionScope protection = enclosing;
            int layerIndex = 0;
            ImportTraversal nestedScope = traversal;
            ProtectionFamily family = declaredFamily;
            while (family != null) {
                final ImportTraversal layerScope = layerIndex == 0 ? nestedScope.view("protection") : nestedScope.child("protection");
                final boolean implementationPresent = family == ProtectionFamily.SMIME ? ModuleLoader.smimeModuleAvailable() : ModuleLoader.openPgpModuleAvailable();
                if (implementationPresent && !layerScope.admit()) {
                    note(source, EmailProblem.Code.IMPORT_LIMIT_EXCEEDED, layerScope.stopDescription());
                    protection = deferPart(protection, selected, family, source);
                    break;
                }
                if (implementationPresent) nestedScope = layerScope;
                message = selected instanceof MimeMessage ? (MimeMessage) selected : asMessage(selected);
                final ProtectionLayerResult layer = openLayer(message);
                if (!layer.isRecognized()) {
                    if (!layer.isInspectionComplete()) protection = protection.incomplete();
                    noteLayer(layer, source);
                    break;
                }
                final EmailSourcePart layerSource;
                if (layerIndex == 0) layerSource = source;
                else {
                    final EmailSourceData captured = InputInspection.capture(message, false, EmailSourceData.Representation.EXTRACTED).getSourceData();
                    layerSources.add(captured);
                    layerSource = captured.getParts().isEmpty() ? source : captured.getParts().get(0);
                }
                protection = protection.withLayer(layer, layerSource);
                authoredUnavailable |= layer.isAuthoredHeaders() && !layer.isOpened();
                final IOException readFailure = layer.getInputReadFailure();
                final IOException closeFailure = layer.getInputCloseFailure();
                noteLayer(layer, layerSource, !layer.isOpened() && (readFailure != null || closeFailure != null));
                if (!layer.isOpened() && (readFailure != null || closeFailure != null)) {
                    final Exception captured = layer.getProcessingFailure() instanceof Exception ? (Exception) layer.getProcessingFailure()
                            : readFailure != null ? readFailure : closeFailure;
                    return IncomingPartInterpreter.PartSelection.builder().content(selected).protection(protection).authoredHeaders(authored)
                            .authoredProtection(authoredProtection).protectedContainer(true).authoredHeaderViewUnavailable(authoredUnavailable)
                            .inputFailure(captured).contentTraversal(nestedScope).build();
                }
                if (!layer.isOpened()) break;
                selected = layer.getEffectiveMimeMessage();
                if (layer.isAuthoredHeaders()) {
                    authoredHeadersSelected = true;
                    authored = layer.getEffectiveMimeMessage();
                    authoredProtection = protection;
                }
                layerIndex++;
                try {
                    family = recognizedFamily(selected);
                } catch (final MessagingException innerDeclarationFailure) {
                    InterruptedIoGuard.rethrowIfInterrupted(innerDeclarationFailure);
                    note(layerSource, EmailProblem.Code.FIELD_NOT_INTERPRETED, "The opened content's protection declaration could not be classified");
                    protection = protection.incomplete();
                    break;
                }
            }
            return IncomingPartInterpreter.PartSelection.builder().content(selected).protection(protection).authoredHeaders(authored)
                    .authoredProtection(authoredProtection).protectedContainer(true).authoredHeaderViewUnavailable(authoredUnavailable)
                    .contentTraversal(nestedScope).build();
        } catch (final MessagingException | IOException failure) {
            InterruptedIoGuard.rethrowIfInterrupted(failure);
            throw MimeMessageParser.selectedContentFailure(failure);
        }
    }

    SelectedAttachment readAttachment(final AttachmentResource resource, final AttachmentResource original,
            final ProtectionScope enclosing, final EmailSourcePart source, final ImportContext context) {
        ProtectionScope protection = enclosing;
        AttachmentResource retainedOriginal = original;
        try {
            if (!context.traversal.admit()) {
                note(source, EmailProblem.Code.IMPORT_LIMIT_EXCEEDED, context.traversal.stopDescription());
                final ProtectionScope uninspected = enclosing.incomplete();
                return new SelectedAttachment(resource, original, uninspected.inspect(List.of(source), ValueAvailability.UNINSPECTED), uninspected);
            }
            if (resource.getEmail() != null) {
                final ValueInspection contained = enclosing.inspect(List.of(source), ValueAvailability.PRESENT);
                final boolean generatedNativeMessage = source.getProperties().stream().anyMatch(value -> value.getName().equals("kind") && "embedded-message".equals(value.getValue()));
                return new SelectedAttachment(resource, original, generatedNativeMessage ? MaterializedMessageInspection.describe(resource.getEmail(), contained) : contained, enclosing);
            }
            try {
                sourceContentType(source);
            } catch (final ParseException malformedDeclaration) {
                // The payload may be readable after MIME metadata recovery, but its generated type cannot establish protection.
                final ProtectionScope unclassified = enclosing.incomplete();
                return new SelectedAttachment(resource, original, unclassified.inspect(List.of(source), ValueAvailability.PRESENT), unclassified);
            }
            if (retainedOriginal == null && !eager) {
                retainedOriginal = acquireProtectedResource(resource, source);
                if (retainedOriginal == null) {
                    final ProtectionScope unavailable = enclosing.incomplete();
                    return new SelectedAttachment(null, null, unavailable.inspect(List.of(source), ValueAvailability.UNAVAILABLE), unavailable);
                }
            }
            AttachmentResource selected = original == null && retainedOriginal != null ? retainedOriginal : resource;
            org.simplejavamail.internal.email.OriginalMessageProtection originalProtection = enclosing.getDecryptions().stream()
                    .anyMatch(value -> value.getStatus() == DecryptionStatus.DECRYPTED)
                    ? org.simplejavamail.internal.email.OriginalMessageProtection.CONFIDENTIAL : org.simplejavamail.internal.email.OriginalMessageProtection.AS_RECEIVED;
            MimePart entity = attachmentEntity(selected);
            boolean messagePayload = isMessage(entity);
            ImportTraversal nestedScope = context.traversal;
            int layerIndex = 0;
            while (!messagePayload && recognizedFamily(entity) != null) {
                final ImportTraversal layerScope = layerIndex == 0 ? nestedScope.view("protection") : nestedScope.child("protection");
                if (!layerScope.admit()) {
                    note(source, EmailProblem.Code.IMPORT_LIMIT_EXCEEDED, layerScope.stopDescription());
                    protection = deferPart(protection, entity, recognizedFamily(entity), source);
                    break;
                }
                nestedScope = layerScope;
                final ProtectionLayerResult layer = openLayer(asMessage(entity));
                if (!layer.isRecognized()) {
                    if (!layer.isInspectionComplete()) protection = protection.incomplete();
                    noteLayer(layer, source);
                    break;
                }
                protection = protection.withLayer(layer, source);
                if (layerIndex == 0 && layer.isOpened() && layer.getDecryptions().stream().anyMatch(value -> value.getStatus() == DecryptionStatus.DECRYPTED)) {
                    originalProtection = org.simplejavamail.internal.email.OriginalMessageProtection.INTACT_PROTECTED;
                }
                noteLayer(layer, source);
                if (!layer.isOpened() || layer.getOpenedEntity() == null) break;
                entity = layer.getOpenedEntity();
                selected = resourceFromEntity(entity, resource);
                messagePayload = isMessage(entity);
                layerIndex++;
            }
            if (messagePayload) {
                final byte[] bytes = selected.readAllBytes();
                final MimeMessage childMessage = FinalizedMimeMessage.fromOwnedBytes(session(), bytes, FinalizedMimeMessage.ProtectionState.NONE, false);
                final LogicalMimeReader childReader = new LogicalMimeReader(owner, starter, smimeRuntime, smimeKeys, pgpKeys, eager);
                final Email child = childReader.read(childMessage, context.at(context.traversal.child("message")), protection, retainedOriginal, originalProtection).buildEmail();
                selected = ParsedAttachmentResource.withEmail(selected, child);
            } else if (selected.getEmail() == null && isNativeMessage(entity) && ModuleLoader.outlookModuleAvailable()) {
                final Email child = owner.outlookMsgToEmailBuilderWithOutlookData(new ByteArrayInputStream(selected.readAllBytes()), smimeKeys,
                        context.rawAt(context.traversal.child("message")), protection, retainedOriginal, pgpKeys, originalProtection).buildEmail();
                selected = ParsedAttachmentResource.withEmail(selected, child);
            }
            return new SelectedAttachment(selected, retainedOriginal, protection.inspect(List.of(source), ValueAvailability.PRESENT), protection);
        } catch (final IOException | MessagingException failure) {
            InterruptedIoGuard.rethrowIfInterrupted(failure);
            note(source, EmailProblem.Code.CONTENT_READ_FAILED, failure.getMessage() == null ? "The selected attachment could not be read" : failure.getMessage());
            final ProtectionScope unavailable = protection.incomplete();
            return new SelectedAttachment(original == null && retainedOriginal != null ? retainedOriginal : resource, retainedOriginal,
                    unavailable.inspect(List.of(source), ValueAvailability.PRESENT), unavailable);
        }
    }

    private ProtectionLayerResult openLayer(final MimeMessage message) throws MessagingException {
        final ProtectionFamily family = recognizedFamily(message);
        if (family == ProtectionFamily.SMIME && ModuleLoader.smimeModuleAvailable()) {
            return (smimeKeys == null ? ModuleLoader.loadSmimeModule() : smimeRuntime.module()).openIncomingLayer(session(), message, smimeKeys);
        }
        if (family == ProtectionFamily.OPENPGP && (ModuleLoader.openPgpModuleAvailable() || pgpKeys != null)) {
            return ModuleLoader.loadOpenPgpModule().openIncomingLayer(session(), message, pgpKeys);
        }
        final ContentType declaration = new ContentType(message.getContentType());
        final boolean encrypted = message.isMimeType("multipart/encrypted")
                || "enveloped-data".equalsIgnoreCase(declaration.getParameter("smime-type"));
        final boolean signed = family != null && (message.isMimeType("multipart/signed")
                || family == ProtectionFamily.SMIME && "signed-data".equalsIgnoreCase(declaration.getParameter("smime-type")));
        final String reason = family + " processing is unavailable because its optional module is not present";
        return ProtectionLayerResult.builder().recognized(family != null).effectiveMimeMessage(message).inspectionComplete(false).failureReason(reason)
                .signatureEnvelope(signed)
                .decryptions(encrypted && family != null ? List.of(DecryptionInspectionImpl.builder().family(family).status(DecryptionStatus.UNAVAILABLE)
                        .coverage(ProtectionCoverage.UNKNOWN).failureReason(reason).build()) : List.of()).build();
    }

    private void noteLayer(final ProtectionLayerResult layer, final EmailSourcePart source) {
        noteLayer(layer, source, false);
    }

    private void noteLayer(final ProtectionLayerResult layer, final EmailSourcePart source, final boolean parserCapturesReadFailure) {
        final IOException inputRead = layer.getInputReadFailure();
        final IOException inputClose = layer.getInputCloseFailure();
        final boolean acquisitionFailure = inputRead != null || inputClose != null;
        boolean recorded = false;
        for (final SignatureObservation signature : layer.getSignatures()) {
            if (signature.getStatus() == SignatureVerification.INVALID) { note(source, EmailProblem.Code.SIGNATURE_INVALID, "The received signature is invalid"); recorded = true; }
            else if (signature.getFailureReason() != null && !acquisitionFailure) { note(source, EmailProblem.Code.PROTECTION_FAILED, signature.getFailureReason()); recorded = true; }
        }
        for (final DecryptionInspection decryption : layer.getDecryptions()) {
            if (!acquisitionFailure && (decryption.getStatus() == DecryptionStatus.FAILED || decryption.getStatus() == DecryptionStatus.UNAVAILABLE)) {
                note(source, EmailProblem.Code.PROTECTION_FAILED, decryption.getFailureReason() == null
                        ? "The protected content could not be opened with the available credentials" : decryption.getFailureReason());
                recorded = true;
            }
        }
        if (!recorded && layer.getFailureReason() != null && !acquisitionFailure) note(source, EmailProblem.Code.PROTECTION_FAILED, layer.getFailureReason());
        if (!parserCapturesReadFailure && inputRead != null) note(source, EmailProblem.Code.CONTENT_READ_FAILED,
                inputRead.getMessage() == null ? "The selected protection input could not be read" : inputRead.getMessage());
        if (!parserCapturesReadFailure && inputClose != null) note(source, EmailProblem.Code.RESOURCE_CLOSE_FAILED,
                inputClose.getMessage() == null ? "The selected protection input could not be closed" : inputClose.getMessage());
        final Throwable processing = layer.getProcessingFailure();
        if (processing != null) {
            InterruptedIoGuard.rethrowIfInterrupted(processing);
        }
    }

    private void note(final EmailSourcePart source, final EmailProblem.Code code, final String message) {
        layerProblems.add(EmailInspection.problem(source.getOrigin(), EmailProblem.Operation.IMPORT, code, source.getLocation(), null, message, List.of(source)));
    }

    static ProtectionFamily recognizedFamily(final MimePart part) throws MessagingException {
        final boolean pkcs7 = part.isMimeType("application/pkcs7-mime") || part.isMimeType("application/x-pkcs7-mime");
        final boolean signed = !pkcs7 && part.isMimeType("multipart/signed");
        final boolean encrypted = !pkcs7 && !signed && part.isMimeType("multipart/encrypted");
        // A provider can recognize the base media type even when its parameters are malformed.
        final ContentType type = new ContentType(part.getContentType());
        if (pkcs7) return ProtectionFamily.SMIME;
        if (signed || encrypted) {
            final String protocol = type.getParameter("protocol");
            if (signed && ("application/pkcs7-signature".equalsIgnoreCase(protocol) || "application/x-pkcs7-signature".equalsIgnoreCase(protocol))) return ProtectionFamily.SMIME;
            if (signed && "application/pgp-signature".equalsIgnoreCase(protocol)
                    || encrypted && "application/pgp-encrypted".equalsIgnoreCase(protocol)) return ProtectionFamily.OPENPGP;
            if (signed && protocol == null) return ProtectionFamily.SMIME;
        }
        return null;
    }

    static ProtectionInspection family(final ProtectionFamily family, final List<ProtectionScope> scopes, final boolean visited) {
        return family(family, scopes, visited, null);
    }

    static ProtectionInspection family(final ProtectionFamily family, final List<ProtectionScope> scopes, final boolean visited,
            final ProtectionInspection historical) {
        final Map<UUID, SignatureObservation> signatures = new LinkedHashMap<>();
        final Map<UUID, DecryptionInspection> decryptions = new LinkedHashMap<>();
        final List<EmailSourceElement> sources = new ArrayList<>();
        boolean complete = visited;
        if (historical != null) {
            complete &= historical.isInspectionComplete();
            historical.getSignatures().forEach(value -> signatures.put(value.getReference(), value));
            historical.getDecryptions().forEach(value -> decryptions.put(value.getReference(), value));
        }
        for (final ProtectionScope scope : scopes) {
            complete &= scope.isComplete();
            for (final SignatureObservation value : scope.getSignatures()) if (value.getFamily() == family) signatures.put(value.getReference(), value);
            for (final DecryptionInspection value : scope.getDecryptions()) if (value.getFamily() == family) decryptions.put(value.getReference(), value);
        }
        signatures.values().forEach(value -> sources.addAll(value.getSources()));
        decryptions.values().forEach(value -> sources.addAll(value.getSources()));
        return ProtectionInspectionImpl.builder().family(family).inspectionComplete(complete).signatures(new ArrayList<>(signatures.values()))
                .decryptions(new ArrayList<>(decryptions.values())).sources(sources).build();
    }

    private static AttachmentResource withSourceContentId(final AttachmentResource selected,
            final MimeMessageParser.ParsedMimeMessageComponents parsed, final int index) {
        final MimeDataSource source = parsed.getAttachmentList().get(index);
        return parsed.hasConflictingContentId(source) ? selected.toBuilder().withContentId(source.getContentId()).build() : selected;
    }

    private static ProtectionScope deferPart(final ProtectionScope enclosing, final MimePart part, final ProtectionFamily family,
            final EmailSourcePart source) throws MessagingException {
        if (family == null) return enclosing.incomplete();
        if (part.isMimeType("multipart/signed")) return enclosing.deferredSignature();
        if (part.isMimeType("multipart/encrypted")) return enclosing.deferredDecryption(family, source);
        return family == ProtectionFamily.SMIME ? deferOpaqueSmime(enclosing, new ContentType(part.getContentType()), source) : enclosing.incomplete();
    }

    /** Uses declarations already captured by the MIME reader; deferred content is never opened to invent inspection facts. */
    private static ValueInspection deferredAttachment(final ProtectionScope enclosing, final EmailSourcePart source) {
        return deferredScope(enclosing, source).inspect(List.of(source), ValueAvailability.PRESENT);
    }

    private static SelectedAttachment deferredResource(final AttachmentResource resource, final ProtectionScope enclosing, final EmailSourcePart source) {
        final ProtectionScope scope = deferredScope(enclosing, source);
        return new SelectedAttachment(resource, null, scope.inspect(List.of(source), ValueAvailability.PRESENT), scope);
    }

    private static ProtectionScope deferredScope(final ProtectionScope enclosing, final EmailSourcePart source) {
        try {
            final ContentType type = sourceContentType(source);
            if (type == null) return enclosing.incomplete();
            final String protocol = type.getParameter("protocol");
            if (type.match("application/pkcs7-mime") || type.match("application/x-pkcs7-mime")) {
                return deferOpaqueSmime(enclosing, type, source);
            }
            if (type.match("multipart/signed")) {
                return protocol == null || "application/pkcs7-signature".equalsIgnoreCase(protocol)
                        || "application/x-pkcs7-signature".equalsIgnoreCase(protocol) || "application/pgp-signature".equalsIgnoreCase(protocol)
                        ? enclosing.deferredSignature() : enclosing.incomplete();
            }
            if (type.match("multipart/encrypted")) {
                return "application/pgp-encrypted".equalsIgnoreCase(protocol)
                        ? enclosing.deferredDecryption(ProtectionFamily.OPENPGP, source) : enclosing.incomplete();
            }
            if (type.match("message/rfc822") || type.match("message/global") || type.match("application/vnd.ms-outlook")) {
                return enclosing.incomplete();
            }
            return enclosing;
        } catch (final ParseException unavailable) {
            return enclosing.incomplete();
        }
    }

    private static ProtectionScope deferOpaqueSmime(final ProtectionScope enclosing, final ContentType type, final EmailSourcePart source) {
        final String smimeType = type.getParameter("smime-type");
        if ("signed-data".equalsIgnoreCase(smimeType)) return enclosing.deferredSignature();
        if ("enveloped-data".equalsIgnoreCase(smimeType)) return enclosing.deferredDecryption(ProtectionFamily.SMIME, source);
        return enclosing.incomplete();
    }

    private static ContentType sourceContentType(final EmailSourcePart source) throws ParseException {
        for (final EmailSourceHeader header : source.getHeaders()) {
            if (header.getName().equalsIgnoreCase("Content-Type")) {
                return header.getRawValue() == null ? null : new ContentType(header.getRawValue());
            }
        }
        return null;
    }

    private static boolean selectsProtectedRead(final EmailSourcePart source) {
        for (final EmailSourceHeader header : source.getHeaders()) {
            if (!header.getName().equalsIgnoreCase("Content-Type") || header.getRawValue() == null) continue;
            try {
                final ContentType type = new ContentType(header.getRawValue());
                final String protocol = type.getParameter("protocol");
                if (type.match("application/pkcs7-mime") || type.match("application/x-pkcs7-mime") || type.match("multipart/signed")
                        && (protocol == null || "application/pkcs7-signature".equalsIgnoreCase(protocol) || "application/x-pkcs7-signature".equalsIgnoreCase(protocol))) return ModuleLoader.smimeModuleAvailable();
                return (type.match("multipart/signed") && "application/pgp-signature".equalsIgnoreCase(protocol)
                        || type.match("multipart/encrypted") && "application/pgp-encrypted".equalsIgnoreCase(protocol)) && ModuleLoader.openPgpModuleAvailable();
            } catch (final jakarta.mail.internet.ParseException unavailable) { return false; }
        }
        return false;
    }

    private AttachmentResource acquireProtectedResource(final AttachmentResource resource, final EmailSourcePart source) {
        byte[] bytes;
        try {
            bytes = org.simplejavamail.internal.protection.ProtectionInputReadException.acquire(resource::openStream);
        } catch (final org.simplejavamail.internal.protection.ProtectionInputReadException failure) {
            InterruptedIoGuard.rethrowIfInterrupted(failure);
            bytes = failure.getCapturedBytes();
            if (failure.getCloseFailure() != null) note(source, EmailProblem.Code.RESOURCE_CLOSE_FAILED,
                    failure.getCloseFailure().getMessage() == null ? "The selected attachment could not be closed" : failure.getCloseFailure().getMessage());
            if (!failure.isFullyAcquired()) {
                final EmailSourcePart captured = EmailInspection.part(source.getOrigin(), source.getLocation(), source.getHeaders(), source.getProperties(),
                        List.of(), null, EmailInspection.ownedContent(bytes, EmailSourceContent.Representation.READER_BYTES, false));
                layerSources.add(EmailInspection.source(source.getOrigin(), EmailSourceData.Format.MIME, EmailSourceData.Representation.RECEIVED,
                        List.of(), List.of(), List.of(captured), List.of()));
                note(captured, EmailProblem.Code.CONTENT_READ_FAILED, failure.getReadFailure() == null || failure.getReadFailure().getMessage() == null
                        ? "The selected protected attachment could not be read" : failure.getReadFailure().getMessage());
                return null;
            }
        }
        return resource.toBuilder().withDataSource(org.simplejavamail.internal.util.AttachmentDataSources.fromOwnedBytes(bytes,
                resource.getName(), source.getHeaders().stream().filter(header -> header.getName().equalsIgnoreCase("Content-Type"))
                        .map(EmailSourceHeader::getRawValue).filter(java.util.Objects::nonNull).findFirst().orElse("application/octet-stream"))).build();
    }

    private static MimeBodyPart attachmentEntity(final AttachmentResource resource) throws MessagingException {
        final MimeBodyPart part = new MimeBodyPart();
        part.setDataHandler(new DataHandler(resource.getDataSource()));
        part.setHeader("Content-Type", resource.getDataSource().getContentType());
        if (resource.getName() != null) part.setFileName(resource.getName());
        return part;
    }

    private static AttachmentResource resourceFromEntity(final MimePart entity, final AttachmentResource prior) throws MessagingException, IOException {
        final byte[] bytes;
        try (InputStream content = entity.getInputStream()) { bytes = content.readAllBytes(); }
        return AttachmentResource.builder().withName(entity.getFileName() != null ? entity.getFileName() : prior.getName())
                .withData(bytes, entity.getContentType()).withDescription(entity.getDescription() != null ? entity.getDescription() : prior.getDescription())
                .withContentId(prior.getContentId()).build();
    }

    private static boolean isMessage(final MimePart part) throws MessagingException { return part.isMimeType("message/rfc822") || part.isMimeType("message/global"); }
    private static boolean isNativeMessage(final MimePart part) throws MessagingException { return part.isMimeType("application/vnd.ms-outlook"); }

    static MimeMessage asMessage(final MimePart part) throws MessagingException, IOException {
        if (part instanceof MimeMessage) return (MimeMessage) part;
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ((MimeBodyPart) part).writeTo(bytes);
        return FinalizedMimeMessage.fromOwnedBytes(session(), bytes.toByteArray(), FinalizedMimeMessage.ProtectionState.NONE, false);
    }

    private static Session session() { return Session.getInstance(new Properties()); }

    boolean hasAuthoredHeadersSelected() { return authoredHeadersSelected; }

    static final class SelectedAttachment {
        final AttachmentResource resource;
        final AttachmentResource original;
        final ValueInspection inspection;
        final ProtectionScope scope;
        private SelectedAttachment(final AttachmentResource resource, final AttachmentResource original, final ValueInspection inspection, final ProtectionScope scope) {
            this.resource = resource; this.original = original; this.inspection = inspection;
            this.scope = scope;
        }
    }
}
