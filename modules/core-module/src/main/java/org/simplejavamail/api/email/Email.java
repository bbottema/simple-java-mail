package org.simplejavamail.api.email;



import jakarta.activation.DataSource;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.email.config.OpenPgpEncryptionConfig;
import org.simplejavamail.api.email.config.OpenPgpSigningConfig;
import org.simplejavamail.api.email.config.SmimeEncryptionConfig;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.api.internal.smimesupport.model.PlainSmimeDetails;
import org.simplejavamail.internal.config.EmailProperty;
import org.simplejavamail.internal.util.MiscUtil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TimeZone;

import static jakarta.mail.Message.RecipientType.BCC;
import static jakarta.mail.Message.RecipientType.CC;
import static jakarta.mail.Message.RecipientType.TO;
import static java.lang.Boolean.TRUE;
import static java.lang.String.format;
import static java.util.Collections.unmodifiableList;
import static java.util.Collections.unmodifiableMap;
import static java.util.stream.Collectors.toList;
import static org.simplejavamail.internal.util.ListUtil.merge;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;

/**
 * Email message with all necessary data for an effective mailing action, including attachments etc.
 * Exclusively created through the email builder API obtained from a configured {@code SimpleJavaMail} factory.
 *
 * <h2>Java serialization</h2>
 * Since 9.2.0, Java serialization produces a send-ready snapshot. Attachment, embedded-image and decrypted-attachment data is read into
 * byte-backed resources; a forwarded {@link MimeMessage} is stored in RFC 822 form; and {@link SmimeSigningConfig} is retained. Deserialized
 * resources are read-only, and the concrete {@link DataSource} implementations and the forwarded message's original {@link Session} are not retained.
 * Any lazy or remote data source is therefore consumed while the {@code Email} is serialized, and serialization fails if its data cannot be read.
 *
 * <p>The serialized form can contain message content, credentials, S/MIME private keys and passwords. Protect it accordingly. OpenPGP sending
 * configuration, secret keys and passphrases are deliberately excluded and must be supplied again after deserialization. Streams created before
 * 9.2.0 remain readable for ordinary message fields, but those versions never stored attachment data, forwarded MIME content or S/MIME signing
 * configuration. Accessing missing legacy attachment data fails with an exception that identifies the pre-9.2.0 format.</p>
 */
@SuppressWarnings("SameParameterValue")
public class Email implements Serializable {

	private static final long serialVersionUID = 1234567L;
	private static final int SERIALIZATION_FORMAT_VERSION = 1;
	private final int serializationFormatVersion;

	/**
	 * @see EmailPopulatingBuilder#ignoringDefaults(boolean)
	 */
	private final boolean ignoreDefaults;

	/**
	 * @see EmailPopulatingBuilder#ignoringOverrides(boolean)
	 */
	private final boolean ignoreOverrides;

	/**
	 * @see EmailPopulatingBuilder#dontApplyDefaultValueFor(EmailProperty...)
	 */
	private final Set<EmailProperty> propertiesNotToApplyDefaultValueFor;

	/**
	 * @see EmailPopulatingBuilder#dontApplyOverrideValueFor(EmailProperty...)
	 */
	private final Set<EmailProperty> propertiesNotToApplyOverrideValueFor;

	/**
	 * @see EmailPopulatingBuilder#fixingMessageId(String)
	 */
	protected String id;
	
	/**
	 * @see EmailPopulatingBuilder#from(Recipient)
	 */
	private final Recipient fromRecipient;
	
	/**
	 * @see EmailPopulatingBuilder#withReplyTo(Recipient)
	 */
	@NotNull
	private final List<Recipient> replyToRecipients;

	/**
	 * @see EmailPopulatingBuilder#withBounceTo(Recipient)
	 */
	private final Recipient bounceToRecipient;
	
	/**
	 * @see EmailPopulatingBuilder#withPlainText(String)
	 */
	private final String text;
	
	/**
	 * @see EmailPopulatingBuilder#withHTMLText(String)
	 */
	private final String textHTML;
	
	/**
	 * @see EmailPopulatingBuilder#withCalendarText(CalendarMethod, String)
	 */
	private final CalendarMethod calendarMethod;

	/**
	 * @see EmailPopulatingBuilder#withCalendarText(CalendarMethod, String)
	 */
	private final String textCalendar;

