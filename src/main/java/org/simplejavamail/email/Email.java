package org.simplejavamail.email;

import org.simplejavamail.internal.util.MiscUtil;

import javax.activation.DataSource;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.mail.internet.MimeMessage;
import javax.mail.util.ByteArrayDataSource;
import java.io.File;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static java.util.Collections.unmodifiableList;
import static java.util.Collections.unmodifiableMap;
import static org.simplejavamail.internal.util.MiscUtil.valueNullOrEmpty;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;

/**
 * Email message with all necessary data for an effective mailing action, including attachments etc.
 *
 * @author Benny Bottema
 */
@SuppressWarnings("SameParameterValue")
public class Email {

	/**
	 * @see EmailPopulatingBuilder#fixingMessageId(String)
	 */
	private String id;

	/**
	 * @see EmailPopulatingBuilder#from(Recipient)
	 */
	private Recipient fromRecipient;
	
	/**
	 * @see EmailPopulatingBuilder#withReplyTo(Recipient)
	 */
	private Recipient replyToRecipient;
	
	/**
	 * @see EmailPopulatingBuilder#withBounceTo(Recipient)
	 */
	private Recipient bounceToRecipient;
	
	/**
	 * @see EmailPopulatingBuilder#withPlainText(String)
	 */
	private String text;

	/**
	 * @see EmailPopulatingBuilder#withHTMLText(String)
	 */
	private String textHTML;

	/**
	 * @see EmailPopulatingBuilder#withSubject(String)
	 */
	private String subject;

	/**
	 * @see EmailPopulatingBuilder#to(Recipient...)
	 * @see EmailPopulatingBuilder#cc(Recipient...)
	 * @see EmailPopulatingBuilder#bcc(Recipient...)
	 */
	private final List<Recipient> recipients;

	/**
	 * @see EmailPopulatingBuilder#withEmbeddedImage(String, DataSource)
	 */
	private final List<AttachmentResource> embeddedImages;

	/**
	 * @see EmailPopulatingBuilder#withAttachment(String, DataSource)
	 */
	private final List<AttachmentResource> attachments;

	/**
	 * @see EmailPopulatingBuilder#withHeader(String, Object)
	 * @see EmailPopulatingBuilder#replyingTo(MimeMessage, boolean, String)
	 */
	private final Map<String, String> headers;
	
	/**
	 * @see EmailPopulatingBuilder#withDispositionNotificationTo()
	 * @see EmailPopulatingBuilder#withDispositionNotificationTo(Recipient)
	 */
	private boolean useDispositionNotificationTo;
	
	/**
	 * @see EmailPopulatingBuilder#withDispositionNotificationTo()
	 * @see EmailPopulatingBuilder#withDispositionNotificationTo(Recipient)
	 */
	private Recipient dispositionNotificationTo;
	
	/**
	 * @see EmailPopulatingBuilder#withReturnReceiptTo()
	 * @see EmailPopulatingBuilder#withReturnReceiptTo(Recipient)
	 */
	private boolean useReturnReceiptTo;
	
	/**
	 * @see EmailPopulatingBuilder#withReturnReceiptTo()
	 * @see EmailPopulatingBuilder#withReturnReceiptTo(Recipient)
	 */
	private Recipient returnReceiptTo;
	
	/**
	 * @see EmailPopulatingBuilder#forwarding(MimeMessage)
	 */
	private MimeMessage emailToForward;
	
	/**
	 * @see EmailPopulatingBuilder#signWithDomainKey(InputStream, String, String)
	 */
	private InputStream dkimPrivateKeyInputStream;
	
	/**
	 * @see #signWithDomainKey(File, String, String)
	 */
	private File dkimPrivateKeyFile; // supported separately, so we don't have to do resource management ourselves for the InputStream
	
	/**
	 * @see EmailPopulatingBuilder#signWithDomainKey(InputStream, String, String)
	 * @see EmailPopulatingBuilder#signWithDomainKey(File, String, String)
	 */
	private String dkimSigningDomain;
	
