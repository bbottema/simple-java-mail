package org.simplejavamail.converter.internal.mimemessage;

import org.simplejavamail.internal.converter.ImportedSentDateParser;

import java.util.Objects;

import org.simplejavamail.api.email.*;
import org.simplejavamail.internal.converter.ImportTraversal;
import org.simplejavamail.api.email.EmailSourceData;
import org.simplejavamail.api.email.ContentTransferEncoding;
import org.simplejavamail.api.email.CalendarMethod;

import jakarta.activation.*;
import jakarta.mail.Address;
import jakarta.mail.Header;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.*;
import jakarta.mail.util.ByteArrayDataSource;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.val;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.internal.general.MessageHeader;
import org.simplejavamail.api.internal.general.HeadersToIgnoreWhenParsingExternalEmails;
import org.simplejavamail.api.email.DeliveryStatusReport;
import org.simplejavamail.internal.util.MiscUtil;
import org.simplejavamail.internal.util.NamedDataSource;
import org.simplejavamail.internal.util.MimeTypeUtil;
import org.simplejavamail.internal.util.Preconditions;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Supplier;

import static com.pivovarit.function.ThrowingFunction.unchecked;
import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Optional.ofNullable;
import static org.simplejavamail.internal.util.MiscUtil.*;
import static org.simplejavamail.internal.util.InterruptedIoGuard.rethrowIfInterrupted;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Parses a MimeMessage and stores the individual parts such a plain text, HTML text and attachments.
 *
 * @version current: MimeMessageParser.java 2016-02-25 Benny Bottema
 */
public final class MimeMessageParser {

	private static final Logger LOGGER = getLogger(MimeMessageParser.class);
	// Preserve historical leading-token recovery after Jakarta Mail has identified the genuine method parameter.
	private static final Pattern CALENDAR_METHOD_TOKEN_PATTERN = Pattern.compile("^(\\w+)");
	private static final Pattern CALENDAR_BODY_METHOD_PATTERN = Pattern.compile("(?i)^METHOD:(\\w+)", Pattern.MULTILINE);

    /** Preserves the parser's recognized selected-input failure boundary for cross-package layer readers. */
    public static RuntimeException selectedContentFailure(final Exception failure) {
        return new MimeMessageParseException(MimeMessageParseException.ERROR_PARSING_CONTENT, failure);
    }

	/**
	 * Delegates to {@link #parseMimeMessage(MimeMessage, boolean)}.
	 */
	public static ParsedMimeMessageComponents parseMimeMessage(@NotNull final MimeMessage mimeMessage) {
		return parseMimeMessage(mimeMessage, true);
	}

	/**
	 * Extracts the content of a MimeMessage recursively.
	 */
	public static ParsedMimeMessageComponents parseMimeMessage(@NotNull final MimeMessage mimeMessage, boolean fetchAttachmentData) {
        return parseMimeMessage(mimeMessage, fetchAttachmentData, InputInspection.capture(mimeMessage));
    }

    public static ParsedMimeMessageComponents parseMimeMessage(@NotNull final MimeMessage mimeMessage, boolean fetchAttachmentData,
            InputInspection inspection) {
        return parseMimeMessage(mimeMessage, fetchAttachmentData, inspection, null, ProtectionScope.plain());
    }

    public static ParsedMimeMessageComponents parseMimeMessage(final MimeMessage mimeMessage, final boolean fetchAttachmentData,
            final InputInspection inspection, final IncomingPartInterpreter interpreter, final ProtectionScope covering) {
        final ParsedMimeMessageComponents parsedComponents = new ParsedMimeMessageComponents();
        parsedComponents.inspection = inspection;
        parsedComponents.partInterpreter = interpreter;
        parsedComponents.headerProtection = covering;
        parsedComponents.carrierProtection = covering;
        parsedComponents.carrierHeaders = inspection;
        parsedComponents.subjectHeaders = inspection;
        parsedComponents.subjectProtection = covering;
        final HeaderView outerHeaders = new HeaderView(mimeMessage, inspection, covering);
        parsedComponents.headerViews.add(outerHeaders);
        parsedComponents.authoredView = outerHeaders;
        parsedComponents.nativeSubject = publicSubject(inspection);
        final MimePartCapture root = inspection.beginRootPart();
        final ImportTraversal traversal = inspection.importTraversal();
        if (inspection.contentStop() != null || !traversal.admit()) {
            root.problem(inspection.contentStop() != null ? inspection.contentStopCode() : EmailProblem.Code.IMPORT_LIMIT_EXCEEDED, null,
                    inspection.contentStop() != null ? inspection.contentStop() : traversal.stopDescription());
        } else {
		    parseMimePartTree(mimeMessage, parsedComponents, fetchAttachmentData, false, root, traversal, covering, false);
        }
        finishHeaderSelection(parsedComponents);
		if (parsedComponents.deliveryStatusReport == null && (parsedComponents.reportContainer || parsedComponents.deliveryStatusPart != null)) {
			parsedComponents.deliveryStatusReport = DeliveryStatusReportParser.unavailableReport(parsedComponents.deliveryStatusPart != null
                    ? "The delivery-status content may be incomplete because it could not be read."
                    : "The notification has no readable delivery-status part.");
		}
		parsedComponents.deliveryStatusPart = null;
		parsedComponents.deliveryStatusWarning = null;
        parsedComponents.deliveryStatusCandidates.clear();
		moveInvalidEmbeddedResourcesToAttachments(parsedComponents);
		removeReferencedImplicitImageAttachments(parsedComponents);
        finishSourceRetention(parsedComponents);
        // Freeze references only after healthy represented payloads have been released from diagnostic capture.
        inspection.noteReportWarnings(parsedComponents.deliveryStatusReport, parsedComponents.deliveryStatusSources.isEmpty()
                ? Collections.singletonList(root) : parsedComponents.deliveryStatusSources);
        parsedComponents.deliveryStatusSources.clear();
        inspection.finishTraversal();
		return parsedComponents;
	}

    private static void parseMessageHeaders(final MimeMessage mimeMessage, final ParsedMimeMessageComponents parsedComponents,
            final InputInspection inspection) {
        parsedComponents.selectedHeaders = inspection;
		parsedComponents.messageId = inspection.recover("Message-ID", () -> parseMessageId(mimeMessage), () -> inspection.raw("Message-ID"));
		parsedComponents.sentDate = inspection.recover("Date", () -> parseSentDate(mimeMessage), () -> null);
        inspection.noteUninterpretedDate(parsedComponents.sentDate);
        parsedComponents.toAddresses.addAll(inspection.recover("To", inspection.joined("To"), () -> parseToAddresses(mimeMessage),
                () -> Arrays.asList(rawAddressHeader(inspection.joined("To")))));
        parsedComponents.ccAddresses.addAll(inspection.recover("Cc", inspection.joined("Cc"), () -> parseCcAddresses(mimeMessage),
                () -> Arrays.asList(rawAddressHeader(inspection.joined("Cc")))));
        parsedComponents.bccAddresses.addAll(inspection.recover("Bcc", inspection.joined("Bcc"), () -> parseBccAddresses(mimeMessage),
                () -> Arrays.asList(rawAddressHeader(inspection.joined("Bcc")))));
        parsedComponents.fromAddress = inspection.recover("From", () -> parseFromAddress(mimeMessage, inspection),
                () -> rawAddress(inspection.raw("From")));
        parsedComponents.replyToAddresses.addAll(inspection.recover("Reply-To", inspection.joined("Reply-To"), () -> parseReplyToAddresses(mimeMessage, inspection),
                () -> Arrays.asList(rawAddressHeader(inspection.joined("Reply-To")))));
    }

    private static final class HeaderView {
        private final MimeMessage message;
        private final InputInspection inspection;
        private final ProtectionScope protection;
        private HeaderView(final MimeMessage message, final InputInspection inspection, final ProtectionScope protection) {
            this.message = message; this.inspection = inspection; this.protection = protection;
        }
    }

    private static void selectAuthoredHeaders(final MimeMessage message, final InputInspection inspection,
            final ParsedMimeMessageComponents target, final ProtectionScope protection) {
        final HeaderView view = new HeaderView(message, inspection, protection);
        target.headerViews.add(view);
        if (target.authoredView == null || protection.getOpenedDepth() >= target.authoredView.protection.getOpenedDepth()) target.authoredView = view;
    }

    /** Choose each authority before invoking its typed reader; displaced source headers remain passive captured evidence. */
    private static void finishHeaderSelection(final ParsedMimeMessageComponents target) {
        final HeaderView authored = target.authoredView;
        target.selectedHeaders = authored.inspection;
        target.headerProtection = authored.protection;
        target.authoredHeaderViewUnavailable = target.unavailableAuthoredDepth >= authored.protection.getOpenedDepth();
        if (!target.authoredHeaderViewUnavailable) parseMessageHeaders(authored.message, target, authored.inspection);
        final List<HeaderView> preferred = new ArrayList<>(target.headerViews);
        Collections.reverse(preferred);
        preferred.sort(Comparator.comparingInt((HeaderView view) -> view.protection.getOpenedDepth()).reversed());
        HeaderView subject = target.headerViews.get(0);
        for (final HeaderView view : preferred) {
            if (view.inspection.raw("Subject") == null && view.inspection.isHeaderCaptureComplete() && view != target.headerViews.get(0)) continue;
            final String value = selectedSubject(view);
            subject = view;
            if (value != null) { target.subject = value; break; }
        }
        target.subjectHeaders = subject.inspection;
        target.subjectProtection = subject.protection;
        final HeaderView outer = target.headerViews.get(0);
        HeaderView carrier = outer;
        if (outer.inspection.raw("Return-Path") == null) {
            for (final HeaderView view : preferred) if (view.inspection.raw("Return-Path") != null) { carrier = view; break; }
        }
        target.carrierHeaders = carrier.inspection;
        target.carrierProtection = carrier.protection;
        final Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        target.headerViews.forEach(view -> view.inspection.headers().forEach(header -> names.add(header.getName())));
        for (final String name : names) {
            final HeaderView owner;
            if (name.equalsIgnoreCase("Subject")) owner = subject;
            else if (name.equalsIgnoreCase("Return-Path")) owner = carrier;
            else if (authoredField(name)) owner = authored;
            else if ((org.simplejavamail.internal.email.HeaderRoles.classify(name) == org.simplejavamail.internal.email.HeaderRoles.Role.DELIVERY_HISTORY
                    || org.simplejavamail.internal.email.HeaderRoles.classify(name) == org.simplejavamail.internal.email.HeaderRoles.Role.MIME_STRUCTURE)
                    && outer.inspection.headers().stream().anyMatch(header -> header.getName().equalsIgnoreCase(name))) owner = outer;
            else owner = preferred.stream().filter(view -> view.inspection.headers().stream().anyMatch(header -> header.getName().equalsIgnoreCase(name))).findFirst().orElse(outer);
            target.headerProtectionByName.put(name, owner.protection);
            for (final Header header : owner.inspection.headers()) {
                if (!header.getName().equalsIgnoreCase(name)) continue;
                if (target.authoredHeaderViewUnavailable && authoredField(name)) {
                    target.headers.computeIfAbsent(header.getName(), ignored -> new ArrayList<>())
                            .add(header.getValue() == null ? null : MimeUtility.unfold(header.getValue()));
                    continue;
                }
                owner.inspection.recover(name, header.getValue(), () -> { parseHeader(header, target, owner.inspection); return null; }, () -> null);
            }
        }
        if (!target.authoredHeaderViewUnavailable) {
            authored.inspection.noteAddressRecovery("From", "To", "Cc", "Bcc", "Reply-To", "Disposition-Notification-To", "Return-Receipt-To");
        }
        carrier.inspection.noteAddressRecovery("Return-Path");
    }

