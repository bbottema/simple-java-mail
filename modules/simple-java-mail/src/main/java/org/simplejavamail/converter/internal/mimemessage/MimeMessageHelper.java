package org.simplejavamail.converter.internal.mimemessage;

import jakarta.activation.DataHandler;
import jakarta.activation.DataSource;
import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Part;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimePart;
import jakarta.mail.internet.MimeUtility;
import jakarta.mail.internet.ParameterList;
import lombok.val;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.ContentTransferEncoding;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.internal.util.MiscUtil;
import org.simplejavamail.internal.util.NamedDataSource;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import static java.lang.Boolean.TRUE;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static java.util.Optional.ofNullable;
import static org.simplejavamail.internal.util.MiscUtil.valueNullOrEmpty;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;

/**
 * Helper class that produces and populates a mime messages. Deals with jakarta.mail RFC MimeMessage stuff, as well as DKIM signing.
 */
public class MimeMessageHelper {

	/**
	 * Encoding used for setting body text, email address, headers, reply-to fields etc. ({@link StandardCharsets#UTF_8}).
	 */
	private static final String CHARACTER_ENCODING = StandardCharsets.UTF_8.name();

	private static final String HEADER_CONTENT_TRANSFER_ENCODING = "Content-Transfer-Encoding";

	private MimeMessageHelper() {

	}

	static void setSubject(@NotNull final Email email, final MimeMessage message) throws MessagingException {
		message.setSubject(email.getSubject(), CHARACTER_ENCODING);
	}

	static void setFrom(@NotNull final Email email, final MimeMessage message) throws UnsupportedEncodingException, MessagingException {
		val fromRecipient = email.getFromRecipient();
		if (fromRecipient != null) {
			message.setFrom(new InternetAddress(fromRecipient.getAddress(), fromRecipient.getName(), CHARACTER_ENCODING));
		}
	}
	
	/**
	 * Fills the {@link Message} instance with recipients from the {@link Email}.
	 *
	 * @param email   The message in which the recipients are defined.
	 * @param message The javax message that needs to be filled with recipients.
	 * @throws UnsupportedEncodingException See {@link InternetAddress#InternetAddress(String, String)}.
	 * @throws MessagingException           See {@link Message#addRecipient(Message.RecipientType, Address)}
	 */
	static void setRecipients(final Email email, final Message message)
			throws UnsupportedEncodingException, MessagingException {
		for (final Recipient recipient : email.getRecipients()) {
				message.addRecipient(recipient.getType(), new InternetAddress(recipient.getAddress(), recipient.getName(), CHARACTER_ENCODING));
		}
	}

	/**
	 * Fills the {@link Message} instance with reply-to address(es).
	 *
	 * @param email   The message in which the recipients are defined.
	 * @param message The javax message that needs to be filled with reply-to addresses.
	 * @throws UnsupportedEncodingException See {@link InternetAddress#InternetAddress(String, String)}.
	 * @throws MessagingException           See {@link Message#setReplyTo(Address[])}
	 */
	static void setReplyTo(@NotNull final Email email, final Message message)
			throws UnsupportedEncodingException, MessagingException {
		if (!email.getReplyToRecipients().isEmpty()) {
			val replyToAddresses = new Address[email.getReplyToRecipients().size()];
			int i = 0;
			for (val replyToRecipient : email.getReplyToRecipients()) {
				replyToAddresses[i++] = new InternetAddress(replyToRecipient.getAddress(), replyToRecipient.getName(), CHARACTER_ENCODING);
			}
			message.setReplyTo(replyToAddresses);
		}
	}

