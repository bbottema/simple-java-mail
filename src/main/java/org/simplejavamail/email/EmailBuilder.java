package org.simplejavamail.email;

import org.simplejavamail.internal.util.MiscUtil;

import javax.activation.DataSource;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.mail.Message;
import javax.mail.util.ByteArrayDataSource;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.simplejavamail.internal.util.MiscUtil.extractEmailAddresses;
import static org.simplejavamail.internal.util.MiscUtil.valueNullOrEmpty;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;
import static org.simplejavamail.util.ConfigLoader.Property.DEFAULT_BCC_ADDRESS;
import static org.simplejavamail.util.ConfigLoader.Property.DEFAULT_BCC_NAME;
import static org.simplejavamail.util.ConfigLoader.Property.DEFAULT_CC_ADDRESS;
import static org.simplejavamail.util.ConfigLoader.Property.DEFAULT_CC_NAME;
import static org.simplejavamail.util.ConfigLoader.Property.DEFAULT_FROM_ADDRESS;
import static org.simplejavamail.util.ConfigLoader.Property.DEFAULT_FROM_NAME;
import static org.simplejavamail.util.ConfigLoader.Property.DEFAULT_REPLYTO_ADDRESS;
import static org.simplejavamail.util.ConfigLoader.Property.DEFAULT_REPLYTO_NAME;
import static org.simplejavamail.util.ConfigLoader.Property.DEFAULT_SUBJECT;
import static org.simplejavamail.util.ConfigLoader.Property.DEFAULT_TO_ADDRESS;
import static org.simplejavamail.util.ConfigLoader.Property.DEFAULT_TO_NAME;
import static org.simplejavamail.util.ConfigLoader.getProperty;
import static org.simplejavamail.util.ConfigLoader.hasProperty;

/**
 * Fluent interface Builder for Emails
 *
 * @author Jared Stewart, Benny Bottema
 */
@SuppressWarnings("UnusedReturnValue")
public class EmailBuilder {
	
	/**
	 * Optional ID, which will be used when sending using the underlying Java Mail framework. Will be generated otherwise.
	 * <p>
	 * Note that id can only ever be filled by end-users for sending an email. This library will never fill this field when converting a MimeMessage.
	 * <p>
	 * The id-format should be conform <a href="https://tools.ietf.org/html/rfc5322#section-3.6.4">rfc5322#section-3.6.4</a>
	 */
	private String id;

	private Recipient fromRecipient;
	/**
	 * The reply-to-address, optional. Can be used in conjunction with {@link #fromRecipient}.
	 */
	private Recipient replyToRecipient;

	/**
	 * The email message body in plain text.
	 */
	private String text;

	/**
	 * The email message body in html.
	 */
	private String textHTML;

	/**
	 * The subject of the email message.
	 */
	private String subject;

	/**
	 * List of {@link Recipient}.
	 */
	private final List<Recipient> recipients;

	/**
	 * List of {@link AttachmentResource}.
	 */
	private final List<AttachmentResource> embeddedImages;

	/**
	 * List of {@link AttachmentResource}.
	 */
	private final List<AttachmentResource> attachments;

	/**
	 * Map of header name and values, such as <code>X-Priority</code> etc.
	 */
	private final Map<String, String> headers;

	/**
	 * A file reference to the private key to be used for signing with DKIM.
	 */
	private File dkimPrivateKeyFile;

	/**
	 * An input stream containg the private key data to be used for signing with DKIM.
	 */
	private InputStream dkimPrivateKeyInputStream;

	/**
	 * The domain used for signing with DKIM.
	 */
	private String signingDomain;

	/**
	 * The dkimSelector to be used in combination with the domain.
	 */
	private String dkimSelector;

