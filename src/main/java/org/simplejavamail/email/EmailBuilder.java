package org.simplejavamail.email;

import javax.activation.DataSource;
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
import static org.simplejavamail.internal.util.ConfigLoader.Property.*;
import static org.simplejavamail.internal.util.ConfigLoader.getProperty;
import static org.simplejavamail.internal.util.ConfigLoader.hasProperty;

/**
 * Fluent interface Builder for Emails
 *
 * @author Jared Stewart, Benny Bottema
 */
@SuppressWarnings("UnusedReturnValue")
public class EmailBuilder {
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
	 * The selector to be used in combination with the domain.
	 */
	private String selector;

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
			to((String) getProperty(DEFAULT_TO_NAME), (String) getProperty(DEFAULT_TO_ADDRESS));
		}
		if (hasProperty(DEFAULT_CC_ADDRESS)) {
			cc((String) getProperty(DEFAULT_CC_NAME), (String) getProperty(DEFAULT_CC_ADDRESS));
		}
		if (hasProperty(DEFAULT_BCC_ADDRESS)) {
			bcc((String) getProperty(DEFAULT_BCC_NAME), (String) getProperty(DEFAULT_BCC_ADDRESS));
		}
	}

	/**
	 *
	 */
	public Email build() {
		return new Email(this);
	}

	/**
	 * Sets the sender address.
	 *
	 * @param name        The sender's name.
	 * @param fromAddress The sender's email address.
	 */
	public EmailBuilder from(final String name, final String fromAddress) {
		this.fromRecipient = new Recipient(name, fromAddress, null);
		return this;
	}

	/**
	 * Sets the reply-to address (optional).
	 *
	 * @param name           The replied-to-receiver name.
	 * @param replyToAddress The replied-to-receiver email address.
	 */
	public EmailBuilder replyTo(final String name, final String replyToAddress) {
		this.replyToRecipient = new Recipient(name, replyToAddress, null);
		return this;
	}

	/**
	 * Sets the {@link #subject}.
	 */
	public EmailBuilder subject(final String subject) {
		this.subject = subject;
		return this;
	}

	/**
	 * Sets the {@link #text}.
	 */
	public EmailBuilder text(final String text) {
		this.text = text;
		return this;
	}

	/**
	 * Sets the {@link #textHTML}.
	 */
	public EmailBuilder textHTML(final String textHTML) {
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
	public EmailBuilder to(final String name, final String address) {
		recipients.add(new Recipient(name, address, Message.RecipientType.TO));
		return this;
	}

	/**
	 * Adds a new {@link Recipient} to the list on account of name, address with recipient type {@link Message.RecipientType#TO}.
	 *
	 * @param recipient The recipent whose name and address to use
	 * @see #recipients
	 * @see Recipient
	 */
	public EmailBuilder to(final Recipient recipient) {
		recipients.add(new Recipient(recipient.getName(), recipient.getAddress(), Message.RecipientType.TO));
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
	public EmailBuilder cc(final String name, final String address) {
		recipients.add(new Recipient(name, address, Message.RecipientType.CC));
		return this;
	}

	/**
	 * Adds a new {@link Recipient} to the list on account of name, address with recipient type {@link Message.RecipientType#CC}.
	 *
	 * @param recipient The recipent whose name and address to use
	 * @see #recipients
	 * @see Recipient
	 */
	public EmailBuilder cc(final Recipient recipient) {
		recipients.add(new Recipient(recipient.getName(), recipient.getAddress(), Message.RecipientType.CC));
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
	public EmailBuilder bcc(final String name, final String address) {
		recipients.add(new Recipient(name, address, Message.RecipientType.BCC));
		return this;
	}

	/**
	 * Adds a new {@link Recipient} to the list on account of name, address with recipient type {@link Message.RecipientType#BCC}.
	 *
	 * @param recipient The recipent whose name and address to use
	 * @see #recipients
	 * @see Recipient
	 */
	public EmailBuilder bcc(final Recipient recipient) {
		recipients.add(new Recipient(recipient.getName(), recipient.getAddress(), Message.RecipientType.BCC));
		return this;
	}

	/**
	 * Adds an embedded image (attachment type) to the email message and generates the necessary {@link DataSource} with the given byte data. Then
	 * delegates to {@link Email#addEmbeddedImage(String, DataSource)}. At this point the datasource is actually a {@link ByteArrayDataSource}.
	 *
	 * @param name     The name of the image as being referred to from the message content body (eg. '&lt;cid:signature&gt;').
	 * @param data     The byte data of the image to be embedded.
	 * @param mimetype The content type of the given data (eg. "image/gif" or "image/jpeg").
	 * @see ByteArrayDataSource
	 * @see Email#addEmbeddedImage(String, DataSource)
	 */
	public EmailBuilder embedImage(final String name, final byte[] data, final String mimetype) {
		final ByteArrayDataSource dataSource = new ByteArrayDataSource(data, mimetype);
		dataSource.setName(name);
		return embedImage(name, dataSource);
	}

	/**
	 * Overloaded method which sets an embedded image on account of name and {@link DataSource}.
	 *
	 * @param name      The name of the image as being referred to from the message content body (eg. '&lt;cid:embeddedimage&gt;').
	 * @param imagedata The image data.
	 */
	public EmailBuilder embedImage(final String name, final DataSource imagedata) {
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
	public EmailBuilder addHeader(final String name, final Object value) {
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
	public EmailBuilder addAttachment(final String name, final byte[] data, final String mimetype) {
		final ByteArrayDataSource dataSource = new ByteArrayDataSource(data, mimetype);
		dataSource.setName(name);
		addAttachment(name, dataSource);
		return this;
	}

	/**
	 * Overloaded method which sets an attachment on account of name and {@link DataSource}.
	 *
	 * @param name     The name of the attachment (eg. 'filename.ext').
	 * @param filedata The attachment data.
	 */
	public EmailBuilder addAttachment(final String name, final DataSource filedata) {
		attachments.add(new AttachmentResource(name, filedata));
		return this;
	}

	/**
	 * Sets all info needed for DKIM, using a byte array for private key data.
	 */
	public EmailBuilder signWithDomainKey(final byte[] dkimPrivateKey, final String signingDomain, final String selector) {
		this.dkimPrivateKeyInputStream = new ByteArrayInputStream(dkimPrivateKey);
		this.signingDomain = signingDomain;
		this.selector = selector;
		return this;
	}

	/**
	 * Sets all info needed for DKIM, using a byte array for private key data.
	 */
	public EmailBuilder signWithDomainKey(final String dkimPrivateKey, final String signingDomain, final String selector) {
		this.dkimPrivateKeyInputStream = new ByteArrayInputStream(dkimPrivateKey.getBytes(UTF_8));
		this.signingDomain = signingDomain;
		this.selector = selector;
		return this;
	}

	/**
	 * Sets all info needed for DKIM, using a file reference for private key data.
	 */
	public EmailBuilder signWithDomainKey(final File dkimPrivateKeyFile, final String signingDomain, final String selector) {
		this.dkimPrivateKeyFile = dkimPrivateKeyFile;
		this.signingDomain = signingDomain;
		this.selector = selector;
		return this;
	}

	/**
	 * Sets all info needed for DKIM, using an input stream for private key data.
	 */
	public EmailBuilder signWithDomainKey(final InputStream dkimPrivateKeyInputStream, final String signingDomain, final String selector) {
		this.dkimPrivateKeyInputStream = dkimPrivateKeyInputStream;
		this.signingDomain = signingDomain;
		this.selector = selector;
		return this;
	}

	/*
		SETTERS / GETTERS
	 */

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

	public String getSelector() {
		return selector;
	}
}
