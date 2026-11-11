package org.simplejavamail.internal.outlooksupport.converter;

import org.simplejavamail.api.email.config.EmailTemplateOptions;
import org.simplejavamail.internal.outlooksupport.model.EmailFromOutlookMessageImpl;

import org.simplejavamail.internal.outlooksupport.model.OutlookMessageDataImpl;

import jakarta.mail.MessagingException;
import jakarta.mail.Header;
import jakarta.mail.internet.InternetHeaders;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.MimeUtility;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EmailStartingBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.ValueDerivation;
import org.simplejavamail.internal.email.ParsedAttachmentResource;
import org.simplejavamail.internal.converter.ImportedSentDateParser;
import org.simplejavamail.api.internal.general.EmailPopulatingBuilderFactory;
import org.simplejavamail.api.internal.general.HeadersToIgnoreWhenParsingExternalEmails;
import org.simplejavamail.api.internal.general.MessageHeader;
import org.simplejavamail.api.internal.outlooksupport.model.EmailFromOutlookMessage;
import org.simplejavamail.api.outlook.OutlookMessageData;
import org.simplejavamail.internal.modules.OutlookModule;
import org.simplejavamail.internal.outlooksupport.internal.model.OutlookMessageProxy;
import org.simplejavamail.internal.util.InternalEmailConverter;
import org.simplejavamail.internal.converter.ImportTraversal;
import org.simplejavamail.outlookmessageparser.model.OutlookAttachment;
import org.simplejavamail.outlookmessageparser.model.OutlookFileAttachment;
import org.simplejavamail.outlookmessageparser.model.OutlookMessage;
import org.simplejavamail.outlookmessageparser.model.OutlookMsgAttachment;
import org.simplejavamail.outlookmessageparser.model.OutlookRecipient;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.IdentityHashMap;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.regex.Pattern;
import jakarta.activation.DataSource;

import static jakarta.mail.Message.RecipientType.BCC;
import static jakarta.mail.Message.RecipientType.CC;
import static jakarta.mail.Message.RecipientType.TO;
import static java.util.Optional.ofNullable;
import static org.simplejavamail.internal.util.MiscUtil.extractCID;
import static org.simplejavamail.internal.util.MiscUtil.interpretRecipient;
import static org.simplejavamail.internal.util.MiscUtil.valueNullOrEmpty;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;
import static org.simplejavamail.internal.util.Preconditions.verifyNonnullOrEmpty;
import static org.slf4j.LoggerFactory.getLogger;

@SuppressWarnings("unused")
public class OutlookEmailConverter implements OutlookModule {

	private static final Logger LOGGER = getLogger(OutlookEmailConverter.class);
	// Match the mode selection of the reader's OutlookRtfToHtmlConverter, without converting the RTF again.
	private static final Pattern ENCAPSULATED_HTML = Pattern.compile("\\\\fromhtml\\d*\\b");
	private static final Pattern ENCAPSULATED_TEXT = Pattern.compile("\\\\fromtext\\b");

	@Override
	public EmailFromOutlookMessage outlookMsgToEmailBuilder(
			@NotNull final File msgFile,
			@NotNull final EmailStartingBuilder emailStartingBuilder,
			@NotNull final EmailPopulatingBuilderFactory builderFactory,
			@NotNull final InternalEmailConverter internalEmailConverter) {
		return buildEmailFromOutlookMessage(
				emailStartingBuilder.startingBlank().withTemplateOptions(EmailTemplateOptions.builder().ignoringDefaults().build()),
				parseOutlookMsg(checkNonEmptyArgument(msgFile, "msgFile")),
				builderFactory,
				internalEmailConverter);
	}

	@Override
	public EmailFromOutlookMessage outlookMsgToEmailBuilder(
			@NotNull final String msgFileName,
			@NotNull final EmailStartingBuilder emailStartingBuilder,
			@NotNull final EmailPopulatingBuilderFactory builderFactory,
			@NotNull final InternalEmailConverter internalEmailConverter) {
		return buildEmailFromOutlookMessage(
				emailStartingBuilder.startingBlank().withTemplateOptions(EmailTemplateOptions.builder().ignoringDefaults().build()),
				parseOutlookMsg(checkNonEmptyArgument(msgFileName, "msgFile")),
				builderFactory,
				internalEmailConverter);
	}
	