	/**
	 * @see EmailPopulatingBuilder#signWithDomainKey(InputStream, String, String)
	 * @see EmailPopulatingBuilder#signWithDomainKey(File, String, String)
	 */
	private String dkimSelector;
	
	/**
	 * Simply transfers everything from EmailPopulatingBuilder to this Email instance.
	 *
	 * @see EmailPopulatingBuilder#buildEmail()
	 */
	public Email(@Nonnull final EmailPopulatingBuilder builder) {
		checkNonEmptyArgument(builder, "builder");
		recipients = unmodifiableList(builder.getRecipients());
		embeddedImages = unmodifiableList(builder.getEmbeddedImages());
		attachments = unmodifiableList(builder.getAttachments());
		headers = unmodifiableMap(builder.getHeaders());
		
		id = builder.getId();
		fromRecipient = builder.getFromRecipient();
		replyToRecipient = builder.getReplyToRecipient();
		bounceToRecipient = builder.getBounceToRecipient();
		text = builder.getText();
		textHTML = builder.getTextHTML();
		subject = builder.getSubject();
		
		useDispositionNotificationTo = builder.isUseDispositionNotificationTo();
		useReturnReceiptTo = builder.isUseReturnReceiptTo();
		dispositionNotificationTo = builder.getDispositionNotificationTo();
		returnReceiptTo = builder.getReturnReceiptTo();
		emailToForward = builder.getEmailToForward();
		
		if (useDispositionNotificationTo && valueNullOrEmpty(builder.getDispositionNotificationTo())) {
			//noinspection IfMayBeConditional
			if (builder.getReplyToRecipient() != null) {
				dispositionNotificationTo = builder.getReplyToRecipient();
			} else {
				dispositionNotificationTo = builder.getFromRecipient();
			}
		}
		
		if (useReturnReceiptTo && valueNullOrEmpty(builder.getDispositionNotificationTo())) {
			//noinspection IfMayBeConditional
			if (builder.getReplyToRecipient() != null) {
				returnReceiptTo = builder.getReplyToRecipient();
			} else {
				returnReceiptTo = builder.getFromRecipient();
			}
		}
		
		if (builder.getDkimPrivateKeyFile() != null) {
			signWithDomainKey(builder.getDkimPrivateKeyFile(), builder.getSigningDomain(), builder.getDkimSelector());
		} else if (builder.getDkimPrivateKeyInputStream() != null) {
			signWithDomainKey(builder.getDkimPrivateKeyInputStream(), builder.getSigningDomain(), builder.getDkimSelector());
		}
	}
	
