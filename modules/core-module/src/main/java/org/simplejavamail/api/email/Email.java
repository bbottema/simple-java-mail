package org.simplejavamail.api.email;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.internal.smimesupport.model.PlainSmimeDetails;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.internal.util.MiscUtil;

import javax.activation.DataSource;
import javax.mail.internet.MimeMessage;
import java.io.File;
import java.io.InputStream;
import java.io.Serializable;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import static java.lang.String.format;
import static java.util.Collections.unmodifiableList;
import static java.util.Collections.unmodifiableMap;
import static org.simplejavamail.internal.util.ListUtil.merge;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;

/**
 * Email message with all necessary data for an effective mailing action, including attachments etc.
 * Exclusively created using <em>EmailBuilder</em>.
 */
@SuppressWarnings("SameParameterValue")
public class Email implements Serializable {

	private static final long serialVersionUID = 1234567L;

	/**
	 * @see EmailPopulatingBuilder#fixingMessageId(String)
	 */
	private String id;
	
	/**
	 * @see EmailPopulatingBuilder#from(Recipient)
	 */
	private final Recipient fromRecipient;
	
	/**
	 * @see EmailPopulatingBuilder#withReplyTo(Recipient)
	 */
	private final Recipient replyToRecipient;
	
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
	 * @see EmailPopulatingBuilder#withSubject(String)
	 */
	private final String subject;
	
	/**
	 * @see EmailPopulatingBuilder#to(Recipient...)
	 * @see EmailPopulatingBuilder#cc(Recipient...)
	 * @see EmailPopulatingBuilder#bcc(Recipient...)
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
	private final Map<String, String> headers;
	
	/**
	 * @see EmailPopulatingBuilder#withDispositionNotificationTo()
	 * @see EmailPopulatingBuilder#withDispositionNotificationTo(Recipient)
	 */
	private final boolean useDispositionNotificationTo;
	
	/**
	 * @see EmailPopulatingBuilder#withDispositionNotificationTo()
	 * @see EmailPopulatingBuilder#withDispositionNotificationTo(Recipient)
	 */
	private final Recipient dispositionNotificationTo;
	
	/**
	 * @see EmailPopulatingBuilder#withReturnReceiptTo()
	 * @see EmailPopulatingBuilder#withReturnReceiptTo(Recipient)
	 */
	private final boolean useReturnReceiptTo;
	
	/**
	 * @see EmailPopulatingBuilder#withReturnReceiptTo()
	 * @see EmailPopulatingBuilder#withReturnReceiptTo(Recipient)
	 */
	private final Recipient returnReceiptTo;
	
	/**
	 * @see EmailStartingBuilder#forwarding(MimeMessage)
	 */
	// mime message is not serializable, so transient
	private transient final MimeMessage emailToForward;
	
	/**
	 * @see EmailPopulatingBuilder#signWithDomainKey(InputStream, String, String)
	 * @see EmailPopulatingBuilder#signWithDomainKey(byte[], String, String)
	 * @see EmailPopulatingBuilder#signWithDomainKey(File, String, String)
	 */
	private final byte[] dkimPrivateKeyData;
	
	/**
	 * @see EmailPopulatingBuilder#signWithDomainKey(InputStream, String, String)
	 * @see EmailPopulatingBuilder#signWithDomainKey(File, String, String)
	 */
	private final String dkimSigningDomain;

	/**
	 * @see EmailPopulatingBuilder#signWithDomainKey(InputStream, String, String)
	 * @see EmailPopulatingBuilder#signWithDomainKey(File, String, String)
	 */
	private final String dkimSelector;

	/**
	 * @see EmailPopulatingBuilder#signWithSmime(Pkcs12Config)
	 * @see EmailPopulatingBuilder#signWithSmime(InputStream, String, String, String)
	 */
	private final X509Certificate x509CertificateForSmimeEncryption;

	/**
	 * @see EmailPopulatingBuilder#encryptWithSmime(X509Certificate)
	 * @see EmailPopulatingBuilder#encryptWithSmime(InputStream)
	 */
	// data source is not serializable, so transient
	private final transient Pkcs12Config pkcs12ConfigForSmimeSigning;

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
	 * @see Email#wasMergedWithSmimeSignedMessage()
	 */
	private final boolean wasMergedWithSmimeSignedMessage;

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

		smimeSignedEmail = builder.getSmimeSignedEmail();

		final boolean smimeMerge = builder.isMergeSingleSMIMESignedAttachment() && smimeSignedEmail != null;

		this.wasMergedWithSmimeSignedMessage = smimeMerge;

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
		replyToRecipient = builder.getReplyToRecipient();
		bounceToRecipient = builder.getBounceToRecipient();
		text = smimeMerge ? smimeSignedEmail.getPlainText() : builder.getText();
		textHTML = smimeMerge ? smimeSignedEmail.getHTMLText() : builder.getTextHTML();
		calendarMethod = builder.getCalendarMethod();
		textCalendar = builder.getTextCalendar();
		subject = builder.getSubject();
		
		useDispositionNotificationTo = builder.isUseDispositionNotificationTo();
		useReturnReceiptTo = builder.isUseReturnReceiptTo();
		emailToForward = builder.getEmailToForward();

		originalSmimeDetails = builder.getOriginalSmimeDetails();

		sentDate = builder.getSentDate();

		x509CertificateForSmimeEncryption = builder.getX509CertificateForSmimeEncryption();
		pkcs12ConfigForSmimeSigning = builder.getPkcs12ConfigForSmimeSigning();