	/**
	 * @see EmailPopulatingBuilder#withContentTransferEncoding(ContentTransferEncoding)
	 */
	@Nullable
	private final ContentTransferEncoding contentTransferEncoding;

	/**
	 * @see EmailPopulatingBuilder#withPlainTextContentTransferEncoding(ContentTransferEncoding)
	 */
	@Nullable
	private final ContentTransferEncoding plainTextContentTransferEncoding;

	/**
	 * @see EmailPopulatingBuilder#withHTMLTextContentTransferEncoding(ContentTransferEncoding)
	 */
	@Nullable
	private final ContentTransferEncoding htmlTextContentTransferEncoding;

	/**
	 * @see EmailPopulatingBuilder#withCalendarTextContentTransferEncoding(ContentTransferEncoding)
	 */
	@Nullable
	private final ContentTransferEncoding calendarTextContentTransferEncoding;

	/**
	 * @see EmailPopulatingBuilder#withSubject(String)
	 */
	private final String subject;
	
	/**
	 * @see EmailPopulatingBuilder#withRecipients(Recipient...)
	 * @see EmailPopulatingBuilder#withRecipients(Collection)
	 */
	@NotNull
	private final List<Recipient> recipients;
	
	/**
	 * @see EmailPopulatingBuilder#withEmbeddedImage(String, DataSource)
	 */
	@NotNull
	private final List<AttachmentResource> embeddedImages;

	/**
	 * @see EmailPopulatingBuilder#withAttachment(String, DataSource)
	 */
	@NotNull
	private final List<AttachmentResource> attachments;

	/**
	 * If the S/MIME module is loaded, this list will contain the same attachments as {@link #attachments},
	 * but with any S/MIME signed attachments decrypted.
	 */
	@NotNull
	private final List<AttachmentResource> decryptedAttachments;

	/**
	 * @see EmailPopulatingBuilder#withHeader(String, Object)
	 * @see EmailStartingBuilder#replyingTo(MimeMessage, boolean, String)
	 */
	@NotNull
	private final Map<String, Collection<String>> headers;
	
	/**
	 * @see EmailPopulatingBuilder#withDispositionNotificationTo()
	 * @see EmailPopulatingBuilder#withDispositionNotificationTo(Recipient)
	 */
	@Nullable
	private final Boolean useDispositionNotificationTo;
	
	/**
	 * @see EmailPopulatingBuilder#withDispositionNotificationTo()
	 * @see EmailPopulatingBuilder#withDispositionNotificationTo(Recipient)
	 */
	private final Recipient dispositionNotificationTo;
	
	/**
	 * @see EmailPopulatingBuilder#withReturnReceiptTo()
	 * @see EmailPopulatingBuilder#withReturnReceiptTo(Recipient)
	 */
	@Nullable
	private final Boolean useReturnReceiptTo;

	/**
	 * @see EmailPopulatingBuilder#withReturnReceiptTo()
	 * @see EmailPopulatingBuilder#withReturnReceiptTo(Recipient)
	 */
	private final Recipient returnReceiptTo;

	/**
	 * @see EmailPopulatingBuilder#withDeliveryStatusNotification(DeliveryStatusNotification)
	 */
	@Nullable
	private final DeliveryStatusNotification deliveryStatusNotification;

	/**
	 * @see EmailPopulatingBuilder#withOverrideReceivers(Recipient...)
	 */
	private final List<Recipient> overrideReceivers;
	
	/**
	 * @see EmailStartingBuilder#forwarding(MimeMessage)
	 */
	private transient MimeMessage emailToForward;


	/**
	 * @see EmailPopulatingBuilder#signWithDomainKey(DkimConfig)
	 * @see EmailPopulatingBuilder#signWithDomainKey(byte[], String, String, Set)
	 */
	private final DkimConfig dkimConfig;

	/**
	 * @see EmailPopulatingBuilder#encryptWithSmime(SmimeEncryptionConfig)
	 * @see EmailPopulatingBuilder#encryptWithSmime(File, String, String)
	 */
	private final SmimeEncryptionConfig smimeEncryptionConfig;

	/**
	 * @see EmailPopulatingBuilder#signWithSmime(SmimeSigningConfig)
	 * @see EmailPopulatingBuilder#signWithSmime(File, String, String, String, String)
	 */
	private final SmimeSigningConfig smimeSigningConfig;