	@Override
	public EmailFromOutlookMessage outlookMsgToEmailBuilder(
			@NotNull final InputStream msgInputStream,
			@NotNull final EmailStartingBuilder emailStartingBuilder,
			@NotNull final EmailPopulatingBuilderFactory builderFactory,
			@NotNull final InternalEmailConverter internalEmailConverter) {
		return buildEmailFromOutlookMessage(
				emailStartingBuilder.startingBlank().withTemplateOptions(EmailTemplateOptions.builder().ignoringDefaults().build()),
				parseOutlookMsg(checkNonEmptyArgument(msgInputStream, "msgInputStream")),
				builderFactory,
				internalEmailConverter);
	}
	
	private static EmailFromOutlookMessage buildEmailFromOutlookMessage(
			@NotNull final EmailPopulatingBuilder builder,
			@NotNull final OutlookMessage outlookMessage,
			@NotNull final EmailPopulatingBuilderFactory builderFactory,
			@NotNull final InternalEmailConverter internalEmailConverter) {
        return buildEmailFromOutlookMessage(builder, outlookMessage, builderFactory, internalEmailConverter,
                internalEmailConverter.importTraversal());
    }

    private static EmailFromOutlookMessage buildEmailFromOutlookMessage(final EmailPopulatingBuilder builder,
            final OutlookMessage outlookMessage, final EmailPopulatingBuilderFactory builderFactory,
            final InternalEmailConverter internalEmailConverter, final ImportTraversal traversal) {
		checkNonEmptyArgument(builder, "emailBuilder");
		checkNonEmptyArgument(outlookMessage, "outlookMessage");
        traversal.admit();
		final Map<DataSource, Integer> attachmentSourceIndices = new IdentityHashMap<>();
		outlookMessage.getHeadersMap().entrySet().stream()
				.filter(e -> !valueNullOrEmpty(e.getKey()))
				.filter(e -> !e.getKey().equalsIgnoreCase(MessageHeader.RETURN_PATH.getName()))
				.filter(e -> !isReceiptHeader(e.getKey()))
				.forEach((entry) -> entry.getValue()
						.forEach(headerValue -> parseHeader(entry.getKey(), MimeUtility.unfold(headerValue), builder))
				);
		parseReceiptHeaders(outlookMessage, builder);
		parseReturnPathHeaders(outlookMessage, builder);
		final String fromEmail = outlookMessage.getFromEmail();
		if (fromEmail != null) {
			builder.from(importedRecipient(outlookMessage.getFromName(), fromEmail));
		}
		builder.fixingMessageId(outlookMessage.getMessageId());
		final Date sentDate = statedSentDate(outlookMessage);
		if (sentDate != null) {
			builder.fixingSentDate(sentDate);
		}
		if (!valueNullOrEmpty(outlookMessage.getReplyToEmail())) {
			builder.withReplyTo(importedRecipients(outlookMessage.getReplyToName(), outlookMessage.getReplyToEmail()));
		}
		copyReceiversFromOutlookMessage(builder, outlookMessage);
		builder.withSubject(outlookMessage.getSubject());
        final String plain = outlookMessage.getBodyText();
        final String html = selectedHtml(outlookMessage);
        if (admitBody(traversal, "plain", plain)) builder.withPlainText(plain);
        if (admitBody(traversal, "html", html)) builder.withHTMLText(html);
		
        final Map<OutlookAttachment, Integer> nativeAttachmentIndices = admittedAttachments(outlookMessage, traversal);
		final Set<String> ambiguousContentIds = new LinkedHashSet<>();
		final Map<String, OutlookFileAttachment> cidMap = referencedImages(outlookMessage, internalEmailConverter,
				nativeAttachmentIndices, ambiguousContentIds);
		for (final Map.Entry<String, OutlookFileAttachment> cid : cidMap.entrySet()) {
			final String contentId = verifyNonnullOrEmpty(extractCID(checkNonEmptyArgument(cid.getKey(), "cid.key")));
			final OutlookFileAttachment attachment = cid.getValue();
			String attachmentName = attachment.getLongFilename();
			if (valueNullOrEmpty(attachmentName)) {
				attachmentName = attachment.getFilename();
			}
			if (valueNullOrEmpty(attachmentName)) {
				attachmentName = contentId;
			}
			final AttachmentResource resource = AttachmentResource.builder()
					.withName(attachmentName)
					.withData(attachment.getData(), attachmentMimeType(attachment))
					.withContentId(contentId)
					.build();
			builder.withEmbeddedImage(resource);
			attachmentSourceIndices.put(resource.getDataSource(), nativeAttachmentIndices.get(attachment));
		}
		for (final OutlookFileAttachment attachment : ordinaryAttachments(outlookMessage, cidMap, nativeAttachmentIndices)) {
			String attachmentName = ofNullable(attachment.getLongFilename()).orElse(attachment.getFilename());
			final AttachmentResource resource = AttachmentResource.builder().withName(attachmentName)
					.withData(attachment.getData(), attachmentMimeType(attachment)).build();
			builder.withAttachment(resource);
			attachmentSourceIndices.put(resource.getDataSource(), nativeAttachmentIndices.get(attachment));
		}
		for (int i = 0; i < outlookMessage.getOutlookAttachments().size(); i++) {
		    final ImportTraversal attachmentScope = traversal.child("attachments[" + i + "]");
            if (!attachmentScope.isAdmitted()) break;
			final OutlookAttachment attachment = outlookMessage.getOutlookAttachments().get(i);
			if (attachment instanceof OutlookMsgAttachment) {
                final ImportTraversal nestedScope = attachmentScope.child("message");
                if (!nestedScope.admit()) {
                    attachmentScope.noteStoppedChildren();
                    continue;
                }
				final OutlookMessage nestedMsg = ((OutlookMsgAttachment) attachment).getOutlookMessage();
				final EmailFromOutlookMessage nativeChild = buildEmailFromOutlookMessage(builderFactory.create(), nestedMsg,
						builderFactory, internalEmailConverter, nestedScope);
				final Email email = internalEmailConverter.completeOutlookChild(nativeChild, nestedScope);
				if (hasNoMimeContent(email)) {
					// Some Exchange delivery receipts contain a nominal .msg attachment without any MIME body.
					// It could not be serialized before MIME finalization became eager, so retain that behavior explicitly.
					continue;
				}
				final MimeMessage message = internalEmailConverter.emailToMimeMessage(email);
				try {
					final byte[] mimedata = internalEmailConverter.mimeMessageToEMLByteArray(message);
					final AttachmentResource resource = ParsedAttachmentResource.withEmail(AttachmentResource.builder()
							.withName(nestedMsg.getSubject() + ".eml")
							.withData(mimedata, "message/rfc822").build(), email);
					builder.withAttachment(resource);
					attachmentSourceIndices.put(resource.getDataSource(), i);
				} catch (IllegalStateException e) {
					boolean reasonIsEmptyMessage = e.getCause() instanceof MessagingException && e.getCause().getMessage().equals("No MimeMessage content");
					if (!reasonIsEmptyMessage) throw e;
					// :sadface: nested message attachment is actually invalid (possibly empty because of #396),
					// so we're just going to ignore it
				}
			}
		}

		return EmailFromOutlookMessageImpl.builder()
			.emailBuilder(builder)
			.outlookMessage(new OutlookMessageProxy(outlookMessage))
			.outlookMessageData(extractOutlookMessageData(outlookMessage))
			.htmlDerivation(htmlDerivation(outlookMessage))
			.ambiguousContentIds(ambiguousContentIds)
			.attachmentSourceIndices(attachmentSourceIndices)
			.build();
	}