	public EmailBuilder() {
		recipients = new ArrayList<>();
		embeddedImages = new ArrayList<>();
		attachments = new ArrayList<>();
		headers = new HashMap<>();

		if (hasProperty(DEFAULT_FROM_ADDRESS)) {
			from((String) getProperty(DEFAULT_FROM_NAME), (String) getProperty(DEFAULT_FROM_ADDRESS));
		}
		if (hasProperty(DEFAULT_REPLYTO_ADDRESS)) {
			replyTo((String) getProperty(DEFAULT_REPLYTO_NAME), (String) getProperty(DEFAULT_REPLYTO_ADDRESS));
		}
		if (hasProperty(DEFAULT_TO_ADDRESS)) {
			if (hasProperty(DEFAULT_TO_NAME)) {
				to((String) getProperty(DEFAULT_TO_NAME), (String) getProperty(DEFAULT_TO_ADDRESS));
			} else {
				to((String) getProperty(DEFAULT_TO_ADDRESS));
			}
		}
		if (hasProperty(DEFAULT_CC_ADDRESS)) {
			if (hasProperty(DEFAULT_CC_NAME)) {
				cc((String) getProperty(DEFAULT_CC_NAME), (String) getProperty(DEFAULT_CC_ADDRESS));
			} else {
				cc((String) getProperty(DEFAULT_CC_ADDRESS));
			}
		}
		if (hasProperty(DEFAULT_BCC_ADDRESS)) {
			if (hasProperty(DEFAULT_BCC_NAME)) {
				bcc((String) getProperty(DEFAULT_BCC_NAME), (String) getProperty(DEFAULT_BCC_ADDRESS));
			} else {
				bcc((String) getProperty(DEFAULT_BCC_ADDRESS));
			}
		}
		if (hasProperty(DEFAULT_SUBJECT)) {
			subject((String) getProperty(DEFAULT_SUBJECT));
		}
	}

	public Email build() {
		return new Email(this);
	}

	/**
	 * Sets the optional id to be used when sending using the underlying Java Mail framework. Will be generated otherwise.
	 */
	public EmailBuilder id(@Nullable final String id) {
		this.id = id;
		return this;
	}

	/**
	 * Sets the sender address {@link #fromRecipient}.
	 *
	 * @param name        The sender's name.
	 * @param fromAddress The sender's email address.
	 */
	public EmailBuilder from(@Nullable final String name, @Nonnull final String fromAddress) {
		checkNonEmptyArgument(fromAddress, "fromAddress");
		this.fromRecipient = new Recipient(name, fromAddress, null);
		return this;
	}

	/**
	 * Sets the sender address {@link #fromRecipient} with preconfigured {@link Recipient}.
	 *
	 * @param recipient Preconfigured recipient (name is optional).
	 */
	public EmailBuilder from(@Nonnull final Recipient recipient) {
		checkNonEmptyArgument(recipient, "recipient");
		this.fromRecipient = new Recipient(recipient.getName(), recipient.getAddress(), null);
		return this;
	}

	/**
	 * Sets {@link #replyToRecipient} (optional).
	 *
	 * @param name           The replied-to-receiver name.
	 * @param replyToAddress The replied-to-receiver email address.
	 */
	public EmailBuilder replyTo(@Nullable final String name, @Nonnull final String replyToAddress) {
		checkNonEmptyArgument(replyToAddress, "replyToAddress");
		this.replyToRecipient = new Recipient(name, replyToAddress, null);
		return this;
	}

	/**
	 * Sets {@link #replyToRecipient} (optional) with preconfigured {@link Recipient}.
	 *
	 * @param recipient Preconfigured recipient (name is optional).
	 */
	public EmailBuilder replyTo(@Nonnull final Recipient recipient) {
		checkNonEmptyArgument(recipient, "recipient");
		this.replyToRecipient = new Recipient(recipient.getName(), recipient.getAddress(), null);
		return this;
	}

	/**
	 * Sets the {@link #subject}.
	 */
	public EmailBuilder subject(@Nonnull final String subject) {
		this.subject = checkNonEmptyArgument(subject, "subject");
		return this;
	}