    private static boolean authoredField(final String name) {
        return !name.equalsIgnoreCase("Subject") && org.simplejavamail.internal.email.HeaderRoles.classify(name) == org.simplejavamail.internal.email.HeaderRoles.Role.TYPED
                || name.equalsIgnoreCase("Sender") || name.equalsIgnoreCase("References") || name.equalsIgnoreCase("In-Reply-To");
    }

    private static String selectedSubject(final HeaderView view) {
        final InputInspection inspection = view.inspection;
        String value = inspection.recover("Subject", () -> parseSubject(view.message),
                () -> inspection.raw("Subject") == null ? null : MimeUtility.unfold(inspection.raw("Subject")));
        final String raw = inspection.raw("Subject");
        if (InputInspection.unavailableCharset(raw) && !inspection.decodingFailed("Subject", raw)
                && (Objects.equals(value, raw) || Objects.equals(value, MimeUtility.unfold(raw)))) {
            value = MimeUtility.unfold(raw);
            inspection.record(EmailProblem.Code.VALUE_NOT_DECODED, "Subject", raw,
                    "The received subject charset is unavailable; the complete original value is retained");
        }
        return value;
    }

    private static String publicSubject(final InputInspection inspection) {
        final String raw = inspection.raw("Subject");
        if (raw == null) return null;
        try { return MimeUtility.unfold(MimeUtility.decodeText(raw)); }
        catch (final UnsupportedEncodingException unavailable) { return null; }
    }

    private static final class BodyValue {
        private final String content;
        private final MimePartCapture source;
        private final ProtectionScope protection;
        private final List<AlternativeChoice> alternatives;
        private BodyValue(final String content, final MimePartCapture source, final ProtectionScope protection, final Collection<AlternativeChoice> alternatives) {
            this.content = content; this.source = source; this.protection = protection;
            this.alternatives = List.copyOf(alternatives);
        }
    }

    private static final class AlternativeChoice {
        private final String owner;
        private final int branch;
        private AlternativeChoice(final String owner, final int branch) { this.owner = owner; this.branch = branch; }
    }

    private static List<BodyValue> selectedBodies(final List<BodyValue> values) {
        final Map<String, Integer> selected = new LinkedHashMap<>();
        final Map<String, Integer> strengths = new LinkedHashMap<>();
        for (final BodyValue value : values) {
            for (final AlternativeChoice choice : value.alternatives) {
                final int strength = value.protection.getOpenedDepth();
                final int previous = strengths.getOrDefault(choice.owner, -1);
                if (strength > previous || strength == previous && choice.branch > selected.getOrDefault(choice.owner, -1)) {
                    strengths.put(choice.owner, strength); selected.put(choice.owner, choice.branch);
                }
            }
        }
        return values.stream().filter(value -> value.alternatives.stream().allMatch(choice -> selected.get(choice.owner) == choice.branch))
                .collect(java.util.stream.Collectors.toList());
    }

    private static String bodyContent(final List<BodyValue> values) {
        if (values.isEmpty()) return null;
        final StringBuilder result = new StringBuilder();
        selectedBodies(values).forEach(value -> result.append(value.content));
        return result.toString();
    }

    private static void finishSourceRetention(final ParsedMimeMessageComponents parsed) {
        final String plain = parsed.getPlainContent();
        final String html = parsed.getHtmlContent();
        parsed.completedPlainContent = plain;
        parsed.completedHtmlContent = html;
        parsed.complete = true;
        for (final MimePartCapture capture : parsed.plainSources) {
            capture.finishRepresentation(!capture.isBodyValue(plain));
        }
        for (final MimePartCapture capture : parsed.htmlSources) {
            capture.finishRepresentation(!capture.isBodyValue(html));
        }
        for (final MimePartCapture capture : parsed.calendarSources) capture.finishRepresentation(capture != parsed.calendarSource);
        final Set<MimeDataSource> represented = Collections.newSetFromMap(new IdentityHashMap<>());
        parsed.attachmentList.stream().filter(value -> !parsed.bodyContainers.contains(value)).forEach(represented::add);
        represented.addAll(parsed.cidMap.values());
        parsed.resourceSources.forEach((resource, capture) -> capture.finishRepresentation(!represented.contains(resource)));
    }

	private static void parseMimePartTree(@NotNull final MimePart currentPart, @NotNull final ParsedMimeMessageComponents parsedComponents,
			final boolean fetchAttachmentData, final boolean insideRelated, final MimePartCapture capture, final ImportTraversal traversal, final ProtectionScope covering,
            final boolean alternativeChild) {
        if (parsedComponents.deliveryStatusCandidates.contains(currentPart)) {
            parsedComponents.deliveryStatusSources.add(capture);
        }
        try {
            MimePart selected = currentPart;
            ProtectionScope protection = covering;
            final boolean messageBody = currentPart instanceof MimeMessage || alternativeChild;
            if (parsedComponents.partInterpreter != null
                    && (messageBody || !Part.ATTACHMENT.equalsIgnoreCase(parseDisposition(currentPart, capture)))) {
                final IncomingPartInterpreter.PartSelection selection = parsedComponents.partInterpreter.open(currentPart, covering,
                        capture.layerSource(), traversal, messageBody);
                selected = selection.getContent();
                protection = selection.getProtection();
                if (selection.isAuthoredHeaderViewUnavailable()) {
                    parsedComponents.unavailableAuthoredDepth = Math.max(parsedComponents.unavailableAuthoredDepth, protection.getOpenedDepth() + 1);
                }
                if (selection.isProtectedContainer() && !messageBody) {
                    if (prepareTransferEncoding(currentPart, capture, fetchAttachmentData)) {
                        parseDataSource(currentPart, parsedComponents, fetchAttachmentData, parseDisposition(currentPart, capture), insideRelated, capture, covering, traversal);
                    }
                    capture.represented();
                    return;
                }
                if (protection != covering) parsedComponents.encounteredScopes.add(protection);
                if (selection.getAuthoredHeaders() != null) {
                    final InputInspection authored = InputInspection.capture(selection.getAuthoredHeaders(), false, EmailSourceData.Representation.EXTRACTED);
                    selectAuthoredHeaders(selection.getAuthoredHeaders(), authored, parsedComponents, selection.getAuthoredProtection());
                }
                if (selection.getInputFailure() != null) {
                    final MimePartCapture failed;
                    if (selected == currentPart) failed = capture;
                    else { failed = parsedComponents.inspection.beginPart(selected, capture.getLocation() + "/opened"); capture.add(failed); }
                    failed.failed(selected, new MimeMessageParseException(MimeMessageParseException.ERROR_READING_CONTENT, selection.getInputFailure()), fetchAttachmentData);
                    parsedComponents.inspection.noteUnvisitedStructure(); parsedComponents.unavailableBody = true;
                    return;
                }
                if (selected != currentPart) {
                    final MimePartCapture opened = parsedComponents.inspection.beginPart(selected, capture.getLocation() + "/opened");
                    if (!protection.isComplete() || protection.getDecryptions().stream().anyMatch(value -> value.getStatus() != DecryptionStatus.DECRYPTED)) {
                        parsedComponents.unavailableBody = true;
                        parsedComponents.protectedBodyParts.add(opened);
                    }
                    capture.add(opened);
                    interpretMimePart(selected, parsedComponents, fetchAttachmentData, insideRelated, opened,
                            selection.getContentTraversal() != null ? selection.getContentTraversal() : traversal.view("opened"), protection);
                    opened.represented();
                    capture.represented();
                    return;
                }
                if (protection != covering) parsedComponents.unavailableBody = true;
                if (selection.isProtectedContainer() && protection != covering) {
                    parsedComponents.protectedBodyParts.add(capture);
                    if (protection.hasRetainedCiphertext() && isMimeType(currentPart, "multipart/encrypted", capture)) {
                        if (prepareTransferEncoding(currentPart, capture, fetchAttachmentData)) {
                            parseDataSource(currentPart, parsedComponents, fetchAttachmentData, parseDisposition(currentPart, capture), insideRelated, capture, protection, traversal);
                        }
                        capture.represented();
                        return;
                    }
                }
            }
            interpretMimePart(selected, parsedComponents, fetchAttachmentData, insideRelated, capture, traversal, protection);
            capture.represented();
        } catch (final MimeMessageParseException failure) {
            if (!InputInspection.recognizedFailure(failure)) {
                throw failure;
            }
            parsedComponents.inspection.noteUnvisitedStructure();
            capture.failed(currentPart, failure, fetchAttachmentData || !capture.hasMultipleHeaders("Content-Disposition"));
        }
    }

    private static void interpretMimePart(final MimePart currentPart, final ParsedMimeMessageComponents parsedComponents,
            final boolean fetchAttachmentData, final boolean insideRelated, final MimePartCapture capture, final ImportTraversal traversal, final ProtectionScope protection) {
        if (currentPart instanceof MimeMessage) {
            parsedComponents.reportContainer = inspectOuterReportContainer(currentPart, parsedComponents.inspection);
            if (DeliveryStatusReportParser.isStatusPart(currentPart)) {
                parsedComponents.deliveryStatusPart = currentPart;
                parsedComponents.deliveryStatusSources.add(capture);
            }
        }
		final String disposition = parseDisposition(currentPart, capture);
		// A child's disposition owns that resource; the effective root still supplies message content.
		final boolean canSupplyBody = currentPart instanceof MimeMessage || !Part.ATTACHMENT.equalsIgnoreCase(disposition);

		if (isMimeType(currentPart, "text/plain", capture) && canSupplyBody) {
            if (!prepareTransferEncoding(currentPart, capture, true)) return;
            final String content = parseTextContent(currentPart, capture);
			parsedComponents.plainContent.append(content);
            capture.bodyValue(content);
            parsedComponents.inspection.noteReadableBody();
            parsedComponents.plainSources.add(capture);
            parsedComponents.plainValues.add(new BodyValue(content, capture, protection, parsedComponents.activeAlternatives));
            capture.deferRepresentation();
			parsedComponents.plainContentPresent = true;
			if (parsedComponents.plainTextContentTransferEncoding == null) {
				parsedComponents.plainTextContentTransferEncoding = checkContentTransferEncoding(parsedComponents, capture);
			}
		} else if (isMimeType(currentPart, "text/html", capture) && canSupplyBody) {
            if (!prepareTransferEncoding(currentPart, capture, true)) return;
            final String content = parseTextContent(currentPart, capture);
			parsedComponents.htmlContent.append(content);
            capture.bodyValue(content);
            parsedComponents.inspection.noteReadableBody();
            parsedComponents.inspection.originalHtml(capture);
            parsedComponents.htmlSources.add(capture);
            parsedComponents.htmlValues.add(new BodyValue(content, capture, protection, parsedComponents.activeAlternatives));
            capture.deferRepresentation();
			parsedComponents.htmlContentPresent = true;
			if (parsedComponents.htmlTextContentTransferEncoding == null) {
				parsedComponents.htmlTextContentTransferEncoding = checkContentTransferEncoding(parsedComponents, capture);
			}
        } else if (isMimeType(currentPart, "text/calendar", capture) && canSupplyBody) {
            parsedComponents.calendarInputs.put(capture, protection);
            if (!prepareTransferEncoding(currentPart, capture, true)) return;
			final String content = parseCalendarContent(currentPart, capture);
            final String contentType = retrieveDataHandler(currentPart).getContentType();
            final String method = parseCalendarHeaderMethod(contentType)
                    .orElseGet(() -> findFirstMatch(CALENDAR_BODY_METHOD_PATTERN, content).orElse(null));
            if (method == null || Arrays.stream(CalendarMethod.values()).noneMatch(value -> value.name().equalsIgnoreCase(method))) {
                capture.unrepresented("No supported Calendar METHOD could be established");
                return;
            }
            capture.bodyValue(content); capture.deferRepresentation(); parsedComponents.calendarSources.add(capture);
            if (protection.getOpenedDepth() <= parsedComponents.calendarDepth) return;
			parsedComponents.calendarContent = content;
            parsedComponents.calendarSource = capture;
            parsedComponents.calendarProtection = protection;
            parsedComponents.calendarDepth = protection.getOpenedDepth();
            parsedComponents.inspection.noteReadableBody();
			parsedComponents.calendarMethod = method;
			parsedComponents.calendarTextContentTransferEncoding = checkContentTransferEncoding(parsedComponents, capture);
        } else if (isMimeType(currentPart, "multipart/*", capture) && canSupplyBody) {
            parseMultipart(currentPart, parsedComponents, fetchAttachmentData, insideRelated, capture, traversal, protection);
        } else {
            if (!prepareTransferEncoding(currentPart, capture, fetchAttachmentData)) return;
			parseDataSource(currentPart, parsedComponents, fetchAttachmentData, disposition, insideRelated, capture, protection, traversal);
		}
	}