    private static boolean admitBody(final ImportTraversal traversal, final String kind, final String value) {
        if (value == null) return false;
        if (traversal.child("body[" + kind + "]").admit()) return true;
        traversal.noteStoppedChildren();
        return false;
    }

    private static Map<OutlookAttachment, Integer> admittedAttachments(final OutlookMessage message, final ImportTraversal traversal) {
        final Map<OutlookAttachment, Integer> admitted = new IdentityHashMap<>();
        final List<OutlookAttachment> attachments = message.getOutlookAttachments();
        for (int index = 0; index < attachments.size(); index++) {
            if (!traversal.child("attachments[" + index + "]").admit()) {
                traversal.noteStoppedChildren();
                break;
            }
            admitted.put(attachments.get(index), index);
        }
        return admitted;
    }

	@NotNull
	private static Map<String, OutlookFileAttachment> referencedImages(final OutlookMessage message, final InternalEmailConverter converter,
			final Map<OutlookAttachment, Integer> admitted, final Set<String> ambiguous) {
		final Map<String, OutlookFileAttachment> selected = new LinkedHashMap<>();
		final String html = selectedHtml(message);
		if (html == null || html.isEmpty()) return selected;
		final Set<String> references = converter.referencedContentIds(html);
		final Map<String, List<OutlookFileAttachment>> candidates = new LinkedHashMap<>();
		for (final OutlookAttachment attachment : message.getOutlookAttachments()) {
			if (!admitted.containsKey(attachment)) break;
			if (attachment instanceof OutlookFileAttachment) {
				final OutlookFileAttachment file = (OutlookFileAttachment) attachment;
				if (file.getData().length != 0) {
					final String reference = preferredReference(file, references);
					if (reference != null) candidates.computeIfAbsent(reference, ignored -> new ArrayList<>()).add(file);
				}
			}
		}
		for (final Map.Entry<String, List<OutlookFileAttachment>> entry : candidates.entrySet()) {
			if (entry.getValue().size() == 1) selected.put(entry.getKey(), entry.getValue().get(0));
			else ambiguous.add(entry.getKey());
		}
		return selected;
	}