	/**
	 * Sets the {@link #text}.
	 */
	public EmailBuilder text(@Nullable final String text) {
		this.text = text;
		return this;
	}

	/**
	 * Sets the {@link #textHTML}.
	 */
	public EmailBuilder textHTML(@Nullable final String textHTML) {
		this.textHTML = textHTML;
		return this;
	}

	/**
	 * Adds a new {@link Recipient} to the list on account of name, address with recipient type {@link Message.RecipientType#TO}.
	 *
	 * @param name    The name of the recipient.
	 * @param address The emailaddress of the recipient.
	 * @see #recipients
	 * @see Recipient
	 */
	public EmailBuilder to(@Nullable final String name, @Nonnull final String address) {
		checkNonEmptyArgument(address, "address");
		recipients.add(new Recipient(name, address, Message.RecipientType.TO));
		return this;
	}

	/**
	 * Adds new {@link Recipient} instances to the list on account of name, address with recipient type {@link Message.RecipientType#TO}.
	 *
	 * @param recipientsToAdd The recipients whose name and address to use
	 * @see #recipients
	 * @see Recipient
	 */
	public EmailBuilder to(@Nonnull final Recipient... recipientsToAdd) {
		for (final Recipient recipient : checkNonEmptyArgument(recipientsToAdd, "recipientsToAdd")) {
			recipients.add(new Recipient(recipient.getName(), recipient.getAddress(), Message.RecipientType.TO));
		}
		return this;
	}

	/**
	 * Adds anew {@link Recipient} instances to the list on account of empty name, address with recipient type {@link Message.RecipientType#TO}. List can be
	 * comma ',' or semicolon ';' separated.
	 *
	 * @param emailAddressList The recipients whose address to use for both name and address
	 * @see #recipients
	 * @see Recipient
	 */
	public EmailBuilder to(@Nonnull final String emailAddressList) {
		checkNonEmptyArgument(emailAddressList, "emailAddressList");
		return addCommaOrSemicolonSeparatedEmailAddresses(emailAddressList, Message.RecipientType.TO);
	}

	@Nonnull
	private EmailBuilder addCommaOrSemicolonSeparatedEmailAddresses(@Nonnull final String emailAddressList, @Nonnull final Message.RecipientType type) {
		checkNonEmptyArgument(type, "type");
		for (final String emailAddress : extractEmailAddresses(checkNonEmptyArgument(emailAddressList, "emailAddressList"))) {
			recipients.add(new Recipient(null, emailAddress, type));
		}
		return this;
	}

	/**
	 * Adds new {@link Recipient} instances to the list on account of empty name, address with recipient type {@link Message.RecipientType#TO}.
	 *
	 * @param emailAddresses The recipients whose address to use for both name and address
	 * @see #recipients
	 * @see Recipient
	 */
	public EmailBuilder to(@Nonnull final String... emailAddresses) {
		for (final String emailAddress : checkNonEmptyArgument(emailAddresses, "emailAddresses")) {
			recipients.add(new Recipient(null, emailAddress, Message.RecipientType.TO));
		}
		return this;
	}

	/**
	 * Adds a new {@link Recipient} to the list on account of name, address with recipient type {@link Message.RecipientType#CC}.
	 *
	 * @param name    The name of the recipient.
	 * @param address The emailaddress of the recipient.
	 * @see #recipients
	 * @see Recipient
	 */
	@SuppressWarnings({ "WeakerAccess", "QuestionableName" })
	public EmailBuilder cc(@Nullable final String name, @Nonnull final String address) {
		checkNonEmptyArgument(address, "address");
		recipients.add(new Recipient(name, address, Message.RecipientType.CC));
		return this;
	}

