package org.simplejavamail.converter.internal;

import jakarta.mail.internet.InternetAddress;
import java.util.Map;
import lombok.experimental.UtilityClass;
import lombok.val;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.CalendarMethod;
import org.simplejavamail.api.email.ContentTransferEncoding;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.internal.general.HeadersToIgnoreWhenParsingExternalEmails;
import org.simplejavamail.converter.internal.mimemessage.MimeDataSource;
import org.simplejavamail.converter.internal.mimemessage.MimeMessageParser.ParsedMimeMessageComponents;
import org.simplejavamail.email.internal.InternalEmailPopulatingBuilder;

import static jakarta.mail.Message.RecipientType.BCC;
import static jakarta.mail.Message.RecipientType.CC;
import static jakarta.mail.Message.RecipientType.TO;
import static org.simplejavamail.internal.util.MiscUtil.extractCID;
import static org.simplejavamail.internal.util.MiscUtil.valueNullOrEmpty;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;
import static org.simplejavamail.internal.util.Preconditions.verifyNonnullOrEmpty;

/**
 * Copies parsed MIME fields into the caller's builder. Composed conversion and exact-EML inspection share this mapping without sharing protection processing.
 */
@UtilityClass
class MimeMessageEmailMapper {

	private static final String GENERATED_ATTACHMENT_CONTENT_ID_PATTERN = "sjm-[A-Za-z0-9-]+@simplejavamail\\.generated";

	static EmailPopulatingBuilder populate(@NotNull final EmailPopulatingBuilder builder, @NotNull final ParsedMimeMessageComponents parsed) {
		checkNonEmptyArgument(builder, "emailBuilder");
		checkNonEmptyArgument(parsed, "parsedMimeMessageComponents");
		((InternalEmailPopulatingBuilder) builder).withDeliveryStatusReport(parsed.getDeliveryStatusReport());
		if (parsed.getSentDate() != null) {
			builder.fixingSentDate(parsed.getSentDate());
		}
		if (parsed.getFromAddress() != null) {
			builder.from(parsed.getFromAddress().getPersonal(), parsed.getFromAddress().getAddress());
		}
		if (parsed.getReplyToAddresses() != null) {
			builder.withReplyTo(parsed.getReplyToAddresses().getPersonal(), parsed.getReplyToAddresses().getAddress());
		}

		for (val headerEntry : parsed.getHeaders().entrySet()) {
			if (!HeadersToIgnoreWhenParsingExternalEmails.shouldIgnoreHeader(headerEntry.getKey())) {
				for (Object headerValue : headerEntry.getValue()) {
					builder.withHeader(headerEntry.getKey(), headerValue);
				}
			}
		}

		if (parsed.getDispositionNotificationTo() != null) {
			builder.withDispositionNotificationTo(parsed.getDispositionNotificationTo());
		}
		if (parsed.getReturnReceiptTo() != null) {
			builder.withReturnReceiptTo(parsed.getReturnReceiptTo());
		}
		if (parsed.getBounceToAddress() != null) {
			builder.withBounceTo(parsed.getBounceToAddress());
		}
		final ContentTransferEncoding contentTransferEncoding = toContentTransferEncoding(parsed.getContentTransferEncoding());
		if (contentTransferEncoding != null) {
			builder.withContentTransferEncoding(contentTransferEncoding);
		}
		builder.fixingMessageId(parsed.getMessageId());
		for (final InternetAddress to : parsed.getToAddresses()) {
			builder.withRecipients(new Recipient(to.getPersonal(), to.getAddress(), TO, null));
		}
		//noinspection QuestionableName
		for (final InternetAddress cc : parsed.getCcAddresses()) {
			builder.withRecipients(new Recipient(cc.getPersonal(), cc.getAddress(), CC, null));
		}
		for (final InternetAddress bcc : parsed.getBccAddresses()) {
			builder.withRecipients(new Recipient(bcc.getPersonal(), bcc.getAddress(), BCC, null));
		}
		builder.withSubject(parsed.getSubject() != null ? parsed.getSubject() : "");
		builder.withPlainText(parsed.getPlainContent());
		final ContentTransferEncoding plainTextContentTransferEncoding = toContentTransferEncoding(parsed.getPlainTextContentTransferEncoding());
		if (isBodyPartContentTransferEncodingOverride(contentTransferEncoding, plainTextContentTransferEncoding)) {
			builder.withPlainTextContentTransferEncoding(plainTextContentTransferEncoding);
		}
		builder.withHTMLText(parsed.getHtmlContent());
		final ContentTransferEncoding htmlTextContentTransferEncoding = toContentTransferEncoding(parsed.getHtmlTextContentTransferEncoding());
		if (isBodyPartContentTransferEncodingOverride(contentTransferEncoding, htmlTextContentTransferEncoding)) {
			builder.withHTMLTextContentTransferEncoding(htmlTextContentTransferEncoding);
		}

		if (parsed.getCalendarMethod() != null) {
			builder.withCalendarText(CalendarMethod.valueOf(parsed.getCalendarMethod()), verifyNonnullOrEmpty(parsed.getCalendarContent()));
			final ContentTransferEncoding calendarTextContentTransferEncoding = toContentTransferEncoding(parsed.getCalendarTextContentTransferEncoding());
			if (isBodyPartContentTransferEncodingOverride(contentTransferEncoding, calendarTextContentTransferEncoding)) {
				builder.withCalendarTextContentTransferEncoding(calendarTextContentTransferEncoding);
			}
		}

		for (final Map.Entry<String, MimeDataSource> cid : parsed.getCidMap().entrySet()) {
			final String cidName = checkNonEmptyArgument(cid.getKey(), "cid.key");
			final String resourceName = extractCID(cid.getValue().getName());
			final String contentId = extractCID(cidName);
			builder.withEmbeddedImage(resourceName, cid.getValue().getDataSource(), contentId != null && contentId.equals(resourceName) ? null : contentId);
		}
		for (final MimeDataSource attachment : parsed.getAttachmentList()) {
			final ContentTransferEncoding encoding = !valueNullOrEmpty(attachment.getContentTransferEncoding())
					? ContentTransferEncoding.byEncoder(attachment.getContentTransferEncoding()) : null;
			builder.withAttachment(extractCID(attachment.getName()), attachment.getDataSource(), attachment.getContentDescription(), encoding,
					userProvidedContentId(attachment));
		}
		return builder;
	}

	private static boolean isBodyPartContentTransferEncodingOverride(@Nullable final ContentTransferEncoding fallbackContentTransferEncoding,
																	 @Nullable final ContentTransferEncoding bodyPartContentTransferEncoding) {
		return bodyPartContentTransferEncoding != null && bodyPartContentTransferEncoding != fallbackContentTransferEncoding;
	}

	@Nullable
	private static ContentTransferEncoding toContentTransferEncoding(@Nullable final String contentTransferEncoding) {
		return !valueNullOrEmpty(contentTransferEncoding) ? ContentTransferEncoding.byEncoder(contentTransferEncoding) : null;
	}

	@Nullable
	private static String userProvidedContentId(final MimeDataSource attachment) {
		final String contentId = extractCID(attachment.getContentId());
		return contentId != null && contentId.matches(GENERATED_ATTACHMENT_CONTENT_ID_PATTERN) ? null : contentId;
	}

}