	@Nullable
	private static String preferredReference(final OutlookFileAttachment file, final Set<String> references) {
		if (references.contains(file.getContentId())) return file.getContentId();
		if (references.contains(file.getFilename())) return file.getFilename();
		if (references.contains(file.getLongFilename())) return file.getLongFilename();
		return null;
	}

	private static List<OutlookFileAttachment> ordinaryAttachments(final OutlookMessage message,
			final Map<String, OutlookFileAttachment> cidMap, final Map<OutlookAttachment, Integer> admitted) {
		final List<OutlookFileAttachment> files = new ArrayList<>();
		for (final OutlookAttachment attachment : message.getOutlookAttachments()) {
			if (!admitted.containsKey(attachment)) break;
			if (attachment instanceof OutlookFileAttachment && cidMap.values().stream().noneMatch(selected -> selected == attachment)) {
				files.add((OutlookFileAttachment) attachment);
			}
		}
		return files;
	}

	@NotNull
	private static String attachmentMimeType(@NotNull final OutlookFileAttachment attachment) {
		final String mimeType = attachment.getMimeTag();
		return valueNullOrEmpty(mimeType) ? "application/octet-stream" : mimeType;
	}

	private static boolean hasNoMimeContent(@NotNull final Email email) {
		return email.getPlainText() == null
				&& email.getHTMLText() == null
				&& email.getCalendarText() == null
				&& email.getAttachments().isEmpty()
				&& email.getEmbeddedImages().isEmpty()
				&& email.getEmailToForward() == null;
	}

	@NotNull
	private static OutlookMessageData extractOutlookMessageData(@NotNull final OutlookMessage outlookMessage) {
		return OutlookMessageDataImpl.builder()
			.headers(outlookMessage.getHeadersMap())
			.rawHeaders(outlookMessage.getHeaders())
			.messageClass(outlookMessage.getMessageClass())
			.displayTo(outlookMessage.getDisplayTo())
			.displayCc(outlookMessage.getDisplayCc())
			.displayBcc(outlookMessage.getDisplayBcc())
			.date(outlookMessage.getDate())
			.clientSubmitTime(outlookMessage.getClientSubmitTime())
			.creationDate(outlookMessage.getCreationDate())
			.lastModificationDate(outlookMessage.getLastModificationDate())
			.lastModifierName(outlookMessage.getLastModifierName())
			.propertiesAsHex(outlookMessage.getPropertiesAsHex())
			.propertyCodes(outlookMessage.getPropertyCodes())
			.propertyListing(outlookMessage.getPropertyListing())
			.bodyHTML(outlookMessage.getBodyHTML())
			.bodyRTF(outlookMessage.getBodyRTF())
			.build();
	}

	@Nullable
	private static String selectedHtml(final OutlookMessage message) {
		return message.getBodyHTML() != null ? message.getBodyHTML() : message.getConvertedBodyHTML();
	}

	private static ValueDerivation htmlDerivation(final OutlookMessage message) {
		if (message.getBodyHTML() != null) return ValueDerivation.NATIVE_HTML;
		if (message.getConvertedBodyHTML() == null || message.getBodyRTF() == null) return ValueDerivation.UNKNOWN;
		if (ENCAPSULATED_HTML.matcher(message.getBodyRTF()).find()) return ValueDerivation.RTF_ENCAPSULATED_HTML;
		if (ENCAPSULATED_TEXT.matcher(message.getBodyRTF()).find()) return ValueDerivation.RTF_ENCAPSULATED_TEXT;
		return ValueDerivation.RTF_RENDERED_HTML;
	}