	/**
	 * Adds new {@link Recipient} instances to the list on account of empty name, address with recipient type {@link Message.RecipientType#CC}.
	 *
	 * @param emailAddresses The recipients whose address to use for both name and address
	 * @see #recipients
	 * @see Recipient
	 */
	@SuppressWarnings("QuestionableName")
	public EmailBuilder cc(@Nonnull final String... emailAddresses) {
		for (final String emailAddress : checkNonEmptyArgument(emailAddresses, "emailAddresses")) {
			recipients.add(new Recipient(null, emailAddress, Message.RecipientType.CC));
		}
		return this;
	}

	/**
	 * Adds anew {@link Recipient} instances to the list on account of empty name, address with recipient type {@link Message.RecipientType#CC}. List can be
	 * comma ',' or semicolon ';' separated.
	 *
	 * @param emailAddressList The recipients whose address to use for both name and address
	 * @see #recipients
	 * @see Recipient
	 */
	@SuppressWarnings("QuestionableName")
	public EmailBuilder cc(@Nonnull final String emailAddressList) {
		checkNonEmptyArgument(emailAddressList, "emailAddressList");
		return addCommaOrSemicolonSeparatedEmailAddresses(emailAddressList, Message.RecipientType.CC);
	}

	/**
	 * Adds new {@link Recipient} instances to the list on account of name, address with recipient type {@link Message.RecipientType#CC}.
	 *
	 * @param recipientsToAdd The recipients whose name and address to use
	 * @see #recipients
	 * @see Recipient
	 */
	@SuppressWarnings("QuestionableName")
	public EmailBuilder cc(@Nonnull final Recipient... recipientsToAdd) {
		for (final Recipient recipient : checkNonEmptyArgument(recipientsToAdd, "recipientsToAdd")) {
			recipients.add(new Recipient(recipient.getName(), recipient.getAddress(), Message.RecipientType.CC));
		}
		return this;
	}

	/**
	 * Adds a new {@link Recipient} to the list on account of name, address with recipient type {@link Message.RecipientType#BCC}.
	 *
	 * @param name    The name of the recipient.
	 * @param address The emailaddress of the recipient.
	 * @see #recipients
	 * @see Recipient
	 */
	@SuppressWarnings("WeakerAccess")
	public EmailBuilder bcc(@Nullable final String name, @Nonnull final String address) {
		checkNonEmptyArgument(address, "address");
		recipients.add(new Recipient(name, address, Message.RecipientType.BCC));
		return this;
	}

	/**
	 * Adds new {@link Recipient} instances to the list on account of empty name, address with recipient type {@link Message.RecipientType#BCC}.
	 *
	 * @param emailAddresses The recipients whose address to use for both name and address
	 * @see #recipients
	 * @see Recipient
	 */
	public EmailBuilder bcc(@Nonnull final String... emailAddresses) {
		for (final String emailAddress : checkNonEmptyArgument(emailAddresses, "emailAddresses")) {
			recipients.add(new Recipient(null, emailAddress, Message.RecipientType.BCC));
		}
		return this;
	}

	/**
	 * Adds anew {@link Recipient} instances to the list on account of empty name, address with recipient type {@link Message.RecipientType#BCC}. List can be
	 * comma ',' or semicolon ';' separated.
	 *
	 * @param emailAddressList The recipients whose address to use for both name and address
	 * @see #recipients
	 * @see Recipient
	 */
	public EmailBuilder bcc(@Nonnull final String emailAddressList) {
		checkNonEmptyArgument(emailAddressList, "emailAddressList");
		return addCommaOrSemicolonSeparatedEmailAddresses(emailAddressList, Message.RecipientType.BCC);
	}

	/**
	 * Adds new {@link Recipient} instances to the list on account of name, address with recipient type {@link Message.RecipientType#BCC}.
	 *
	 * @param recipientsToAdd The recipients whose name and address to use
	 * @see #recipients
	 * @see Recipient
	 */
	public EmailBuilder bcc(@Nonnull final Recipient... recipientsToAdd) {
		for (final Recipient recipient : checkNonEmptyArgument(recipientsToAdd, "recipientsToAdd")) {
			recipients.add(new Recipient(recipient.getName(), recipient.getAddress(), Message.RecipientType.BCC));
		}
		return this;
	}