	/** OpenPGP secret material is deliberately excluded from Java serialization. */
	private final transient OpenPgpSigningConfig openPgpSigningConfig;

	/** OpenPGP sending configuration is deliberately excluded from Java serialization. */
	private final transient OpenPgpEncryptionConfig openPgpEncryptionConfig;

	@NotNull
	private OriginalOpenPgpDetails originalOpenPgpDetails;

	/**
	 * @see EmailPopulatingBuilder#getSmimeSignedEmail()
	 */
	private final Email smimeSignedEmail;

	/**
	 * @see EmailPopulatingBuilder#getOriginalSmimeDetails()
	 */
	@NotNull
	private final OriginalSmimeDetails originalSmimeDetails;

	/**
	 * @see "ExtendedEmail.wasMergedWithSmimeSignedMessage()"
	 */
	protected final boolean wasMergedWithSmimeSignedMessage;

	/**
	 * @see EmailPopulatingBuilder#fixingSentDate(Date)
	 */
	@Nullable
	private final Date sentDate;

	/**
	 * Simply transfers everything from {@link EmailPopulatingBuilder} to this Email instance.
	 *
	 * @see EmailPopulatingBuilder#buildEmail()
	 */
	public Email(@NotNull final EmailPopulatingBuilder builder) {
		checkNonEmptyArgument(builder, "builder");
		serializationFormatVersion = SERIALIZATION_FORMAT_VERSION;

		ignoreDefaults = builder.isIgnoreDefaults();
		ignoreOverrides = builder.isIgnoreOverrides();
		propertiesNotToApplyDefaultValueFor = builder.getPropertiesNotToApplyDefaultValueFor();
		propertiesNotToApplyOverrideValueFor = builder.getPropertiesNotToApplyOverrideValueFor();
		smimeSignedEmail = builder.getSmimeSignedEmail();

		final boolean smimeMerge = builder.isMergeSingleSMIMESignedAttachment() && smimeSignedEmail != null;

		wasMergedWithSmimeSignedMessage = smimeMerge;
		recipients = unmodifiableList(builder.getRecipients());
		embeddedImages = unmodifiableList((smimeMerge)
				? merge(builder.getEmbeddedImages(), smimeSignedEmail.getEmbeddedImages())
				: builder.getEmbeddedImages());
		attachments = unmodifiableList((smimeMerge)
				? merge(builder.getAttachments(), smimeSignedEmail.getAttachments())
				: builder.getAttachments());
		decryptedAttachments = unmodifiableList((smimeMerge)
				? merge(builder.getDecryptedAttachments(), smimeSignedEmail.getDecryptedAttachments())
				: builder.getDecryptedAttachments());
		headers = unmodifiableMap((smimeMerge)
				? merge(builder.getHeaders(), smimeSignedEmail.getHeaders())
				: builder.getHeaders());
		id = builder.getId();
		fromRecipient = builder.getFromRecipient();
		replyToRecipients = unmodifiableList(builder.getReplyToRecipients());
		bounceToRecipient = builder.getBounceToRecipient();
		text = smimeMerge ? smimeSignedEmail.getPlainText() : builder.getText();
		textHTML = smimeMerge ? smimeSignedEmail.getHTMLText() : builder.getTextHTML();
		calendarMethod = builder.getCalendarMethod();
		textCalendar = builder.getTextCalendar();
		contentTransferEncoding = builder.getContentTransferEncoding();
		plainTextContentTransferEncoding = smimeMerge ? smimeSignedEmail.getPlainTextContentTransferEncoding() : builder.getPlainTextContentTransferEncoding();
		htmlTextContentTransferEncoding = smimeMerge ? smimeSignedEmail.getHTMLTextContentTransferEncoding() : builder.getHTMLTextContentTransferEncoding();
		calendarTextContentTransferEncoding = builder.getCalendarTextContentTransferEncoding();
		subject = builder.getSubject();
		useDispositionNotificationTo = builder.getUseDispositionNotificationTo();
		dispositionNotificationTo = builder.getDispositionNotificationTo();
		useReturnReceiptTo = builder.getUseReturnReceiptTo();
		returnReceiptTo = builder.getReturnReceiptTo();
		deliveryStatusNotification = builder.getDeliveryStatusNotification();
		overrideReceivers = builder.getOverrideReceivers();
		emailToForward = builder.getEmailToForward();
		originalSmimeDetails = builder.getOriginalSmimeDetails();
		originalOpenPgpDetails = builder.getOriginalOpenPgpDetails();
		sentDate = builder.getSentDate();
		smimeEncryptionConfig = builder.getSmimeEncryptionConfig();
		smimeSigningConfig = builder.getSmimeSigningConfig();
		openPgpSigningConfig = builder.getOpenPgpSigningConfig();
		openPgpEncryptionConfig = builder.getOpenPgpEncryptionConfig();
		dkimConfig = builder.getDkimConfig();
	}