	@Nullable
	private static Date statedSentDate(final OutlookMessage message) {
		if (message.getClientSubmitTime() != null) return message.getClientSubmitTime();
		for (final Map.Entry<String, ? extends java.util.Collection<String>> header : message.getHeadersMap().entrySet()) {
			if ("Date".equalsIgnoreCase(header.getKey()) && !header.getValue().isEmpty()) {
				return ImportedSentDateParser.parse(header.getValue().iterator().next());
			}
		}
		return null;
	}

	private static void parseHeader(final String headerName, final String headerValue, final EmailPopulatingBuilder builder) {
		if (!HeadersToIgnoreWhenParsingExternalEmails.shouldIgnoreHeader(headerName)) {
			builder.withHeader(headerName, headerValue);
		}
	}

	private static void parseReceiptHeaders(final OutlookMessage message, final EmailPopulatingBuilder builder) {
		final List<Recipient> dispositionRecipients = new ArrayList<>();
		final List<Recipient> returnRecipients = new ArrayList<>();
		for (final Header header : orderedReceiptHeaders(message)) {
			final String value = MimeUtility.unfold(header.getValue());
			if (isEmailHeader(header.getName(), value, MessageHeader.DISPOSITION_NOTIFICATION_TO.getName())) {
				dispositionRecipients.addAll(importedRecipients(null, value));
			} else if (isEmailHeader(header.getName(), value, MessageHeader.RETURN_RECEIPT_TO.getName())) {
				returnRecipients.addAll(importedRecipients(null, value));
			}
			// Empty/uninterpretable source occurrences remain in the native capture. Replaying one
			// through withHeader would treat it as a newly authored notification destination.
		}
		if (!dispositionRecipients.isEmpty()) {
			builder.withDispositionNotificationTo(dispositionRecipients);
		}
		if (!returnRecipients.isEmpty()) {
			builder.withReturnReceiptTo(returnRecipients);
		}
	}

	private static List<Header> orderedReceiptHeaders(final OutlookMessage message) {
		final List<Header> receipts = new ArrayList<>();
		final Set<String> rawReceiptNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		if (message.getHeaders() != null) {
			try {
				final InternetHeaders headers = new InternetHeaders(new ByteArrayInputStream(message.getHeaders().getBytes(StandardCharsets.UTF_8)), true);
				for (final Enumeration<Header> entries = headers.getAllHeaders(); entries.hasMoreElements();) {
					final Header header = entries.nextElement();
					if (isReceiptHeader(header.getName())) {
						receipts.add(header);
						rawReceiptNames.add(header.getName());
					}
				}
			} catch (final MessagingException unreadable) {
				// Fall back to the specialist parser's available receipt values.
			}
		}
		message.getHeadersMap().entrySet().stream()
				.filter(entry -> entry.getKey() != null && isReceiptHeader(entry.getKey()))
				.filter(entry -> !rawReceiptNames.contains(entry.getKey()))
				.forEach(entry -> entry.getValue().forEach(value -> receipts.add(new Header(entry.getKey(), value))));
		return receipts;
	}

	private static boolean isReceiptHeader(final String name) {
		return name.equalsIgnoreCase(MessageHeader.DISPOSITION_NOTIFICATION_TO.getName())
				|| name.equalsIgnoreCase(MessageHeader.RETURN_RECEIPT_TO.getName());
	}

	private static void parseReturnPathHeaders(final OutlookMessage message, final EmailPopulatingBuilder builder) {
		if (message.getHeaders() != null) {
			try {
				final InternetHeaders headers = new InternetHeaders(new ByteArrayInputStream(message.getHeaders().getBytes(StandardCharsets.UTF_8)), true);
				boolean foundReturnPath = false;
				for (final Enumeration<Header> entries = headers.getAllHeaders(); entries.hasMoreElements();) {
					final Header header = entries.nextElement();
					if (header.getName().equalsIgnoreCase(MessageHeader.RETURN_PATH.getName())) {
						parseReturnPathHeader(MimeUtility.unfold(header.getValue()), builder);
						foundReturnPath = true;
					}
				}
				if (foundReturnPath) {
					return;
				}
			} catch (final MessagingException unreadable) {
				// Fall back to the specialist parser's available header values.
			}
		}
		message.getHeadersMap().entrySet().stream()
				.filter(entry -> entry.getKey() != null && entry.getKey().equalsIgnoreCase(MessageHeader.RETURN_PATH.getName()))
				.forEach(entry -> entry.getValue().forEach(value -> parseReturnPathHeader(MimeUtility.unfold(value), builder)));
	}

