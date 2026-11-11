package org.simplejavamail.converter.internal;

import jakarta.activation.DataHandler;
import jakarta.activation.DataSource;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import org.simplejavamail.api.email.*;
import org.simplejavamail.api.internal.outlooksupport.model.EmailFromOutlookMessage;
import org.simplejavamail.api.internal.outlooksupport.model.OutlookFileAttachment;
import org.simplejavamail.api.internal.outlooksupport.model.OutlookMessage;
import org.simplejavamail.api.internal.outlooksupport.model.OutlookSmime;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.api.outlook.OutlookEmailConversionResult;
import org.simplejavamail.api.outlook.OutlookMessageData;
import org.simplejavamail.converter.internal.mimemessage.CidReferences;
import org.simplejavamail.converter.internal.mimemessage.InputInspection;
import org.simplejavamail.converter.internal.mimemessage.ProtectionScope;
import org.simplejavamail.email.internal.InternalEmailPopulatingBuilder;
import org.simplejavamail.internal.email.EmailInspection;
import org.simplejavamail.internal.email.HeaderSettings;
import org.simplejavamail.internal.email.InspectionOutlookData;
import org.simplejavamail.internal.email.ProtectionInspectionImpl;
import org.simplejavamail.internal.email.ReceivedEmailInspection;
import org.simplejavamail.internal.email.ValueInspectionImpl;
import org.simplejavamail.internal.smime.FactorySmimeRuntime;
import org.simplejavamail.internal.util.InterruptedIoGuard;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

/** Combines explicitly identified native message envelopes with already materialized native alternatives once. */
final class LogicalOutlookReader {
    private final ConfiguredEmailConverterImpl owner;
    private final EmailStartingBuilder starter;
    private final FactorySmimeRuntime smimeRuntime;
    private final Pkcs12Config keys;
    private final org.simplejavamail.api.email.config.OpenPgpReceiveConfig pgpKeys;
    private ProtectionScope enclosing = ProtectionScope.plain();
    private ImportContext importContext;
    private OutlookMessage nativeSource;

    LogicalOutlookReader(final ConfiguredEmailConverterImpl owner, final EmailStartingBuilder starter,
            final FactorySmimeRuntime smimeRuntime, final Pkcs12Config keys,
            final org.simplejavamail.api.email.config.OpenPgpReceiveConfig pgpKeys) {
        this.owner = owner; this.starter = starter; this.smimeRuntime = smimeRuntime; this.keys = keys;
        this.pgpKeys = pgpKeys;
    }

    OutlookEmailConversionResult read(final EmailFromOutlookMessage imported, final ImportContext context, final AttachmentResource original) {
        return read(imported, context, original, ProtectionScope.plain());
    }

    OutlookEmailConversionResult read(final EmailFromOutlookMessage imported, final ImportContext context, final AttachmentResource original,
            final ProtectionScope covering) {
        return read(imported, context, original, covering, original == null ? org.simplejavamail.internal.email.OriginalMessageProtection.UNKNOWN
                : org.simplejavamail.internal.email.OriginalMessageProtection.AS_RECEIVED);
    }