	/**
	 * Adds an embedded image (attachment type) to the email message and generates the necessary {@link DataSource} with the given byte data. Then
	 * delegates to {@link Email#addEmbeddedImage(String, DataSource)}. At this point the datasource is actually a {@link ByteArrayDataSource}.
	 *
	 * @param name     The name of the image as being referred to from the message content body (eg. 'signature').
	 * @param data     The byte data of the image to be embedded.
	 * @param mimetype The content type of the given data (eg. "image/gif" or "image/jpeg").
	 * @see ByteArrayDataSource
	 * @see Email#addEmbeddedImage(String, DataSource)
	 */
	public EmailBuilder embedImage(@Nonnull final String name, @Nonnull final byte[] data, @Nonnull final String mimetype) {
		checkNonEmptyArgument(name, "name");
		checkNonEmptyArgument(data, "data");
		checkNonEmptyArgument(mimetype, "mimetype");

		final ByteArrayDataSource dataSource = new ByteArrayDataSource(data, mimetype);
		dataSource.setName(name);
		return embedImage(name, dataSource);
	}

	/**
	 * Overloaded method which sets an embedded image on account of name and {@link DataSource}.
	 *
	 * @param name      The name of the image as being referred to from the message content body (eg. 'embeddedimage'). If not provided, the name of the given
	 *                  data source is used instead.
	 * @param imagedata The image data.
	 */
	@SuppressWarnings("WeakerAccess")
	public EmailBuilder embedImage(@Nullable final String name, @Nonnull final DataSource imagedata) {
		checkNonEmptyArgument(imagedata, "imagedata");
		if (valueNullOrEmpty(name) && valueNullOrEmpty(imagedata.getName())) {
			throw new EmailException(EmailException.NAME_MISSING_FOR_EMBEDDED_IMAGE);
		}
		embeddedImages.add(new AttachmentResource(name, imagedata));
		return this;
	}

	/**
	 * Adds a header to the {@link #headers} list. The value is stored as a <code>String</code>. example: <code>email.addHeader("X-Priority",
	 * 2)</code>
	 *
	 * @param name  The name of the header.
	 * @param value The value of the header, which will be stored using {@link String#valueOf(Object)}.
	 */
	public EmailBuilder addHeader(@Nonnull final String name, @Nonnull final Object value) {
		checkNonEmptyArgument(name, "name");
		checkNonEmptyArgument(value, "value");
		headers.put(name, String.valueOf(value));
		return this;
	}

	/**
	 * Adds an attachment to the email message and generates the necessary {@link DataSource} with the given byte data. Then delegates to {@link
	 * #addAttachment(String, DataSource)}. At this point the datasource is actually a {@link ByteArrayDataSource}.
	 *
	 * @param name     The name of the extension (eg. filename including extension).
	 * @param data     The byte data of the attachment.
	 * @param mimetype The content type of the given data (eg. "plain/text", "image/gif" or "application/pdf").
	 * @see ByteArrayDataSource
	 * @see #addAttachment(String, DataSource)
	 */
	public EmailBuilder addAttachment(@Nullable final String name, @Nonnull final byte[] data, @Nonnull final String mimetype) {
		checkNonEmptyArgument(data, "data");
		checkNonEmptyArgument(mimetype, "mimetype");
		final ByteArrayDataSource dataSource = new ByteArrayDataSource(data, mimetype);
		dataSource.setName(MiscUtil.encodeText(name));
		addAttachment(MiscUtil.encodeText(name), dataSource);
		return this;
	}