	/**
	 * Writes a versioned MIME snapshot of the forwarded message after serializing the regular Email state.
	 */
	private void writeObject(@NotNull final ObjectOutputStream outputStream)
			throws IOException {
		outputStream.defaultWriteObject();
		outputStream.writeObject(snapshotForwardedMessage());
	}

	/**
	 * Restores a forwarded message from its MIME snapshot. Streams produced before this snapshot was added leave the field empty.
	 */
	private void readObject(@NotNull final ObjectInputStream inputStream)
			throws IOException, ClassNotFoundException {
		inputStream.defaultReadObject();
		if (originalOpenPgpDetails == null) {
			originalOpenPgpDetails = OpenPgpDetails.plain();
		}
		if (serializationFormatVersion == 0) {
			emailToForward = null;
			return;
		}
		if (serializationFormatVersion != SERIALIZATION_FORMAT_VERSION) {
			throw invalidSerializedForm("Unsupported Email serialization format version: " + serializationFormatVersion, null);
		}

		final Object serializedForwardedMessage = inputStream.readObject();
		if (serializedForwardedMessage == null) {
			emailToForward = null;
		} else if (serializedForwardedMessage instanceof byte[]) {
			emailToForward = restoreForwardedMessage((byte[]) serializedForwardedMessage);
		} else {
			throw invalidSerializedForm("Serialized Email does not contain a MIME snapshot for its forwarded message", null);
		}
	}

	@Nullable
	private byte[] snapshotForwardedMessage()
			throws IOException {
		if (emailToForward == null) {
			return null;
		}

		final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		try {
			emailToForward.writeTo(outputStream);
			return outputStream.toByteArray();
		} catch (final MessagingException e) {
			throw new IOException("Unable to serialize the forwarded MIME message", e);
		}
	}

	@NotNull
	private static MimeMessage restoreForwardedMessage(byte @NotNull [] serializedForwardedMessage)
			throws InvalidObjectException {
		try {
			return new MimeMessage(
					Session.getInstance(new Properties()),
					new ByteArrayInputStream(serializedForwardedMessage));
		} catch (final MessagingException e) {
			throw invalidSerializedForm("Unable to restore the forwarded MIME message", e);
		}
	}

	@NotNull
	private static InvalidObjectException invalidSerializedForm(@NotNull final String message, @Nullable final Exception cause) {
		final InvalidObjectException exception = new InvalidObjectException(message);
		if (cause != null) {
			exception.initCause(cause);
		}
		return exception;
	}

	@SuppressWarnings("SameReturnValue")
	@Override
	public int hashCode() {
		return 0;
	}
	
	@Override
	public boolean equals(@Nullable final Object o) {
		return (this == o) || ((o != null) && (getClass() == o.getClass()) &&
				EqualsHelper.equalsEmail(this, (Email) o));
	}
	
