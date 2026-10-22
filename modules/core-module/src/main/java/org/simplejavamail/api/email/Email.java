package org.simplejavamail.api.email;

import org.simplejavamail.api.email.config.SmtpDeliveryOptions;
import org.simplejavamail.api.email.config.EmailTemplateOptions;
import org.simplejavamail.api.email.config.ContentEncodingConfig;
import jakarta.activation.DataSource;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.email.config.OpenPgpEncryptionConfig;
import org.simplejavamail.api.email.config.OpenPgpSigningConfig;
import org.simplejavamail.api.email.config.SmimeEncryptionConfig;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.api.internal.smimesupport.model.PlainSmimeDetails;
import org.simplejavamail.api.email.EmailProperty;
import org.simplejavamail.internal.util.MiscUtil;
import org.simplejavamail.internal.email.OpenPgpDetailsImpl;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import static org.simplejavamail.internal.config.EmailPropertySerialization.publicSelectors;
import static org.simplejavamail.internal.config.EmailPropertySerialization.serializedSelectors;
import static org.simplejavamail.internal.util.ListUtil.merge;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;

/**
 * Email message with all necessary data for an effective mailing action, including attachments etc.
 * Exclusively created through the email builder API obtained from a configured {@code SimpleJavaMail} factory.
 * Collection structure and dates are detached from their builder. Stored message collections are unmodifiable;
 * filtered To/Cc/Bcc lists are independent copies whose changes do not affect this Email.
 * A visible recipient with no supplied role is interpreted as To, including when restoring supported older snapshots.
 * This does not change the supplied Recipient or the roles of From, Reply-To and envelope-only recipients.
 * Attachment data sources and forwarded MIME messages remain borrowed: building an Email does not read or freeze their content.
 *
 * <h2>Java serialization</h2>
 * Since 9.2.0, Java serialization produces a send-ready snapshot. Attachment, embedded-image and decrypted-attachment data is read into
 * byte-backed resources; a forwarded {@link MimeMessage} is stored in RFC 822 form; and {@link SmimeSigningConfig} is retained. Since 10.0.0,
 * {@link OpenPgpSigningConfig} and {@link OpenPgpEncryptionConfig} are retained too, including their key and algorithm preferences. Deserialized
 * resources are read-only, and the concrete {@link DataSource} implementations and the forwarded message's original {@link Session} are not retained.
 * Any lazy or remote data source is therefore consumed while the {@code Email} is serialized, and serialization fails if its data cannot be read.
 *
 * <p>The serialized form can contain message content, credentials, S/MIME private keys and passwords, and OpenPGP secret key rings and passphrases.
 * Protect it accordingly; automatic rendering hides known secrets but serialization retains them. Encryption-only OpenPGP configuration contains
 * recipient public key rings and the cipher preference. Earlier snapshots that omitted OpenPGP configuration remain readable with that state absent;
 * data that was never stored cannot be recovered. Exact protected EML retains its authoritative bytes and is not protected again on restoration.
 * Streams created before
 * 9.2.0 remain readable for ordinary message fields, but those versions never stored attachment data, forwarded MIME content or S/MIME signing
 * configuration. Accessing missing legacy attachment data fails with an exception that identifies the pre-9.2.0 format.</p>
 */
@SuppressWarnings("SameParameterValue")
public class Email implements Serializable {

	private static final long serialVersionUID = 1234567L;
	private static final int SERIALIZATION_FORMAT_VERSION = 1;
	private final int serializationFormatVersion;

	/**
	 * @see EmailTemplateOptions.Builder#ignoringDefaults(boolean)
	 */
	private final boolean ignoreDefaults;

	/**
	 * @see EmailTemplateOptions.Builder#ignoringOverrides(boolean)
	 */
	private final boolean ignoreOverrides;

	/**
	 * @see EmailTemplateOptions.Builder#dontApplyDefaultValueFor(EmailProperty...)
	 */
	private final Set<? extends Enum<?>> propertiesNotToApplyDefaultValueFor;
	private transient Set<EmailProperty> defaultSuppression;

