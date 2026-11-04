package org.simplejavamail.converter.internal.mimemessage;

import java.util.Objects;

import org.simplejavamail.api.email.EmailProblem;
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
		final ParsedMimeMessageComponents parsedComponents = new ParsedMimeMessageComponents();
        parsedComponents.inspection = inspection;
		parsedComponents.messageId = inspection.recover("Message-ID", () -> parseMessageId(mimeMessage), () -> inspection.raw("Message-ID"));
		parsedComponents.sentDate = inspection.recover("Date", () -> parseSentDate(mimeMessage), () -> null);
        inspection.noteUninterpretedDate(parsedComponents.sentDate);
        parsedComponents.subject = inspection.recover("Subject", () -> parseSubject(mimeMessage),
                () -> inspection.raw("Subject") == null ? null : MimeUtility.unfold(inspection.raw("Subject")));
        if (InputInspection.unavailableCharset(inspection.raw("Subject")) && !inspection.decodingFailed("Subject", inspection.raw("Subject"))
                && (Objects.equals(parsedComponents.subject, inspection.raw("Subject"))
                    || Objects.equals(parsedComponents.subject, MimeUtility.unfold(inspection.raw("Subject"))))) {
            parsedComponents.subject = MimeUtility.unfold(inspection.raw("Subject"));
            inspection.record(EmailProblem.Code.VALUE_NOT_DECODED, "Subject", inspection.raw("Subject"),
                    "The received subject charset is unavailable; the complete original value is retained");
        }
        parsedComponents.toAddresses.addAll(inspection.recover("To", inspection.joined("To"), () -> parseToAddresses(mimeMessage),
                () -> Arrays.asList(rawAddressHeader(inspection.joined("To")))));
        parsedComponents.ccAddresses.addAll(inspection.recover("Cc", inspection.joined("Cc"), () -> parseCcAddresses(mimeMessage),
                () -> Arrays.asList(rawAddressHeader(inspection.joined("Cc")))));
        parsedComponents.bccAddresses.addAll(inspection.recover("Bcc", inspection.joined("Bcc"), () -> parseBccAddresses(mimeMessage),
                () -> Arrays.asList(rawAddressHeader(inspection.joined("Bcc")))));
        parsedComponents.fromAddress = inspection.recover("From", () -> parseFromAddress(mimeMessage),
                () -> rawAddressHeader(inspection.raw("From"))[0]);
        parsedComponents.replyToAddresses.addAll(inspection.recover("Reply-To", inspection.joined("Reply-To"), () -> parseReplyToAddresses(mimeMessage),
                () -> Arrays.asList(rawAddressHeader(inspection.joined("Reply-To")))));
		// Only the effective root message owns message-level headers; child parts retain their own resource metadata.
		for (final Header header : inspection.headers()) {
            inspection.recover(header.getName(), header.getValue(), () -> {
                parseHeader(header, parsedComponents);
                return null;
            }, () -> null);
		}
        inspection.noteAddressRecovery();
		parseMimePartTree(mimeMessage, parsedComponents, fetchAttachmentData, false, inspection.beginRootPart());
		if (parsedComponents.deliveryStatusReport == null && (parsedComponents.reportContainer || parsedComponents.deliveryStatusPart != null)) {
			parsedComponents.deliveryStatusReport = DeliveryStatusReportParser.unavailableReport(parsedComponents.deliveryStatusPart != null
                    ? "The delivery-status content may be incomplete because it could not be read."
                    : "The notification has no readable delivery-status part.");
		}
        inspection.noteReportWarnings(parsedComponents.deliveryStatusReport);
		parsedComponents.deliveryStatusPart = null;
		parsedComponents.deliveryStatusWarning = null;
		moveInvalidEmbeddedResourcesToAttachments(parsedComponents);
		removeReferencedImplicitImageAttachments(parsedComponents);
        finishSourceRetention(parsedComponents);
		return parsedComponents;
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
        final Set<MimeDataSource> represented = Collections.newSetFromMap(new IdentityHashMap<>());
        represented.addAll(parsed.attachmentList);
        represented.addAll(parsed.cidMap.values());
        parsed.resourceSources.forEach((resource, capture) -> capture.finishRepresentation(!represented.contains(resource)));
    }

	private static void parseMimePartTree(@NotNull final MimePart currentPart, @NotNull final ParsedMimeMessageComponents parsedComponents,
			final boolean fetchAttachmentData, final boolean insideRelated, final MimePartCapture capture) {
        try {
            interpretMimePart(currentPart, parsedComponents, fetchAttachmentData, insideRelated, capture);
            capture.represented();
        } catch (final MimeMessageParseException failure) {
            if (!InputInspection.recognizedFailure(failure)) {
                throw failure;
            }
            capture.failed(currentPart, failure, fetchAttachmentData || !capture.hasMultipleHeaders("Content-Disposition"));
        }
    }

    private static void interpretMimePart(final MimePart currentPart, final ParsedMimeMessageComponents parsedComponents,
            final boolean fetchAttachmentData, final boolean insideRelated, final MimePartCapture capture) {
        if (currentPart instanceof MimeMessage) {
            parsedComponents.reportContainer = DeliveryStatusReportParser.isReportContainer(currentPart);
            if (DeliveryStatusReportParser.isStatusPart(currentPart)) {
                parsedComponents.deliveryStatusPart = currentPart;
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
            parsedComponents.plainSources.add(capture);
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
            parsedComponents.inspection.originalHtml(capture);
            parsedComponents.htmlSources.add(capture);
            capture.deferRepresentation();
			parsedComponents.htmlContentPresent = true;
			if (parsedComponents.htmlTextContentTransferEncoding == null) {
				parsedComponents.htmlTextContentTransferEncoding = checkContentTransferEncoding(parsedComponents, capture);
			}
		} else if (isMimeType(currentPart, "text/calendar", capture) && parsedComponents.calendarContent == null && canSupplyBody) {
            if (!prepareTransferEncoding(currentPart, capture, true)) return;
			final String content = parseCalendarContent(currentPart, capture);
            final String contentType = retrieveDataHandler(currentPart).getContentType();
            final String method = parseCalendarHeaderMethod(contentType)
                    .orElseGet(() -> findFirstMatch(CALENDAR_BODY_METHOD_PATTERN, content).orElse(null));
            if (method == null || Arrays.stream(CalendarMethod.values()).noneMatch(value -> value.name().equalsIgnoreCase(method))) {
                capture.unrepresented("No supported Calendar METHOD could be established");
                return;
            }
			parsedComponents.calendarContent = content;
			parsedComponents.calendarMethod = method;
			if (parsedComponents.calendarTextContentTransferEncoding == null) {
				parsedComponents.calendarTextContentTransferEncoding = checkContentTransferEncoding(parsedComponents, capture);
			}
		} else if (isMimeType(currentPart, "multipart/*", capture) && canSupplyBody) {
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
                selectOuterDeliveryStatusPart(currentPart, mp, parsedComponents);
            } catch (final MimeMessageParseException failure) {
                if (!InputInspection.recognizedFailure(failure)) throw failure;
                capture.problem(EmailProblem.Code.CONTENT_NOT_REPRESENTED, null, InputInspection.describe(failure));
            }
			final boolean childrenInsideRelated = insideRelated || isMimeType(currentPart, "multipart/related", capture);
			for (int i = 0, count = countBodyParts(mp); i < count; i++) {
                final String location = capture.getLocation() + "/children[" + i + "]";
                final MimeBodyPart child;
                try {
                    child = getBodyPartAtIndex(mp, i);
                } catch (final MimeMessageParseException failure) {
                    if (!InputInspection.recognizedFailure(failure)) throw failure;
                    final MimePartCapture unavailable = new MimePartCapture(parsedComponents.inspection, location, Collections.emptyList());
                    capture.add(unavailable);
                    unavailable.problem(EmailProblem.Code.CONTENT_NOT_REPRESENTED, null, InputInspection.describe(failure));
                    continue;
                }
                final MimePartCapture childCapture = parsedComponents.inspection.beginPart(child, location);
                capture.add(childCapture);
				parseMimePartTree(child, parsedComponents, fetchAttachmentData, childrenInsideRelated, childCapture);
			}
		} else {
            if (!prepareTransferEncoding(currentPart, capture, fetchAttachmentData)) return;
			parseDataSource(currentPart, parsedComponents, fetchAttachmentData, disposition, insideRelated, capture);
		}
	}

	private static void selectOuterDeliveryStatusPart(final MimePart currentPart, final Multipart parts,
			final ParsedMimeMessageComponents parsedComponents) {
		if (!(currentPart instanceof MimeMessage) || !DeliveryStatusReportParser.isReportContainer(currentPart)) {
			return;
		}
		int selectedIndex = -1;
		for (int index = 0, count = countBodyParts(parts); index < count; index++) {
			if (DeliveryStatusReportParser.isStatusPart(getBodyPartAtIndex(parts, index))) {
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
			boolean fetchAttachmentData, String disposition, final boolean insideRelated, final MimePartCapture capture) {
        final String contentID = interpretReceivedContentId(recoverMetadata(capture, "Content-ID", () -> parseContentID(currentPart),
                () -> capture.raw("Content-ID")));
		final boolean statusPart = currentPart == parsedComponents.deliveryStatusPart;
		final MimeDataSource mimeDataSource = parseAttachment(contentID, currentPart,
                createDataSource(currentPart, fetchAttachmentData, statusPart, capture), capture);
		if (statusPart) {
			// Reuse bytes already cached for attachment conversion; do not fetch the original status body again.
			parsedComponents.deliveryStatusReport = DeliveryStatusReportParser.parse(mimeDataSource.getDataSource(), parsedComponents.deliveryStatusWarning);
		}
		final boolean isAttachment = Part.ATTACHMENT.equalsIgnoreCase(disposition);
		final boolean isInline = Part.INLINE.equalsIgnoreCase(disposition);
		final boolean implicitRelatedImage = disposition == null && insideRelated && isMimeType(currentPart, "image/*", capture);

		if (disposition != null && !isAttachment && !isInline) {
			LOGGER.warn("Content-Disposition '{}' for data source not recognized (it should be either 'attachment' or 'inline'). Skipping body part", disposition);
		}

		// Preserve every traversal slot independently from CID lookup, which can replace an earlier resource with the same ID.
		parsedComponents.attachmentList.add(mimeDataSource);
        parsedComponents.resourceSources.put(mimeDataSource, capture);
        capture.deferRepresentation();
		if (isInline && contentID != null) {
			parsedComponents.explicitInlineResources.add(mimeDataSource);
		}
		if (contentID != null) {
			parsedComponents.cidMap.put(contentID, mimeDataSource);
			if (implicitRelatedImage) {
				parsedComponents.implicitRelatedImages.add(mimeDataSource);
			}
			// when parsing is done, we'll move any sources from cidMap that are not referenced in HTML, to attachments (or remove if already there)
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

	private static void parseHeader(final Header header, @NotNull final ParsedMimeMessageComponents parsedComponents) {
        final InputInspection inspection = parsedComponents.inspection;
        final String name = inspection.recover("header-name", header.getName(), () -> decodeText(header.getName()), header::getName);
        if (!name.matches("[!-9;-~]+")) {
            inspection.record(EmailProblem.Code.FIELD_NOT_INTERPRETED, "header-name", header.getName(),
                    "The received field name cannot be used as an outgoing header name");
            return;
        }
        if (HeadersToIgnoreWhenParsingExternalEmails.shouldIgnoreHeader(name)) return;
        if (header.getValue() == null) {
            parsedComponents.headers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(null);
            return;
        }
        final DecodedHeader decodedHeader = name.equalsIgnoreCase("Message-ID") ? new DecodedHeader(name, header.getValue())
                : inspection.recover(name, header.getValue(), () -> DecodedHeader.of(header), () -> new DecodedHeader(name, header.getValue()));
        if (isGenericRootHeader(name) && InputInspection.unavailableCharset(header.getValue())) {
            inspection.record(EmailProblem.Code.VALUE_NOT_DECODED, name, header.getValue(),
                    "The received header charset is unavailable; the complete original value is retained");
        }
        if (inspection.decodingFailed(name, header.getValue()) && (name.equalsIgnoreCase("Disposition-Notification-To")
                || name.equalsIgnoreCase("Return-Receipt-To") || name.equalsIgnoreCase("Return-Path"))) {
            if (name.equalsIgnoreCase("Return-Path")) parsedComponents.headers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(header.getValue());
            return;
        }

		if (isEmailHeader(decodedHeader, MessageHeader.DISPOSITION_NOTIFICATION_TO.getName())) {
			parsedComponents.dispositionNotificationToRecipients.addAll(createAddressesFromEncodedHeader(header));
		} else if (isEmailHeader(decodedHeader, MessageHeader.RETURN_RECEIPT_TO.getName())) {
			parsedComponents.returnReceiptToRecipients.addAll(createAddressesFromEncodedHeader(header));
		} else if (decodedHeader.getName().equalsIgnoreCase(MessageHeader.RETURN_PATH.getName())) {
			parseReturnPathHeader(header, parsedComponents);
		} else {
			if (!parsedComponents.headers.containsKey(decodedHeader.getName())) {
				parsedComponents.headers.put(decodedHeader.getName(), new ArrayList<>());
			}
			parsedComponents.headers.get(decodedHeader.getName()).add(MimeUtility.unfold(decodedHeader.getValue()));
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
		parsedComponents.headers.computeIfAbsent(MessageHeader.RETURN_PATH.getName(), ignored -> new ArrayList<>())
				.add(MimeUtility.unfold(header.getValue()));
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
			} catch (final ParseException malformedParameter) {
				final String recovered = recoverMalformedDisposition(currentPart);
				if (recovered == null) {
					throw malformedParameter;
				}
                if (capture != null) {
                    capture.headerProblem(EmailProblem.Code.FIELD_NOT_INTERPRETED, "Content-Disposition",
                            capture.raw("Content-Disposition"), malformedParameter.getMessage());
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
		try {
			final Address[] addresses;
			try {
				addresses = mimeMessage.getFrom();
			} catch (final AddressException malformedAddress) {
				return recoverFromAddressHeader(mimeMessage, malformedAddress)[0];
			}
			return (addresses == null || addresses.length == 0) ? null : (InternetAddress) addresses[0];
		} catch (final MessagingException e) {
			throw new MimeMessageParseException(MimeMessageParseException.ERROR_PARSING_FROMADDRESS, e);
		}
	}

	@SuppressWarnings("WeakerAccess")
	@NotNull
	public static List<InternetAddress> parseReplyToAddresses(@NotNull final MimeMessage mimeMessage) {
		try {
			final Address[] addresses;
			try {
				addresses = mimeMessage.getReplyTo();
			} catch (final AddressException malformedAddress) {
				final InternetAddress[] retained = retainMalformedAddressHeader(mimeMessage.getHeader("Reply-To", ","), malformedAddress);
				return Arrays.asList(retained != null ? retained : recoverFromAddressHeader(mimeMessage, malformedAddress));
			}
			return parseInternetAddresses(addresses);
		} catch (final MessagingException e) {
			throw new MimeMessageParseException(MimeMessageParseException.ERROR_PARSING_REPLY_TO_ADDRESSES, e);
		}
	}

	private static InternetAddress[] recoverFromAddressHeader(final MimeMessage message, final AddressException originalFailure)
			throws MessagingException {
		final String from = message.getHeader("From", ",");
		final InternetAddress[] retained = retainMalformedAddressHeader(from != null ? from : message.getHeader("Sender", ","), originalFailure);
		if (retained == null) {
			throw originalFailure;
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

	private static InternetAddress[] rawAddressHeader(final String source) {
		final InternetAddress retained = new InternetAddress();
		retained.setAddress(source);
		return new InternetAddress[] {retained};
	}

	@NotNull
	public static String parseSubject(@NotNull final MimeMessage mimeMessage) {
		try {
			return ofNullable(mimeMessage.getSubject()).orElse("");
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

	static void moveInvalidEmbeddedResourcesToAttachments(ParsedMimeMessageComponents parsedComponents) {
		final String htmlContent = parsedComponents.htmlContent.toString();
		final Set<String> referencedCids = CidReferences.inHtml(htmlContent);
		for (Iterator<Map.Entry<String, MimeDataSource>> it = parsedComponents.cidMap.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<String, MimeDataSource> cidEntry = it.next();
			String cid = extractCID(cidEntry.getKey());
			if (!referencedCids.contains(cid)) {
				parsedComponents.attachmentList.add(cidEntry.getValue());
				it.remove();
			}
		}
		// Keep the existing referenced-CID selection, including collisions; only unreferenced inline occurrences become attachments.
		parsedComponents.attachmentList.removeIf(resource -> parsedComponents.explicitInlineResources.contains(resource)
				&& referencedCids.contains(resource.getContentId()));
		parsedComponents.explicitInlineResources.clear();
	}

	private static void removeReferencedImplicitImageAttachments(final ParsedMimeMessageComponents parsedComponents) {
		if (parsedComponents.implicitRelatedImages.isEmpty()) {
			return;
		}
		final Set<MimeDataSource> referenced = Collections.newSetFromMap(new IdentityHashMap<>());
		referenced.addAll(parsedComponents.cidMap.values());
		// Remove only the same parsed occurrence, leaving ordinary attachment slots and #491's explicit views intact.
		parsedComponents.attachmentList.removeIf(resource -> parsedComponents.implicitRelatedImages.contains(resource) && referenced.contains(resource));
		parsedComponents.implicitRelatedImages.clear();
	}

	@Getter
	public static class ParsedMimeMessageComponents {
        @Getter(AccessLevel.NONE) private InputInspection inspection;
        public EmailSourceData getSourceData() { return inspection.getSourceData(); }
        public List<EmailProblem> getProblems() { return inspection.getProblems(); }
        public List<org.simplejavamail.api.email.EmailSourcePart> getOriginalHtmlParts() { return inspection.originalHtmlParts(); }
        @Getter(AccessLevel.NONE) private final List<MimePartCapture> plainSources = new ArrayList<>();
        @Getter(AccessLevel.NONE) private final List<MimePartCapture> htmlSources = new ArrayList<>();
        @Getter(AccessLevel.NONE) private final Map<MimeDataSource, MimePartCapture> resourceSources = new IdentityHashMap<>();
        @Getter(AccessLevel.NONE) private boolean complete;
        @Getter(AccessLevel.NONE) private String completedPlainContent;
        @Getter(AccessLevel.NONE) private String completedHtmlContent;
		final Set<MimeDataSource> attachmentList = new LinkedHashSet<>();
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
        private boolean reportContainer;

		@Nullable
		public String getPlainContent() {
			return complete ? completedPlainContent : plainContentPresent ? plainContent.toString() : null;
		}

		@Nullable
		public String getHtmlContent() {
			return complete ? completedHtmlContent : htmlContentPresent ? htmlContent.toString() : null;
		}

		@Nullable
		public Date getSentDate() {
			return sentDate != null ? new Date(sentDate.getTime()) : null;
		}
	}

}
