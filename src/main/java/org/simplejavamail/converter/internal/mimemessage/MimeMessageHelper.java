package org.simplejavamail.converter.internal.mimemessage;

import net.markenwerk.utils.mail.dkim.Canonicalization;
import net.markenwerk.utils.mail.dkim.DkimMessage;
import net.markenwerk.utils.mail.dkim.DkimSigner;
import net.markenwerk.utils.mail.dkim.SigningAlgorithm;
import org.simplejavamail.email.AttachmentResource;
import org.simplejavamail.email.Email;
import org.simplejavamail.email.Recipient;

import javax.activation.DataHandler;
import javax.activation.DataSource;
import javax.annotation.Nonnull;
import javax.mail.Address;
import javax.mail.BodyPart;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Part;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import javax.mail.internet.MimeUtility;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import static java.lang.String.format;
import static org.simplejavamail.internal.util.MiscUtil.checkArgumentNotEmpty;
import static org.simplejavamail.internal.util.MiscUtil.valueNullOrEmpty;

/**
 * Helper class that deals with javax.mail RFC MimeMessage stuff, as well as DKIM signing.
 */
public final class MimeMessageHelper {

	/**
	 * Encoding used for setting body text, email address, headers, reply-to fields etc. ({@link StandardCharsets#UTF_8}).
	 */
	private static final String CHARACTER_ENCODING = StandardCharsets.UTF_8.name();

	private MimeMessageHelper() {

	}

	/**
	 * Creates a new {@link MimeMessage} instance coupled to a specific {@link Session} instance and prepares it in the email structure, so that it
	 * can be filled and send.
	 * <p/>
	 * Fills subject, from,reply-to, content, sent-date, recipients, texts, embedded images, attachments, content and adds all headers.
	 *
	 * @param email   The email message from which the subject and From-address are extracted.
	 * @param session The Session to attach the MimeMessage to
	 * @return A fully preparated {@link Message} instance, ready to be sent.
	 * @throws MessagingException           May be thrown when the message couldn't be processed by JavaMail.
	 * @throws UnsupportedEncodingException Zie {@link InternetAddress#InternetAddress(String, String)}.
	 * @see #setRecipients(Email, Message)
	 * @see #setTexts(Email, MimeMultipart)
	 * @see #setEmbeddedImages(Email, MimeMultipart)
	 * @see #setAttachments(Email, MimeMultipart)
	 */
	public static MimeMessage produceMimeMessage(@Nonnull final Email email, @Nonnull final Session session)
			throws MessagingException, UnsupportedEncodingException {
		checkArgumentNotEmpty(email, "email is missing");
		checkArgumentNotEmpty(session, "session is needed, it cannot be attached later");
		
		// create new wrapper for each mail being sent (enable sending multiple emails with one mailer)
		final MimeEmailMessageWrapper messageRoot = new MimeEmailMessageWrapper();
		final MimeMessage message = new MimeMessage(session) {
			@Override
			protected void updateMessageID() throws MessagingException {
				if (valueNullOrEmpty(email.getId())) {
					super.updateMessageID();
				} else {
					setHeader("Message-ID", email.getId());
				}
			}
		};
		// set basic email properties
		message.setSubject(email.getSubject(), CHARACTER_ENCODING);
		message.setFrom(new InternetAddress(email.getFromRecipient().getAddress(), email.getFromRecipient().getName(), CHARACTER_ENCODING));
		setReplyTo(email, message);
		setRecipients(email, message);
		// fill multipart structure
		setTexts(email, messageRoot.multipartAlternativeMessages);
		configureForwarding(email, messageRoot.multipartRootMixed);
		setEmbeddedImages(email, messageRoot.multipartRelated);
		setAttachments(email, messageRoot.multipartRootMixed);
		message.setContent(messageRoot.multipartRootMixed);
		setHeaders(email, message);
		message.setSentDate(new Date());

		if (!valueNullOrEmpty(email.getDkimSigningDomain())) {
			return signMessageWithDKIM(message, email);
		}
		
		return message;
	}
	