    OutlookEmailConversionResult read(final EmailFromOutlookMessage imported, final ImportContext context, final AttachmentResource original,
            final ProtectionScope covering, final org.simplejavamail.internal.email.OriginalMessageProtection originalProtection) {
        enclosing = covering;
        importContext = context;
        nativeSource = imported.getOutlookMessage();
        final InternalEmailPopulatingBuilder builder = (InternalEmailPopulatingBuilder) imported.getEmailBuilder();
        final OutlookMessage nativeMessage = imported.getOutlookMessage();
        final InputInspection capture = InputInspection.capture(imported.getOutlookMessageData(), builder.getId());
        final List<EmailProblem> problems = new ArrayList<>();
        final List<AttachmentResource> nativeAttachments = new ArrayList<>(builder.getAttachments());
        final List<AttachmentResource> nativeImages = new ArrayList<>(builder.getEmbeddedImages());
        final Map<String, Collection<String>> nativeHeaders = headers(capture.getSourceData());
        final String envelopeDeclaration = declaredType(nativeMessage.getSmimeMime(), nativeHeaders);
        final int envelope = findEnvelope(envelopeDeclaration, nativeAttachments);
        Email opened = null;
        boolean authoredHeaders = false;
        boolean authoredUnavailable = false;
        if (envelope >= 0) {
            try {
                final LogicalMimeReader reader = reader();
                opened = reader.readGenerated(envelope(nativeAttachments.get(envelope), envelopeDeclaration),
                        context.at(context.traversal), enclosing).buildEmail();
                authoredHeaders = reader.hasAuthoredHeadersSelected();
                authoredUnavailable = ((org.simplejavamail.email.internal.InternalEmail) opened).isAuthoredHeaderViewUnavailable();
                if (opened.getPlainText() != null) {
                    builder.withPlainText(opened.getPlainText());
                    final ContentTransferEncoding encoding = selectedEncoding(opened.getInspection().getPlainText(), opened.getPlainTextContentTransferEncoding());
                    if (encoding == null) builder.clearPlainTextContentTransferEncoding(); else builder.withPlainTextContentTransferEncoding(encoding);
                }
                if (opened.getHTMLText() != null) {
                    builder.clearEmbeddedImages(); builder.withHTMLText(opened.getHTMLText()).withEmbeddedImages(opened.getEmbeddedImages());
                    final ContentTransferEncoding encoding = selectedEncoding(opened.getInspection().getHTMLText(), opened.getHTMLTextContentTransferEncoding());
                    if (encoding == null) builder.clearHTMLTextContentTransferEncoding(); else builder.withHTMLTextContentTransferEncoding(encoding);
                }
                if (opened.getCalendarText() != null) {
                    builder.withCalendarText(opened.getCalendarMethod(), opened.getCalendarText());
                    final ContentTransferEncoding encoding = selectedEncoding(opened.getInspection().getCalendar(), opened.getCalendarTextContentTransferEncoding());
                    if (encoding == null) builder.clearCalendarTextContentTransferEncoding(); else builder.withCalendarTextContentTransferEncoding(encoding);
                }
                if (opened.getDeliveryStatusReport() != null) builder.withDeliveryStatusReport(opened.getDeliveryStatusReport());
                if (authoredHeaders && opened.getSubject() != null) builder.withSubject(opened.getSubject());
                if (authoredUnavailable) clearAuthoredFields(builder);
                else if (authoredHeaders) applyAuthoredHeaders(builder, opened);
                problems.addAll(opened.getProblems());
            } catch (final MessagingException | IOException failure) {
                InterruptedIoGuard.rethrowIfInterrupted(failure);
                problems.add(EmailInspection.problem(capture.getOrigin(), EmailProblem.Operation.IMPORT, EmailProblem.Code.PROTECTION_FAILED,
                        "source/native-envelope", null, "The native protected message envelope could not be interpreted"));
            }
        }
        capture.noteAddressRecovery("Return-Path");
        if (!authoredHeaders && !authoredUnavailable) {
            capture.noteAddressRecovery("Disposition-Notification-To", "Return-Receipt-To");
            if (nativeMessage.getReplyToEmail() != null && !nativeMessage.getReplyToEmail().trim().isEmpty()) capture.noteAddressRecovery("Reply-To");
            capture.noteUninterpretedDate(builder.getSentDate());
        }
        problems.addAll(capture.getProblems());
        final Map<Integer, Email> materializedChildren = new java.util.LinkedHashMap<>();
        for (final AttachmentResource resource : nativeAttachments) {
            final Integer index = imported.getAttachmentSourceIndices().get(resource.getDataSource());
            if (index != null && resource.getEmail() != null) materializedChildren.put(index, resource.getEmail());
        }
        final Map<Integer, EmailSourceContent> retainedComponents = new java.util.LinkedHashMap<>();
        if (original == null && envelope >= 0) {
            final AttachmentResource retainedEnvelope = nativeAttachments.get(envelope);
            final Integer nativeIndex = imported.getAttachmentSourceIndices().get(retainedEnvelope.getDataSource());
            if (nativeIndex == null) throw new IllegalStateException("The native message envelope has no captured source occurrence");
            EmailSourceContent content = org.simplejavamail.internal.util.AttachmentDataSources.capturedOwnedContent(retainedEnvelope.getDataSource());
            if (content == null && nativeMessage.getOutlookAttachments().get(nativeIndex) instanceof OutlookFileAttachment) {
                content = EmailInspection.content(((OutlookFileAttachment) nativeMessage.getOutlookAttachments().get(nativeIndex)).getData(), EmailSourceContent.Representation.READER_BYTES, true);
            }
            if (content != null) retainedComponents.put(nativeIndex, content);
        }
        EmailSourceData source = OutlookSourceCapture.capture(capture.getSourceData(), nativeMessage, builder, problems, Map.of(), context.traversal, materializedChildren, retainedComponents);
        final List<AttachmentResource> selected = new ArrayList<>();
        final List<AttachmentResource> originals = new ArrayList<>();
        final List<ValueInspection> attachmentFacts = new ArrayList<>();
        final List<EmailSourcePart> attachmentLocations = new ArrayList<>();
        final List<Boolean> childAlreadyAggregated = new ArrayList<>();
        final List<ValueInspection> imageFacts = new ArrayList<>();
        final List<ProtectionScope> attachmentScopes = new ArrayList<>();
        attachmentScopes.add(enclosing);
        final LogicalMimeReader attachmentReader = reader();
        for (int index = 0; index < nativeAttachments.size(); index++) {
            if (index == envelope) continue;
            final AttachmentResource resource = nativeAttachments.get(index);
            final Integer nativeIndex = imported.getAttachmentSourceIndices().get(resource.getDataSource());
            final EmailSourcePart part = nativePart(source, nativeIndex);
            final AttachmentResource retained = isNativeFile(imported, resource) ? resource : null;
            final LogicalMimeReader.SelectedAttachment result = attachmentReader.readAttachment(resource, retained, enclosing, part,
                    context.at(nativeIndex == null ? context.traversal.view("native-resources[" + index + "]")
                            : context.traversal.child("attachments[" + nativeIndex + "]")));
            selected.add(result.resource); originals.add(retained); attachmentFacts.add(result.inspection);
            attachmentScopes.add(result.scope);
            attachmentLocations.add(part); childAlreadyAggregated.add(false);
        }
        if (opened != null) {
            for (int index = 0; index < opened.getAttachments().size(); index++) {
                selected.add(opened.getAttachments().get(index)); originals.add(opened.getInspection().getOriginalAttachment(index));
                attachmentFacts.add(opened.getInspection().getAttachment(index));
                attachmentLocations.add(opened.getInspection().getAttachment(index).getSources().stream().filter(value -> value instanceof EmailSourcePart)
                        .map(value -> (EmailSourcePart) value).findFirst().orElse(nativePart(source, null)));
                childAlreadyAggregated.add(true);
            }
            if (opened.getHTMLText() != null) {
                final Set<String> referenced = CidReferences.inHtml(opened.getHTMLText());
                for (final AttachmentResource image : nativeImages) {
                    selected.add(referenced.contains(image.getContentId()) ? image.toBuilder().clearContentId().build() : image);
                    originals.add(isNativeFile(imported, image) ? image : null);
                    attachmentFacts.add(nativeResource(source, imported, image));
                    attachmentLocations.add(nativePart(source, imported.getAttachmentSourceIndices().get(image.getDataSource()))); childAlreadyAggregated.add(false);
                }
            }
        }
        builder.clearAttachments().withAttachments(selected);
        if (opened != null && opened.getHTMLText() != null) {
            for (int index = 0; index < opened.getEmbeddedImages().size(); index++) imageFacts.add(opened.getInspection().getEmbeddedImage(index));
        } else {
            for (final AttachmentResource image : nativeImages) imageFacts.add(nativeResource(source, imported, image));
        }
        problems.addAll(attachmentReader.getProcessingProblems());
        final List<EmailSourceData> derived = new ArrayList<>(source.getDerivedSources());
        if (opened != null) derived.add(opened.getSourceData());
        for (int index = 0; index < selected.size(); index++) {
            if (selected.get(index).getEmail() != null && !childAlreadyAggregated.get(index)) {
                final Email child = selected.get(index).getEmail(); derived.add(child.getSourceData());
                for (final EmailProblem problem : child.getProblems()) problems.add(EmailInspection.qualify(problem, attachmentLocations.get(index).getLocation() + "/message/"));
            }
        }
        for (final String cid : imported.getAmbiguousContentIds()) {
            final List<EmailSourceElement> candidates = new ArrayList<>();
            for (final EmailSourcePart part : source.getParts()) {
                if (part.getProperties().stream().anyMatch(property -> property.getName().equals("contentId") && cid.equals(property.getValue()))) candidates.add(part);
            }
            problems.add(EmailInspection.problem(source.getOrigin(), EmailProblem.Operation.IMPORT, EmailProblem.Code.CONTENT_NOT_REPRESENTED,
                    "source/resources", cid, "Multiple native resources match this selected HTML Content-ID; no unique association was established", candidates));
        }
        source = EmailInspection.withDerivedSources(source, derived);
        builder.retainInspection(source, problems);
        Map<String, Collection<String>> selectedHeaders = nativeHeaders;
        if (authoredHeaders && !authoredUnavailable) {
            final Map<String, Collection<String>> outer = new TreeMap<>(String.CASE_INSENSITIVE_ORDER); outer.putAll(nativeHeaders);
            for (final String name : List.of("From", "Sender", "To", "Cc", "Bcc", "Reply-To", "Message-ID", "Date", "References", "In-Reply-To",
                    "Disposition-Notification-To", "Return-Receipt-To")) outer.remove(name);
            if (opened.getSubject() != null) outer.remove("Subject");
            selectedHeaders = HeaderSettings.mergeSignedContent(outer, opened.getHeaders());
        }
        MimeMessageEmailMapper.retainSelectedHeaders(builder, selectedHeaders);
        final java.util.Set<String> confidentialHeaders = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (authoredHeaders && !authoredUnavailable) {
            confidentialHeaders.addAll(((org.simplejavamail.email.internal.InternalEmail) opened).getConfidentialHeaderNames());
        } else {
            for (final String name : List.of("References", "In-Reply-To")) {
                if (selectedHeaders.containsKey(name) && nativeValue(source, name, selectedHeaders.get(name)).getEncryptionStatus() == EncryptionStatus.DECRYPTED) confidentialHeaders.add(name);
            }
        }
        builder.retainConfidentialHeaderNames(confidentialHeaders);
        final org.simplejavamail.api.email.EmailInspection protectedFacts = opened == null ? ReceivedEmailInspection.unknown() : opened.getInspection();
        final boolean protectedAuthor = authoredHeaders || authoredUnavailable;
        builder.retainSelectionInspection(ReceivedEmailInspection.builder()
                .plainText(opened != null && (opened.getPlainText() != null || builder.getPlainText() == null) ? protectedFacts.getPlainText() : nativeBody(source, "plain", builder.getPlainText(), ValueDerivation.NONE))
                .htmlText(opened != null && (opened.getHTMLText() != null || builder.getHTMLText() == null) ? protectedFacts.getHTMLText() : nativeBody(source, "html", builder.getHTMLText(), imported.getHTMLDerivation()))
                .calendar(opened != null && (opened.getCalendarText() != null || builder.getCalendarText() == null) ? protectedFacts.getCalendar() : nativeValue(source, "calendar", builder.getCalendarText()))
                .subject(authoredHeaders && opened.getSubject() != null ? protectedFacts.getSubject() : nativeValue(source, "Subject", builder.getSubject()))
                .fromRecipient(protectedAuthor ? protectedFacts.getFromRecipient() : nativeValue(source, "From", builder.getFromRecipient()))
                .toRecipients(protectedAuthor ? protectedFacts.getToRecipients() : nativeValue(source, "To", recipients(builder, jakarta.mail.Message.RecipientType.TO)))
                .ccRecipients(protectedAuthor ? protectedFacts.getCcRecipients() : nativeValue(source, "Cc", recipients(builder, jakarta.mail.Message.RecipientType.CC)))
                .bccRecipients(protectedAuthor ? protectedFacts.getBccRecipients() : nativeValue(source, "Bcc", recipients(builder, jakarta.mail.Message.RecipientType.BCC)))
                .replyToRecipients(protectedAuthor ? protectedFacts.getReplyToRecipients() : nativeValue(source, "Reply-To", builder.getReplyToRecipients()))
                .bounceToRecipient(nativeValue(source, "Return-Path", builder.getBounceToRecipient()))
                .id(protectedAuthor ? protectedFacts.getId() : nativeValue(source, "Message-ID", builder.getId()))
                .sentDate(protectedAuthor ? protectedFacts.getSentDate() : nativeValue(source, nativeMessage.getClientSubmitTime() != null ? "clientSubmitTime" : "Date", builder.getSentDate()))
                .attachments(attachmentFacts).embeddedImages(imageFacts).originalMessage(original).originalAttachments(originals)
                .authoredHeaderViewUnavailable(authoredUnavailable)
                .smime(LogicalMimeReader.family(ProtectionFamily.SMIME, attachmentScopes, !context.traversal.hasOmissions(), opened != null ? protectedFacts.getSmime() : null))
                .openPgp(LogicalMimeReader.family(ProtectionFamily.OPENPGP, attachmentScopes, !context.traversal.hasOmissions(), opened != null ? protectedFacts.getOpenPgp() : null)).build());
        builder.retainOriginalMessageProtection(original == null ? org.simplejavamail.internal.email.OriginalMessageProtection.UNKNOWN : originalProtection);
        if (nativeMessage.getBodyHTML() != null && java.util.Objects.equals(nativeMessage.getBodyHTML(), builder.getHTMLText())) {
            builder.retainReceivedHtml(builder.getHTMLText(), OutlookSourceCapture.originalHtmlParts(source, nativeMessage));
        }
        final OutlookMessageData metadata = InspectionOutlookData.copyOf(imported.getOutlookMessageData());
        return new NativeConversionResult(builder, metadata);
    }

