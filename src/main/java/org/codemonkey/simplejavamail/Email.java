package org.codemonkey.simplejavamail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.activation.DataSource;
import javax.mail.Message.RecipientType;
import javax.mail.util.ByteArrayDataSource;

/**
 * Email message with all necessary data for an effective mailing action, including attachments etc.
 *
 * @author Benny Bottema
 */
public class Email {
	/**
	 * The sender of the email. Can be used in conjunction with {@link #replyToRecipient}.
	 */
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
	 * Constructor, creates all internal lists.
	 */
	public Email() {
		recipients = new ArrayList<Recipient>();
		embeddedImages = new ArrayList<AttachmentResource>();
		attachments = new ArrayList<AttachmentResource>();
		headers = new HashMap<String, String>();
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
	 * Sets the reply-to address (optional).
	 *
	 * @param name The replied-to-receiver name.
	 * @param replyToAddress The replied-to-receiver email address.
	 */
	public void setReplyToAddress(final String name, final String replyToAddress) {
		replyToRecipient = new Recipient(name, replyToAddress, null);
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
	 * Adds a new {@link Recipient} to the list on account of name, address and recipient type (eg. {@link RecipientType#CC}).
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
	 * Adds an embedded image (attachment type) to the email message and generates the necessary {@link DataSource} with the given byte
	 * data. Then delegates to {@link #addEmbeddedImage(String, DataSource)}. At this point the datasource is actually a
	 * {@link ByteArrayDataSource}.
	 *
	 * @param name The name of the image as being referred to from the message content body (eg. '&lt;cid:signature&gt;').
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
	 * @param name The name of the image as being referred to from the message content body (eg. '&lt;cid:embeddedimage&gt;').
	 * @param imagedata The image data.
	 */
	public void addEmbeddedImage(final String name, final DataSource imagedata) {
		embeddedImages.add(new AttachmentResource(name, imagedata));
	}

	/**
	 * Adds a header to the {@link #headers} list. The value is stored as a <code>String</code>.
	 * <p>
	 * example: <code>email.addHeader("X-Priority", 2)</code>
	 *
	 * @param name The name of the header.
	 * @param value The value of the header, which will be stored using {@link String#valueOf(Object)}.
	 */
	public void addHeader(final String name, final Object value) {
		headers.put(name, String.valueOf(value));
	}

	/**
	 * Adds an attachment to the email message and generates the necessary {@link DataSource} with the given byte data. Then delegates to
	 * {@link #addAttachment(String, DataSource)}. At this point the datasource is actually a {@link ByteArrayDataSource}.
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
	 * Bean getter for {@link #replyToRecipient}.
	 */
	public Recipient getReplyToRecipient() {
		return replyToRecipient;
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

	/**
	 * Bean getter for {@link #headers} as unmodifiable map.
	 */
	public Map<String, String> getHeaders() {
		return Collections.unmodifiableMap(headers);
	}

	/**
	 * Fluent interface Builder for Emails
	 * @author Jared Stewart
	 */
	public static class Builder {
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

		private Email email;

		public Builder() {
			recipients = new ArrayList<Recipient>();
			embeddedImages = new ArrayList<AttachmentResource>();
			attachments = new ArrayList<AttachmentResource>();
			headers = new HashMap<String, String>();
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
		 * @param name The sender's name.
		 * @param fromAddress The sender's email address.
		 */
		public Builder from(final String name, final String fromAddress){
			this.fromRecipient = new Recipient(name, fromAddress, null);
			return this;
		}

		/**
		 * Sets the reply-to address (optional).
		 *
		 * @param name The replied-to-receiver name.
		 * @param replyToAddress The replied-to-receiver email address.
		 */
		public Builder replyTo(final String name, final String replyToAddress) {
			this.replyToRecipient = new Recipient(name, replyToAddress, null);
			return this;
		}


		/**
		 * Sets the {@link #subject}.
		 */
		public Builder subject(final String subject) {
			this.subject = subject;
			return this;
		}

		/**
		 * Sets the {@link #text}.
		 */
		public Builder text(final String text) {
			this.text = text;
			return this;
		}

		/**
		 * Sets the {@link #textHTML}.
		 */
		public Builder textHTML(final String textHTML) {
			this.textHTML = textHTML;
			return this;
		}

		/**
		 * Adds a new {@link Recipient} to the list on account of name, address with recipient type {@link RecipientType#TO}.
		 *
		 * @param name The name of the recipient.
		 * @param address The emailaddress of the recipient.
		 * @see #recipients
		 * @see Recipient
		 */
		public Builder to(final String name, final String address) {
			recipients.add(new Recipient(name, address, RecipientType.TO));
			return this;
		}

		/**
		 * Adds a new {@link Recipient} to the list on account of name, address with recipient type {@link RecipientType#TO}.
		 *
		 * @param recipient The recipent whose name and address to use
		 * @see #recipients
		 * @see Recipient
		 */
		public Builder to(final Recipient recipient) {
			recipients.add(new Recipient(recipient.getName(), recipient.getAddress(), RecipientType.TO));
			return this;
		}



		/**
		 * Adds a new {@link Recipient} to the list on account of name, address with recipient type {@link RecipientType#CC}.
		 *
		 * @param name The name of the recipient.
		 * @param address The emailaddress of the recipient.
		 * @see #recipients
		 * @see Recipient
		 */
		public Builder cc(final String name, final String address) {
			recipients.add(new Recipient(name, address, RecipientType.CC));
			return this;
		}

		/**
		 * Adds a new {@link Recipient} to the list on account of name, address with recipient type {@link RecipientType#CC}.
		 *
		 * @param recipient The recipent whose name and address to use
		 * @see #recipients
		 * @see Recipient
		 */
		public Builder cc(final Recipient recipient) {
			recipients.add(new Recipient(recipient.getName(), recipient.getAddress(), RecipientType.CC));
			return this;
		}

		/**
		 * Adds a new {@link Recipient} to the list on account of name, address with recipient type {@link RecipientType#BCC}.
		 *
		 * @param name The name of the recipient.
		 * @param address The emailaddress of the recipient.
		 * @see #recipients
		 * @see Recipient
		 */
		public Builder bcc(final String name, final String address) {
			recipients.add(new Recipient(name, address, RecipientType.BCC));
			return this;
		}

		/**
		 * Adds a new {@link Recipient} to the list on account of name, address with recipient type {@link RecipientType#BCC}.
		 *
		 * @param recipient The recipent whose name and address to use
		 * @see #recipients
		 * @see Recipient
		 */
		public Builder bcc(final Recipient recipient) {
			recipients.add(new Recipient(recipient.getName(), recipient.getAddress(), RecipientType.BCC));
			return this;
		}

		/**
		 * Adds an embedded image (attachment type) to the email message and generates the necessary {@link DataSource} with the given byte
		 * data. Then delegates to {@link #addEmbeddedImage(String, DataSource)}. At this point the datasource is actually a
		 * {@link ByteArrayDataSource}.
		 *
		 * @param name The name of the image as being referred to from the message content body (eg. '&lt;cid:signature&gt;').
		 * @param data The byte data of the image to be embedded.
		 * @param mimetype The content type of the given data (eg. "image/gif" or "image/jpeg").
		 * @see ByteArrayDataSource
		 * @see #addEmbeddedImage(String, DataSource)
		 */
		public Builder embedImage(final String name, final byte[] data, final String mimetype) {
			final ByteArrayDataSource dataSource = new ByteArrayDataSource(data, mimetype);
			dataSource.setName(name);
			return embedImage(name, dataSource);
		}

		/**
		 * Overloaded method which sets an embedded image on account of name and {@link DataSource}.
		 *
		 * @param name The name of the image as being referred to from the message content body (eg. '&lt;cid:embeddedimage&gt;').
		 * @param imagedata The image data.
		 */
		public Builder embedImage(final String name, final DataSource imagedata) {
			embeddedImages.add(new AttachmentResource(name, imagedata));
			return this;
		}

		/**
		 * Adds a header to the {@link #headers} list. The value is stored as a <code>String</code>.
		 * <p>
		 * example: <code>email.addHeader("X-Priority", 2)</code>
		 *
		 * @param name The name of the header.
		 * @param value The value of the header, which will be stored using {@link String#valueOf(Object)}.
		 */
		public Builder addHeader(final String name, final Object value) {
			headers.put(name, String.valueOf(value));
			return this;
		}

		/**
		 * Adds an attachment to the email message and generates the necessary {@link DataSource} with the given byte data. Then delegates to
		 * {@link #addAttachment(String, DataSource)}. At this point the datasource is actually a {@link ByteArrayDataSource}.
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

	}

	/**
	 * Constructor for the Builder class
	 * @param builder The builder from which to create the email.
	 */
	private Email(Builder builder) {
		recipients = builder.recipients;
		embeddedImages = builder.embeddedImages;
		attachments = builder.attachments;
		headers = builder.headers;

		fromRecipient = builder.fromRecipient;
		replyToRecipient = builder.replyToRecipient;
		text = builder.text;
		textHTML = builder.textHTML;
		subject = builder.subject;
	}
}