	/**
	 * @see EmailTemplateOptions.Builder#dontApplyOverrideValueFor(EmailProperty...)
	 */
	private final Set<? extends Enum<?>> propertiesNotToApplyOverrideValueFor;
	private transient Set<EmailProperty> overrideSuppression;

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
	private List<Recipient> replyToRecipients;

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
	 * @see ContentEncodingConfig.Builder#withContentTransferEncoding(ContentTransferEncoding)
	 */
	@Nullable
	private final ContentTransferEncoding contentTransferEncoding;

	/**
	 * @see ContentEncodingConfig.Builder#withPlainTextContentTransferEncoding(ContentTransferEncoding)
	 */
	@Nullable
	private final ContentTransferEncoding plainTextContentTransferEncoding;

	/**
	 * @see ContentEncodingConfig.Builder#withHTMLTextContentTransferEncoding(ContentTransferEncoding)
	 */
	@Nullable
	private final ContentTransferEncoding htmlTextContentTransferEncoding;

	/**
	 * @see ContentEncodingConfig.Builder#withCalendarTextContentTransferEncoding(ContentTransferEncoding)
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
	private List<Recipient> recipients;
	
	/**
	 * @see EmailPopulatingBuilder#withEmbeddedImage(String, DataSource)
	 */
	@NotNull
	private List<AttachmentResource> embeddedImages;

	/**
	 * @see EmailPopulatingBuilder#withAttachment(String, DataSource)
	 */
	@NotNull
	private List<AttachmentResource> attachments;

	/**
	 * If the S/MIME module is loaded, this list will contain the same attachments as {@link #attachments},
	 * but with any S/MIME signed attachments decrypted.
	 */
	@NotNull
	private List<AttachmentResource> decryptedAttachments;

	/**
	 * @see EmailPopulatingBuilder#withHeader(String, Object)
	 * @see EmailStartingBuilder#replyingTo(MimeMessage, boolean, String)
	 */
	@NotNull
	private Map<String, Collection<String>> headers;
	
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
	// Retained only for reading/writing supported legacy singleton snapshots.
	private final Recipient dispositionNotificationTo;
	@NotNull
	private List<Recipient> dispositionNotificationToRecipients;
	
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
	// Retained only for reading/writing supported legacy singleton snapshots.
	private final Recipient returnReceiptTo;
	@NotNull
	private List<Recipient> returnReceiptToRecipients;

	/**
	 * @see SmtpDeliveryOptions.Builder#withDeliveryStatusNotification(DeliveryStatusNotification)
	 */
	@Nullable
	private final DeliveryStatusNotification deliveryStatusNotification;
	@Nullable
	private final DeliveryStatusReport deliveryStatusReport;
	private final boolean tlsRequiredForOnwardDelivery;
	/** @see SmtpDeliveryOptions.Builder#withSendingToAcceptedRecipients(boolean) */
	@Nullable
	private final Boolean sendingToAcceptedRecipients;

	/**
	 * @see EmailPopulatingBuilder#withOverrideReceivers(Recipient...)
	 */
	private List<Recipient> overrideReceivers;
	
	/**
	 * @see EmailStartingBuilder#forwarding(MimeMessage)
	 */
	private transient MimeMessage emailToForward;


	/**
	 * @see EmailPopulatingBuilder#signWithDomainKey(DkimConfig)
	 * @see org.simplejavamail.api.email.config.DkimConfig.DkimConfigBuilder#dkimPrivateKeyData(byte[])
	 */
	private final DkimConfig dkimConfig;

	/**
	 * @see EmailPopulatingBuilder#encryptWithSmime(SmimeEncryptionConfig)
	 * @see org.simplejavamail.api.email.config.SmimeEncryptionConfig.SmimeEncryptionConfigBuilder#x509Certificate(File)
	 */
	private final SmimeEncryptionConfig smimeEncryptionConfig;

	/**
	 * @see EmailPopulatingBuilder#signWithSmime(SmimeSigningConfig)
	 * @see org.simplejavamail.api.email.config.SmimeSigningConfig.SmimeSigningConfigBuilder#pkcs12Config(File, String, String, String)
	 */
	private final SmimeSigningConfig smimeSigningConfig;

	/** Retained in Java snapshots, including secret key material and passphrase. */
	private final OpenPgpSigningConfig openPgpSigningConfig;