    private static void parseMultipart(final MimePart currentPart, final ParsedMimeMessageComponents parsedComponents,
            final boolean fetchAttachmentData, final boolean insideRelated, final MimePartCapture capture, final ImportTraversal traversal, final ProtectionScope protection) {
        boolean childrenVisited = false;
        try {
            if (!prepareTransferEncoding(currentPart, capture, true)) return;
            // Interpret the effective root and ordinary body containers; an attached child multipart stays one resource.
            final Object supplied = parseContent(currentPart);
            if (supplied instanceof InputStream) {
                try {
                    capture.read((InputStream) supplied);
                } catch (final IOException failure) {
                    throw new MimeMessageParseException(MimeMessageParseException.ERROR_PARSING_CONTENT, failure);
                }
                capture.unrepresented("The MIME reader supplied a multipart stream without interpreted child parts");
                return;
            }
			final Multipart mp = (Multipart) supplied;
            try {
                selectOuterDeliveryStatusPart(currentPart, mp, parsedComponents, traversal);
            } catch (final MimeMessageParseException failure) {
                if (!InputInspection.recognizedFailure(failure)) throw failure;
                capture.problem(EmailProblem.Code.CONTENT_NOT_REPRESENTED, null, InputInspection.describe(failure));
            }
			final boolean childrenInsideRelated = insideRelated || isMimeType(currentPart, "multipart/related", capture);
            final boolean alternativeChildren = isMimeType(currentPart, "multipart/alternative", capture);
            final boolean relatedChildren = isMimeType(currentPart, "multipart/related", capture);
            final String relatedRoot = relatedChildren ? relatedStart(currentPart) : null;
            boolean allChildren = true;
			for (int i = 0, count = countBodyParts(mp); i < count; i++) {
                final ImportTraversal childTraversal = traversal.child("children[" + i + "]");
                if (!childTraversal.admit()) {
                    allChildren = false;
                    capture.problem(EmailProblem.Code.IMPORT_LIMIT_EXCEEDED, null, childTraversal.stopDescription());
                    // All remaining children of this owner have the same depth; one bounded observation describes the skipped suffix.
                    break;
                }
                final String location = capture.getLocation() + "/children[" + i + "]";
                final MimeBodyPart child;
                try {
                    child = getBodyPartAtIndex(mp, i);
                } catch (final MimeMessageParseException failure) {
                    if (!InputInspection.recognizedFailure(failure)) throw failure;
                    allChildren = false;
                    final MimePartCapture unavailable = new MimePartCapture(parsedComponents.inspection, location, Collections.emptyList());
                    capture.add(unavailable);
                    unavailable.problem(EmailProblem.Code.CONTENT_NOT_REPRESENTED, null, InputInspection.describe(failure));
                    continue;
                }
                final MimePartCapture childCapture = parsedComponents.inspection.beginPart(child, location);
                capture.add(childCapture);
				if (i == 1 && signedContainerArtifact(currentPart, child)) {
                    childCapture.represented();
                    continue;
                }
				final boolean relatedBody = relatedChildren && (relatedRoot == null ? i == 0 : relatedRoot.equals(extractCID(childCapture.raw("Content-ID"))));
                if (alternativeChildren) parsedComponents.activeAlternatives.addLast(new AlternativeChoice(capture.getLocation(), i));
                try {
                    parseMimePartTree(child, parsedComponents, fetchAttachmentData, childrenInsideRelated, childCapture, childTraversal, protection, alternativeChildren || relatedBody);
                } finally {
                    if (alternativeChildren) parsedComponents.activeAlternatives.removeLast();
                }
			}
            childrenVisited = allChildren;
        } finally {
            if (!childrenVisited) parsedComponents.inspection.noteUnvisitedStructure();
        }
    }

    private static String relatedStart(final MimePart owner) {
        try { return extractCID(new ContentType(owner.getContentType()).getParameter("start")); }
        catch (final MessagingException unavailable) { return null; }
    }

    private static boolean signedContainerArtifact(final MimePart parent, final MimePart child) {
        try {
            if (!parent.isMimeType("multipart/signed")) return false;
            final String protocol = new ContentType(parent.getContentType()).getParameter("protocol");
            if (protocol == null) return child.isMimeType("application/pkcs7-signature") || child.isMimeType("application/x-pkcs7-signature") || child.isMimeType("application/pgp-signature");
            return protocol != null && (protocol.equalsIgnoreCase("application/pkcs7-signature")
                    || protocol.equalsIgnoreCase("application/x-pkcs7-signature") || protocol.equalsIgnoreCase("application/pgp-signature"))
                    && child.isMimeType(protocol);
        } catch (final MessagingException unavailable) {
            return false;
        }
    }

    private static boolean inspectOuterReportContainer(final MimePart part, final InputInspection inspection) {
        if (inspection.isContentTypeReadUnavailable()) return false;
        try {
            return DeliveryStatusReportParser.isReportContainer(part);
        } catch (final MimeMessageParseException failure) {
            if (!InputInspection.recognizedFailure(failure)) throw failure;
            inspection.noteUnreadableContentType(failure);
            return false;
        }
    }

	private static void selectOuterDeliveryStatusPart(final MimePart currentPart, final Multipart parts,
			final ParsedMimeMessageComponents parsedComponents, final ImportTraversal traversal) {
		if (!(currentPart instanceof MimeMessage) || !parsedComponents.reportContainer) {
			return;
		}
		int selectedIndex = -1;
		for (int index = 0, count = countBodyParts(parts); index < count; index++) {
            // Classification is selected import work too; later payload traversal reuses these admitted occurrences.
            if (!traversal.child("children[" + index + "]").admit()) {
                parsedComponents.deliveryStatusPart = null;
                parsedComponents.deliveryStatusReport = DeliveryStatusReportParser.unavailableReport(
                        "Import limits prevented inspecting every direct status candidate; no unique complete report was established.");
                return;
            }
            final MimePart candidate = getBodyPartAtIndex(parts, index);
			if (DeliveryStatusReportParser.isStatusPart(candidate)) {
                parsedComponents.deliveryStatusCandidates.add(candidate);
				if (selectedIndex != -1) {
					parsedComponents.deliveryStatusReport = DeliveryStatusReportParser.unavailableReport(
							"The notification contains multiple delivery-status parts; their results were left unavailable because there is no single report to use.");
					return;
				}
				selectedIndex = index;
			}
		}
		if (selectedIndex != -1) {
			// Recover a uniquely identifiable direct child, never a status part inside an attached or nested message.
			parsedComponents.deliveryStatusPart = getBodyPartAtIndex(parts, selectedIndex);
			if (selectedIndex != 1) {
				parsedComponents.deliveryStatusWarning = "The notification's delivery-status part is out of order; the only matching part was recovered.";
			}
		}
	}

	private static void parseDataSource(@NotNull MimePart currentPart, @NotNull ParsedMimeMessageComponents parsedComponents,
			boolean fetchAttachmentData, String disposition, final boolean insideRelated, final MimePartCapture capture, final ProtectionScope protection,
            final ImportTraversal traversal) {
        final String contentID = interpretReceivedContentId(recoverMetadata(capture, "Content-ID", () -> parseContentID(currentPart),
                () -> capture.raw("Content-ID")));
		final boolean statusPart = currentPart == parsedComponents.deliveryStatusPart;
		final MimeDataSource mimeDataSource = parseAttachment(contentID, currentPart,
                createDataSource(currentPart, fetchAttachmentData, statusPart, capture), capture);
        if (parsedComponents.inspection.needsCompleteProjection()) {
            noteOpaqueStructure(capture, parsedComponents.inspection);
        }
		if (statusPart) {
			// Reuse bytes already cached for attachment conversion; do not fetch the original status body again.
			parsedComponents.deliveryStatusReport = DeliveryStatusReportParser.parse(mimeDataSource.getDataSource(), parsedComponents.deliveryStatusWarning, capture);
		}
		final boolean isAttachment = Part.ATTACHMENT.equalsIgnoreCase(disposition);
		final boolean isInline = Part.INLINE.equalsIgnoreCase(disposition);
		final boolean implicitRelatedImage = disposition == null && insideRelated && isMimeType(currentPart, "image/*", capture);

		if (disposition != null && !isAttachment && !isInline) {
			LOGGER.warn("Content-Disposition '{}' for data source not recognized (it should be either 'attachment' or 'inline'). Skipping body part", disposition);
		}

		// Preserve every occurrence until its association with the selected HTML is known.
		parsedComponents.attachmentList.add(mimeDataSource);
        // A non-body effective root can be retained as one resource; it is not an independently attached child.
        if (currentPart instanceof MimeMessage) parsedComponents.rootRepresentedAsResource = true;
        parsedComponents.resourceSources.put(mimeDataSource, capture);
        parsedComponents.resourceProtection.put(mimeDataSource, protection);
        parsedComponents.resourceTraversals.put(mimeDataSource, traversal);
        if (parsedComponents.protectedBodyParts.contains(capture)) parsedComponents.bodyContainers.add(mimeDataSource);
        capture.deferRepresentation();
		if (isInline && contentID != null) {
			parsedComponents.explicitInlineResources.add(mimeDataSource);
		}
		if (contentID != null && implicitRelatedImage) {
			parsedComponents.implicitRelatedImages.add(mimeDataSource);
		}
	}

    private static void noteOpaqueStructure(final MimePartCapture capture, final InputInspection inspection) {
        // A resource's metadata getter may open a deferred URL; completeness uses only headers already captured.
        final String suppliedType = capture.raw("Content-Type");
        if (suppliedType == null || capture.hasMultipleHeaders("Content-Type")) {
            inspection.noteUnvisitedStructure();
            return;
        }
        try {
            final ContentType type = new ContentType(suppliedType);
            if (type.match("multipart/*") || type.match("message/rfc822") || type.match("message/global")) {
                inspection.noteUnvisitedStructure();
            }
        } catch (final ParseException unavailableType) {
            inspection.noteUnvisitedStructure();
        }
    }

