package org.codemonkey.simplejavamail;

import java.io.UnsupportedEncodingException;
import java.util.Date;
import java.util.Properties;

import javax.activation.DataHandler;
import javax.activation.DataSource;
import javax.mail.Address;
import javax.mail.Authenticator;
import javax.mail.BodyPart;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Part;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

import org.apache.log4j.Logger;

/**
 * Mailing tool aimed for simplicity, for sending emails of any complexity. This includes emails with plain text and/or html content,
 * embedded images and separate attachments<br />
 * <br />
 * This mailing tool abstracts the javax.mail API to a higher level easy to use API. For public use, this tool only works with {@link Email}
 * instances. <br />
 * <br />
 * The email message structure is built to work with all email clients and has beed tested with many different webclients as well as some
 * mainstream client applications such as MS Outlook or Mozilla Thunderbird.<br />
 * <br />
 * Technically, the resulting email structure is a follows:<br />
 * 
 * <pre>
 * - root
 * 	- related
 * 		- alternative
 * 			- mail tekst
 * 			- mail html tekst
 * 		- embedded images
 * 	- attachments
 * </pre>
 * 
 * <br />
 * Usage example:<br />
 * 
 * <pre>
 * Email email = new Email();
 * email.setFromAddress(&quot;lollypop&quot;, &quot;lolly.pop@somemail.com&quot;);
 * email.addRecipient(&quot;Sugar Cae&quot;, &quot;sugar.cane@candystore.org&quot;, RecipientType.TO);
 * email.setText(&quot;We should meet up!!&quot;);
 * email.setTextHTML(&quot;&lt;b&gt;We should meet up!&lt;/b&gt;&quot;);
 * email.setSubject(&quot;Hey&quot;);
 * new Mailer(preconfiguredMailSession).sendMail(email);
 * // or:
 * new Mailer(&quot;smtp.someserver.com&quot;, 25, &quot;username&quot;, &quot;password&quot;).sendMail(email);
 * </pre>
 * 
 * @author Benny Bottema
 * @see MimeEmailMessageWrapper
 * @see Email
 */
public class Mailer {

	private static final Logger logger = Logger.getLogger(Mailer.class);

	/**
	 * Used to actually send the email. This session can come from being passed in the default constructor, or made by this mailer directly,
	 * when no <code>Session</code> was provided.
	 * 
	 * @see #Mailer(Session)
	 * @see #Mailer(String, int, String, String)
	 */
	private final Session session;

	/**
	 * Email address restriction flags set either by constructor or overridden by getter by user.
	 * 
	 * @see EmailAddressValidationCriteria
	 */
	private EmailAddressValidationCriteria emailAddressValidationCriteria;

	/**
	 * Default constructor, stores the given mail session for later use.
	 * <p>
	 * Also defines a default email address validation criteria object, which remains true to RFC 2822, meaning allowing both domain
	 * literals and quoted identifiers (see {@link EmailAddressValidationCriteria#EmailAddressValidationCriteria(boolean, boolean)}).
	 * 
	 * @param session A preconfigured mail {@link Session} object with which a {@link Message} can be produced.
	 */
	public Mailer(final Session session) {
		this.session = session;
		this.emailAddressValidationCriteria = new EmailAddressValidationCriteria(true, true);
	}

	/**
	 * Overloaded constructor which produces a new {@link Session} on the fly. Use this if you don't have a mail session configured in your
	 * web container, or Spring context etc.
	 * 
	 * @param host The address of the smtp server to be used.
	 * @param port The port of the smtp server.
	 * @param username An optional username, may be <code>null</code>.
	 * @param password An optional password, may be <code>null</code>.
	 */
	public Mailer(final String host, final int port, final String username, final String password) {
		this(createMailSession(host, port, username, password));
	}