	/**
	 * Fills the {@link Message} instance with recipients from the {@link Email}.
	 *
	 * @param email   The message in which the recipients are defined.
	 * @param message The javax message that needs to be filled with recipients.
	 * @throws UnsupportedEncodingException See {@link InternetAddress#InternetAddress(String, String)}.
	 * @throws MessagingException           See {@link Message#addRecipient(Message.RecipientType, Address)}
	 */
	private static void setRecipients(final Email email, final Message message)
			throws UnsupportedEncodingException, MessagingException {
		for (final Recipient recipient : email.getRecipients()) {
			final Address address = new InternetAddress(recipient.getAddress(), recipient.getName(), CHARACTER_ENCODING);
			message.addRecipient(recipient.getType(), address);
		}
	}

	/**
	 * Fills the {@link Message} instance with reply-to address.
	 *
	 * @param email   The message in which the recipients are defined.
	 * @param message The javax message that needs to be filled with reply-to address.
	 * @throws UnsupportedEncodingException See {@link InternetAddress#InternetAddress(String, String)}.
	 * @throws MessagingException           See {@link Message#setReplyTo(Address[])}
	 */
	private static void setReplyTo(final Email email, final Message message)
			throws UnsupportedEncodingException, MessagingException {
		final Recipient replyToRecipient = email.getReplyToRecipient();
		if (replyToRecipient != null) {
			final InternetAddress replyToAddress = new InternetAddress(replyToRecipient.getAddress(), replyToRecipient.getName(),
					CHARACTER_ENCODING);
			message.setReplyTo(new Address[] { replyToAddress });
		}
	}

	/**
	 * Fills the {@link Message} instance with the content bodies (text and html).
	 *
	 * @param email                        The message in which the content is defined.
	 * @param multipartAlternativeMessages See {@link MimeMultipart#addBodyPart(BodyPart)}
	 * @throws MessagingException See {@link BodyPart#setText(String)}, {@link BodyPart#setContent(Object, String)} and {@link
	 *                            MimeMultipart#addBodyPart(BodyPart)}.
	 */
	private static void setTexts(final Email email, final MimeMultipart multipartAlternativeMessages)
			throws MessagingException {
		if (email.getPlainText() != null) {
			final MimeBodyPart messagePart = new MimeBodyPart();
			messagePart.setText(email.getPlainText(), CHARACTER_ENCODING);
			multipartAlternativeMessages.addBodyPart(messagePart);
		}
		if (email.getHTMLText() != null) {
			final MimeBodyPart messagePartHTML = new MimeBodyPart();
			messagePartHTML.setContent(email.getHTMLText(), "text/html; charset=\"" + CHARACTER_ENCODING + "\"");
			multipartAlternativeMessages.addBodyPart(messagePartHTML);
		}
	}
	