    private static final class NativeConversionResult implements OutlookEmailConversionResult {
        private final EmailPopulatingBuilder builder;
        private final OutlookMessageData metadata;
        private NativeConversionResult(final EmailPopulatingBuilder builder, final OutlookMessageData metadata) { this.builder = builder; this.metadata = metadata; }
        @Override public EmailPopulatingBuilder getEmailBuilder() { return builder; }
        @Override public OutlookMessageData getOutlookMessageData() { return metadata; }
        @Override public Email buildEmail() { return builder.buildEmail(); }
    }

    private LogicalMimeReader reader() { return new LogicalMimeReader(owner, starter, smimeRuntime, keys, pgpKeys, true); }

    /** A native fallback keeps its own choices. A selected MIME field carries only its actual supported source hint. */
    private static ContentTransferEncoding selectedEncoding(final ValueInspection value, final ContentTransferEncoding specific) {
        if (specific != null) return specific;
        ContentTransferEncoding selected = null;
        for (final EmailSourceElement element : value.getSources()) {
            if (!(element instanceof EmailSourcePart)) return null;
            ContentTransferEncoding part = null;
            for (final EmailSourceHeader header : ((EmailSourcePart) element).getHeaders()) {
                if (!header.getName().equalsIgnoreCase("Content-Transfer-Encoding") || header.getRawValue() == null) continue;
                try {
                    final jakarta.mail.internet.HeaderTokenizer tokens = new jakarta.mail.internet.HeaderTokenizer(header.getRawValue(), jakarta.mail.internet.HeaderTokenizer.MIME);
                    final jakarta.mail.internet.HeaderTokenizer.Token token = tokens.next();
                    if (token.getType() == jakarta.mail.internet.HeaderTokenizer.Token.ATOM && tokens.next().getType() == jakarta.mail.internet.HeaderTokenizer.Token.EOF) {
                        part = ContentTransferEncoding.byEncoder(token.getValue());
                    }
                } catch (final jakarta.mail.internet.ParseException | IllegalArgumentException unavailable) { return null; }
                break;
            }
            if (part == null || selected != null && selected != part) return null;
            selected = part;
        }
        return selected;
    }