	@Nullable
	private static String checkContentTransferEncoding(@NotNull final ParsedMimeMessageComponents parsedComponents,
            final MimePartCapture capture) {
		final String contentTransferEncoding = interpretedTransferEncoding(capture);
		if (parsedComponents.contentTransferEncoding == null && contentTransferEncoding != null) {
			parsedComponents.contentTransferEncoding = contentTransferEncoding;
		}
		return contentTransferEncoding;
	}

	private static MimeDataSource parseAttachment(@Nullable final String contentId, final @NotNull MimePart mimePart, final DataSource ds,
            final MimePartCapture capture) {
		return MimeDataSource.builder()
				.name(parseResourceNameOrUnnamed(contentId, recoverMetadata(capture, "Content-Disposition",
                        () -> parseFileNameWithoutGenericFallback(mimePart), () -> sourceFileName(capture))))
				.dataSource(ds)
				.contentDescription(recoverMetadata(capture, "Content-Description", () -> parseContentDescription(mimePart),
                        () -> capture.raw("Content-Description")))
				.contentTransferEncoding(interpretedTransferEncoding(capture))
				.contentId(extractCID(contentId))
				.build();
	}

    private static <T> T recoverMetadata(final MimePartCapture capture, final String field,
            final Supplier<T> read, final Supplier<T> fallback) {
        try {
            return read.get();
        } catch (final MimeMessageParseException failure) {
            if (!InputInspection.recognizedFailure(failure)) {
                throw failure;
            }
            capture.headerProblem(InputInspection.causedBy(failure, UnsupportedEncodingException.class)
                            ? EmailProblem.Code.VALUE_NOT_DECODED : EmailProblem.Code.FIELD_NOT_INTERPRETED,
                    field, capture.raw(field), InputInspection.describe(failure));
            return fallback.get();
        }
    }

    @Nullable
    private static String sourceFileName(final MimePartCapture capture) {
        final String disposition = capture.raw("Content-Disposition");
        if (disposition != null) {
            try {
                final String name = new ContentDisposition(disposition).getParameter("filename");
                if (name != null) return name;
            } catch (final ParseException unavailable) {
                // Keep the original naming header in source data; the independent type may still provide a name.
            }
        }
        final String type = capture.raw("Content-Type");
        if (type != null) {
            try {
                return new ContentType(type).getParameter("name");
            } catch (final ParseException unavailable) {
                // No unambiguous name can be supplied from the received metadata.
            }
        }
        return null;
    }

    @Nullable
    private static String interpretedTransferEncoding(final MimePartCapture capture) {
        final String value = capture.transferEncoding();
        if (value != null) {
            try {
                ContentTransferEncoding.byEncoder(value);
            } catch (final IllegalArgumentException unsupported) {
                // The selected reader owns decoding support; SJM's optional output hint is a narrower vocabulary.
                return null;
            }
        }
        return value;
    }

    private static boolean prepareTransferEncoding(final MimePart part, final MimePartCapture capture, final boolean externalReadSelected) {
        final String[] declarations;
        try {
            declarations = part.getHeader("Content-Transfer-Encoding");
        } catch (final MessagingException failure) {
            throw new MimeMessageParseException(MimeMessageParseException.ERROR_GETTING_CONTENT_TRANSFER_ENCODING, failure);
        }
        if (declarations == null || declarations.length == 0) {
            capture.transferEncoding(null);
            return true;
        }
        if (declarations.length == 1 && declarations[0] != null) {
            try {
                final HeaderTokenizer tokens = new HeaderTokenizer(declarations[0], HeaderTokenizer.MIME);
                final HeaderTokenizer.Token token = tokens.next();
                if (token.getType() == HeaderTokenizer.Token.ATOM && tokens.next().getType() == HeaderTokenizer.Token.EOF) {
                    capture.transferEncoding(token.getValue());
                    return true;
                }
            } catch (final ParseException malformed) {
                // No transfer decoder may choose a partial token from an invalid declaration.
            }
        }
        capture.invalidTransferEncoding(part, externalReadSelected,
                "Content-Transfer-Encoding must contain one unambiguous token in one header occurrence");
        return false;
    }

	private static void parseHeader(final Header header, @NotNull final ParsedMimeMessageComponents parsedComponents, final InputInspection inspection) {
        final String name = inspection.recover("header-name", header.getName(), () -> decodeText(header.getName()), header::getName);
        if (!name.matches("[!-9;-~]+")) {
            inspection.record(EmailProblem.Code.FIELD_NOT_INTERPRETED, "header-name", header.getName(),
                    "The received field name cannot be used as an outgoing header name");
            return;
        }
        if (header.getValue() == null) {
            parsedComponents.headers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(null);
            return;
        }
        if (HeadersToIgnoreWhenParsingExternalEmails.shouldIgnoreHeader(name) || name.equalsIgnoreCase("Message-ID")
                || org.simplejavamail.internal.email.HeaderRoles.classify(name) == org.simplejavamail.internal.email.HeaderRoles.Role.DELIVERY_HISTORY && !name.equalsIgnoreCase("Return-Path")
                || org.simplejavamail.internal.email.HeaderRoles.classify(name) == org.simplejavamail.internal.email.HeaderRoles.Role.MIME_STRUCTURE) {
            parsedComponents.headers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(MimeUtility.unfold(header.getValue()));
            return;
        }
        final DecodedHeader decodedHeader = name.equalsIgnoreCase("Message-ID") ? new DecodedHeader(name, header.getValue())
                : inspection.recover(name, header.getValue(), () -> DecodedHeader.of(header), () -> new DecodedHeader(name, header.getValue()));
        if (isGenericRootHeader(name) && !inspection.decodingFailed(name, header.getValue()) && InputInspection.unavailableCharset(header.getValue())) {
            inspection.record(EmailProblem.Code.VALUE_NOT_DECODED, name, header.getValue(),
                    "The received header charset is unavailable; the complete original value is retained");
        }
        parsedComponents.headers.computeIfAbsent(decodedHeader.getName(), ignored -> new ArrayList<>()).add(MimeUtility.unfold(decodedHeader.getValue()));
        if (inspection.decodingFailed(name, header.getValue()) && (name.equalsIgnoreCase("Disposition-Notification-To")
                || name.equalsIgnoreCase("Return-Receipt-To") || name.equalsIgnoreCase("Return-Path"))) {
            return;
        }

		if (isEmailHeader(decodedHeader, MessageHeader.DISPOSITION_NOTIFICATION_TO.getName())) {
			parsedComponents.dispositionNotificationToRecipients.addAll(createAddressesFromEncodedHeader(header));
		} else if (isEmailHeader(decodedHeader, MessageHeader.RETURN_RECEIPT_TO.getName())) {
			parsedComponents.returnReceiptToRecipients.addAll(createAddressesFromEncodedHeader(header));
		} else if (decodedHeader.getName().equalsIgnoreCase(MessageHeader.RETURN_PATH.getName())) {
			parseReturnPathHeader(header, parsedComponents);
		}
	}

	static boolean isGenericRootHeader(final String name) {
		return !HeadersToIgnoreWhenParsingExternalEmails.shouldIgnoreHeader(name)
				&& !name.equalsIgnoreCase(MessageHeader.MESSAGE_ID.getName())
				&& !name.equalsIgnoreCase(MessageHeader.DISPOSITION_NOTIFICATION_TO.getName())
				&& !name.equalsIgnoreCase(MessageHeader.RETURN_RECEIPT_TO.getName())
				&& !name.equalsIgnoreCase(MessageHeader.RETURN_PATH.getName())
				&& !name.equalsIgnoreCase("Content-Description");
	}

	private static void parseReturnPathHeader(final Header header, final ParsedMimeMessageComponents parsedComponents) {
		try {
			final String source = header.getValue();
			final InternetAddress[] addresses = source.trim().isEmpty() || source.trim().equals("<>")
					? new InternetAddress[0] : InternetAddress.parseHeader(source, true);
			if (addresses.length > 0) {
				parsedComponents.bounceToAddress = addresses[0];
				return;
			}
		} catch (final AddressException unparseable) {
			// Keep the source header when no bounce address can be interpreted.
		}
		parsedComponents.bounceToAddress = null;
	}

	private static boolean isEmailHeader(DecodedHeader header, String emailHeaderName) {
		return header.getName().equalsIgnoreCase(emailHeaderName) &&
				!valueNullOrEmpty(header.getValue()) &&
				!valueNullOrEmpty(header.getValue().trim()) &&
				!header.getValue().equals("<>");
	}

	@SuppressWarnings("WeakerAccess")
	@NotNull
	public static String parseFileName(@NotNull final Part currentPart) {
		final String fileName = parseFileNameWithoutGenericFallback(currentPart);
		return fileName != null ? fileName : "UnknownAttachment";
	}

	@Nullable
	private static String parseFileNameWithoutGenericFallback(@NotNull final Part currentPart) {
		try {
			final String fileName = readFileNameWithDispositionRecovery(currentPart);
			if (fileName != null) {
				return decodeText(fileName);
			} else {
				// replicate behavior from Thunderbird
				final String[] contentTypes = currentPart.getHeader(MessageHeader.CONTENT_TYPE.getName());
				if (contentTypes != null && Arrays.asList(contentTypes).contains("message/rfc822")) {
					return "ForwardedMessage.eml";
				}
			}
			return null;
		} catch (final MessagingException e) {
			throw new MimeMessageParseException(MimeMessageParseException.ERROR_GETTING_FILENAME, e);
		}
	}

	@Nullable
	private static String readFileNameWithDispositionRecovery(final Part currentPart) throws MessagingException {
		try {
			return currentPart.getFileName();
		} catch (final ParseException malformedParameter) {
			if (recoverMalformedDisposition(currentPart) == null) {
				throw malformedParameter;
			}
			final String[] contentTypes = currentPart.getHeader("Content-Type");
			if (contentTypes != null && contentTypes.length > 0) {
				try {
					return new ContentType(contentTypes[0]).getParameter("name");
				} catch (final ParseException malformedType) {
					// Unusable independent naming metadata keeps the established missing-name fallback.
				}
			}
			return null;
		}
	}
	
    /** Reads provider-returned text streams without transferring ownership of their DataSource. */
    private static String parseTextContent(final MimePart currentPart, final MimePartCapture capture) {
        final Object content = parseContent(currentPart);
        capture.readerText(content);
        if (!(content instanceof InputStream)) {
            return String.valueOf(content);
        }
        String decoded = null;
        try (InputStream input = (InputStream) content) {
            Charset encoding;
            try {
                final String declaredCharset = new ContentType(currentPart.getContentType()).getParameter("charset");
                // Match the ordinary text handler, including Jakarta Mail's MIME-to-Java charset aliases.
                final String mimeCharset = declaredCharset != null ? declaredCharset : "us-ascii";
                try {
                    encoding = Charset.forName(MimeUtility.javaCharset(mimeCharset));
                } catch (IllegalArgumentException invalidCharset) {
                    final UnsupportedEncodingException unsupported = new UnsupportedEncodingException(mimeCharset);
                    unsupported.initCause(invalidCharset);
                    throw unsupported;
                }
            } catch (final IOException | MessagingException interpretationFailure) {
                rethrowIfInterrupted(interpretationFailure);
                // The declared interpretation still owns the primary failure; capture available bytes without trying another decoder.
                try {
                    capture.readOpen(input);
                } catch (final IOException acquisitionFailure) {
                    interpretationFailure.addSuppressed(acquisitionFailure);
                }
                throw interpretationFailure;
            }
            decoded = decodeBodyBytes(capture.readOpen(input), encoding);
            return decoded;
        } catch (IOException | MessagingException failure) {
            rethrowIfInterrupted(failure);
            if (decoded != null && failure instanceof IOException) {
                capture.problem(EmailProblem.Code.RESOURCE_CLOSE_FAILED, null, InputInspection.describe(failure));
                return decoded;
            }
            if (failure instanceof CapturedMimeContent.Failure && failure.getCause() instanceof IOException) {
                final IOException primary = (IOException) failure.getCause();
                for (final Throwable suppressed : failure.getSuppressed()) primary.addSuppressed(suppressed);
                throw new MimeMessageParseException(MimeMessageParseException.ERROR_PARSING_CONTENT, primary);
            }
            throw new MimeMessageParseException(MimeMessageParseException.ERROR_PARSING_CONTENT, failure);
        }
    }