	/**
	 * Creates a {@link Session} configured with an smtp host, port and optional username and password.
	 * 
	 * @param host The address of the smtp server to be used.
	 * @param port The port of the smtp server.
	 * @param username An optional username, may be <code>null</code>.
	 * @param password An optional password, may be <code>null</code>.
	 * @return A fully configured <code>Session</code> possibly with authentication set.
	 */
	private static Session createMailSession(final String host, final int port, final String username, final String password) {
		assert host != null : "Can't send an email without host";
		assert password == null || username != null : "Can't have a password without username";

		final Properties props = new Properties();
		props.put("mail.smtp.host", host);
		props.put("mail.smtp.port", String.valueOf(port));

		if (username != null) {
			props.put("mail.smtp.username", username);
		}

		if (password != null) {
			// setup mail session to authenticate when connecting
			props.put("mail.smtp.auth", true);
			return Session.getInstance(props, new Authenticator() {
				@Override
				protected PasswordAuthentication getPasswordAuthentication() {
					return new PasswordAuthentication(username, password);
				}
			});
		} else {
			return Session.getInstance(props);
		}
	}

	/**
	 * Processes an {@link Email} instance into a completely configured {@link Message}, which in turn is being sent to all defined
	 * recipients.
	 * 
	 * @param email The information for the email to be sent.
	 * @throws MailException Can be thrown if an email isnt validating correctly, or some other problem occurs during connection, sending
	 *             etc.
	 * @see {@link Email}
	 * @see #validate(Email)
	 * @see #prepareMessage(Email, MimeMultipart)
	 * @see #setRecipients(Email, Message)
	 * @see #setTexts(Email, MimeMultipart)
	 * @see #setEmbeddedImages(Email, MimeMultipart)
	 * @see #setAttachments(Email, MimeMultipart)
	 */
	public final void sendMail(final Email email)
			throws MailException {
		if (validate(email)) {
			try {
				// create new wrapper for each mail being sent (enable sending multiple emails with one mailer)
				final MimeEmailMessageWrapper messageRoot = new MimeEmailMessageWrapper();
				// fill and send wrapped mime message parts
				final Message message = prepareMessage(email, messageRoot.multipartRoot);
				setRecipients(email, message);
				setTexts(email, messageRoot.multipartAlternativeMessages);
				setEmbeddedImages(email, messageRoot.multipartRelated);
				setAttachments(email, messageRoot.multipartRoot);
				Transport.send(message);
			} catch (final UnsupportedEncodingException e) {
				logger.error(e.getMessage(), e);
				throw new MailException(String.format(MailException.INVALID_ENCODING, e.getMessage()));
			} catch (final MessagingException e) {
				logger.error(e.getMessage(), e);
				throw new MailException(String.format(MailException.GENERIC_ERROR, e.getMessage()), e);
			}
		}
	}

	/**
	 * Validates an {@link Email} instance. Validation fails if the subject is missing, content is missing, or no recipients are defined.
	 * 
	 * @param email The email that needs to be configured correctly.
	 * @return Always <code>true</code> (throws a {@link MailException} exception if validation fails).
	 * @throws MailException Is being thrown in any of the above causes.
	 * @see EmailValidationUtil
	 */
	public boolean validate(final Email email)
			throws MailException {
		if (email.getText() == null && email.getTextHTML() == null) {
			throw new MailException(MailException.MISSING_CONTENT);
		} else if (email.getSubject() == null || email.getSubject().equals("")) {
			throw new MailException(MailException.MISSING_SUBJECT);
		} else if (email.getRecipients().size() == 0) {
			throw new MailException(MailException.MISSING_RECIPIENT);
		} else if (email.getFromRecipient() == null) {
			throw new MailException(MailException.MISSING_SENDER);
		} else {
			if (!EmailValidationUtil.isValid(email.getFromRecipient().getAddress(), emailAddressValidationCriteria)) {
				throw new MailException(String.format(MailException.INVALID_SENDER, email));
			}
			for (final Recipient recipient : email.getRecipients()) {
				if (!EmailValidationUtil.isValid(recipient.getAddress(), emailAddressValidationCriteria)) {
					throw new MailException(String.format(MailException.INVALID_RECIPIENT, email));
				}
			}
		}
		return true;
	}

