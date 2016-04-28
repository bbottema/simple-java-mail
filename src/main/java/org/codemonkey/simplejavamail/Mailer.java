package org.codemonkey.simplejavamail;

import static org.hazlewood.connor.bottema.emailaddress.EmailAddressCriteria.RFC_COMPLIANT;

import java.io.UnsupportedEncodingException;
import java.util.Date;
import java.util.EnumSet;
import java.util.Map;
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
import javax.mail.internet.MimeUtility;

import org.codemonkey.simplejavamail.email.AttachmentResource;
import org.codemonkey.simplejavamail.email.Email;
import org.codemonkey.simplejavamail.email.Recipient;
import org.hazlewood.connor.bottema.emailaddress.EmailAddressCriteria;
import org.hazlewood.connor.bottema.emailaddress.EmailAddressValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mailing tool aimed for simplicity, for sending e-mails of any complexity. This includes e-mails with plain text and/or html content, embedded images and
 * separate attachments, SMTP, SMTPS / SSL and SMTP + SSL<br> <br> This mailing tool abstracts the javax.mail API to a higher level easy to use API. For public
 * use, this tool only works with {@link Email} instances. <br> <br> The e-mail message structure is built to work with all e-mail clients and has been tested
 * with many different webclients as well as some mainstream client applications such as MS Outlook or Mozilla Thunderbird.<br> <br> Technically, the resulting
 * email structure is as follows:<br>
 * <p/>
 * <pre>
 * - root
 * 	- related
 * 		- alternative
 * 			- mail text
 * 			- mail html text
 * 		- embedded images
 * 	- attachments
 * </pre>
 * <p/>
 * <br> Usage example:<br>
 * <p/>
 * <pre>
 * Email email = new Email();
 * email.setFromAddress(&quot;lollypop&quot;, &quot;lolly.pop@somemail.com&quot;);
 * email.addRecipient(&quot;Sugar Cane&quot;, &quot;sugar.cane@candystore.org&quot;, RecipientType.TO);
 * email.setText(&quot;We should meet up!!&quot;);
 * email.setTextHTML(&quot;&lt;b&gt;We should meet up!&lt;/b&gt;&quot;);
 * email.setSubject(&quot;Hey&quot;);
 * new Mailer(preconfiguredMailSession).sendMail(email);
 * // or:
 * new Mailer(&quot;smtp.someserver.com&quot;, 25, &quot;username&quot;, &quot;password&quot;).sendMail(email);
 * </pre>
 *
 * @author Benny Bottema
 * @see Mailer.MimeEmailMessageWrapper
 * @see Email
 */
public class Mailer {

	private static final Logger LOGGER = LoggerFactory.getLogger(Mailer.class);

	/**
	 * Encoding used for setting body text, email address, headers, reply-to fields etc. ({@value #CHARACTER_ENCODING}).
	 */
	private static final String CHARACTER_ENCODING = "UTF-8";

	/**
	 * Used to actually send the email. This session can come from being passed in the default constructor, or made by <code>Mailer</code> directly, when no
	 * <code>Session</code> instance was provided.
	 *
	 * @see #Mailer(Session)
	 * @see #Mailer(String, Integer, String, String, TransportStrategy)
	 */
	private final Session session;

	/**
	 * The transport protocol strategy enum that actually handles the session configuration. Session configuration meaning setting the right properties for the
	 * appropriate transport type (ie. <em>"mail.smtp.host"</em> for SMTP, <em>"mail.smtps.host"</em> for SMTPS).
	 */
	private TransportStrategy transportStrategy;

	/**
	 * Email address restriction flags set to {@link EmailAddressCriteria#RFC_COMPLIANT} or overridden by by user with {@link
	 * #setEmailAddressCriteria(EnumSet)}.
	 */
	private EnumSet<EmailAddressCriteria> emailAddressCriteria = RFC_COMPLIANT;

	/**
	 * Default constructor, stores the given mail session for later use. Assumes that *all* properties used to make a connection are configured (host, port,
	 * authentication and transport protocol settings).
	 *
	 * @param session A preconfigured mail {@link Session} object with which a {@link Message} can be produced.
	 */
	public Mailer(final Session session) {
		this.session = session;
	}