	/**
	 * @return Returns the "content" part as String from the Calendar content type
     */
	@NotNull
    public static String parseCalendarContent(@NotNull MimePart currentPart) {
        return parseCalendarContent(currentPart, null);
    }

    private static String parseCalendarContent(final MimePart currentPart, final MimePartCapture capture) {
		final Charset encoding;
		try {
			final DataHandler dataHandler = currentPart.getDataHandler();
			encoding = CalendarDataContentHandler.readCharset(dataHandler.getContentType(), UTF_8);
			CalendarDataContentHandler.configure(dataHandler);
		} catch (MessagingException | UnsupportedEncodingException e) {
			throw new MimeMessageParseException(MimeMessageParseException.ERROR_PARSING_CALENDAR_CONTENT, e);
		}
        final Object content;
        try {
            content = parseContent(currentPart);
        } catch (final MimeMessageParseException failure) {
            final CapturedMimeContent.Failure captured = CapturedMimeContent.failureIn(failure);
            if (capture == null || captured == null || !captured.isFullyCaptured() || captured.isInterpretationFailure()) throw failure;
            try {
                return decodeBodyBytes(capture.closedAfterCompleteRead(captured), encoding);
            } catch (final IOException interpretationFailure) {
                throw new MimeMessageParseException(MimeMessageParseException.ERROR_PARSING_CALENDAR_CONTENT, interpretationFailure);
            }
        }
        if (capture != null) capture.readerText(content);
        if (content instanceof InputStream) {
            if (capture != null) {
                try {
                    return decodeBodyBytes(capture.read((InputStream) content), encoding);
                } catch (final IOException failure) {
                    throw new MimeMessageParseException(MimeMessageParseException.ERROR_PARSING_CALENDAR_CONTENT, failure);
                }
            }
            try (InputStream calendarContent = (InputStream) content) {
                return decodeBodyBytes(MiscUtil.readInputStreamToBytes(calendarContent), encoding);
            } catch (IOException e) {
                throw new MimeMessageParseException(MimeMessageParseException.ERROR_PARSING_CALENDAR_CONTENT, e);
            }
        }
        return String.valueOf(content);
    }

    private static String decodeBodyBytes(final byte[] bytes, final Charset encoding) throws IOException {
        return CapturedMimeContent.decode(bytes, encoding);
    }

	/**
	 * @return Returns the "method" part from the Calendar content type (such as "{@code text/calendar; charset="UTF-8"; method="REQUEST"}").
	 * The MIME parameter name is case-insensitive; the calendar body's METHOD is used when the header supplies no method.
	 * The returned value retains its source spelling for interpretation by the Email mapper.
	 */
	@SuppressWarnings("WeakerAccess")
	public static String parseCalendarMethod(@NotNull MimePart currentPart, @NotNull String calendarContent) {
        final String contentType;
		try {
			contentType = currentPart.getDataHandler().getContentType();
		} catch (final MessagingException e) {
			throw new MimeMessageParseException(MimeMessageParseException.ERROR_GETTING_CALENDAR_CONTENTTYPE, e);
		}

		return parseCalendarHeaderMethod(contentType)
				.orElseGet(() -> findFirstMatch(CALENDAR_BODY_METHOD_PATTERN, calendarContent)
						.orElseThrow(() -> new IllegalArgumentException("Calendar METHOD not found in bodypart's content type or calendar content itself")));
    }

	private static Optional<String> parseCalendarHeaderMethod(final String contentType) {
		try {
			final Optional<String> method = ofNullable(new ContentType(contentType).getParameter("method"))
					.flatMap(value -> findFirstMatch(CALENDAR_METHOD_TOKEN_PATTERN, value));
			// Keep the first genuine method occurrence: ContentType otherwise replaces repeated parameter values with the last one.
			return findCalendarHeaderMethod(contentType).or(() -> method);
		} catch (final ParseException malformedContentType) {
			// Retain the existing narrow recovery for malformed headers instead of rejecting otherwise readable calendars.
			return findCalendarHeaderMethod(contentType);
		}
	}

	private static Optional<String> findCalendarHeaderMethod(final String contentType) {
		final HeaderTokenizer tokens = new HeaderTokenizer(contentType, HeaderTokenizer.MIME);
		try {
			for (HeaderTokenizer.Token token = tokens.next(); token.getType() != HeaderTokenizer.Token.EOF; token = tokens.next()) {
				if (token.getType() != ';') {
					continue;
				}
				final HeaderTokenizer.Token name = tokens.peek();
				if (name.getType() != HeaderTokenizer.Token.ATOM || !name.getValue().equalsIgnoreCase("method")) {
					continue;
				}
				tokens.next();
				if (tokens.peek().getType() != '=') {
					continue;
				}
				tokens.next();
				final HeaderTokenizer.Token value = tokens.peek();
				if (value.getType() != HeaderTokenizer.Token.ATOM && value.getType() != HeaderTokenizer.Token.QUOTEDSTRING) {
					continue;
				}
				final Optional<String> method = findFirstMatch(CALENDAR_METHOD_TOKEN_PATTERN, tokens.next().getValue());
				if (method.isPresent()) {
					return method;
				}
			}
		} catch (final ParseException unreadableParameter) {
			// An unterminated token cannot identify a genuine method parameter; keep the existing body fallback.
		}
		return Optional.empty();
	}

	@SuppressWarnings("WeakerAccess")
	@Nullable
	public static String parseContentID(@NotNull final MimePart currentPart) {
		try {
			return ofNullable(currentPart.getContentID())
					.map(MimeMessageParser::decodeText)
					.orElse(null);
		} catch (final MessagingException e) {
			throw new MimeMessageParseException(MimeMessageParseException.ERROR_GETTING_CONTENT_ID, e);
		}
	}

	@Nullable
	private static String interpretReceivedContentId(@Nullable final String received) {
		if (received == null) {
			return null;
		}
		try {
			final HeaderTokenizer prefix = new HeaderTokenizer(received, HeaderTokenizer.RFC822);
			if (prefix.next().getType() == '<') {
				final String remainder = prefix.getRemainder();
				final int closing = remainder == null ? -1 : findContentIdEnd(remainder);
				if (closing > 0) {
					final String identity = remainder.substring(0, closing);
					final HeaderTokenizer suffix = new HeaderTokenizer(remainder.substring(closing + 1), HeaderTokenizer.RFC822);
					if (suffix.next().getType() == HeaderTokenizer.Token.EOF) {
						// Keep the established bracketed map key while discarding only valid external CFWS.
						return '<' + identity + '>';
					}
				}
			}
		} catch (final ParseException malformedHeader) {
			// Retain the existing interpretation of malformed or competing source text rather than guessing an ID.
		}
		return received;
	}

	private static int findContentIdEnd(final String remainder) {
		// Keep the core opaque, including quoted text and domain literals, without copying it once per token.
		boolean quoted = false;
		boolean domainLiteral = false;
		boolean escaped = false;
		for (int index = 0; index < remainder.length(); index++) {
			final char current = remainder.charAt(index);
			if (quoted) {
				if (current == '>' || current == '\r' || current == '\n') {
					return -1;
				} else if (escaped) {
					escaped = false;
				} else if (current == '\\') {
					escaped = true;
				} else if (current == '"') {
					quoted = false;
				}
			} else if (domainLiteral) {
				if (current == '[' || current == '>' || current == '\\'
						|| current == ' ' || current == '\t' || current == '\r' || current == '\n') {
					return -1;
				} else if (current == ']') {
					domainLiteral = false;
				}
			} else if (current == '>') {
				return index;
			} else if (current == '<' || current == '(' || current == ')' || current == ']'
					|| current == ' ' || current == '\t' || current == '\r' || current == '\n') {
				return -1;
			} else if (current == '"') {
				quoted = true;
			} else if (current == '[') {
				domainLiteral = true;
			}
		}
		return -1;
	}

	@SuppressWarnings("WeakerAccess")
	public static MimeBodyPart getBodyPartAtIndex(final Multipart parentMultiPart, final int index) {
		try {
			return (MimeBodyPart) parentMultiPart.getBodyPart(index);
		} catch (final MessagingException e) {
			throw new MimeMessageParseException(format(MimeMessageParseException.ERROR_GETTING_BODYPART_AT_INDEX, index), e);
		}
	}

	@SuppressWarnings("WeakerAccess")
	public static int countBodyParts(final Multipart mp) {
		try {
			return mp.getCount();
		} catch (final MessagingException e) {
			throw new MimeMessageParseException(MimeMessageParseException.ERROR_PARSING_MULTIPART_COUNT, e);
		}
	}

	@SuppressWarnings({"WeakerAccess", "unchecked"})
	public static <T> T parseContent(@NotNull final MimePart currentPart) {
		try {
			return (T) currentPart.getContent();
		} catch (IOException | MessagingException e) {
			throw new MimeMessageParseException(MimeMessageParseException.ERROR_PARSING_CONTENT, e);
		}
	}

	@SuppressWarnings("WeakerAccess")
	@Nullable
	public static String parseDisposition(@NotNull final MimePart currentPart) {
        return parseDisposition(currentPart, null);
    }

    @Nullable
    private static String parseDisposition(final MimePart currentPart, final MimePartCapture capture) {
		try {
            final String[] declarations = currentPart.getHeader("Content-Disposition");
            if (declarations != null && declarations.length > 1
                    || capture != null && capture.hasMultipleHeaders("Content-Disposition")) {
                throw new ParseException("Content-Disposition must have one unambiguous header occurrence");
            }
			try {
				return currentPart.getDisposition();
			} catch (final MessagingException unavailable) {
                if (capture != null && (declarations == null || declarations.length == 0)
                        && !capture.hasHeader("Content-Disposition")) {
                    org.simplejavamail.internal.util.InterruptedIoGuard.rethrowIfInterrupted(unavailable);
                    capture.headerProblem(EmailProblem.Code.FIELD_NOT_INTERPRETED, "Content-Disposition", null,
                            InputInspection.describe(new MimeMessageParseException(MimeMessageParseException.ERROR_PARSING_DISPOSITION, unavailable)));
                    return null;
                }
                if (!(unavailable instanceof ParseException)) {
                    throw unavailable;
                }
				final String recovered = recoverMalformedDisposition(currentPart);
				if (recovered == null) {
					throw unavailable;
				}
                if (capture != null) {
                    capture.headerProblem(EmailProblem.Code.FIELD_NOT_INTERPRETED, "Content-Disposition",
                            capture.raw("Content-Disposition"), unavailable.getMessage());
                }
				return recovered;
			}
		} catch (final MessagingException e) {
			throw new MimeMessageParseException(MimeMessageParseException.ERROR_PARSING_DISPOSITION, e);
		}
	}