	/** Retained in Java snapshots, including recipient public key rings and cipher preference. */
	private final OpenPgpEncryptionConfig openPgpEncryptionConfig;

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
	private Date sentDate;

	/**
	 * Initializes the library's Email implementation from its builder. Applications create messages through
	 * {@link EmailPopulatingBuilder#buildEmail()} rather than calling this constructor or subclassing Email.
	 *
	 * @see EmailPopulatingBuilder#buildEmail()
	 */
	@ApiStatus.Internal
	protected Email(@NotNull final EmailPopulatingBuilder builder) {
		checkNonEmptyArgument(builder, "builder");
		serializationFormatVersion = SERIALIZATION_FORMAT_VERSION;
		final EmailTemplateOptions templateOptions = builder.getTemplateOptions();
		final ContentEncodingConfig encodingConfig = builder.getContentEncodingConfig();
		final SmtpDeliveryOptions deliveryOptions = builder.getSmtpDeliveryOptions();

		ignoreDefaults = templateOptions.isIgnoreDefaults();
		ignoreOverrides = templateOptions.isIgnoreOverrides();
		// Preserve the enum names stored by released Email snapshots; the public getters use the API selectors.
		propertiesNotToApplyDefaultValueFor = serializedSelectors(templateOptions.getPropertiesNotToApplyDefaultValueFor());
		propertiesNotToApplyOverrideValueFor = serializedSelectors(templateOptions.getPropertiesNotToApplyOverrideValueFor());
		restorePropertySelectors();
		smimeSignedEmail = builder.getSmimeSignedEmail();

		final boolean smimeMerge = builder.isMergeSingleSMIMESignedAttachment() && smimeSignedEmail != null;

		wasMergedWithSmimeSignedMessage = smimeMerge;
		recipients = builder.getRecipients();
		embeddedImages = (smimeMerge)
				? merge(builder.getEmbeddedImages(), smimeSignedEmail.getEmbeddedImages())
				: builder.getEmbeddedImages();
		attachments = (smimeMerge)
				? merge(builder.getAttachments(), smimeSignedEmail.getAttachments())
				: builder.getAttachments();
		decryptedAttachments = (smimeMerge)
				? merge(builder.getDecryptedAttachments(), smimeSignedEmail.getDecryptedAttachments())
				: builder.getDecryptedAttachments();
		headers = (smimeMerge)
				? merge(builder.getHeaders(), smimeSignedEmail.getHeaders())
				: builder.getHeaders();
		id = builder.getId();
		fromRecipient = builder.getFromRecipient();
		replyToRecipients = builder.getReplyToRecipients();
		bounceToRecipient = builder.getBounceToRecipient();
		text = smimeMerge ? smimeSignedEmail.getPlainText() : builder.getPlainText();
		textHTML = smimeMerge ? smimeSignedEmail.getHTMLText() : builder.getHTMLText();
		calendarMethod = builder.getCalendarMethod();
		textCalendar = builder.getCalendarText();
		contentTransferEncoding = encodingConfig.getContentTransferEncoding();
		plainTextContentTransferEncoding = smimeMerge ? smimeSignedEmail.getPlainTextContentTransferEncoding() : encodingConfig.getPlainTextContentTransferEncoding();
		htmlTextContentTransferEncoding = smimeMerge ? smimeSignedEmail.getHTMLTextContentTransferEncoding() : encodingConfig.getHTMLTextContentTransferEncoding();
		calendarTextContentTransferEncoding = encodingConfig.getCalendarTextContentTransferEncoding();
		subject = builder.getSubject();
		useDispositionNotificationTo = builder.getUseDispositionNotificationTo();
		dispositionNotificationToRecipients = builder.getDispositionNotificationToRecipients();
		dispositionNotificationTo = dispositionNotificationToRecipients.size() == 1 ? dispositionNotificationToRecipients.get(0) : null;
		useReturnReceiptTo = builder.getUseReturnReceiptTo();
		returnReceiptToRecipients = builder.getReturnReceiptToRecipients();
		returnReceiptTo = returnReceiptToRecipients.size() == 1 ? returnReceiptToRecipients.get(0) : null;
		deliveryStatusNotification = deliveryOptions.getDeliveryStatusNotification();
		deliveryStatusReport = builder.getDeliveryStatusReport();
		tlsRequiredForOnwardDelivery = deliveryOptions.isTlsRequiredForOnwardDelivery();
		sendingToAcceptedRecipients = deliveryOptions.getSendingToAcceptedRecipients();
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
		detachMessageMetadata();
	}