	/**
	 * Fills the {@link Message} instance with the content bodies (text, html and calendar), with Content-Transfer-Encoding header taken from Email.
	 *
	 * @param email                        The message in which the content is defined.
	 * @param multipartAlternativeMessages See {@link MimeMultipart#addBodyPart(BodyPart)}
	 * @throws MessagingException See {@link BodyPart#setText(String)}, {@link BodyPart#setContent(Object, String)} and {@link MimeMultipart#addBodyPart(BodyPart)}.
	 */
	static void setTexts(@NotNull final Email email, final MimeMultipart multipartAlternativeMessages)
			throws MessagingException {
		if (email.getPlainText() != null) {
			val messagePart = new MimeBodyPart();
			messagePart.setText(email.getPlainText(), CHARACTER_ENCODING);
			messagePart.addHeader(HEADER_CONTENT_TRANSFER_ENCODING, determineContentTransferEncoder(email));
			multipartAlternativeMessages.addBodyPart(messagePart);
		}
		if (email.getHTMLText() != null) {
			val messagePartHTML = new MimeBodyPart();
			messagePartHTML.setContent(email.getHTMLText(), format("text/html; charset=\"%s\"", CHARACTER_ENCODING));
			messagePartHTML.addHeader(HEADER_CONTENT_TRANSFER_ENCODING, determineContentTransferEncoder(email));
			multipartAlternativeMessages.addBodyPart(messagePartHTML);
		}
		if (email.getCalendarText() != null) {
			val calendarMethod = requireNonNull(email.getCalendarMethod(), "calendarMethod is required when calendarText is set");
			val messagePartCalendar = new MimeBodyPart();
			messagePartCalendar.setContent(email.getCalendarText(), format("text/calendar; charset=\"%s\"; method=\"%s\"", CHARACTER_ENCODING, calendarMethod));
			messagePartCalendar.addHeader(HEADER_CONTENT_TRANSFER_ENCODING, determineContentTransferEncoder(email));
			multipartAlternativeMessages.addBodyPart(messagePartCalendar);
		}
	}

	private static String determineContentTransferEncoder(@NotNull Email email) {
		return (email.getContentTransferEncoding() != null
				? email.getContentTransferEncoding()
				: ContentTransferEncoding.getDefault()).getEncoder();
	}

	/**
	 * Fills the {@link MimeBodyPart} instance with the content body content (text, html and calendar), with Content-Transfer-Encoding header taken from Email.
	 *
	 * @param email       The message in which the content is defined.
	 * @param messagePart The {@link MimeBodyPart} that will contain the body content (either plain text, HTML text or iCalendar text)
	 *                    and the Content-Transfer-Encoding header.
	 * @throws MessagingException See {@link BodyPart#setText(String)}, {@link BodyPart#setContent(Object, String)}.
	 */
	static void setTexts(@NotNull final Email email, final MimePart messagePart)
			throws MessagingException {
		if (email.getPlainText() != null) {
			messagePart.setText(email.getPlainText(), CHARACTER_ENCODING);
		}
		if (email.getHTMLText() != null) {
			messagePart.setContent(email.getHTMLText(), format("text/html; charset=\"%s\"", CHARACTER_ENCODING));
		}
		if (email.getCalendarText() != null) {
			val calendarMethod = requireNonNull(email.getCalendarMethod(), "CalendarMethod must be set when CalendarText is set");
			messagePart.setContent(email.getCalendarText(), format("text/calendar; charset=\"%s\"; method=\"%s\"", CHARACTER_ENCODING, calendarMethod));
		}
		messagePart.addHeader(HEADER_CONTENT_TRANSFER_ENCODING, determineContentTransferEncoder(email));
	}
	
	/**
	 * If provided, adds the {@code emailToForward} as a MimeBodyPart to the mixed multipart root.
	 * <p>
	 * <strong>Note:</strong> this is done without setting {@code Content-Disposition} so email clients can choose
	 * how to display embedded forwards. Most client will show the forward as inline, some may show it as attachment.
	 */
	static void configureForwarding(@NotNull final Email email, @NotNull final MimeMultipart multipartRootMixed) throws MessagingException {
		if (email.getEmailToForward() != null) {
			final BodyPart fordwardedMessage = new MimeBodyPart();
			fordwardedMessage.setContent(email.getEmailToForward(), "message/rfc822");
			multipartRootMixed.addBodyPart(fordwardedMessage);
		}
	}