	@Override
	public String toString() {
		String s = "Email{" +
				"\n\tid=" + id + ("\n\tsentDate=" + formatDate(sentDate) +
				"\n\tfromRecipient=" + fromRecipient +
				",\n\treplyToRecipients=" + replyToRecipients +
				",\n\tbounceToRecipient=" + bounceToRecipient +
				",\n\ttext='" + text + '\'' +
				",\n\ttextHTML='" + textHTML + '\'' +
				",\n\ttextCalendar='" + format("%s (method: %s)", textCalendar, calendarMethod) + '\'' +
				",\n\tcontentTransferEncoding='" + (contentTransferEncoding != null ? contentTransferEncoding : ContentTransferEncoding.getDefault()) + '\'' +
				",\n\tplainTextContentTransferEncoding='" + plainTextContentTransferEncoding + '\'' +
				",\n\thtmlTextContentTransferEncoding='" + htmlTextContentTransferEncoding + '\'' +
				",\n\tcalendarTextContentTransferEncoding='" + calendarTextContentTransferEncoding + '\'' +
				",\n\tsubject='" + subject + '\'' +
				",\n\trecipients=" + recipients);
		if (!MiscUtil.valueNullOrEmpty(dkimConfig)) {
			s += ",\n\tdkimConfig=" + dkimConfig;
		}
		if (TRUE.equals(useDispositionNotificationTo)) {
			s += ",\n\tuseDispositionNotificationTo=" + true +
					",\n\t\tdispositionNotificationTo=" + dispositionNotificationTo;
		}
		if (TRUE.equals(useReturnReceiptTo)) {
			s += ",\n\tuseReturnReceiptTo=" + true +
					",\n\t\treturnReceiptTo=" + returnReceiptTo;
		}
		if (!MiscUtil.valueNullOrEmpty(deliveryStatusNotification)) {
			s += ",\n\tdeliveryStatusNotification=" + deliveryStatusNotification;
		}
		if (!overrideReceivers.isEmpty()) {
			s += ",\n\toverrideReceivers=" + true +
					",\n\t\toverrideReceivers=" + overrideReceivers;
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
		if (!decryptedAttachments.isEmpty()) {
			s += ",\n\tdecryptedAttachments=" + decryptedAttachments;
		}
		if (emailToForward != null) {
			s += ",\n\tforwardingEmail=true";
		}

		if (smimeSignedEmail != null || smimeSigningConfig != null
				|| smimeEncryptionConfig != null || !(originalSmimeDetails instanceof PlainSmimeDetails)) {
			s += ",\n\tsmime details: {\n";
			s += "\t----------------------\n";
			if (smimeSignedEmail != null) {
				s += "\t\tsmimeSignedEmail=" + smimeSignedEmail + ",\n";
			}
			if (smimeSigningConfig != null) {
				s += "\t\tsmimeSigningConfig=" + smimeSigningConfig + ",\n";
			}
			if (smimeEncryptionConfig != null) {
				s += "\t\tsmimeEncryptionConfig=" + smimeEncryptionConfig;
			}
			s += "\t\toriginalSmimeDetails=" + originalSmimeDetails + "\n";
			s += "\t----------------------\n\t}";
		}
		if (openPgpSigningConfig != null || openPgpEncryptionConfig != null
				|| originalOpenPgpDetails.getOpenPgpMode() != OriginalOpenPgpDetails.OpenPgpMode.PLAIN) {
			s += ",\n\topenpgp details: {\n";
			if (openPgpSigningConfig != null) {
				s += "\t\tsigning=*** configured,\n";
			}
			if (openPgpEncryptionConfig != null) {
				s += "\t\tencryptionRecipients=" + openPgpEncryptionConfig.getRecipientPublicKeyRings().size() + ",\n";
			}
			s += "\t\tresult=" + originalOpenPgpDetails + "\n\t}";
		}
		s +=  "\n}";
		return s;
	}

	@Nullable
	private String formatDate(@Nullable Date date) {
		if (date == null) {
			return null;
		}
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss");
		sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
		return sdf.format(date);
	}

	/**
	 * @see EmailPopulatingBuilder#ignoringDefaults(boolean)
	 */
	public boolean isIgnoreDefaults() {
		return ignoreDefaults;
	}

	/**
	 * @see EmailPopulatingBuilder#ignoringOverrides(boolean)
	 */
	public boolean isIgnoreOverrides() {
		return ignoreOverrides;
	}

	/**
	 * @see EmailPopulatingBuilder#dontApplyDefaultValueFor(EmailProperty...)
	 */
	@Nullable
	public Set<EmailProperty> getPropertiesNotToApplyDefaultValueFor() {
		return propertiesNotToApplyDefaultValueFor;
	}

	/**
	 * @see EmailPopulatingBuilder#dontApplyOverrideValueFor(EmailProperty...)
	 */
	@Nullable
	public Set<EmailProperty> getPropertiesNotToApplyOverrideValueFor() {
		return propertiesNotToApplyOverrideValueFor;
	}

	/**
	 * @see EmailPopulatingBuilder#fixingMessageId(String)
	 */
	@Nullable
	public String getId() {
		return id;
	}
	
	/**
	 * @see EmailPopulatingBuilder#from(Recipient)
	 */
	@Nullable
	public Recipient getFromRecipient() {
		return fromRecipient;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withReplyTo(Recipient)
	 */
	@NotNull
	public List<Recipient> getReplyToRecipients() {
		return replyToRecipients;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withBounceTo(Recipient)
	 */
	@Nullable
	public Recipient getBounceToRecipient() {
		return bounceToRecipient;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withSubject(String)
	 */
	@Nullable
	public String getSubject() {
		return subject;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withDispositionNotificationTo()
	 * @see EmailPopulatingBuilder#withDispositionNotificationTo(Recipient)
	 */
	@Nullable
	public Boolean getUseDispositionNotificationTo() {
		return useDispositionNotificationTo;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withDispositionNotificationTo()
	 * @see EmailPopulatingBuilder#withDispositionNotificationTo(Recipient)
	 */
	@Nullable
	public Recipient getDispositionNotificationTo() {
		return dispositionNotificationTo;
	}

	/**
	 * @see EmailPopulatingBuilder#withReturnReceiptTo()
	 * @see EmailPopulatingBuilder#withReturnReceiptTo(Recipient)
	 */
	@Nullable
	public Boolean getUseReturnReceiptTo() {
		return useReturnReceiptTo;
	}

	/**
	 * @see EmailPopulatingBuilder#withOverrideReceivers(Recipient...)
	 */
	@NotNull
	public List<Recipient> getOverrideReceivers() {
		return overrideReceivers;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withReturnReceiptTo()
	 * @see EmailPopulatingBuilder#withReturnReceiptTo(Recipient)
	 */
	@Nullable
	public Recipient getReturnReceiptTo() {
		return returnReceiptTo;
	}

	/**
	 * @see EmailPopulatingBuilder#withDeliveryStatusNotification(DeliveryStatusNotification)
	 */
	@Nullable
	public DeliveryStatusNotification getDeliveryStatusNotification() {
		return deliveryStatusNotification;
	}
	
	/**
	 * @see EmailStartingBuilder#forwarding(MimeMessage)
	 */
	@Nullable
	public MimeMessage getEmailToForward() {
		return emailToForward;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withPlainText(String)
	 */
	@Nullable
	public String getPlainText() {
		return text;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withHTMLText(String)
	 */
	@Nullable
	public String getHTMLText() {
		return textHTML;
	}

	/**
	 * @see EmailPopulatingBuilder#withCalendarText(CalendarMethod, String)
	 */
	@Nullable
	public CalendarMethod getCalendarMethod() {
		return calendarMethod;
	}

	/**
	 * @see EmailPopulatingBuilder#withCalendarText(CalendarMethod, String)
	 */
	@Nullable
	public String getCalendarText() {
		return textCalendar;
	}

	/**
	 * @see EmailPopulatingBuilder#withAttachment(String, DataSource)
	 */
	@NotNull
	public List<AttachmentResource> getAttachments() {
		return attachments;
	}

	/**
	 * @see EmailPopulatingBuilder#getDecryptedAttachments()
	 */
	@NotNull
	public List<AttachmentResource> getDecryptedAttachments() {
		return decryptedAttachments;
	}

	/**
	 * @see EmailPopulatingBuilder#withEmbeddedImage(String, DataSource)
	 */
	@NotNull
	public List<AttachmentResource> getEmbeddedImages() {
		return embeddedImages;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withRecipients(Recipient...)
	 * @see EmailPopulatingBuilder#withRecipients(Collection)
	 */
	@NotNull
	public List<Recipient> getRecipients() {
		return recipients;
	}

	/**
	 * @see EmailPopulatingBuilder#withRecipients(Recipient...)
	 * @see EmailPopulatingBuilder#withRecipients(Collection)
	 */
	@NotNull
	public List<Recipient> getToRecipients() {
		return recipients.stream().filter(r -> r.getType() == TO).collect(toList());
	}

	/**
	 * @see EmailPopulatingBuilder#withRecipients(Recipient...)
	 * @see EmailPopulatingBuilder#withRecipients(Collection)
	 */
	@NotNull
	public List<Recipient> getCcRecipients() {
		return recipients.stream().filter(r -> r.getType() == CC).collect(toList());
	}

	/**
	 * @see EmailPopulatingBuilder#withRecipients(Recipient...)
	 * @see EmailPopulatingBuilder#withRecipients(Collection)
	 */
	@NotNull
	public List<Recipient> getBccRecipients() {
		return recipients.stream().filter(r -> r.getType() == BCC).collect(toList());
	}
	
	/**
	 * @see EmailPopulatingBuilder#withHeader(String, Object)
	 * @see EmailStartingBuilder#replyingTo(MimeMessage, boolean, String)
	 */
	@NotNull
	public Map<String, Collection<String>> getHeaders() {
		return headers;
	}
	
	/**
	 * @see EmailPopulatingBuilder#signWithDomainKey(DkimConfig)
	 * @see EmailPopulatingBuilder#signWithDomainKey(byte[], String, String, Set)
	 */
	@Nullable
	public DkimConfig getDkimConfig() {
		return dkimConfig;
	}
	/**
	 * @see EmailPopulatingBuilder#encryptWithSmime(SmimeEncryptionConfig)
	 * @see EmailPopulatingBuilder#encryptWithSmime(File, String, String)
	 */
	@Nullable
	public SmimeEncryptionConfig getSmimeEncryptionConfig() {
		return smimeEncryptionConfig;
	}

	/**
	 * @see EmailPopulatingBuilder#signWithSmime(SmimeSigningConfig)
	 * @see EmailPopulatingBuilder#signWithSmime(File, String, String, String, String)
	 */
	@Nullable
	public SmimeSigningConfig getSmimeSigningConfig() {
		return smimeSigningConfig;
	}

	@Nullable
	public OpenPgpSigningConfig getOpenPgpSigningConfig() {
		return openPgpSigningConfig;
	}

	@Nullable
	public OpenPgpEncryptionConfig getOpenPgpEncryptionConfig() {
		return openPgpEncryptionConfig;
	}

	@NotNull
	public OriginalOpenPgpDetails getOriginalOpenPgpDetails() {
		return originalOpenPgpDetails;
	}

	/**
	 * @see EmailPopulatingBuilder#getSmimeSignedEmail()
	 */
	@Nullable
	public Email getSmimeSignedEmail() {
		return smimeSignedEmail;
	}

	/**
	 * @see EmailPopulatingBuilder#getOriginalSmimeDetails()
	 */
	@NotNull
	public OriginalSmimeDetails getOriginalSmimeDetails() {
		return originalSmimeDetails;
	}

	/**
	 * @see EmailPopulatingBuilder#fixingSentDate(Date)
	 */
	@Nullable
	public Date getSentDate() {
		return sentDate != null ? new Date(sentDate.getTime()) : null;
	}

	/**
	 * Returns the caller-controlled sent date as an {@link Instant}, or {@code null} when no date was fixed.
	 *
	 * @see EmailPopulatingBuilder#fixingSentDate(Instant)
	 */
	@Nullable
	public Instant getSentDateAsInstant() {
		return sentDate != null ? sentDate.toInstant() : null;
	}

	/**
	 * @see EmailPopulatingBuilder#withContentTransferEncoding(ContentTransferEncoding)
	 */
	@Nullable
	public ContentTransferEncoding getContentTransferEncoding() {
		return contentTransferEncoding;
	}

	/**
	 * @see EmailPopulatingBuilder#withPlainTextContentTransferEncoding(ContentTransferEncoding)
	 */
	@Nullable
	public ContentTransferEncoding getPlainTextContentTransferEncoding() {
		return plainTextContentTransferEncoding;
	}

	/**
	 * @see EmailPopulatingBuilder#withHTMLTextContentTransferEncoding(ContentTransferEncoding)
	 */
	@Nullable
	public ContentTransferEncoding getHTMLTextContentTransferEncoding() {
		return htmlTextContentTransferEncoding;
	}

	/**
	 * @see EmailPopulatingBuilder#withCalendarTextContentTransferEncoding(ContentTransferEncoding)
	 */
	@Nullable
	public ContentTransferEncoding getCalendarTextContentTransferEncoding() {
		return calendarTextContentTransferEncoding;
	}
}