	/** Copy metadata, not borrowed content sources. The same boundary repairs shared collections in older serialized Emails. */
	private void detachMessageMetadata() {
		returnReceiptToRecipients = notificationSnapshot(returnReceiptToRecipients, returnReceiptTo);
		dispositionNotificationToRecipients = notificationSnapshot(dispositionNotificationToRecipients, dispositionNotificationTo);
		recipients = visibleRecipientSnapshot(recipients);
		replyToRecipients = immutableListSnapshot(replyToRecipients);
		overrideReceivers = immutableListSnapshot(overrideReceivers);
		attachments = immutableListSnapshot(attachments);
		embeddedImages = immutableListSnapshot(embeddedImages);
		decryptedAttachments = immutableListSnapshot(decryptedAttachments);
		headers = immutableHeaderSnapshot(headers);
		sentDate = sentDate == null ? null : new Date(sentDate.getTime());
	}

	@NotNull
	private static List<Recipient> notificationSnapshot(@Nullable final List<Recipient> values, @Nullable final Recipient legacy) {
		return immutableListSnapshot(values != null ? values : legacy == null
				? java.util.Collections.emptyList() : java.util.Collections.singletonList(legacy));
	}

	@NotNull
	private static List<Recipient> visibleRecipientSnapshot(@NotNull final Collection<Recipient> values) {
		final List<Recipient> snapshot = new ArrayList<>(values.size());
		for (final Recipient recipient : values) {
			// Older snapshots may share a role-less contact with other fields or objects in the serialized graph.
			// Interpret only its visible use without changing that contact or any of its delivery metadata.
			snapshot.add(recipient != null && recipient.getType() == null
					? new Recipient(recipient.getName(), recipient.getAddress(), TO, recipient.getSmimeCertificate(),
							recipient.getDeliveryStatusNotificationNotifyOptions())
					: recipient);
		}
		return unmodifiableList(snapshot);
	}

	@NotNull
	private static <T> List<T> immutableListSnapshot(@NotNull final Collection<T> values) {
		// List.copyOf would also reject legacy null elements; this boundary copies structure without tightening value validation.
		return unmodifiableList(new ArrayList<>(values));
	}