	/**
	 * Fills the {@link Message} instance with the embedded images from the {@link Email}.
	 *
	 * @param email            The message in which the embedded images are defined.
	 * @param multipartRelated The branch in the email structure in which we'll stuff the embedded images.
	 * @throws MessagingException See {@link MimeMultipart#addBodyPart(BodyPart)} and {@link #getBodyPartFromDatasource(AttachmentResource, String)}
	 */
	static void setEmbeddedImages(@NotNull final Email email, final MimeMultipart multipartRelated)
			throws MessagingException {
		for (final AttachmentResource embeddedImage : email.getEmbeddedImages()) {
			multipartRelated.addBodyPart(getBodyPartFromDatasource(embeddedImage, Part.INLINE));
		}
	}


	/**
	 * Fills the {@link Message} instance with the attachments from the {@link Email}.
	 *
	 * @param email         The message in which the attachments are defined.
	 * @param multipartRoot The branch in the email structure in which we'll stuff the attachments.
	 * @throws MessagingException See {@link MimeMultipart#addBodyPart(BodyPart)} and {@link #getBodyPartFromDatasource(AttachmentResource, String)}
	 */
	static void setAttachments(@NotNull final Email email, final MimeMultipart multipartRoot)
			throws MessagingException {
		for (final AttachmentResource attachment : email.getAttachments()) {
			multipartRoot.addBodyPart(getBodyPartFromDatasource(attachment, Part.ATTACHMENT));
		}
	}

	/**
	 * Sets all headers on the {@link Message} instance. Since we're not using a high-level JavaMail method, the JavaMail library says we need to do
	 * some encoding and 'folding' manually, to get the value right for the headers (see {@link MimeUtility}.
	 * <p>
	 * Furthermore sets the notification flags <code>Disposition-Notification-To</code> and <code>Return-Receipt-To</code> if provided. It used
	 * JavaMail's built in method for producing an RFC compliant email address (see {@link InternetAddress#toString()}).
	 *
	 * @param email   The message in which the headers are defined.
	 * @param message The {@link Message} on which to set the raw, encoded and folded headers.
	 * @throws UnsupportedEncodingException See {@link MimeUtility#encodeText(String, String, String)}
	 * @throws MessagingException           See {@link Message#addHeader(String, String)}
	 * @see MimeUtility#encodeText(String, String, String)
	 * @see MimeUtility#fold(int, String)
	 */
	static void setHeaders(@NotNull final Email email, final Message message)
			throws UnsupportedEncodingException, MessagingException {

		// add headers (for raw message headers we need to 'fold' them using MimeUtility
		for (val header : email.getHeaders().entrySet()) {
			setHeader(message, header);
		}

		if (TRUE.equals(email.getUseDispositionNotificationTo())) {
			final Recipient dispositionTo = checkNonEmptyArgument(email.getDispositionNotificationTo(), "dispositionNotificationTo");
			final Address address = new InternetAddress(dispositionTo.getAddress(), dispositionTo.getName(), CHARACTER_ENCODING);
			message.setHeader("Disposition-Notification-To", address.toString());
		}

		if (TRUE.equals(email.getUseReturnReceiptTo())) {
			final Recipient returnReceiptTo = checkNonEmptyArgument(email.getReturnReceiptTo(), "returnReceiptTo");
			final Address address = new InternetAddress(returnReceiptTo.getAddress(), returnReceiptTo.getName(), CHARACTER_ENCODING);
			message.setHeader("Return-Receipt-To", address.toString());
		}
	}