	private static void parseReturnPathHeader(final String source, final EmailPopulatingBuilder builder) {
		try {
			final InternetAddress[] addresses = source.trim().isEmpty() || source.trim().equals("<>")
					? new InternetAddress[0] : InternetAddress.parse(source, false);
			if (addresses.length > 0) {
				builder.withBounceTo(new Recipient(addresses[0].getPersonal(), addresses[0].getAddress(), null, null));
				return;
			}
		} catch (final AddressException unparseable) {
			// Keep the source header when no bounce address can be interpreted.
		}
		builder.withBounceTo((Recipient) null);
		// Main retains every raw occurrence from native source data. Replaying it as a composition setter would
		// reinterpret this failed source value and undo the selected absent Bounce-To.
	}

	@NotNull
	private static List<Recipient> importedRecipients(@Nullable final String name, @NotNull final String source) {
		try {
			final List<Recipient> recipients = new ArrayList<>();
			for (final InternetAddress address : InternetAddress.parse(source, false)) {
				recipients.add(new Recipient(valueNullOrEmpty(name) ? address.getPersonal() : name, address.getAddress(), null, null));
			}
			if (!recipients.isEmpty()) {
				return recipients;
			}
		} catch (final AddressException unparseable) {
			// Preserve received text, including unusual groups, for the configured outgoing validator.
		}
		return Collections.singletonList(new Recipient(name, source, null, null));
	}

	/**
	 * Preserve Outlook's existing first-mailbox/name interpretation and malformed-input fallback.
	 * Imported fields do not pass through the stricter outgoing Email convenience API.
	 */
	@SuppressWarnings("deprecation")
	private static Recipient importedRecipient(@Nullable final String name, @NotNull final String address) {
		return interpretRecipient(name, true, address, null);
	}

	private static boolean isEmailHeader(String name, String value, String emailHeaderName) {
		return name.equalsIgnoreCase(emailHeaderName) &&
				!valueNullOrEmpty(value) &&
				!valueNullOrEmpty(value.trim()) &&
				!value.equals("<>");
	}

	private static void copyReceiversFromOutlookMessage(@NotNull EmailPopulatingBuilder builder, @NotNull OutlookMessage outlookMessage) {
		//noinspection QuestionableName
		for (final OutlookRecipient to : outlookMessage.getToRecipients()) {
			if (hasUsableAddress(to)) {
				builder.withRecipients(new Recipient(to.getName(), to.getAddress(), TO, null));
			}
		}
		for (final OutlookRecipient cc : outlookMessage.getCcRecipients()) {
			if (hasUsableAddress(cc)) {
				builder.withRecipients(new Recipient(cc.getName(), cc.getAddress(), CC, null));
			}
		}
		for (final OutlookRecipient bcc : outlookMessage.getBccRecipients()) {
			if (hasUsableAddress(bcc)) {
				builder.withRecipients(new Recipient(bcc.getName(), bcc.getAddress(), BCC, null));
			}
		}
	}

	private static boolean hasUsableAddress(@NotNull OutlookRecipient recipient) {
		final String address = recipient.getAddress();
		return !valueNullOrEmpty(address) && !valueNullOrEmpty(address.trim());
	}
	
	@NotNull
	private static OutlookMessage parseOutlookMsg(@NotNull final File msgFile) {
		try {
			return new org.simplejavamail.outlookmessageparser.OutlookMessageParser().parseMsg(msgFile);
		} catch (final IOException | org.apache.poi.UnsupportedFileFormatException | org.apache.poi.EmptyFileException e) {
			throw new org.simplejavamail.api.internal.outlooksupport.model.OutlookInputException(e);
		}
	}
	
	@NotNull
	private static OutlookMessage parseOutlookMsg(@NotNull final InputStream msgInputStream) {
		try {
			return new org.simplejavamail.outlookmessageparser.OutlookMessageParser().parseMsg(msgInputStream);
		} catch (final IOException | org.apache.poi.UnsupportedFileFormatException | org.apache.poi.EmptyFileException e) {
			throw new org.simplejavamail.api.internal.outlooksupport.model.OutlookInputException(e);
		}
	}
	
	@NotNull
	private static OutlookMessage parseOutlookMsg(@NotNull final String msgFileName) {
		try {
			return new org.simplejavamail.outlookmessageparser.OutlookMessageParser().parseMsg(msgFileName);
		} catch (final IOException | org.apache.poi.UnsupportedFileFormatException | org.apache.poi.EmptyFileException e) {
			throw new org.simplejavamail.api.internal.outlooksupport.model.OutlookInputException(e);
		}
	}
}