    private static List<Recipient> recipients(final EmailPopulatingBuilder builder, final jakarta.mail.Message.RecipientType type) {
        return builder.getRecipients().stream().filter(recipient -> recipient.getType() == type).collect(java.util.stream.Collectors.toList());
    }

    private static int findEnvelope(final String declared, final List<AttachmentResource> attachments) {
        if (declared == null) return -1;
        final ContentType declaration;
        try {
            declaration = new ContentType(declared);
        } catch (final jakarta.mail.internet.ParseException unavailable) {
            return -1;
        }
        int selected = -1;
        for (int index = 0; index < attachments.size(); index++) {
            try {
                final ContentType type = new ContentType(attachments.get(index).getDataSource().getContentType());
                if (type.match(declaration.getBaseType()) || isPkcs7Mime(type) && isPkcs7Mime(declaration)) {
                    if (selected >= 0) return -1;
                    selected = index;
                }
            } catch (final jakarta.mail.internet.ParseException unavailable) {
                // Unestablished component roles remain ordinary resources.
            }
        }
        return selected;
    }

    private static String declaredType(final OutlookSmime smime, final Map<String, Collection<String>> nativeHeaders) {
        if (smime instanceof OutlookSmime.OutlookSmimeApplicationSmime) {
            final OutlookSmime.OutlookSmimeApplicationSmime declaration = (OutlookSmime.OutlookSmimeApplicationSmime) smime;
            final String type = declaration.getSmimeMime();
            if (type != null) return type + (declaration.getSmimeType() == null ? "" : "; smime-type=" + declaration.getSmimeType());
        }
        if (smime instanceof OutlookSmime.OutlookSmimeMultipartSigned) {
            final OutlookSmime.OutlookSmimeMultipartSigned declaration = (OutlookSmime.OutlookSmimeMultipartSigned) smime;
            return declaration.getSmimeMime() + "; protocol=\"" + declaration.getSmimeProtocol() + "\""
                    + (declaration.getSmimeMicalg() == null ? "" : "; micalg=" + declaration.getSmimeMicalg());
        }
        // The native reader does not fill its summary when it reads an embedded message directory.
        // Its own captured header can still declare a protected body, independently of the attachment name.
        final Collection<String> contentTypes = nativeHeaders.get("Content-Type");
        if (contentTypes == null || contentTypes.size() != 1) return null;
        final String captured = contentTypes.iterator().next();
        try {
            final ContentType type = new ContentType(captured);
            final String protocol = type.getParameter("protocol");
            if (isPkcs7Mime(type) || type.match("multipart/signed") && ("application/pkcs7-signature".equalsIgnoreCase(protocol)
                    || "application/x-pkcs7-signature".equalsIgnoreCase(protocol))) return captured;
        } catch (final jakarta.mail.internet.ParseException unavailable) {
            // An unreadable declaration cannot establish an envelope role.
        }
        return null;
    }