		if (useDispositionNotificationTo && MiscUtil.valueNullOrEmpty(builder.getDispositionNotificationTo())) {
			//noinspection IfMayBeConditional
			if (builder.getReplyToRecipient() != null) {
				dispositionNotificationTo = builder.getReplyToRecipient();
			} else {
				dispositionNotificationTo = builder.getFromRecipient();
			}
		} else {
			dispositionNotificationTo = builder.getDispositionNotificationTo();
		}
		
		if (useReturnReceiptTo && MiscUtil.valueNullOrEmpty(builder.getReturnReceiptTo())) {
			//noinspection IfMayBeConditional
			if (builder.getReplyToRecipient() != null) {
				returnReceiptTo = builder.getReplyToRecipient();
			} else {
				returnReceiptTo = builder.getFromRecipient();
			}
		} else {
			returnReceiptTo = builder.getReturnReceiptTo();
		}
		
		if (builder.getDkimPrivateKeyData() != null) {
			this.dkimPrivateKeyData = builder.getDkimPrivateKeyData();
			this.dkimSigningDomain = builder.getDkimSigningDomain();
			this.dkimSelector = builder.getDkimSelector();
		} else {
			this.dkimPrivateKeyData = null;
			this.dkimSigningDomain = null;
			this.dkimSelector = null;
		}
	}

	/**
	 * @deprecated Don't use this method, refer to {@link EmailPopulatingBuilder#fixingMessageId(String)} instead. This method is used internally to
	 * update the message id once a mail has been sent.
	 */
	@Deprecated
	public void internalSetId(@NotNull final String id) {
		this.id = id;
	}

	/**
	 * @deprecated Don't use this method. This method is used internally when using the builder API to copy an email that
	 * contains an S/MIME signed message. Without this method, we don't know if the copy should also be merged to match the
	 * copied email.
	 */
	@Deprecated
	public boolean wasMergedWithSmimeSignedMessage() {
		return wasMergedWithSmimeSignedMessage;
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
				",\n\treplyToRecipient=" + replyToRecipient +
				",\n\tbounceToRecipient=" + bounceToRecipient +
				",\n\ttext='" + text + '\'' +
				",\n\ttextHTML='" + textHTML + '\'' +
				",\n\ttextCalendar='" + format("%s (method: %s)", textCalendar, calendarMethod) + '\'' +
				",\n\tsubject='" + subject + '\'' +
				",\n\trecipients=" + recipients);
		if (!MiscUtil.valueNullOrEmpty(dkimSigningDomain)) {
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
		if (!decryptedAttachments.isEmpty()) {
			s += ",\n\tdecryptedAttachments=" + decryptedAttachments;
		}
		if (emailToForward != null) {
			s += ",\n\tforwardingEmail=true";
		}

		if (smimeSignedEmail != null || pkcs12ConfigForSmimeSigning != null
				|| x509CertificateForSmimeEncryption != null || !(originalSmimeDetails instanceof PlainSmimeDetails)) {
			s += ",\n\tsmime details: {\n";
			s += "\t----------------------\n";
			if (smimeSignedEmail != null) {
				s += "\t\tsmimeSignedEmail=" + smimeSignedEmail + ",\n";
			}
			if (pkcs12ConfigForSmimeSigning != null) {
				s += "\t\tpkcs12ConfigForSmimeSigning=" + pkcs12ConfigForSmimeSigning + ",\n";
			}
			if (x509CertificateForSmimeEncryption != null) {
				s += "\t\tx509CertificateForSmimeEncryption=" + x509CertificateForSmimeEncryption;
			}
			s += "\t\toriginalSmimeDetails=" + originalSmimeDetails + "\n";
			s += "\t----------------------\n\t}";
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
	@Nullable
	public Recipient getReplyToRecipient() {
		return replyToRecipient;
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
	public boolean isUseDispositionNotificationTo() {
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
	public boolean isUseReturnReceiptTo() {
		return useReturnReceiptTo;
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
	 * @see EmailPopulatingBuilder#to(Recipient...)
	 * @see EmailPopulatingBuilder#cc(Recipient...)
	 * @see EmailPopulatingBuilder#bcc(Recipient...)
	 */
	@NotNull
	public List<Recipient> getRecipients() {
		return recipients;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withHeader(String, Object)
	 * @see EmailStartingBuilder#replyingTo(MimeMessage, boolean, String)
	 */
	@NotNull
	public Map<String, String> getHeaders() {
		return headers;
	}
	
	/**
	 * @see EmailPopulatingBuilder#signWithDomainKey(InputStream, String, String)
	 */
	@Nullable
	public byte[] getDkimPrivateKeyData() {
		return dkimPrivateKeyData;
	}
	
	/**
	 * @see EmailPopulatingBuilder#signWithDomainKey(InputStream, String, String)
	 * @see EmailPopulatingBuilder#signWithDomainKey(File, String, String)
	 */
	@Nullable
	public String getDkimSigningDomain() {
		return dkimSigningDomain;
	}

	/**
	 * @see EmailPopulatingBuilder#signWithDomainKey(InputStream, String, String)
	 * @see EmailPopulatingBuilder#signWithDomainKey(File, String, String)
	 */
	@Nullable
	public String getDkimSelector() {
		return dkimSelector;
	}

	/**
	 * @see EmailPopulatingBuilder#signWithSmime(Pkcs12Config)
	 * @see EmailPopulatingBuilder#signWithSmime(InputStream, String, String, String)
	 */
	@Nullable
	public X509Certificate getX509CertificateForSmimeEncryption() {
		return x509CertificateForSmimeEncryption;
	}

	/**
	 * @see EmailPopulatingBuilder#encryptWithSmime(X509Certificate)
	 * @see EmailPopulatingBuilder#encryptWithSmime(InputStream)
	 */
	@Nullable
	public Pkcs12Config getPkcs12ConfigForSmimeSigning() {
		return pkcs12ConfigForSmimeSigning;
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
}