	@NotNull
	private static Map<String, Collection<String>> immutableHeaderSnapshot(@NotNull final Map<String, Collection<String>> source) {
		final Map<String, Collection<String>> snapshot = new LinkedHashMap<>();
		source.forEach((name, values) -> snapshot.put(name, immutableListSnapshot(values)));
		return unmodifiableMap(snapshot);
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
		detachMessageMetadata();
		restorePropertySelectors();
		if (originalOpenPgpDetails == null) {
			originalOpenPgpDetails = OpenPgpDetailsImpl.plain();
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
	private void restorePropertySelectors() {
		defaultSuppression = publicSelectors(propertiesNotToApplyDefaultValueFor);
		overrideSuppression = publicSelectors(propertiesNotToApplyOverrideValueFor);
	}

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
					",\n\t\tdispositionNotificationTo=" + dispositionNotificationToRecipients;
		}
		if (TRUE.equals(useReturnReceiptTo)) {
			s += ",\n\tuseReturnReceiptTo=" + true +
					",\n\t\treturnReceiptTo=" + returnReceiptToRecipients;
		}
		if (!MiscUtil.valueNullOrEmpty(deliveryStatusNotification)) {
			s += ",\n\tdeliveryStatusNotification=" + deliveryStatusNotification;
		}
		if (tlsRequiredForOnwardDelivery) {
			s += ",\n\ttlsRequiredForOnwardDelivery=true";
		}
		if (sendingToAcceptedRecipients != null) {
			s += ",\n\tsendingToAcceptedRecipients=" + sendingToAcceptedRecipients;
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
	 * @see EmailTemplateOptions.Builder#ignoringDefaults(boolean)
	 */
	public boolean isIgnoreDefaults() {
		return ignoreDefaults;
	}

	/**
	 * @see EmailTemplateOptions.Builder#ignoringOverrides(boolean)
	 */
	public boolean isIgnoreOverrides() {
		return ignoreOverrides;
	}

	/**
	 * @see EmailTemplateOptions.Builder#dontApplyDefaultValueFor(EmailProperty...)
	 */
	@Nullable
	public Set<EmailProperty> getPropertiesNotToApplyDefaultValueFor() {
		return defaultSuppression;
	}

	/**
	 * @see EmailTemplateOptions.Builder#dontApplyOverrideValueFor(EmailProperty...)
	 */
	@Nullable
	public Set<EmailProperty> getPropertiesNotToApplyOverrideValueFor() {
		return overrideSuppression;
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
	 * Returns the configured or interpreted bounce recipient, or null when absent. When an imported Return-Path cannot establish an address,
	 * its original value remains in {@link #getHeaders()} and this accessor returns null instead of representing the source text as an address.
	 *
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
	 * @return Immutable destinations; empty when absent or awaiting completion. Request flags remain independent.
	 */
	@NotNull
	public List<Recipient> getDispositionNotificationToRecipients() {
		return dispositionNotificationToRecipients;
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
	 * @return Immutable destinations; empty when absent or awaiting completion. Request flags remain independent.
	 */
	@NotNull
	public List<Recipient> getReturnReceiptToRecipients() {
		return returnReceiptToRecipients;
	}

	/**
	 * @see SmtpDeliveryOptions.Builder#withDeliveryStatusNotification(DeliveryStatusNotification)
	 */
	@Nullable
	public DeliveryStatusNotification getDeliveryStatusNotification() {
		return deliveryStatusNotification;
	}

	/**
	 * @return Whether this email requires RFC 8689 REQUIRETLS for onward SMTP delivery.
	 * @see SmtpDeliveryOptions.Builder#withTlsRequiredForOnwardDelivery()
	 */
	public boolean isTlsRequiredForOnwardDelivery() {
		return tlsRequiredForOnwardDelivery;
	}

	/**
	 * @return Whether this Email is set to continue for accepted recipients after a recipient rejection, or {@code null} when unset.
	 * @see SmtpDeliveryOptions.Builder#withSendingToAcceptedRecipients(boolean)
	 */
	@Nullable
	public Boolean getSendingToAcceptedRecipients() {
		return sendingToAcceptedRecipients;
	}

	/**
	 * Returns delivery feedback extracted from this Email's original notification, or {@code null} when no accessible outer delivery report was found.
	 * For example, convert a received bounce with the usual MIME/EML converter, then inspect its recipients and envelope identifier here.
	 *
	 * <p>This immutable snapshot describes the source notification, not a changing delivery tracker on the Email originally sent. Copies retain it
	 * even if their content is edited, and supported Java serialization preserves it. Defaults/overrides completion omits the report and all its
	 * nested facts while resolving outgoing content and settings. Inspect the original converted Email for this history. New replies and forwards
	 * do not inherit it. The getter performs no parsing or I/O. Ordinary outgoing templates
	 * do not supply it, and conversion back to MIME does not synthesize a delivery report from it.</p>
	 *
	 * <p>Received claims are not authenticated delivery proof or permission to retry. If protected content could not be opened, inspect the existing
	 * protection results as well: {@code null} does not establish that encrypted content contains no report.</p>
	 */
	@Nullable
	public DeliveryStatusReport getDeliveryStatusReport() {
		return deliveryStatusReport;
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
	 * Returns the historical decrypted-attachment inspection list retained during input conversion. Defaults/overrides completion
	 * does not copy this list into the result; it resolves ordinary outgoing attachments separately.
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
	 * Returns the owned, unmodifiable custom-header snapshot. Stored null values remain null through copying and serialization;
	 * ordinary conversion and sending produce a present empty field for them, as for an explicit empty String.
	 *
	 * @see EmailPopulatingBuilder#withHeader(String, Object)
	 * @see EmailStartingBuilder#replyingTo(MimeMessage, boolean, String)
	 */
	@NotNull
	public Map<String, Collection<String>> getHeaders() {
		return headers;
	}
	
	/**
	 * @see EmailPopulatingBuilder#signWithDomainKey(DkimConfig)
	 * @see org.simplejavamail.api.email.config.DkimConfig.DkimConfigBuilder#dkimPrivateKeyData(byte[])
	 */
	@Nullable
	public DkimConfig getDkimConfig() {
		return dkimConfig;
	}
	/**
	 * @see EmailPopulatingBuilder#encryptWithSmime(SmimeEncryptionConfig)
	 * @see org.simplejavamail.api.email.config.SmimeEncryptionConfig.SmimeEncryptionConfigBuilder#x509Certificate(File)
	 */
	@Nullable
	public SmimeEncryptionConfig getSmimeEncryptionConfig() {
		return smimeEncryptionConfig;
	}

	/**
	 * @see EmailPopulatingBuilder#signWithSmime(SmimeSigningConfig)
	 * @see org.simplejavamail.api.email.config.SmimeSigningConfig.SmimeSigningConfigBuilder#pkcs12Config(File, String, String, String)
	 */
	@Nullable
	public SmimeSigningConfig getSmimeSigningConfig() {
		return smimeSigningConfig;
	}

	/** @see EmailPopulatingBuilder#signWithOpenPgp(OpenPgpSigningConfig) */
	@Nullable
	public OpenPgpSigningConfig getOpenPgpSigningConfig() {
		return openPgpSigningConfig;
	}

	/** @see EmailPopulatingBuilder#encryptWithOpenPgp(OpenPgpEncryptionConfig) */
	@Nullable
	public OpenPgpEncryptionConfig getOpenPgpEncryptionConfig() {
		return openPgpEncryptionConfig;
	}

	/**
	 * Returns historical OpenPGP inspection results for the converted source. These results and their original protected bytes are
	 * preserved by ordinary copying and supported Java serialization, but are omitted by defaults/overrides completion.
	 * Outgoing OpenPGP configuration is available separately through {@link #getOpenPgpSigningConfig()} and {@link #getOpenPgpEncryptionConfig()}.
	 */
	@NotNull
	public OriginalOpenPgpDetails getOriginalOpenPgpDetails() {
		return originalOpenPgpDetails;
	}

	/**
	 * Returns the retained S/MIME signed-content view from input conversion, when present. Defaults/overrides completion omits this
	 * historical view and its merge bookkeeping; content already merged into ordinary fields remains outgoing content.
	 * @see EmailPopulatingBuilder#getSmimeSignedEmail()
	 */
	@Nullable
	public Email getSmimeSignedEmail() {
		return smimeSignedEmail;
	}

	/**
	 * Returns historical S/MIME inspection results for the converted source. These results and their original protected bytes are
	 * preserved by ordinary copying and supported Java serialization, but are omitted by defaults/overrides completion.
	 * Outgoing S/MIME configuration is available separately through {@link #getSmimeSigningConfig()} and {@link #getSmimeEncryptionConfig()}.
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
	 * @see ContentEncodingConfig.Builder#withContentTransferEncoding(ContentTransferEncoding)
	 */
	@Nullable
	public ContentTransferEncoding getContentTransferEncoding() {
		return contentTransferEncoding;
	}

	/**
	 * @see ContentEncodingConfig.Builder#withPlainTextContentTransferEncoding(ContentTransferEncoding)
	 */
	@Nullable
	public ContentTransferEncoding getPlainTextContentTransferEncoding() {
		return plainTextContentTransferEncoding;
	}

	/**
	 * @see ContentEncodingConfig.Builder#withHTMLTextContentTransferEncoding(ContentTransferEncoding)
	 */
	@Nullable
	public ContentTransferEncoding getHTMLTextContentTransferEncoding() {
		return htmlTextContentTransferEncoding;
	}

	/**
	 * @see ContentEncodingConfig.Builder#withCalendarTextContentTransferEncoding(ContentTransferEncoding)
	 */
	@Nullable
	public ContentTransferEncoding getCalendarTextContentTransferEncoding() {
		return calendarTextContentTransferEncoding;
	}
}