    private static boolean isPkcs7Mime(final ContentType type) {
        return type.match("application/pkcs7-mime") || type.match("application/x-pkcs7-mime");
    }

    private static MimeMessage envelope(final AttachmentResource resource, final String declaration) throws MessagingException, IOException {
        final MimeBodyPart part = new MimeBodyPart();
        part.setDataHandler(new DataHandler(resource.getDataSource()));
        part.setHeader("Content-Type", declaration);
        return LogicalMimeReader.asMessage(part);
    }

    private static void applyAuthoredHeaders(final InternalEmailPopulatingBuilder builder, final Email selected) {
        clearAuthoredFields(builder);
        if (selected.getFromRecipient() != null) builder.from(selected.getFromRecipient());
        builder.withRecipients(selected.getRecipients()).withReplyTo(selected.getReplyToRecipients());
        builder.withDispositionNotificationToState(selected.getUseDispositionNotificationTo(), selected.getDispositionNotificationToRecipients());
        builder.withReturnReceiptToState(selected.getUseReturnReceiptTo(), selected.getReturnReceiptToRecipients());
        builder.fixingMessageId(selected.getId());
        if (selected.getSentDate() != null) builder.fixingSentDate(selected.getSentDate());
    }

    private static void clearAuthoredFields(final InternalEmailPopulatingBuilder builder) {
        builder.clearFromRecipient().clearRecipients().clearReplyTo().clearId().clearSentDate();
        builder.clearDispositionNotificationTo().clearReturnReceiptTo();
    }