	/**
	 * Overloaded constructor which produces a new {@link Session} on the fly. Use this if you don't have a mail session configured in your web container, or
	 * Spring context etc.
	 *
	 * @param host              The address URL of the SMTP server to be used.
	 * @param port              The port of the SMTP server.
	 * @param username          An optional username, may be <code>null</code>.
	 * @param password          An optional password, may be <code>null</code>, but only if username is <code>null</code> as well.
	 * @param transportStrategy The transport protocol configuration type for handling SSL or TLS (or vanilla SMTP)
	 */
	public Mailer(final String host, final Integer port, final String username, final String password,
			final TransportStrategy transportStrategy) {
		if (host == null || host.trim().equals("")) {
			throw new MailException(MailException.MISSING_HOST);
		} else if ((password != null && !password.trim().equals("")) && (username == null || username.trim().equals(""))) {
			throw new MailException(MailException.MISSING_USERNAME);
		}
		this.transportStrategy = transportStrategy;
		this.session = createMailSession(host, port, username, password);
		this.emailAddressCriteria = null;
	}

	/**
	 * Actually instantiates and configures the {@link Session} instance. Delegates resolving transport protocol specific properties to the {@link
	 * #transportStrategy} in two ways: <ol> <li>request an initial property list which the strategy may pre-populate</li> <li>by requesting the property names
	 * according to the respective transport protocol it handles (for the host property for example it would be <em>"mail.smtp.host"</em> for SMTP and
	 * <em>"mail.smtps.host"</em> for SMTPS)</li> </ol>
	 *
	 * @param host     The address URL of the SMTP server to be used.
	 * @param port     The port of the SMTP server.
	 * @param username An optional username, may be <code>null</code>.
	 * @param password An optional password, may be <code>null</code>.
	 * @return A fully configured <code>Session</code> instance complete with transport protocol settings.
	 * @see TransportStrategy#generateProperties()
	 * @see TransportStrategy#propertyNameHost()
	 * @see TransportStrategy#propertyNamePort()
	 * @see TransportStrategy#propertyNameUsername()
	 * @see TransportStrategy#propertyNameAuthenticate()
	 */
	protected Session createMailSession(final String host, final Integer port, final String username, final String password) {
		if (transportStrategy == null) {
			LOGGER.warn("Transport Strategy not set, using plain SMTP strategy instead!");
			transportStrategy = TransportStrategy.SMTP_PLAIN;
		}
		Properties props = transportStrategy.generateProperties();
		props.put(transportStrategy.propertyNameHost(), host);
		if (port != null) {
			props.put(transportStrategy.propertyNamePort(), String.valueOf(port));
		} else {
			// let JavaMail's Transport objects determine default port base don the used protocol
		}

		if (username != null) {
			props.put(transportStrategy.propertyNameUsername(), username);
		}

		if (password != null) {
			props.put(transportStrategy.propertyNameAuthenticate(), "true");
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
	 * Overloaded constructor which produces a new {@link Session} on the fly, using default vanilla SMTP transport protocol.
	 *
	 * @param host     The address URL of the SMTP server to be used.
	 * @param port     The port of the SMTP server.
	 * @param username An optional username, may be <code>null</code>.
	 * @param password An optional password, may be <code>null</code>, but only if username is <code>null</code> as well.
	 * @see #Mailer(String, Integer, String, String, TransportStrategy)
	 */
	public Mailer(final String host, final Integer port, final String username, final String password) {
		this(host, port, username, password, TransportStrategy.SMTP_PLAIN);
	}

	/**
	 * In case Simple Java Mail falls short somehow, you can get a hold of the internal {@link Session} instance to debug or tweak. Please let us know why you
	 * are needing this on https://github.com/bbottema/simple-java-mail/issues.
	 */
	public Session getSession() {
		LOGGER.warn("Providing access to Session instance for emergency fall-back scenario. Please let us know why you need it.");
		LOGGER.warn("\t>https://github.com/bbottema/simple-java-mail/issues");
		return session;
	}

	/**
	 * Actually sets {@link Session#setDebug(boolean)} so that it generates debug information.
	 *
	 * @param debug Flag to indicate debug mode yes/no.
	 */
	public void setDebug(final boolean debug) {
		session.setDebug(debug);
	}

	/**
	 * Copies all property entries into the {@link Session} using {@link Session#getProperties()}.
	 *
	 * @param properties The source properties to add or override in the internal {@link Session} instance.
	 */
	public void applyProperties(final Properties properties) {
		session.getProperties().putAll(properties);
	}

	/**
	 * Processes an {@link Email} instance into a completely configured {@link Message}.
	 * <p/>
	 * Sends the Sun JavaMail {@link Message} object using {@link Session#getTransport()}. It will call {@link Transport#connect()} assuming all connection
	 * details have been configured in the provided {@link Session} instance.
	 * <p/>
	 * Performs a call to {@link Message#saveChanges()} as the Sun JavaMail API indicates it is needed to configure the message headers and providing a message
	 * id.
	 *
	 * @param email The information for the email to be sent.
	 * @throws MailException Can be thrown if an email isn't validating correctly, or some other problem occurs during connection, sending etc.
	 * @see #validate(Email)
	 * @see #produceMimeMessage(Email, Session)
	 * @see #setRecipients(Email, Message)
	 * @see #setTexts(Email, MimeMultipart)
	 * @see #setEmbeddedImages(Email, MimeMultipart)
	 * @see #setAttachments(Email, MimeMultipart)
	 */
	public final void sendMail(final Email email)
			throws MailException {
		if (validate(email)) {
			try {
				// fill and send wrapped mime message parts
				final Message message = produceMimeMessage(email, session);
				logSession(session, transportStrategy);
				message.saveChanges(); // some headers and id's will be set for this specific message
				Transport transport = session.getTransport();
				try {
					transport.connect();
					transport.sendMessage(message, message.getAllRecipients());
				} finally {
					transport.close();
				}
			} catch (final UnsupportedEncodingException e) {
				LOGGER.error(e.getMessage(), e);
				throw new MailException(String.format(MailException.INVALID_ENCODING, e.getMessage()));
			} catch (final MessagingException e) {
				LOGGER.error(e.getMessage(), e);
				throw new MailException(String.format(MailException.GENERIC_ERROR, e.getMessage()), e);
			}
		}
	}

	/**
	 * Simply logs host details, credentials used and whether authentication will take place and finally the transport protocol used.
	 */
	private void logSession(Session session, TransportStrategy transportStrategy) {
		Properties properties = session.getProperties();
		final String specifics;
		if (transportStrategy != null) {
			final String logmsg = "starting mail session (host: %s, port: %s, username: %s, authenticate: %s, transport: %s)";
			specifics = String.format(logmsg, properties.get(transportStrategy.propertyNameHost()),
					properties.get(transportStrategy.propertyNamePort()), properties.get(transportStrategy.propertyNameUsername()),
					properties.get(transportStrategy.propertyNameAuthenticate()), transportStrategy);
		} else {
			specifics = properties.toString();
		}
		LOGGER.debug(String.format("starting mail session (%s)", specifics));
	}

	/**
	 * Validates an {@link Email} instance. Validation fails if the subject is missing, content is missing, or no recipients are defined.
	 *
	 * @param email The email that needs to be configured correctly.
	 * @return Always <code>true</code> (throws a {@link MailException} exception if validation fails).
	 * @throws MailException Is being thrown in any of the above causes.
	 * @see EmailAddressValidator
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
		} else if (emailAddressCriteria != null) {
			if (!EmailAddressValidator.isValid(email.getFromRecipient().getAddress(), emailAddressCriteria)) {
				throw new MailException(String.format(MailException.INVALID_SENDER, email));
			}
			for (final Recipient recipient : email.getRecipients()) {
				if (!EmailAddressValidator.isValid(recipient.getAddress(), emailAddressCriteria)) {
					throw new MailException(String.format(MailException.INVALID_RECIPIENT, email));
				}
			}
			if (email.getReplyToRecipient() != null) {
				if (!EmailAddressValidator.isValid(email.getReplyToRecipient().getAddress(), emailAddressCriteria)) {
					throw new MailException(String.format(MailException.INVALID_REPLYTO, email));
				}
			}
		}
		return true;
	}

	/**
	 * Creates a new {@link MimeMessage} instance and prepares it in the email structure, so that it can be filled and send.
	 * <p/>
	 * Fills subject, from,reply-to, content, sent-date, recipients, texts, embedded images, attachments, content and adds all headers.
	 *
	 * @param email   The email message from which the subject and From-address are extracted.
	 * @param session The Session to attach the MimeMessage to
	 * @return A fully preparated {@link Message} instance, ready to be sent.
	 * @throws MessagingException           May be thrown when the message couldn't be processed by JavaMail.
	 * @throws UnsupportedEncodingException Zie {@link InternetAddress#InternetAddress(String, String)}.
	 */
	public static MimeMessage produceMimeMessage(final Email email, final Session session)
			throws MessagingException, UnsupportedEncodingException {
		if (email == null) {
			throw new IllegalStateException("email is missing");
		}
		if (session == null) {
			throw new IllegalStateException("session is needed, it cannot be attached later");
		}
		// create new wrapper for each mail being sent (enable sending multiple emails with one mailer)
		final MimeEmailMessageWrapper messageRoot = new MimeEmailMessageWrapper();
		final MimeMessage message = new MimeMessage(session);
		// set basic email properties
		message.setSubject(email.getSubject(), CHARACTER_ENCODING);
		message.setFrom(new InternetAddress(email.getFromRecipient().getAddress(), email.getFromRecipient().getName(), CHARACTER_ENCODING));
		setReplyTo(email, message);
		setRecipients(email, message);
		// fill multipart structure
		setTexts(email, messageRoot.multipartAlternativeMessages);
		setEmbeddedImages(email, messageRoot.multipartRelated);
		setAttachments(email, messageRoot.multipartRoot);
		message.setContent(messageRoot.multipartRoot);
		setHeaders(email, message);
		message.setSentDate(new Date());
		return message;
	}

	/**
	 * Fills the {@link Message} instance with recipients from the {@link Email}.
	 *
	 * @param email   The message in which the recipients are defined.
	 * @param message The javax message that needs to be filled with recipients.
	 * @throws UnsupportedEncodingException See {@link InternetAddress#InternetAddress(String, String)}.
	 * @throws MessagingException           See {@link Message#addRecipient(javax.mail.Message.RecipientType, Address)}
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
			InternetAddress replyToAddress = new InternetAddress(replyToRecipient.getAddress(), replyToRecipient.getName(),
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
		if (email.getText() != null) {
			final MimeBodyPart messagePart = new MimeBodyPart();
			messagePart.setText(email.getText(), CHARACTER_ENCODING);
			multipartAlternativeMessages.addBodyPart(messagePart);
		}
		if (email.getTextHTML() != null) {
			final MimeBodyPart messagePartHTML = new MimeBodyPart();
			messagePartHTML.setContent(email.getTextHTML(), "text/html; charset=\"" + CHARACTER_ENCODING + "\"");
			multipartAlternativeMessages.addBodyPart(messagePartHTML);
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
	 * Sets all headers on the {@link Message} instance. Since we're not using a high-level JavaMail method, the JavaMail library says we need to do some
	 * encoding and 'folding' manually, to get the value right for the headers (see {@link MimeUtility}.
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
		for (Map.Entry<String, String> header : email.getHeaders().entrySet()) {
			String headerName = header.getKey();
			String headerValue = MimeUtility.encodeText(header.getValue(), CHARACTER_ENCODING, null);
			String foldedHeaderValue = MimeUtility.fold(headerName.length() + 2, headerValue);
			message.addHeader(header.getKey(), foldedHeaderValue);
		}
	}

	/**
	 * Helper method which generates a {@link BodyPart} from an {@link AttachmentResource} (from its {@link DataSource}) and a disposition type ({@link
	 * Part#INLINE} or {@link Part#ATTACHMENT}). With this the attachment data can be converted into objects that fit in the email structure. <br> <br> For
	 * every attachment and embedded image a header needs to be set.
	 *
	 * @param resource        An object that describes the attachment and contains the actual content data.
	 * @param dispositionType The type of attachment, {@link Part#INLINE} or {@link Part#ATTACHMENT} .
	 * @return An object with the attachment data read for placement in the email structure.
	 * @throws MessagingException All BodyPart setters.
	 */
	private static BodyPart getBodyPartFromDatasource(final AttachmentResource resource, final String dispositionType)
			throws MessagingException {
		final BodyPart attachmentPart = new MimeBodyPart();
		final DataSource ds = resource.getDataSource();
		// setting headers isn't working nicely using the javax mail API, so let's do that manually
		attachmentPart.setDataHandler(new DataHandler(resource.getDataSource()));
		attachmentPart.setFileName(resource.getName());
		attachmentPart.setHeader("Content-Type", ds.getContentType() + "; filename=" + ds.getName() + "; name=" + ds.getName());
		//Content-ID should be resource name which is unique. Other filename don't have to be unique.
		attachmentPart.setHeader("Content-ID", String.format("<%s>", resource.getName())); 
		attachmentPart.setDisposition(dispositionType + "; size=0");
		return attachmentPart;
	}

	/**
	 * This class conveniently wraps all necessary mimemessage parts that need to be filled with content, attachments etc. The root is ultimately sent using
	 * JavaMail.<br> <br> The constructor creates a new email message constructed from {@link MimeMultipart} as follows:
	 * <p/>
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
	private static class MimeEmailMessageWrapper {

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
				LOGGER.error(e.getMessage(), e);
				throw new RuntimeException(e.getMessage(), e);
			}
		}
	}

	/**
	 * Overrides the default email address validation restrictions {@link #emailAddressCriteria} when validating and sending emails using the current <code>Mailer</code> instance.
	 */
	public void setEmailAddressCriteria(EnumSet<EmailAddressCriteria> emailAddressCriteria) {
		this.emailAddressCriteria = emailAddressCriteria;
	}
}