	@Nullable
	private static String recoverMalformedDisposition(final Part currentPart) throws MessagingException {
		final String[] headers = currentPart.getHeader("Content-Disposition");
		if (headers == null || headers.length != 1) {
			return null;
		}
		try {
			new ContentDisposition(headers[0]);
			return null;
		} catch (final ParseException malformedParameters) {
			try {
				final HeaderTokenizer tokens = new HeaderTokenizer(headers[0], HeaderTokenizer.MIME);
				final HeaderTokenizer.Token disposition = tokens.next();
				final int next = tokens.next().getType();
				if (disposition.getType() == HeaderTokenizer.Token.ATOM
						&& (Part.ATTACHMENT.equalsIgnoreCase(disposition.getValue()) || Part.INLINE.equalsIgnoreCase(disposition.getValue()))
						&& (next == ';' || next == HeaderTokenizer.Token.EOF)) {
					return disposition.getValue();
				}
			} catch (final ParseException malformedPrefix) {
				// An unreadable leading token cannot establish an attachment or inline role.
			}
			return null;
		}
	}

	@NotNull
	private static String parseResourceNameOrUnnamed(@Nullable final String possibleWrappedContentID, @Nullable final String fileName) {
		String resourceName = parseResourceName(possibleWrappedContentID, fileName);
		return valueNullOrEmpty(resourceName) ? "unnamed" : resourceName;
	}

	@NotNull
	private static String parseResourceName(@Nullable String possibleWrappedContentID, @Nullable String fileName) {
		if (valueNullOrEmpty(fileName) && !valueNullOrEmpty(possibleWrappedContentID)) {
			return extractCID(possibleWrappedContentID);
		} else {
			return fileName != null ? fileName : "UnknownAttachment";
		}
	}

	@SuppressWarnings("WeakerAccess")
	@NotNull
	public static List<Header> retrieveAllHeaders(@NotNull final MimePart part) {
		try {
			return Collections.list(part.getAllHeaders());
		} catch (final MessagingException e) {
			throw new MimeMessageParseException(MimeMessageParseException.ERROR_GETTING_ALL_HEADERS, e);
		}
	}

	@NotNull
	private static List<InternetAddress> createAddressesFromEncodedHeader(final Header header) {
		try {
			return parseInternetAddresses(InternetAddress.parseHeader(header.getValue(), false));
		} catch (final AddressException unreadable) {
			// Preserve unusual received syntax for normal validation instead of discarding a destination.
			final InternetAddress original = new InternetAddress();
			original.setAddress(header.getValue());
			return Collections.singletonList(original);
		}
	}

	/**
	 * Checks whether the MimePart contains an object of the given mime type.
	 *
	 * @param part     the current MimePart
	 * @param mimeType the mime type to check
	 * @return {@code true} if the MimePart matches the given mime type, {@code false} otherwise
	 */
	@SuppressWarnings("WeakerAccess")
	public static boolean isMimeType(@NotNull final MimePart part, @NotNull final String mimeType) {
        return isMimeType(part, mimeType, null);
    }

    private static boolean isMimeType(final MimePart part, final String mimeType, final MimePartCapture capture) {
		// Do not use part.isMimeType(String) as it is broken for MimeBodyPart
		// and does not really check the actual content type.

		try {
			final ContentType contentType = new ContentType(retrieveDataHandler(part).getContentType());
			return contentType.match(mimeType);
		} catch (final ParseException ex) {
            if (capture != null) {
                capture.headerProblem(EmailProblem.Code.FIELD_NOT_INTERPRETED, "Content-Type", capture.raw("Content-Type"), ex.getMessage());
            }
			return retrieveContentType(part).equalsIgnoreCase(mimeType);
		}
	}

	@SuppressWarnings("WeakerAccess")
	public static String retrieveContentType(@NotNull final MimePart part) {
		try {
			return part.getContentType();
		} catch (final MessagingException e) {
			throw new MimeMessageParseException(MimeMessageParseException.ERROR_GETTING_CONTENT_TYPE, e);
		}
	}

	@SuppressWarnings("WeakerAccess")
	public static DataHandler retrieveDataHandler(@NotNull final MimePart part) {
		try {
			return part.getDataHandler();
		} catch (final MessagingException e) {
			throw new MimeMessageParseException(MimeMessageParseException.ERROR_GETTING_DATAHANDLER, e);
		}
	}

	/**
	 * Parses the MimePart to create a DataSource.
	 *
	 * @param part the current part to be processed
	 * @return the DataSource
	 */
	@NotNull
	private static DataSource createDataSource(@NotNull final MimePart part, final boolean fetchAttachmentData, final boolean statusPart,
            final MimePartCapture capture) {
		final DataSource supplied = retrieveDataHandler(part).getDataSource();
		final DataSource dataSource = statusPart ? DeliveryStatusReportParser.decodedStatusSource(part, supplied) : supplied;
        final String dataSourceName = parseDataSourceName(part, dataSource, capture);

		if (fetchAttachmentData) {
			final String contentType = MimeTypeUtil.parseOrDefault(dataSource.getContentType()).toString();
            final byte[] bytes;
            try {
                bytes = capture.read(retrieveInputStream(dataSource));
            } catch (final IOException failure) {
                throw new MimeMessageParseException(MimeMessageParseException.ERROR_READING_CONTENT, failure);
            }
			final ByteArrayDataSource result = new ByteArrayDataSource(bytes, contentType);
			result.setName(dataSourceName);
			return result;
		} else {
			return new NamedDataSource(dataSourceName, dataSource);
		}
	}

	@SuppressWarnings("WeakerAccess")
	public static InputStream retrieveInputStream(final DataSource dataSource) {
		try {
			return dataSource.getInputStream();
		} catch (final IOException e) {
			throw new MimeMessageParseException(MimeMessageParseException.ERROR_GETTING_INPUTSTREAM, e);
		}
	}

	@Nullable
    private static String parseDataSourceName(@NotNull final Part part, @NotNull final DataSource dataSource, final MimePartCapture capture) {
        final String suppliedName = dataSource.getName();
        if (valueNullOrEmpty(suppliedName)) {
            return recoverMetadata(capture, "Content-Disposition", () -> {
                final String fallback = parseFileName(part);
                return !valueNullOrEmpty(fallback) ? decodeText(fallback) : null;
            }, () -> sourceFileName(capture));
        }
        try {
            return decodeText(suppliedName);
        } catch (final MimeMessageParseException failure) {
            if (!InputInspection.recognizedFailure(failure)) throw failure;
            if (Objects.equals(suppliedName, sourceFileName(capture))) {
                capture.headerProblem(EmailProblem.Code.VALUE_NOT_DECODED, "Content-Disposition", capture.raw("Content-Disposition"),
                        InputInspection.describe(failure));
            } else {
                capture.propertyProblem(EmailProblem.Code.VALUE_NOT_DECODED, "reader.name", suppliedName, InputInspection.describe(failure));
            }
            return suppliedName;
        }
	}

	static byte @NotNull [] readContent(@NotNull final InputStream is) {
		try (InputStream content = is) {
			return MiscUtil.readInputStreamToBytes(content);
		} catch (final IOException e) {
			throw new MimeMessageParseException(MimeMessageParseException.ERROR_READING_CONTENT, e);
		}
	}

	@SuppressWarnings("WeakerAccess")
	@NotNull
	public static List<InternetAddress> parseToAddresses(@NotNull final MimeMessage mimeMessage) {
		return parseInternetAddresses(retrieveRecipients(mimeMessage, RecipientType.TO));
	}

	@SuppressWarnings("WeakerAccess")
	@NotNull
	public static List<InternetAddress> parseCcAddresses(@NotNull final MimeMessage mimeMessage) {
		return parseInternetAddresses(retrieveRecipients(mimeMessage, RecipientType.CC));
	}

	@SuppressWarnings("WeakerAccess")
	@NotNull
	public static List<InternetAddress> parseBccAddresses(@NotNull final MimeMessage mimeMessage) {
		return parseInternetAddresses(retrieveRecipients(mimeMessage, RecipientType.BCC));
	}

	@SuppressWarnings("WeakerAccess")
	@Nullable
	public static Address[] retrieveRecipients(@NotNull final MimeMessage mimeMessage, final RecipientType recipientType) {
		try {
			// return mimeMessage.getRecipients(recipientType); // can fail in strict mode, see https://github.com/bbottema/simple-java-mail/issues/227
			// workaround following (copied and modified from JavaMail internal code):
			// and while we're at it, properly decode the personal names
			val recipientHeader = mimeMessage.getHeader(getHeaderName(recipientType), ",");
			if (recipientHeader == null) {
				return null;
			}
			final InternetAddress[] addresses;
			try {
				addresses = InternetAddress.parseHeader(recipientHeader, false);
			} catch (final AddressException malformedAddress) {
				return rawAddressHeader(recipientHeader);
			}
			return Arrays.stream(addresses)
					.map(unchecked(ia -> new InternetAddress(ia.getAddress(), decodePersonalName(ia.getPersonal()))))
					.toArray(Address[]::new);
		} catch (final MessagingException e) {
			throw new MimeMessageParseException(format(MimeMessageParseException.ERROR_GETTING_RECIPIENTS, recipientType), e);
		}
	}

	private static String getHeaderName(RecipientType recipientType) {
		if (recipientType == RecipientType.TO) {
			return MessageHeader.TO.getName();
		} else if (recipientType == RecipientType.CC) {
			return MessageHeader.CC.getName();
		} else {
			Preconditions.assumeTrue(recipientType == RecipientType.BCC, "invalid recipient type: " + recipientType);
			return MessageHeader.BCC.getName();
		}
	}

	@Nullable
	private static String decodePersonalName(String personalName) {
		return personalName != null ? decodeText(personalName) : null;
	}

	@Nullable
	public static String parseContentDescription(@NotNull final MimePart mimePart) {
		try {
			return ofNullable(mimePart.getHeader("Content-Description", ","))
					.map(MimeMessageParser::decodeText)
					.orElse(null);
		} catch (final MessagingException e) {
			throw new MimeMessageParseException(MimeMessageParseException.ERROR_GETTING_CONTENT_DESCRIPTION, e);
		}
	}
	@Nullable
	public static String parseContentTransferEncoding(@NotNull final MimePart mimePart) {
		try {
			final String header = mimePart.getHeader(MessageHeader.CONTENT_TRANSFER_ENCODING.getName(), ",");
			if (header == null) {
				return null;
			}
			final String encoding = decodeText(header);
			try {
				// Jakarta Mail owns comment/escape/folding grammar. Unlike getEncoding(), require one unambiguous token.
				final HeaderTokenizer tokenizer = new HeaderTokenizer(encoding, HeaderTokenizer.MIME);
				final HeaderTokenizer.Token token = tokenizer.next();
				if (token.getType() == HeaderTokenizer.Token.ATOM && tokenizer.next().getType() == HeaderTokenizer.Token.EOF) {
					return token.getValue();
				}
			} catch (final ParseException e) {
				// Keep malformed input on the existing strict mapping path instead of recovering a partial encoding.
			}
			return encoding;
		} catch (final MessagingException e) {
			throw new MimeMessageParseException(MimeMessageParseException.ERROR_GETTING_CONTENT_TRANSFER_ENCODING, e);
		}
	}

