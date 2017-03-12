package org.simplejavamail.email;

import org.simplejavamail.internal.util.MiscUtil;

import javax.activation.DataSource;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.mail.Message.RecipientType;
import javax.mail.util.ByteArrayDataSource;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * Email message with all necessary data for an effective mailing action, including attachments etc.
 *
 * @author Benny Bottema
 */
@SuppressWarnings("SameParameterValue")
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

	/*
	DKIM properties
	 */
	private boolean applyDKIMSignature = false;
	private InputStream dkimPrivateKeyInputStream;
	private File dkimPrivateKeyFile; // supported seperately, so we don't have to do resource management ourselves for the InputStream
	private String signingDomain;
	private String selector;

	/**
	 * Constructor, creates all internal lists. Populates default from, reply-to, to, cc and bcc if provided in the config file.
	 */
	public Email() {
		this(true);
	}

	public Email(final boolean readFromDefaults) {
		recipients = new ArrayList<>();
		embeddedImages = new ArrayList<>();
		attachments = new ArrayList<>();
		headers = new HashMap<>();

		if (readFromDefaults) {
			if (hasProperty(DEFAULT_FROM_ADDRESS)) {
				setFromAddress((String) getProperty(DEFAULT_FROM_NAME), (String) getProperty(DEFAULT_FROM_ADDRESS));
			}
			if (hasProperty(DEFAULT_REPLYTO_ADDRESS)) {
				setReplyToAddress((String) getProperty(DEFAULT_REPLYTO_NAME), (String) getProperty(DEFAULT_REPLYTO_ADDRESS));
			}
			if (hasProperty(DEFAULT_TO_ADDRESS)) {
				if (hasProperty(DEFAULT_TO_NAME)) {
					addRecipient((String) getProperty(DEFAULT_TO_NAME), (String) getProperty(DEFAULT_TO_ADDRESS), RecipientType.TO);
				} else {
					addRecipients((String) getProperty(DEFAULT_TO_ADDRESS), RecipientType.TO);
				}
			}
			if (hasProperty(DEFAULT_CC_ADDRESS)) {
				if (hasProperty(DEFAULT_CC_NAME)) {
					addRecipient((String) getProperty(DEFAULT_CC_NAME), (String) getProperty(DEFAULT_CC_ADDRESS), RecipientType.CC);
				} else {
					addRecipients((String) getProperty(DEFAULT_CC_ADDRESS), RecipientType.CC);
				}
			}
			if (hasProperty(DEFAULT_BCC_ADDRESS)) {
				if (hasProperty(DEFAULT_BCC_NAME)) {
					addRecipient((String) getProperty(DEFAULT_BCC_NAME), (String) getProperty(DEFAULT_BCC_ADDRESS), RecipientType.BCC);
				} else {
					addRecipients((String) getProperty(DEFAULT_BCC_ADDRESS), RecipientType.BCC);
				}
			}
			if (hasProperty(DEFAULT_SUBJECT)) {
				setSubject((String) getProperty(DEFAULT_SUBJECT));
			}
		}
	}

	/**
	 * As {@link #signWithDomainKey(InputStream, String, String)}, but with a File reference that is later read as {@code InputStream}.
	 */
	@SuppressWarnings("WeakerAccess")
	public void signWithDomainKey(@Nonnull final File dkimPrivateKeyFile, @Nonnull final String signingDomain, @Nonnull final String selector) {
		this.applyDKIMSignature = true;
		this.dkimPrivateKeyFile = checkNonEmptyArgument(dkimPrivateKeyFile, "dkimPrivateKeyFile");
		this.signingDomain = checkNonEmptyArgument(signingDomain, "signingDomain");
		this.selector = checkNonEmptyArgument(selector, "selector");
	}

	/**
	 * Primes this email for signing with a DKIM domain key. Actual signing is done when sending using a <code>Mailer</code>.
	 * <p/>
	 * Also see:
	 * <pre><ul>
	 *     <li>https://postmarkapp.com/guides/dkim</li>
	 *     <li>https://github.com/markenwerk/java-utils-mail-dkim</li>
	 *     <li>http://www.gettingemaildelivered.com/dkim-explained-how-to-set-up-and-use-domainkeys-identified-mail-effectively</li>
	 *     <li>https://en.wikipedia.org/wiki/DomainKeys_Identified_Mail</li>
	 * </ul></pre>
	 *
	 * @param dkimPrivateKeyInputStream De key content used to sign for the sending party.
	 * @param signingDomain             The domain being authorized to send.
	 * @param selector                  Additional domain specifier.
	 */
	@SuppressWarnings("WeakerAccess")
	public void signWithDomainKey(@Nonnull final InputStream dkimPrivateKeyInputStream, @Nonnull final String signingDomain, @Nonnull final String selector) {
		this.applyDKIMSignature = true;
		this.dkimPrivateKeyInputStream = checkNonEmptyArgument(dkimPrivateKeyInputStream, "dkimPrivateKeyInputStream");
		this.signingDomain = checkNonEmptyArgument(signingDomain, "signingDomain");
		this.selector = checkNonEmptyArgument(selector, "selector");
	}

	/**
	 * Sets the sender address.
	 *
	 * @param name        The sender's name.
	 * @param fromAddress The sender's email address.
	 */
	public void setFromAddress(@Nullable final String name, @Nonnull final String fromAddress) {
		fromRecipient = new Recipient(name, checkNonEmptyArgument(fromAddress, "fromAddress"), null);
	}

	/**
	 * Sets the reply-to address (optional).
	 *
	 * @param name           The replied-to-receiver name.
	 * @param replyToAddress The replied-to-receiver email address.
	 */
	public void setReplyToAddress(@Nullable final String name, @Nonnull final String replyToAddress) {
		replyToRecipient = new Recipient(name, checkNonEmptyArgument(replyToAddress, "replyToAddress"), null);
	}

	/**
	 * Bean setter for {@link #subject}.
	 */
	public void setSubject(@Nonnull final String subject) {
		this.subject = checkNonEmptyArgument(subject, "subject");
	}

	/**
	 * Bean setter for {@link #text}.
	 */
	public void setText(@Nullable final String text) {
		this.text = text;
	}

	/**
	 * Bean setter for {@link #textHTML}.
	 */
	public void setTextHTML(@Nullable final String textHTML) {
		this.textHTML = textHTML;
	}

	/**
	 * Adds a new {@link Recipient} to the list on account of name, address and recipient type (eg. {@link RecipientType#CC}).
	 *
	 * @param name    The name of the recipient.
	 * @param address The emailadres of the recipient.
	 * @param type    The type of receiver (eg. {@link RecipientType#CC}).
	 * @see #recipients
	 * @see Recipient
	 * @see RecipientType
	 */
	public void addRecipient(@Nullable final String name, @Nonnull final String address, @Nonnull final RecipientType type) {
		checkNonEmptyArgument(address, "address");
		checkNonEmptyArgument(type, "type");
		recipients.add(new Recipient(name, address, type));
	}

	/**
	 * Adds a new {@link Recipient} to the list on account of name, address and recipient type (eg. {@link RecipientType#CC}).
	 *
	 * @param emailAddressList The emailadres of the recipient or recipients separated by comma or semicolon.
	 * @param type             The type of receiver (eg. {@link RecipientType#CC}).
	 * @see #recipients
	 * @see Recipient
	 * @see RecipientType
	 */
	public void addRecipients(@Nonnull final String emailAddressList, @Nonnull final RecipientType type) {
		checkNonEmptyArgument(type, "type");
		checkNonEmptyArgument(emailAddressList, "emailAddressList");
		addRecipients(type, extractEmailAddresses(emailAddressList));
	}

	/**
	 * Adds all given recipients addresses to the list on account of address and recipient type (eg. {@link RecipientType#CC}).
	 *
	 * @param recipientEmailAddressesToAdd List of preconfigured recipients.
	 * @see #recipients
	 * @see Recipient
	 * @see RecipientType
	 */
	public void addRecipients(@Nonnull final RecipientType type, @Nonnull final String... recipientEmailAddressesToAdd) {
		checkNonEmptyArgument(type, "type");
		for (String emailAddress : checkNonEmptyArgument(recipientEmailAddressesToAdd, "recipientEmailAddressesToAdd")) {
			recipients.add(new Recipient(null, emailAddress, type));
		}
	}

	/**
	 * Adds all given {@link Recipient} instances to the list (as copies) on account of name, address and recipient type (eg. {@link RecipientType#CC}).
	 *
	 * @param recipientsToAdd List of preconfigured recipients.
	 * @see #recipients
	 * @see Recipient
	 * @see RecipientType
	 */
	public void addRecipients(@Nonnull final Recipient... recipientsToAdd) {
		for (Recipient recipient : checkNonEmptyArgument(recipientsToAdd, "recipientsToAdd")) {
			final String address = checkNonEmptyArgument(recipient.getAddress(), "recipient.address");
			final RecipientType type = checkNonEmptyArgument(recipient.getType(), "recipient.type");
			recipients.add(new Recipient(recipient.getName(), address, type));
		}
	}

	/**
	 * Adds an embedded image (attachment type) to the email message and generates the necessary {@link DataSource} with the given byte data. Then
	 * delegates to {@link #addEmbeddedImage(String, DataSource)}. At this point the datasource is actually a {@link ByteArrayDataSource}.
	 *
	 * @param name     The name of the image as being referred to from the message content body (eg. 'signature').
	 * @param data     The byte data of the image to be embedded.
	 * @param mimetype The content type of the given data (eg. "image/gif" or "image/jpeg").
	 * @see ByteArrayDataSource
	 * @see #addEmbeddedImage(String, DataSource)
	 */
	public void addEmbeddedImage(@Nonnull final String name, @Nonnull final byte[] data, @Nonnull final String mimetype) {
		checkNonEmptyArgument(name, "name");
		checkNonEmptyArgument(data, "data");
		checkNonEmptyArgument(mimetype, "mimetype");
		final ByteArrayDataSource dataSource = new ByteArrayDataSource(data, mimetype);
		dataSource.setName(name);
		addEmbeddedImage(name, dataSource);
	}

	/**
	 * Overloaded method which sets an embedded image on account of name and {@link DataSource}.
	 *
	 * @param name      The name of the image as being referred to from the message content body (eg. 'embeddedimage').
	 * @param imagedata The image data.
	 */
	@SuppressWarnings("WeakerAccess")
	public void addEmbeddedImage(@Nullable final String name, @Nonnull final DataSource imagedata) {
		checkNonEmptyArgument(imagedata, "imagedata");
		if (valueNullOrEmpty(name) && valueNullOrEmpty(imagedata.getName())) {
			throw new EmailException(EmailException.NAME_MISSING_FOR_EMBEDDED_IMAGE);
		}
		embeddedImages.add(new AttachmentResource(name, imagedata));
	}

	/**
	 * Adds a header to the {@link #headers} list. The value is stored as a <code>String</code>. example: <code>email.addHeader("X-Priority",
	 * 2)</code>
	 *
	 * @param name  The name of the header.
	 * @param value The value of the header, which will be stored using {@link String#valueOf(Object)}.
	 */
	@SuppressWarnings("WeakerAccess")
	public void addHeader(@Nonnull final String name, @Nonnull final Object value) {
		checkNonEmptyArgument(name, "name");
		checkNonEmptyArgument(value, "value");
		headers.put(name, String.valueOf(value));
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
	public void addAttachment(@Nonnull final String name, @Nonnull final byte[] data, @Nonnull final String mimetype) {
		checkNonEmptyArgument(name, "name");
		checkNonEmptyArgument(data, "data");
		checkNonEmptyArgument(mimetype, "mimetype");

		final ByteArrayDataSource dataSource = new ByteArrayDataSource(data, mimetype);
			dataSource.setName(MiscUtil.encodeText(name));
			addAttachment(MiscUtil.encodeText(name), dataSource);
	}

	/**
	 * Overloaded method which sets an attachment on account of name and {@link DataSource}.
	 *
	 * @param name     The name of the attachment (eg. 'filename.ext').
	 * @param filedata The attachment data.
	 */
	public void addAttachment(@Nullable final String name, @Nonnull final DataSource filedata) {
		checkNonEmptyArgument(filedata, "filedata");
		attachments.add(new AttachmentResource(MiscUtil.encodeText(name), filedata));
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

	public boolean isApplyDKIMSignature() {
		return applyDKIMSignature;
	}

	public InputStream getDkimPrivateKeyInputStream() {
		return dkimPrivateKeyInputStream;
	}

	public File getDkimPrivateKeyFile() {
		return dkimPrivateKeyFile;
	}

	public String getSigningDomain() {
		return signingDomain;
	}

	public String getSelector() {
		return selector;
	}

	@Override
	public int hashCode() {
		return 0;
	}

	@Override
	public boolean equals(final Object o) {
		return (this == o) || (o != null && getClass() == o.getClass() &&
				EqualsHelper.equalsEmail(this, (Email) o));
	}

	@Override
	public String toString() {
		return "Email{" +
				"\n\tfromRecipient=" + fromRecipient +
				",\n\treplyToRecipient=" + replyToRecipient +
				",\n\ttext='" + text + '\'' +
				",\n\ttextHTML='" + textHTML + '\'' +
				",\n\tsubject='" + subject + '\'' +
				",\n\trecipients=" + recipients +
				",\n\tembeddedImages=" + embeddedImages +
				",\n\tattachments=" + attachments +
				",\n\theaders=" + headers +
				"\n}";
	}

	/**
	 * Constructor for the Builder class
	 *
	 * @param builder The builder from which to create the email.
	 */
	Email(@Nonnull final EmailBuilder builder) {
		checkNonEmptyArgument(builder, "builder");
		recipients = builder.getRecipients();
		embeddedImages = builder.getEmbeddedImages();
		attachments = builder.getAttachments();
		headers = builder.getHeaders();

		fromRecipient = builder.getFromRecipient();
		replyToRecipient = builder.getReplyToRecipient();
		text = builder.getText();
		textHTML = builder.getTextHTML();
		subject = builder.getSubject();

		if (builder.getDkimPrivateKeyFile() != null) {
			signWithDomainKey(builder.getDkimPrivateKeyFile(), builder.getSigningDomain(), builder.getSelector());
		} else if (builder.getDkimPrivateKeyInputStream() != null) {
			signWithDomainKey(builder.getDkimPrivateKeyInputStream(), builder.getSigningDomain(), builder.getSelector());
		}
	}
}