	/**
	 * Creates a new {@link MimeMessage} instance and prepares it in the email structure, so that it can be filled and send.
	 * 
	 * @param email The email message from which the subject and From-address are extracted.
	 * @param multipartRoot The root of the email which holds everything (filled with some email data).
	 * @return Een geprepareerde {@link Message} instantie, klaar om gevuld en verzonden te worden.
	 * @throws MessagingException Kan gegooid worden als het message niet goed behandelt wordt.
	 * @throws UnsupportedEncodingException Zie {@link InternetAddress#InternetAddress(String, String)}.
	 */
	private Message prepareMessage(final Email email, final MimeMultipart multipartRoot)
			throws MessagingException, UnsupportedEncodingException {
		final Message message = new MimeMessage(session);
		message.setSubject(email.getSubject());
		message.setFrom(new InternetAddress(email.getFromRecipient().getAddress(), email.getFromRecipient().getName()));
		message.setContent(multipartRoot);
		message.setSentDate(new Date());
		return message;
	}

	/**
	 * Fills the {@link Message} instance with recipients from the {@link Email}.
	 * 
	 * @param email The message in which the recipients are defined.
	 * @param message The javax message that needs to be filled with recipients.
	 * @throws UnsupportedEncodingException See {@link InternetAddress#InternetAddress(String, String)}.
	 * @throws MessagingException See {@link Message#addRecipient(javax.mail.Message.RecipientType, Address)}
	 */
	private void setRecipients(final Email email, final Message message)
			throws UnsupportedEncodingException, MessagingException {
		for (final Recipient recipient : email.getRecipients()) {
			final Address address = new InternetAddress(recipient.getAddress(), recipient.getName());
			message.addRecipient(recipient.getType(), address);
		}
	}

	/**
	 * Fills the {@link Message} instance with the content bodies (text and html).
	 * 
	 * @param email The message in which the content is defined.
	 * @param multipartAlternativeMessages See {@link MimeMultipart#addBodyPart(BodyPart)}
	 * @throws MessagingException See {@link BodyPart#setText(String)}, {@link BodyPart#setContent(Object, String)} and
	 *             {@link MimeMultipart#addBodyPart(BodyPart)}.
	 */
	private void setTexts(final Email email, final MimeMultipart multipartAlternativeMessages)
			throws MessagingException {
		if (email.getText() != null) {
			final MimeBodyPart messagePart = new MimeBodyPart();
			messagePart.setText(email.getText(), "UTF-8");
			multipartAlternativeMessages.addBodyPart(messagePart);
		}
		if (email.getTextHTML() != null) {
			final MimeBodyPart messagePartHTML = new MimeBodyPart();
			messagePartHTML.setContent(email.getTextHTML(), "text/html; charset=\"UTF-8\"");
			multipartAlternativeMessages.addBodyPart(messagePartHTML);
		}
	}

	/**
	 * Fills the {@link Message} instance with the embedded images from the {@link Email}.
	 * 
	 * @param email The message in which the embedded images are defined.
	 * @param multipartRelated The branch in the email structure in which we'll stuff the embedded images.
	 * @throws MessagingException See {@link MimeMultipart#addBodyPart(BodyPart)} and
	 *             {@link #getBodyPartFromDatasource(AttachmentResource, String)}
	 */
	private void setEmbeddedImages(final Email email, final MimeMultipart multipartRelated)
			throws MessagingException {
		for (final AttachmentResource embeddedImage : email.getEmbeddedImages()) {
			multipartRelated.addBodyPart(getBodyPartFromDatasource(embeddedImage, Part.INLINE));
		}
	}

	/**
	 * Fills the {@link Message} instance with the attachments from the {@link Email}.
	 * 
	 * @param email The message in which the attachments are defined.
	 * @param multipartRoot The branch in the email structure in which we'll stuff the attachments.
	 * @throws MessagingException See {@link MimeMultipart#addBodyPart(BodyPart)} and
	 *             {@link #getBodyPartFromDatasource(AttachmentResource, String)}
	 */
	private void setAttachments(final Email email, final MimeMultipart multipartRoot)
			throws MessagingException {
		for (final AttachmentResource resource : email.getAttachments()) {
			multipartRoot.addBodyPart(getBodyPartFromDatasource(resource, Part.ATTACHMENT));
		}
	}