	/**
	 * As {@link #signWithDomainKey(InputStream, String, String)}, but with a File reference that is later read as {@code InputStream}.
	 */
	@SuppressWarnings("WeakerAccess")
	public void signWithDomainKey(@Nonnull final File dkimPrivateKeyFile, @Nonnull final String signingDomain, @Nonnull final String dkimSelector) {
		this.dkimPrivateKeyFile = checkNonEmptyArgument(dkimPrivateKeyFile, "dkimPrivateKeyFile");
		this.dkimSigningDomain = checkNonEmptyArgument(signingDomain, "dkimSigningDomain");
		this.dkimSelector = checkNonEmptyArgument(dkimSelector, "dkimSelector");
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
	 * @param dkimSelector                  Additional domain specifier.
	 */
	@SuppressWarnings("WeakerAccess")
	public void signWithDomainKey(@Nonnull final InputStream dkimPrivateKeyInputStream, @Nonnull final String signingDomain, @Nonnull final String dkimSelector) {
		this.dkimPrivateKeyInputStream = checkNonEmptyArgument(dkimPrivateKeyInputStream, "dkimPrivateKeyInputStream");
		this.dkimSigningDomain = checkNonEmptyArgument(signingDomain, "dkimSigningDomain");
		this.dkimSelector = checkNonEmptyArgument(dkimSelector, "dkimSelector");
	}

	/**
	 * Bean setter for {@link #id}.
	 */
	public void setId(@Nullable final String id) {
		this.id = id;
	}
	
	/**
	 * Sets the sender address.
	 *
	 * @param name        The sender's name.
	 * @param fromAddress The sender's email address, mandatory.
	 */
	public void setFromAddress(@Nullable final String name, @Nonnull final String fromAddress) {
		fromRecipient = new Recipient(name, checkNonEmptyArgument(fromAddress, "fromAddress"), null);
	}
	
	/**
	 * Sets the sender address from a preconfigured {@link Recipient} object..
	 *
	 * @param recipient The Recipient optional name and mandatory address.
	 */
	public void setFromAddress(@Nonnull final Recipient recipient) {
		fromRecipient = new Recipient(recipient.getName(), checkNonEmptyArgument(recipient.getAddress(), "fromAddress"), null);
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
	 * Sets the reply-to address from a preconfigured {@link Recipient} object..
	 *
	 * @param recipient The Recipient optional name and mandatory address.
	 */
	public void setReplyToAddress(@Nonnull final Recipient recipient) {
		replyToRecipient = new Recipient(recipient.getName(), checkNonEmptyArgument(recipient.getAddress(), "replyToAddress"), null);
	}
	
	/**
	 * Sets the Return-Path (Envelope FROM) address from a preconfigured {@link Recipient} object..
	 *
	 * @param recipient The Recipient optional name and mandatory address.
	 */
	public void setBounceToRecipient(@Nonnull final Recipient recipient) {
		bounceToRecipient = new Recipient(recipient.getName(), checkNonEmptyArgument(recipient.getAddress(), "bounceAddress"), null);
	}

	/**
	 * Bean setter for {@link #subject}.
	 */
	public void setSubject(@Nonnull final String subject) {
		this.subject = checkNonEmptyArgument(subject, "subject");
	}
	
	/**
	 * Bean setter for {@link #useDispositionNotificationTo}.
	 */
	public void setUseDispositionNotificationTo(final boolean useDispositionNotificationTo) {
		this.useDispositionNotificationTo = useDispositionNotificationTo;
	}
	
	/**
	 * Bean setter for {@link #dispositionNotificationTo}.
	 */
	public void setDispositionNotificationTo(final Recipient dispositionNotificationTo) {
		setUseDispositionNotificationTo(true);
		this.dispositionNotificationTo = dispositionNotificationTo;
	}
	
	/**
	 * Bean setter for {@link #useReturnReceiptTo}.
	 */
	public void setUseReturnReceiptTo(final boolean useReturnReceiptTo) {
		this.useReturnReceiptTo = useReturnReceiptTo;
	}
	
	/**
	 * Bean setter for {@link #returnReceiptTo}.
	 */
	public void setReturnReceiptTo(final Recipient returnReceiptTo) {
		setUseReturnReceiptTo(true);
		this.returnReceiptTo = returnReceiptTo;
	}
	
	/**
	 * Bean setter for {@link #emailToForward}.
	 */
	public void setEmailToForward(final MimeMessage emailToForward) {
		this.emailToForward = emailToForward;
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
	public void addHeader(@Nonnull final String name, @Nullable final Object value) {
		checkNonEmptyArgument(name, "name");
		headers.put(name, value != null ? String.valueOf(value) : "");
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
	 * Bean getter for {@link #id}.
	 */
	public String getId() {
		return id;
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
	 * Bean getter for {@link #bounceToRecipient}.
	 */
	public Recipient getBounceToRecipient() {
		return bounceToRecipient;
	}

	/**
	 * Bean getter for {@link #subject}.
	 */
	public String getSubject() {
		return subject;
	}
	
	/**
	 * Bean getter for {@link #useDispositionNotificationTo}.
	 */
	public boolean isUseDispositionNotificationTo() {
		return useDispositionNotificationTo;
	}
	
	/**
	 * Bean getter for {@link #dispositionNotificationTo}.
	 */
	public Recipient getDispositionNotificationTo() {
		return dispositionNotificationTo;
	}
	
	/**
	 * Bean getter for {@link #useReturnReceiptTo}.
	 */
	public boolean isUseReturnReceiptTo() {
		return useReturnReceiptTo;
	}
	
	/**
	 * Bean getter for {@link #returnReceiptTo}.
	 */
	public Recipient getReturnReceiptTo() {
		return returnReceiptTo;
	}
	
	/**
	 * Bean getter for {@link #emailToForward}.
	 */
	public MimeMessage getEmailToForward() {
		return emailToForward;
	}
	
	/**
	 * Bean getter for {@link #text}.
	 */
	public String getPlainText() {
		return text;
	}

	/**
	 * Bean getter for {@link #textHTML}.
	 */
	public String getHTMLText() {
		return textHTML;
	}

	/**
	 * Bean getter for {@link #attachments} as unmodifiable list.
	 */
	public List<AttachmentResource> getAttachments() {
		return attachments;
	}

	/**
	 * Bean getter for {@link #embeddedImages} as unmodifiable list.
	 */
	public List<AttachmentResource> getEmbeddedImages() {
		return embeddedImages;
	}

	/**
	 * Bean getter for {@link #recipients} as unmodifiable list.
	 */
	public List<Recipient> getRecipients() {
		return recipients;
	}

	/**
	 * Bean getter for {@link #headers} as unmodifiable map.
	 */
	public Map<String, String> getHeaders() {
		return headers;
	}

	public InputStream getDkimPrivateKeyInputStream() {
		return dkimPrivateKeyInputStream;
	}

	public File getDkimPrivateKeyFile() {
		return dkimPrivateKeyFile;
	}

	public String getDkimSigningDomain() {
		return dkimSigningDomain;
	}

	public String getDkimSelector() {
		return dkimSelector;
	}

	@Override
	public int hashCode() {
		return 0;
	}

	@Override
	public boolean equals(final Object o) {
		return (this == o) || ((o != null) && (getClass() == o.getClass()) &&
				EqualsHelper.equalsEmail(this, (Email) o));
	}

	@Override
	public String toString() {
		String s = "Email{" +
				"\n\tid=" + id +
				"\n\tfromRecipient=" + fromRecipient +
				",\n\treplyToRecipient=" + replyToRecipient +
				",\n\tbounceToRecipient=" + bounceToRecipient +
				",\n\ttext='" + text + '\'' +
				",\n\ttextHTML='" + textHTML + '\'' +
				",\n\tsubject='" + subject + '\'' +
				",\n\trecipients=" + recipients;
		if (!valueNullOrEmpty(dkimSigningDomain)) {
			s += ",\n\tapplyDKIMSignature=" + true +
					",\n\t\tdkimSelector=" + dkimSelector +
					",\n\t\tdkimSigningDomain=" + dkimSigningDomain;
		}
		if (useDispositionNotificationTo) {
			s += ",\n\tuseDispositionNotificationTo=" + true +
					",\n\t\tdispositionNotificationTo=" + dispositionNotificationTo;
		}
		if (useReturnReceiptTo) {
			s += ",\n\tuseReturnReceiptTo=" + true +
					",\n\t\treturnReceiptTo=" + returnReceiptTo;
		}
		if (!headers.isEmpty()) {
			s += ",\n\theaders=" + headers;
		}
		if (!embeddedImages.isEmpty()) {
			s += ",\n\tembeddedImages=" + embeddedImages;
		}
		if (!attachments.isEmpty()) {
			s += ",\n\tattachments=" + attachments;
		}
		if (emailToForward != null) {
			s += ",\n\tforwardingEmail=true";
		}
		s += "\n}";
		return s;
	}
}