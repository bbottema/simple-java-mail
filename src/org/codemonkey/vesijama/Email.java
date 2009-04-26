package org.codemonkey.vesijama;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.activation.DataSource;
import javax.mail.Message.RecipientType;
import javax.mail.util.ByteArrayDataSource;

/**
 * Email message with all necessary data for an effective mailing action, including attachments etc.<br />
 * 
 * @author Benny Bottema
 */
public class Email {
	private Recipient fromRecipient;

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
	 * Constructor, instantiates all internal lists.
	 */
	public Email() {
		recipients = new ArrayList<Recipient>();
		embeddedImages = new ArrayList<AttachmentResource>();
		attachments = new ArrayList<AttachmentResource>();
	}

	/**
	 * Sets the sender address.
	 * 
	 * @param name The sender's name.
	 * @param fromAddress The sender's email address.
	 */
	public void setFromAddress(final String name, final String fromAddress) {
		fromRecipient = new Recipient(name, fromAddress, null);
	}

	/**
	 * Bean setters for {@link #subject}.
	 */
	public void setSubject(final String subject) {
		this.subject = subject;
	}

	/**
	 * Bean setters for {@link #text}.
	 */
	public void setText(final String text) {
		this.text = text;
	}

	/**
	 * Bean setters for {@link #textHTML}.
	 */
	public void setTextHTML(final String textHTML) {
		this.textHTML = textHTML;
	}

	/**
	 * Adds a new {@link Recipient} to the list on account of name, address and recipient type (eg.
	 * {@link RecipientType#CC}).
	 * 
	 * @param name The name of the recipient.
	 * @param address The emailadres of the recipient.
	 * @param type The type of receiver (eg. {@link RecipientType#CC}).
	 * @see #recipients
	 * @see Recipient
	 * @see RecipientType
	 */
	public void addRecipient(final String name, final String address, final RecipientType type) {
		recipients.add(new Recipient(name, address, type));
	}

	/**
	 * Adds an embedded image (attachment type) to the email message and generates the necessary {@link DataSource} with
	 * the given byte data. Then delegates to {@link #addEmbeddedImage(String, DataSource)}. At this point the
	 * datasource is actually a {@link ByteArrayDataSource}.
	 * 
	 * @param name The name of the image as being referred to from the message content body (eg.
	 *            '&lt;cid:signature&gt;').
	 * @param data The byte data of the image to be embedded.
	 * @param mimetype The content type of the given data (eg. "image/gif" or "image/jpeg").
	 * @see ByteArrayDataSource
	 * @see #addEmbeddedImage(String, DataSource)
	 */
	public void addEmbeddedImage(final String name, final byte[] data, final String mimetype) {
		final ByteArrayDataSource dataSource = new ByteArrayDataSource(data, mimetype);
		dataSource.setName(name);
		addEmbeddedImage(name, dataSource);
	}

	/**
	 * Overloaded method which sets an embedded image on account of name and {@link DataSource}.
	 * 
	 * @param name The name of the image as being referred to from the message content body (eg.
	 *            '&lt;cid:embeddedimage&gt;').
	 * @param imagedata The image data.
	 */
	public void addEmbeddedImage(final String name, final DataSource imagedata) {
		embeddedImages.add(new AttachmentResource(name, imagedata));
	}

	/**
	 * Adds an attachment to the email message and generates the necessary {@link DataSource} with the given byte data.
	 * Then delegates to {@link #addAttachment(String, DataSource)}. At this point the datasource is actually a
	 * {@link ByteArrayDataSource}.
	 * 
	 * @param name The name of the extension (eg. filename including extension).
	 * @param data The byte data of the attachment.
	 * @param mimetype The content type of the given data (eg. "plain/text", "image/gif" or "application/pdf").
	 * @see ByteArrayDataSource
	 * @see #addAttachment(String, DataSource)
	 */
	public void addAttachment(final String name, final byte[] data, final String mimetype) {
		final ByteArrayDataSource dataSource = new ByteArrayDataSource(data, mimetype);
		dataSource.setName(name);
		addAttachment(name, dataSource);
	}

	/**
	 * Overloaded method which sets an attachment on account of name and {@link DataSource}.
	 * 
	 * @param name The name of the attachment (eg. 'filename.ext').
	 * @param filedata The attachment data.
	 */
	public void addAttachment(final String name, final DataSource filedata) {
		attachments.add(new AttachmentResource(name, filedata));
	}

	/**
	 * Bean getter for {@link #fromRecipient}.
	 */
	public Recipient getFromRecipient() {
		return fromRecipient;
	}

	/**
	 * Bean getter for {@link #subject}.
	 */
	public String getSubject() {
		return subject;
	}

	/**
	 * Bean getter for {@link #text}.
	 */
	public String getText() {
		return text;
	}

	/**
	 * Bean getter for {@link #textHTML}.
	 */
	public String getTextHTML() {
		return textHTML;
	}

	/**
	 * Bean getter for {@link #attachments} as unmodifiable list.
	 */
	public List<AttachmentResource> getAttachments() {
		return Collections.unmodifiableList(attachments);
	}

	/**
	 * Bean getter for {@link #embeddedImages} as unmodifiable list.
	 */
	public List<AttachmentResource> getEmbeddedImages() {
		return Collections.unmodifiableList(embeddedImages);
	}

	/**
	 * Bean getter for {@link #recipients} as unmodifiable list.
	 */
	public List<Recipient> getRecipients() {
		return Collections.unmodifiableList(recipients);
	}
}