	@NotNull
	static String decodeText(@NotNull final String result) {
		try {
			return MimeUtility.decodeText(result);
		} catch (final UnsupportedEncodingException e) {
			throw new MimeMessageParseException(MimeMessageParseException.ERROR_DECODING_TEXT, e);
		}
	}

	@NotNull
	private static List<InternetAddress> parseInternetAddresses(@Nullable final Address[] recipients) {
		final List<Address> addresses = (recipients != null) ? Arrays.asList(recipients) : new ArrayList<>();
		final List<InternetAddress> mailAddresses = new ArrayList<>();
		for (final Address address : addresses) {
			if (address instanceof InternetAddress) {
				mailAddresses.add((InternetAddress) address);
			}
		}
		return mailAddresses;
	}

	@SuppressWarnings("WeakerAccess")
	@Nullable
	public static InternetAddress parseFromAddress(@NotNull final MimeMessage mimeMessage) {
		return parseFromAddress(mimeMessage, null);
	}

	@Nullable
	private static InternetAddress parseFromAddress(final MimeMessage mimeMessage, @Nullable final InputInspection inspection) {
		try {
			final Address[] addresses;
			try {
				addresses = mimeMessage.getFrom();
			} catch (final AddressException malformedAddress) {
				return recoverFromAddressHeader(mimeMessage, malformedAddress, inspection)[0];
			}
			return (addresses == null || addresses.length == 0) ? null : (InternetAddress) addresses[0];
		} catch (final MessagingException e) {
			throw new MimeMessageParseException(MimeMessageParseException.ERROR_PARSING_FROMADDRESS, e);
		}
	}

	@SuppressWarnings("WeakerAccess")
	@NotNull
	public static List<InternetAddress> parseReplyToAddresses(@NotNull final MimeMessage mimeMessage) {
		return parseReplyToAddresses(mimeMessage, null);
	}

	@NotNull
	private static List<InternetAddress> parseReplyToAddresses(final MimeMessage mimeMessage, @Nullable final InputInspection inspection) {
		try {
			final Address[] addresses;
			try {
				addresses = mimeMessage.getReplyTo();
			} catch (final AddressException malformedAddress) {
				final InternetAddress[] retained = retainMalformedAddressHeader(mimeMessage.getHeader("Reply-To", ","), malformedAddress);
				return Arrays.asList(retained != null ? retained : recoverFromAddressHeader(mimeMessage, malformedAddress, inspection));
			}
			return parseInternetAddresses(addresses);
		} catch (final MessagingException e) {
			throw new MimeMessageParseException(MimeMessageParseException.ERROR_PARSING_REPLY_TO_ADDRESSES, e);
		}
	}

	private static InternetAddress[] recoverFromAddressHeader(final MimeMessage message, final AddressException originalFailure,
			@Nullable final InputInspection inspection)
			throws MessagingException {
		final String from = message.getHeader("From", ",");
		final String source = from != null ? from : message.getHeader("Sender", ",");
		final InternetAddress[] retained = retainMalformedAddressHeader(source, originalFailure);
		if (retained == null) {
			throw originalFailure;
		}
		if (from == null && inspection != null) {
			inspection.noteSenderRecovery(source, originalFailure.getMessage());
		}
		return retained;
	}

	/** Attribute a getter failure to its complete source field; empty fields retain their existing fallback, valid fields do not justify recovery. */
	@Nullable
	private static InternetAddress[] retainMalformedAddressHeader(@Nullable final String source, final AddressException originalFailure)
			throws AddressException {
		if (source == null) {
			return null;
		}
		final InternetAddress[] parsed;
		try {
			// Reparse only after a getter's AddressException; every successful Session/getter interpretation remains authoritative.
			parsed = InternetAddress.parseHeader(source, true);
		} catch (final AddressException malformedSource) {
			return rawAddressHeader(source);
		}
		if (parsed.length == 0) {
			return null;
		}
		throw originalFailure;
	}

	private static InternetAddress[] rawAddressHeader(@Nullable final String source) {
		final InternetAddress retained = rawAddress(source);
		return retained == null ? new InternetAddress[0] : new InternetAddress[] {retained};
	}

	@Nullable
	private static InternetAddress rawAddress(@Nullable final String source) {
		if (source == null) {
			return null;
		}
		final InternetAddress retained = new InternetAddress();
		retained.setAddress(source);
		return retained;
	}

	@Nullable
	public static String parseSubject(@NotNull final MimeMessage mimeMessage) {
		try {
			return mimeMessage.getSubject();
		} catch (final MessagingException e) {
			throw new MimeMessageParseException(MimeMessageParseException.ERROR_GETTING_SUBJECT, e);
		}
	}

	@SuppressWarnings("WeakerAccess")
	@Nullable
	public static String parseMessageId(@NotNull final MimeMessage mimeMessage) {
		try {
			return mimeMessage.getMessageID();
		} catch (final MessagingException e) {
			throw new MimeMessageParseException(MimeMessageParseException.ERROR_GETTING_MESSAGE_ID, e);
		}
	}

	@SuppressWarnings("WeakerAccess")
	@Nullable
	public static Date parseSentDate(@NotNull final MimeMessage mimeMessage) {
		try {
			return ImportedSentDateParser.parse(mimeMessage.getHeader("Date", null));
		} catch (final MessagingException e) {
			throw new MimeMessageParseException(MimeMessageParseException.ERROR_GETTING_SEND_DATE, e);
		}
	}

    static void moveInvalidEmbeddedResourcesToAttachments(final ParsedMimeMessageComponents parsed) {
        final Set<String> referenced = CidReferences.inHtml(parsed.getHtmlContent() == null ? "" : parsed.getHtmlContent());
        parsed.cidMap.clear();
        for (final String cid : referenced) {
            final List<MimeDataSource> candidates = new ArrayList<>();
            int strongest = -1;
            for (final MimeDataSource resource : parsed.attachmentList) {
                if (!cid.equals(resource.getContentId()) || parsed.bodyContainers.contains(resource)) continue;
                int association = 0;
                for (final BodyValue html : selectedBodies(parsed.htmlValues)) {
                    association = Math.max(association, commonOwner(html.source.getLocation(), parsed.resourceSources.get(resource).getLocation()));
                }
                if (association > strongest) { candidates.clear(); strongest = association; }
                if (association == strongest) candidates.add(resource);
            }
            if (candidates.size() == 1) {
                parsed.cidMap.put(cid, candidates.get(0));
            } else if (candidates.size() > 1) {
                parsed.resourceSources.get(candidates.get(0)).problem(EmailProblem.Code.CONTENT_NOT_REPRESENTED, cid,
                        "Multiple same-message resources match this selected HTML Content-ID; no unique association was established");
            }
        }
        final Set<MimeDataSource> selected = Collections.newSetFromMap(new IdentityHashMap<>());
        selected.addAll(parsed.cidMap.values());
        for (final MimeDataSource resource : parsed.attachmentList) {
            final String contentId = resource.getContentId();
            if (contentId != null && referenced.contains(contentId) && !selected.contains(resource)) {
                parsed.conflictingContentIds.add(resource);
            }
        }
        parsed.attachmentList.removeIf(resource -> selected.contains(resource)
                && (parsed.explicitInlineResources.contains(resource) || parsed.implicitRelatedImages.contains(resource)));
        parsed.explicitInlineResources.clear();
        parsed.implicitRelatedImages.clear();
    }

    private static int commonOwner(final String body, final String resource) {
        final int limit = Math.min(body.length(), resource.length());
        int shared = 0;
        for (int index = 0; index < limit && body.charAt(index) == resource.charAt(index); index++) {
            if (body.charAt(index) == '/') shared = index;
        }
        return shared;
    }

    private static void removeReferencedImplicitImageAttachments(final ParsedMimeMessageComponents parsed) {
        // Classification above owns both implicit and explicit inline occurrences after final body selection.
    }