	/**
	 * Overloaded method which sets an attachment on account of name and {@link DataSource}.
	 *
	 * @param name     The name of the attachment (eg. 'filename.ext').
	 * @param filedata The attachment data.
	 */
	public EmailBuilder addAttachment(@Nullable final String name, @Nonnull final DataSource filedata) {
		checkNonEmptyArgument(filedata, "filedata");
		attachments.add(new AttachmentResource(MiscUtil.encodeText(name), filedata));
		return this;
	}

	/**
	 * Sets all info needed for DKIM, using a byte array for private key data.
	 */
	public EmailBuilder signWithDomainKey(@Nonnull final byte[] dkimPrivateKey, @Nonnull final String signingDomain, @Nonnull final String dkimSelector) {
		this.dkimPrivateKeyInputStream = new ByteArrayInputStream(checkNonEmptyArgument(dkimPrivateKey, "dkimPrivateKey"));
		this.signingDomain = checkNonEmptyArgument(signingDomain, "signingDomain");
		this.dkimSelector = checkNonEmptyArgument(dkimSelector, "dkimSelector");
		return this;
	}

	/**
	 * Sets all info needed for DKIM, using a byte array for private key data.
	 */
	public EmailBuilder signWithDomainKey(@Nonnull final String dkimPrivateKey, @Nonnull final String signingDomain, @Nonnull final String dkimSelector) {
		checkNonEmptyArgument(dkimPrivateKey, "dkimPrivateKey");
		this.dkimPrivateKeyInputStream = new ByteArrayInputStream(dkimPrivateKey.getBytes(UTF_8));
		this.signingDomain = checkNonEmptyArgument(signingDomain, "signingDomain");
		this.dkimSelector = checkNonEmptyArgument(dkimSelector, "dkimSelector");
		return this;
	}

	/**
	 * Sets all info needed for DKIM, using a file reference for private key data.
	 */
	public EmailBuilder signWithDomainKey(@Nonnull final File dkimPrivateKeyFile, @Nonnull final String signingDomain, @Nonnull final String dkimSelector) {
		this.dkimPrivateKeyFile = checkNonEmptyArgument(dkimPrivateKeyFile, "dkimPrivateKeyFile");
		this.signingDomain = checkNonEmptyArgument(signingDomain, "signingDomain");
		this.dkimSelector = checkNonEmptyArgument(dkimSelector, "dkimSelector");
		return this;
	}

	/**
	 * Sets all info needed for DKIM, using an input stream for private key data.
	 */
	public EmailBuilder signWithDomainKey(@Nonnull final InputStream dkimPrivateKeyInputStream, @Nonnull final String signingDomain,
			@Nonnull final String dkimSelector) {
		this.dkimPrivateKeyInputStream = checkNonEmptyArgument(dkimPrivateKeyInputStream, "dkimPrivateKeyInputStream");
		this.signingDomain = checkNonEmptyArgument(signingDomain, "signingDomain");
		this.dkimSelector = checkNonEmptyArgument(dkimSelector, "dkimSelector");
		return this;
	}

	/*
		SETTERS / GETTERS
	 */

	public String getId() {
		return id;
	}

	public Recipient getFromRecipient() {
		return fromRecipient;
	}

	public Recipient getReplyToRecipient() {
		return replyToRecipient;
	}

	public String getText() {
		return text;
	}

	public String getTextHTML() {
		return textHTML;
	}

	public String getSubject() {
		return subject;
	}

	public List<Recipient> getRecipients() {
		return recipients;
	}

	public List<AttachmentResource> getEmbeddedImages() {
		return embeddedImages;
	}

	public List<AttachmentResource> getAttachments() {
		return attachments;
	}

	public Map<String, String> getHeaders() {
		return headers;
	}

	public File getDkimPrivateKeyFile() {
		return dkimPrivateKeyFile;
	}

	public InputStream getDkimPrivateKeyInputStream() {
		return dkimPrivateKeyInputStream;
	}

	public String getSigningDomain() {
		return signingDomain;
	}

	public String getDkimSelector() {
		return dkimSelector;
	}
}