    private static Map<String, Collection<String>> headers(final EmailSourceData source) {
        final Map<String, Collection<String>> values = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (final EmailSourceHeader header : source.getHeaders()) {
            final String raw = header.getRawValue();
            values.computeIfAbsent(header.getName(), ignored -> new ArrayList<>()).add(raw == null ? null : MimeUtility.unfold(raw));
        }
        return values;
    }

    private static boolean isNativeFile(final EmailFromOutlookMessage imported, final AttachmentResource resource) {
        final Integer index = imported.getAttachmentSourceIndices().get(resource.getDataSource());
        return index != null && index >= 0 && index < imported.getOutlookMessage().getOutlookAttachments().size()
                && imported.getOutlookMessage().getOutlookAttachments().get(index) instanceof OutlookFileAttachment;
    }

    private static EmailSourcePart nativePart(final EmailSourceData source, final Integer index) {
        for (final EmailSourcePart part : source.getParts()) {
            if (index != null && part.getProperties().stream().anyMatch(property -> property.getName().equals("nativeAttachmentIndex") && index.toString().equals(property.getValue()))) return part;
        }
        return EmailInspection.part(source.getOrigin(), "source/resources", List.of(), List.of(), List.of(), null, null);
    }

    private ValueInspection nativeResource(final EmailSourceData source, final EmailFromOutlookMessage imported, final AttachmentResource resource) {
        return enclosing.inspect(List.of(nativePart(source, imported.getAttachmentSourceIndices().get(resource.getDataSource()))), ValueAvailability.PRESENT);
    }