	private static void setHeader(Message message, Map.Entry<String, Collection<String>> header) throws UnsupportedEncodingException, MessagingException {
		for (final String headerValue : header.getValue()) {
			final String headerName = header.getKey();
			final String headerValueEncoded = MimeUtility.encodeText(headerValue, CHARACTER_ENCODING, null);
			final String foldedHeaderValue = MimeUtility.fold(headerName.length() + 2, headerValueEncoded);
			message.addHeader(header.getKey(), foldedHeaderValue);
		}
	}

	/**
	 * Helper method which generates a {@link BodyPart} from an {@link AttachmentResource} (from its {@link DataSource}) and a disposition type
	 * ({@link Part#INLINE} or {@link Part#ATTACHMENT}). With this the attachment data can be converted into objects that fit in the email structure.
	 * <p>
	 * For every attachment and embedded image a header needs to be set.
	 *
	 * @param attachmentResource An object that describes the attachment and contains the actual content data.
	 * @param dispositionType    The type of attachment, {@link Part#INLINE} or {@link Part#ATTACHMENT} .
	 *
	 * @return An object with the attachment data read for placement in the email structure.
	 * @throws MessagingException All BodyPart setters.
	 */
	private static BodyPart getBodyPartFromDatasource(final AttachmentResource attachmentResource, final String dispositionType)
			throws MessagingException {
		final BodyPart attachmentPart = new MimeBodyPart();
		// setting headers isn't working nicely using the javax mail API, so let's do that manually
		final String resourceName = determineResourceName(attachmentResource, true);
		final String fileName = determineResourceName(attachmentResource, false);
		attachmentPart.setDataHandler(new DataHandler(new NamedDataSource(fileName, attachmentResource.getDataSource())));
		attachmentPart.setFileName(fileName);
		final String contentType = attachmentResource.getDataSource().getContentType();
		ParameterList pl = new ParameterList();
		pl.set("filename", fileName);
		pl.set("name", fileName);
		attachmentPart.setHeader("Content-Type", contentType + pl);
		attachmentPart.setHeader("Content-ID", format("<%s>", resourceName));

		attachmentPart.setHeader("Content-Description", determineAttachmentDescription(attachmentResource));
		if (!valueNullOrEmpty(attachmentResource.getContentTransferEncoding())) {
			attachmentPart.setHeader("Content-Transfer-Encoding", attachmentResource.getContentTransferEncoding().getEncoder());
		}
		attachmentPart.setDisposition(dispositionType);
		return attachmentPart;
	}

	/**
	 * Determines the right resource name and optionally attaches the correct extension to the name. The result is mime encoded.
	 */
	static String determineResourceName(final AttachmentResource attachmentResource, final boolean encodeResourceName) {
		final String datasourceName = attachmentResource.getDataSource().getName();

		String resourceName;

		if (!valueNullOrEmpty(attachmentResource.getName())) {
			resourceName = attachmentResource.getName();
		} else if (!valueNullOrEmpty(datasourceName)) {
			resourceName = datasourceName;
		} else {
			resourceName = "resource" + UUID.randomUUID();
		}
		if (!valueNullOrEmpty(datasourceName)) {
			resourceName = possiblyAddExtension(datasourceName, resourceName);
		}
		return encodeResourceName ? MiscUtil.encodeText(resourceName) : resourceName;
	}

	@NotNull
	private static String possiblyAddExtension(final String datasourceName, String resourceName) {
		@SuppressWarnings("UnnecessaryLocalVariable")
		final String possibleFilename = datasourceName;
		if (!resourceName.contains(".") && possibleFilename.contains(".")) {
			final String extension = possibleFilename.substring(possibleFilename.lastIndexOf("."));
			if (!resourceName.endsWith(extension)) {
				resourceName += extension;
			}
		}
		return resourceName;
	}

	@Nullable
	private static String determineAttachmentDescription(AttachmentResource attachmentResource) {
		return ofNullable(attachmentResource.getDescription()).map(MiscUtil::encodeText).orElse(null);
	}
}