	/**
	 * If provided, adds the {@code emailToForward} as a MimeBodyPart to the mixed multipart root.
	 * <p>
	 * <strong>Note:</strong> this is done without setting {@code Content-Disposition} so email clients can choose
	 * how to display embedded forwards. Most client will show the forward as inline, some may show it as attachment.
	 */
	private static void configureForwarding(@Nonnull final Email email, @Nonnull final MimeMultipart multipartRootMixed) {
		if (email.getEmailToForward() != null) {
			try {
				final BodyPart fordwardedMessage = new MimeBodyPart();
				fordwardedMessage.setContent(email.getEmailToForward(), "message/rfc822");
				multipartRootMixed.addBodyPart(fordwardedMessage);
			} catch (final MessagingException e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * Fills the {@link Message} instance with the embedded images from the {@link Email}.
	 *
	 * @param email            The message in which the embedded images are defined.
	 * @param multipartRelated The branch in the email structure in which we'll stuff the embedded images.
	 * @throws MessagingException See {@link MimeMultipart#addBodyPart(BodyPart)} and {@link #getBodyPartFromDatasource(AttachmentResource, String)}
	 */
	private static void setEmbeddedImages(final Email email, final MimeMultipart multipartRelated)
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
	private static void setAttachments(final Email email, final MimeMultipart multipartRoot)
			throws MessagingException {
		for (final AttachmentResource resource : email.getAttachments()) {
			multipartRoot.addBodyPart(getBodyPartFromDatasource(resource, Part.ATTACHMENT));
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
	private static void setHeaders(final Email email, final Message message)
			throws UnsupportedEncodingException, MessagingException {
		// add headers (for raw message headers we need to 'fold' them using MimeUtility
		for (final Map.Entry<String, String> header : email.getHeaders().entrySet()) {
			final String headerName = header.getKey();
			final String headerValue = MimeUtility.encodeText(header.getValue(), CHARACTER_ENCODING, null);
			final String foldedHeaderValue = MimeUtility.fold(headerName.length() + 2, headerValue);
			message.addHeader(header.getKey(), foldedHeaderValue);
		}
		
		if (email.isUseDispositionNotificationTo()) {
			final Address address = new InternetAddress(email.getDispositionNotificationTo().getAddress(),
					email.getDispositionNotificationTo().getName(), CHARACTER_ENCODING);
			message.setHeader("Disposition-Notification-To", address.toString());
		}
		
		if (email.isUseReturnReceiptTo()) {
			final Address address = new InternetAddress(email.getReturnReceiptTo().getAddress(),
					email.getReturnReceiptTo().getName(), CHARACTER_ENCODING);
			message.setHeader("Return-Receipt-To", address.toString());
		}
	}

	/**
	 * Helper method which generates a {@link BodyPart} from an {@link AttachmentResource} (from its {@link DataSource}) and a disposition type
	 * ({@link Part#INLINE} or {@link Part#ATTACHMENT}). With this the attachment data can be converted into objects that fit in the email structure.
	 * <br> <br> For every attachment and embedded image a header needs to be set.
	 *
	 * @param attachmentResource An object that describes the attachment and contains the actual content data.
	 * @param dispositionType    The type of attachment, {@link Part#INLINE} or {@link Part#ATTACHMENT} .
	 * @return An object with the attachment data read for placement in the email structure.
	 * @throws MessagingException All BodyPart setters.
	 */
	private static BodyPart getBodyPartFromDatasource(final AttachmentResource attachmentResource, final String dispositionType)
			throws MessagingException {
		final BodyPart attachmentPart = new MimeBodyPart();
		// setting headers isn't working nicely using the javax mail API, so let's do that manually
		final String resourceName = determineResourceName(attachmentResource, false);
		final String fileName = determineResourceName(attachmentResource, true);
		attachmentPart.setDataHandler(new DataHandler(new NamedDataSource(fileName, attachmentResource.getDataSource())));
		attachmentPart.setFileName(fileName);
		final String contentType = attachmentResource.getDataSource().getContentType();
		attachmentPart.setHeader("Content-Type", contentType + "; filename=" + fileName + "; name=" + resourceName);
		attachmentPart.setHeader("Content-ID", format("<%s>", resourceName));
		attachmentPart.setDisposition(dispositionType);
		return attachmentPart;
	}

	/**
	 * Determines the right resource name and optionally attaches the correct extension to the name.
	 */
	static String determineResourceName(final AttachmentResource attachmentResource, final boolean includeExtension) {
		final String datasourceName = attachmentResource.getDataSource().getName();

		String resourceName;

		if (!valueNullOrEmpty(attachmentResource.getName())) {
			resourceName = attachmentResource.getName();
		} else if (!valueNullOrEmpty(datasourceName)) {
			resourceName = datasourceName;
		} else {
			resourceName = "resource" + UUID.randomUUID();
		}
		if (includeExtension && !valueNullOrEmpty(datasourceName)) {
			@SuppressWarnings("UnnecessaryLocalVariable") final
			String possibleFilename = datasourceName;
			if (possibleFilename.contains(".")) {
				final String extension = possibleFilename.substring(possibleFilename.lastIndexOf("."), possibleFilename.length());
				if (!resourceName.endsWith(extension)) {
					resourceName += extension;
				}
			}
		} else if (!includeExtension && resourceName.contains(".") && resourceName.equals(datasourceName)) {
			final String extension = resourceName.substring(resourceName.lastIndexOf("."), resourceName.length());
			resourceName = resourceName.replace(extension, "");
		}
		return resourceName;
	}

	/**
	 * Primes the {@link MimeMessage} instance for signing with DKIM. The signing itself is performed by {@link DkimMessage} and {@link DkimSigner}
	 * during the physical sending of the message.
	 *
	 * @param message The message to be signed when sent.
	 * @param email   The {@link Email} that contains the relevant signing information
	 * @return The original mime message wrapped in a new one that performs signing when sent.
	 */
	public static MimeMessage signMessageWithDKIM(final MimeMessage message, final Email email) {
		try {
			final DkimSigner dkimSigner;
			if (email.getDkimPrivateKeyFile() != null) {
				// InputStream is managed by Dkim library
				dkimSigner = new DkimSigner(email.getDkimSigningDomain(), email.getDkimSelector(),
						email.getDkimPrivateKeyFile());
			} else {
				// InputStream is managed by SimpleJavaMail user
				dkimSigner = new DkimSigner(email.getDkimSigningDomain(), email.getDkimSelector(),
						email.getDkimPrivateKeyInputStream());
			}
			dkimSigner.setIdentity(email.getFromRecipient().getAddress());
			dkimSigner.setHeaderCanonicalization(Canonicalization.SIMPLE);
			dkimSigner.setBodyCanonicalization(Canonicalization.RELAXED);
			dkimSigner.setSigningAlgorithm(SigningAlgorithm.SHA256_WITH_RSA);
			dkimSigner.setLengthParam(true);
			dkimSigner.setZParam(false);
			return new DkimMessage(message, dkimSigner);
		} catch (IOException | NoSuchAlgorithmException | InvalidKeySpecException | MessagingException e) {
			throw new MimeMessageParseException(MimeMessageParseException.INVALID_DOMAINKEY, e);
		}
	}

	/**
	 * This class conveniently wraps all necessary mimemessage parts that need to be filled with content, attachments etc. The root is ultimately sent
	 * using JavaMail.<br> <br> The constructor creates a new email message constructed from {@link MimeMultipart} as follows:
	 * <p/>
	 * <pre>
	 * - mixed root
	 * 	- related
	 * 		- alternative
	 * 			- mail tekst
	 * 			- mail html tekst
	 * 		- embedded images
	 * 	- forwarded message
	 * 	- attachments
	 * </pre>
	 *
	 * @author Benny Bottema
	 */
	private static class MimeEmailMessageWrapper {

		private final MimeMultipart multipartRootMixed;

		private final MimeMultipart multipartRelated;

		private final MimeMultipart multipartAlternativeMessages;

		/**
		 * Creates an email skeleton structure, so that embedded images, attachments and (html) texts are being processed properly.
		 *
		 * Some more <a href="https://blogs.technet.microsoft.com/exchange/2011/04/21/mixed-ing-it-up-multipartmixed-messages-and-you/.">helpful reading material</a>.
		 */
		MimeEmailMessageWrapper() {
			multipartRootMixed = new MimeMultipart("mixed");
			final MimeBodyPart contentRelated = new MimeBodyPart();
			multipartRelated = new MimeMultipart("related");
			final MimeBodyPart contentAlternativeMessages = new MimeBodyPart();
			multipartAlternativeMessages = new MimeMultipart("alternative");
			try {
				// construct mail structure
				multipartRootMixed.addBodyPart(contentRelated);
				contentRelated.setContent(multipartRelated);
				multipartRelated.addBodyPart(contentAlternativeMessages);
				contentAlternativeMessages.setContent(multipartAlternativeMessages);
			} catch (final MessagingException e) {
				throw new MimeMessageParseException(e.getMessage(), e);
			}
		}

	}
}