	/**
	 * Helper method which generates a {@link BodyPart} from an {@link AttachmentResource} (from its {@link DataSource}) and a disposition
	 * type ({@link Part#INLINE} or {@link Part#ATTACHMENT}). With this the attachment data can be converted into objects that fit in the
	 * email structure. <br />
	 * <br />
	 * For every attachment and embedded image a header needs to be set.
	 * 
	 * @param resource An object that describes the attachment and contains the actual content data.
	 * @param dispositionType The type of attachment, {@link Part#INLINE} or {@link Part#ATTACHMENT} .
	 * @return An object with the attachment data read for placement in the email structure.
	 * @throws MessagingException All BodyPart setters.
	 */
	private BodyPart getBodyPartFromDatasource(final AttachmentResource resource, final String dispositionType)
			throws MessagingException {
		final BodyPart attachmentPart = new MimeBodyPart();
		final DataSource ds = resource.getDataSource();
		// setting headers isn't working nicely using the javax mail API, so let's do that manually
		attachmentPart.setDataHandler(new DataHandler(resource.getDataSource()));
		attachmentPart.setFileName(resource.getName());
		attachmentPart.setHeader("Content-Type", ds.getContentType() + "; filename=" + ds.getName() + "; name=" + ds.getName());
		attachmentPart.setHeader("Content-ID", String.format("<%s>", ds.getName()));
		attachmentPart.setDisposition(dispositionType + "; size=0");
		return attachmentPart;
	}

	/**
	 * This class conveniently wraps all necessary mimemessage parts that need to be filled with content, attachments etc. The root is
	 * ultimately sent using JavaMail.<br />
	 * <br />
	 * The constructor creates a new email message constructed from {@link MimeMultipart} as follows:
	 * 
	 * <pre>
	 * - root
	 * 	- related
	 * 		- alternative
	 * 			- mail tekst
	 * 			- mail html tekst
	 * 		- embedded images
	 * 	- attachments
	 * </pre>
	 * 
	 * @author Benny Bottema
	 */
	private class MimeEmailMessageWrapper {

		private final MimeMultipart multipartRoot;

		private final MimeMultipart multipartRelated;

		private final MimeMultipart multipartAlternativeMessages;

		/**
		 * Creates an email skeleton structure, so that embedded images, attachments and (html) texts are being processed properly.
		 */
		MimeEmailMessageWrapper() {
			multipartRoot = new MimeMultipart("mixed");
			final MimeBodyPart contentRelated = new MimeBodyPart();
			multipartRelated = new MimeMultipart("related");
			final MimeBodyPart contentAlternativeMessages = new MimeBodyPart();
			multipartAlternativeMessages = new MimeMultipart("alternative");
			try {
				// construct mail structure
				multipartRoot.addBodyPart(contentRelated);
				contentRelated.setContent(multipartRelated);
				multipartRelated.addBodyPart(contentAlternativeMessages);
				contentAlternativeMessages.setContent(multipartAlternativeMessages);
			} catch (final MessagingException e) {
				logger.error(e.getMessage(), e);
				throw new RuntimeException(e.getMessage(), e);
			}
		}
	}

	/**
	 * Overrides the default email address validation restrictions when validating and sending emails using the current <code>Mailer</code>
	 * instance.
	 * 
	 * @param emailAddressValidationCriteria Refer to
	 *            {@link EmailAddressValidationCriteria#EmailAddressValidationCriteria(boolean, boolean)}.
	 */
	public void setEmailAddressValidationCriteria(EmailAddressValidationCriteria emailAddressValidationCriteria) {
		this.emailAddressValidationCriteria = emailAddressValidationCriteria;
	}
}