	@Getter
	public static class ParsedMimeMessageComponents {
        @Getter(AccessLevel.NONE) private InputInspection inspection;
        @Getter(AccessLevel.NONE) private InputInspection selectedHeaders;
        @Getter(AccessLevel.NONE) private InputInspection carrierHeaders;
        @Getter(AccessLevel.NONE) private InputInspection subjectHeaders;
        @Getter(AccessLevel.NONE) private IncomingPartInterpreter partInterpreter;
        @Getter(AccessLevel.NONE) private ProtectionScope headerProtection = ProtectionScope.plain();
        @Getter(AccessLevel.NONE) private ProtectionScope carrierProtection = ProtectionScope.plain();
        @Getter(AccessLevel.NONE) private ProtectionScope subjectProtection = ProtectionScope.plain();
        @Getter(AccessLevel.NONE) private final Map<String, ProtectionScope> headerProtectionByName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        @Getter(AccessLevel.NONE) private final List<HeaderView> headerViews = new ArrayList<>();
        @Getter(AccessLevel.NONE) private HeaderView authoredView;
        @Getter(AccessLevel.NONE) private int unavailableAuthoredDepth = -1;
        private boolean authoredHeaderViewUnavailable;
        @Getter(AccessLevel.NONE) private boolean unavailableBody;
        private String nativeSubject;
        @Getter(AccessLevel.NONE) private final List<ProtectionScope> encounteredScopes = new ArrayList<>();
        @Getter(AccessLevel.NONE) private final List<BodyValue> plainValues = new ArrayList<>();
        @Getter(AccessLevel.NONE) private final List<BodyValue> htmlValues = new ArrayList<>();
        @Getter(AccessLevel.NONE) private final Deque<AlternativeChoice> activeAlternatives = new ArrayDeque<>();
        @Getter(AccessLevel.NONE) private final List<MimePartCapture> calendarSources = new ArrayList<>();
        @Getter(AccessLevel.NONE) private final Map<MimePartCapture, ProtectionScope> calendarInputs = new LinkedHashMap<>();
        @Getter(AccessLevel.NONE) private MimePartCapture calendarSource;
        @Getter(AccessLevel.NONE) private ProtectionScope calendarProtection;
        @Getter(AccessLevel.NONE) private int calendarDepth = -1;
        @Getter(AccessLevel.NONE) private final Map<MimeDataSource, ProtectionScope> resourceProtection = new IdentityHashMap<>();
        @Getter(AccessLevel.NONE) private final Map<MimeDataSource, ImportTraversal> resourceTraversals = new IdentityHashMap<>();
        @Getter(AccessLevel.NONE) private final Set<MimePartCapture> protectedBodyParts = Collections.newSetFromMap(new IdentityHashMap<>());
        @Getter(AccessLevel.NONE) private final Set<MimeDataSource> bodyContainers = Collections.newSetFromMap(new IdentityHashMap<>());
        @Getter(AccessLevel.NONE) private final Set<MimeDataSource> conflictingContentIds = Collections.newSetFromMap(new IdentityHashMap<>());
        public boolean hasConflictingContentId(final MimeDataSource resource) { return conflictingContentIds.contains(resource); }
        public EmailSourceData getSourceData() {
            final EmailSourceData original = inspection.getSourceData();
            if (headerViews.size() <= 1) return original;
            final List<EmailSourceData> derived = new ArrayList<>(original.getDerivedSources());
            for (int index = 1; index < headerViews.size(); index++) derived.add(headerViews.get(index).inspection.getSourceData());
            return org.simplejavamail.internal.email.EmailInspection.withDerivedSources(original, derived);
        }
        public List<EmailProblem> getProblems() {
            final List<EmailProblem> result = new ArrayList<>(inspection.getProblems());
            for (final HeaderView view : headerViews) if (view.inspection != inspection) result.addAll(view.inspection.getProblems());
            return List.copyOf(result);
        }
        public List<ProtectionScope> getProtectionScopes() { return List.copyOf(encounteredScopes); }
        public void useSuppliedOuterHeaders(final InputInspection original) {
            selectedHeaders = original;
            carrierHeaders = original;
            subjectHeaders = original;
            if (original.raw("Message-ID") == null) { messageId = null; headers.remove("Message-ID"); }
            if (original.raw("Date") == null) { sentDate = null; headers.remove("Date"); }
            if (original.raw("Subject") == null) { subject = null; nativeSubject = null; headers.remove("Subject"); }
        }
        public Set<String> getConfidentialHeaderNames() {
            final Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (final Map.Entry<String, Collection<Object>> header : headers.entrySet()) {
                if (header.getValue() == null || header.getValue().isEmpty()) continue;
                final ProtectionScope protection = headerProtectionByName.get(header.getKey());
                if (protection != null && protection.getDecryptions().stream()
                        .anyMatch(attempt -> attempt.getStatus() == org.simplejavamail.api.email.DecryptionStatus.DECRYPTED)) {
                    names.add(header.getKey());
                }
            }
            return Collections.unmodifiableSet(names);
        }
        public List<MimeDataSource> getAttachmentList() {
            return attachmentList.stream().filter(value -> !bodyContainers.contains(value)).collect(java.util.stream.Collectors.toList());
        }
        public EmailSourcePart getAttachmentSource(final int index) { return resourceSources.get(getAttachmentList().get(index)).snapshot(); }
        public ProtectionScope getAttachmentProtection(final int index) { return resourceProtection.get(getAttachmentList().get(index)); }
        public ImportTraversal getAttachmentTraversal(final int index) { return resourceTraversals.get(getAttachmentList().get(index)); }
        public ValueInspection getAttachmentInspection(final int index) {
            return getAttachmentProtection(index).inspect(List.of(getAttachmentSource(index)), ValueAvailability.PRESENT);
        }
        public ValueInspection getEmbeddedImageInspection(final int index) {
            final MimeDataSource resource = new ArrayList<>(cidMap.values()).get(index);
            return resourceProtection.get(resource).inspect(List.of(resourceSources.get(resource).snapshot()), ValueAvailability.PRESENT);
        }
        public ValueInspection getPlainInspection() { return bodyInspection(plainValues); }
        public ValueInspection getHtmlInspection() { return bodyInspection(htmlValues); }
        public ValueInspection getCalendarInspection() {
            if (calendarSource != null) return calendarProtection.inspect(List.of(calendarSource.snapshot()), ValueAvailability.PRESENT);
            if (calendarInputs.isEmpty()) return absentBodyInspection();
            final List<EmailSourceElement> sources = new ArrayList<>();
            calendarInputs.keySet().forEach(input -> sources.add(input.snapshot()));
            return ProtectionScope.combine(new ArrayList<>(calendarInputs.values()), sources, ValueAvailability.UNAVAILABLE, ValueDerivation.NONE);
        }
        public ValueInspection getHeaderInspection(final String name, final Object value) {
            final InputInspection source = name.equalsIgnoreCase("Return-Path") ? carrierHeaders : name.equalsIgnoreCase("Subject") ? subjectHeaders : selectedHeaders;
            final List<EmailSourceElement> sources = new ArrayList<>();
            for (final EmailSourceHeader header : source.getSourceData().getHeaders()) {
                if (header.getName().equalsIgnoreCase(name)) sources.add(header);
            }
            if (authoredHeaderViewUnavailable && authoredField(name)) {
                return org.simplejavamail.internal.email.ValueInspectionImpl.builder().availability(ValueAvailability.UNAVAILABLE).sources(sources).build();
            }
            if (sources.isEmpty() && name.equalsIgnoreCase("From") && value != null) {
                source.getSourceData().getHeaders().stream().filter(header -> header.getName().equalsIgnoreCase("Sender")).forEach(sources::add);
            }
            if (sources.isEmpty() && name.equalsIgnoreCase("Reply-To") && value instanceof Collection<?> && !((Collection<?>) value).isEmpty()) {
                source.getSourceData().getHeaders().stream().filter(header -> header.getName().equalsIgnoreCase("From")).forEach(sources::add);
                if (sources.isEmpty()) source.getSourceData().getHeaders().stream().filter(header -> header.getName().equalsIgnoreCase("Sender")).forEach(sources::add);
            }
            final boolean missing = value == null || value instanceof Collection<?> && ((Collection<?>) value).isEmpty();
            final boolean emptyList = value instanceof Collection<?> && ((Collection<?>) value).isEmpty();
            final boolean emptyHeader = !sources.isEmpty() && sources.stream().allMatch(element -> element instanceof EmailSourceHeader
                    && (((EmailSourceHeader) element).getRawValue() == null || ((EmailSourceHeader) element).getRawValue().trim().isEmpty()));
            return (name.equalsIgnoreCase("Return-Path") ? carrierProtection : name.equalsIgnoreCase("Subject") ? subjectProtection : headerProtection)
                    .inspect(sources, missing && source.interpretationFailed(name) ? ValueAvailability.UNAVAILABLE
                            : !missing || emptyList && !sources.isEmpty() ? ValueAvailability.PRESENT
                            : sources.isEmpty() || emptyHeader ? ValueAvailability.ABSENT : ValueAvailability.UNAVAILABLE);
        }
        private ValueInspection bodyInspection(final List<BodyValue> candidates) {
            final List<BodyValue> selected = selectedBodies(candidates);
            if (selected.isEmpty()) return absentBodyInspection();
            final List<ProtectionScope> scopes = new ArrayList<>();
            final List<EmailSourceElement> sources = new ArrayList<>();
            selected.forEach(value -> { scopes.add(value.protection); sources.add(value.source.snapshot()); });
            return ProtectionScope.combine(scopes, sources, ValueAvailability.PRESENT, ValueDerivation.NONE);
        }
        private ValueInspection absentBodyInspection() {
            final List<EmailSourceElement> sources = new ArrayList<>();
            encounteredScopes.forEach(scope -> {
                scope.getSignatures().forEach(value -> sources.addAll(value.getSources()));
                scope.getDecryptions().forEach(value -> sources.addAll(value.getSources()));
            });
            final boolean contentUnavailable = inspection.contentStop() != null && inspection.contentStopCode() != EmailProblem.Code.IMPORT_LIMIT_EXCEEDED;
            if (contentUnavailable) sources.addAll(inspection.getSourceData().getParts());
            return ProtectionScope.combine(unavailableBody ? encounteredScopes : List.of(), sources, unavailableBody || contentUnavailable ? ValueAvailability.UNAVAILABLE
                    : inspection.hasUnvisitedStructure() ? ValueAvailability.UNKNOWN : ValueAvailability.ABSENT, ValueDerivation.NONE);
        }
        public String getPlainTextContentTransferEncoding() { return selectedEncoding(plainValues, plainTextContentTransferEncoding); }
        public String getHtmlTextContentTransferEncoding() { return selectedEncoding(htmlValues, htmlTextContentTransferEncoding); }
        public String getContentTransferEncoding() {
            if (!plainValues.isEmpty()) return selectedEncoding(plainValues, null);
            if (!htmlValues.isEmpty()) return selectedEncoding(htmlValues, null);
            return calendarContent != null ? calendarTextContentTransferEncoding : null;
        }
        private String selectedEncoding(final List<BodyValue> values, final String fallback) {
            final List<BodyValue> selected = selectedBodies(values);
            return selected.isEmpty() ? fallback : interpretedTransferEncoding(selected.get(0).source);
        }
        public List<org.simplejavamail.api.email.EmailSourcePart> getOriginalHtmlParts() { return inspection.originalHtmlParts(); }
        /** Exact parsed occurrence identity; no resource reads or filename matching. */
        public org.simplejavamail.api.email.EmailSourcePart getAttachmentSource(final org.simplejavamail.api.email.AttachmentResource attachment) {
            for (final Map.Entry<MimeDataSource, MimePartCapture> entry : resourceSources.entrySet()) {
                if (entry.getKey().getDataSource() == attachment.getDataSource()) {
                    return entry.getValue().snapshot();
                }
            }
            return null;
        }
        @Getter(AccessLevel.NONE) private final List<MimePartCapture> plainSources = new ArrayList<>();
        @Getter(AccessLevel.NONE) private final List<MimePartCapture> htmlSources = new ArrayList<>();
        @Getter(AccessLevel.NONE) private final Map<MimeDataSource, MimePartCapture> resourceSources = new IdentityHashMap<>();
        @Getter(AccessLevel.NONE) private boolean complete;
        @Getter(AccessLevel.NONE) private String completedPlainContent;
        @Getter(AccessLevel.NONE) private String completedHtmlContent;
		final List<MimeDataSource> attachmentList = new ArrayList<>();
        private boolean rootRepresentedAsResource;
		final Map<String, MimeDataSource> cidMap = new TreeMap<>();
		@Getter(AccessLevel.NONE)
		private final Set<MimeDataSource> explicitInlineResources = Collections.newSetFromMap(new IdentityHashMap<>());
		@Getter(AccessLevel.NONE)
		private final Set<MimeDataSource> implicitRelatedImages = Collections.newSetFromMap(new IdentityHashMap<>());
		private final Map<String, Collection<Object>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		private final List<InternetAddress> toAddresses = new ArrayList<>();
		private final List<InternetAddress> ccAddresses = new ArrayList<>();
		private final List<InternetAddress> bccAddresses = new ArrayList<>();
		@Nullable private String messageId;
		@Nullable private String subject;
		@Nullable private InternetAddress fromAddress;
		private final List<InternetAddress> replyToAddresses = new ArrayList<>();
		private final List<InternetAddress> dispositionNotificationToRecipients = new ArrayList<>();
		private final List<InternetAddress> returnReceiptToRecipients = new ArrayList<>();
		@Nullable private InternetAddress bounceToAddress;
		@Nullable private String contentTransferEncoding;
		@Nullable private String plainTextContentTransferEncoding;
		@Nullable private String htmlTextContentTransferEncoding;
		@Nullable private String calendarTextContentTransferEncoding;
		private final StringBuilder plainContent = new StringBuilder();
		final StringBuilder htmlContent = new StringBuilder();
		@Getter(AccessLevel.NONE)
		private boolean plainContentPresent;
		@Getter(AccessLevel.NONE)
		private boolean htmlContentPresent;
		@Nullable private String calendarMethod;
		@Nullable private String calendarContent;
		@Nullable private Date sentDate;
		@Nullable private DeliveryStatusReport deliveryStatusReport;
		@Nullable private MimePart deliveryStatusPart;
		@Nullable private String deliveryStatusWarning;
        @Getter(AccessLevel.NONE)
        private final Set<MimePart> deliveryStatusCandidates = Collections.newSetFromMap(new IdentityHashMap<>());
        @Getter(AccessLevel.NONE)
        private final List<MimePartCapture> deliveryStatusSources = new ArrayList<>();
        @Getter(AccessLevel.NONE)
        private boolean reportContainer;

		@Nullable
		public String getPlainContent() {
			return complete ? completedPlainContent : bodyContent(plainValues);
		}

		@Nullable
		public String getHtmlContent() {
			return complete ? completedHtmlContent : bodyContent(htmlValues);
		}

		@Nullable
		public Date getSentDate() {
			return sentDate != null ? new Date(sentDate.getTime()) : null;
		}
	}

}