    private ValueInspection nativeBody(final EmailSourceData source, final String kind, final String value, final ValueDerivation derivation) {
        final List<EmailSourceElement> parts = new ArrayList<>();
        for (final EmailSourcePart part : source.getParts()) {
            if (part.getProperties().stream().anyMatch(property -> property.getName().equals("bodyFormat") && property.getValue().equals(kind))) parts.add(part);
        }
        if (parts.isEmpty() && "html".equals(kind) && value != null) {
            source.getParts().stream().filter(part -> part.getProperties().stream().anyMatch(property -> property.getName().equals("bodyFormat") && "rtf".equals(property.getValue()))).forEach(parts::add);
        }
        final boolean nativePresent = "plain".equals(kind) ? nativeSource.getBodyText() != null || nativeSource.getPropertyValue(0x1000) != null
                : nativeSource.getBodyHTML() != null || nativeSource.getBodyRTF() != null
                || nativeSource.getPropertyValue(0x1013) != null || nativeSource.getPropertyValue(0x1009) != null;
        final ValueAvailability availability = value != null ? ValueAvailability.PRESENT : !nativePresent ? ValueAvailability.ABSENT
                : importContext.traversal.hasOmissions() ? ValueAvailability.UNINSPECTED : ValueAvailability.UNAVAILABLE;
        final ValueInspection facts = enclosing.inspect(parts, availability);
        return ((ValueInspectionImpl) facts).toBuilder().derivation(derivation)
                .origin(derivation == ValueDerivation.RTF_ENCAPSULATED_HTML ? ValueOrigin.EXTRACTED
                        : derivation == ValueDerivation.RTF_ENCAPSULATED_TEXT || derivation == ValueDerivation.RTF_RENDERED_HTML
                        ? ValueOrigin.DERIVED : ValueOrigin.RECEIVED).build();
    }

    private ValueInspection nativeValue(final EmailSourceData source, final String field, final Object value) {
        final List<EmailSourceElement> sources = new ArrayList<>();
        final String readerField = field.equalsIgnoreCase("Subject") ? "native.subject" : field.equalsIgnoreCase("Message-ID") ? "native.messageId"
                : field.equalsIgnoreCase("From") ? "native.from" : field.equalsIgnoreCase("Reply-To") ? "native.replyTo"
                : field.equalsIgnoreCase("To") ? "native.to[" : field.equalsIgnoreCase("Cc") ? "native.cc[" : field.equalsIgnoreCase("Bcc") ? "native.bcc[" : null;
        if (readerField != null) {
            source.getProperties().stream().filter(property -> property.getName().startsWith(readerField)).forEach(sources::add);
        }
        if (sources.isEmpty()) source.getHeaders().stream().filter(header -> header.getName().equalsIgnoreCase(field)).forEach(sources::add);
        source.getProperties().stream().filter(property -> property.getName().equals(field)).forEach(sources::add);
        final boolean absent = value == null || value instanceof Collection<?> && ((Collection<?>) value).isEmpty();
        return enclosing.inspect(sources, absent ? sources.isEmpty() ? ValueAvailability.ABSENT : ValueAvailability.UNAVAILABLE : ValueAvailability.PRESENT);
    }

    private static ProtectionInspection plain(final ProtectionFamily family, final boolean complete) {
        return ProtectionInspectionImpl.builder().family(family).inspectionComplete(complete).build();
    }
}
