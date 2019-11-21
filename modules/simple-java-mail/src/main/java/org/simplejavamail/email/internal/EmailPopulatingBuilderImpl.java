package org.simplejavamail.email.internal;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.CalendarMethod;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EmailStartingBuilder;
import org.simplejavamail.api.email.OriginalSmimeDetails;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.internal.clisupport.model.Cli;
import org.simplejavamail.api.internal.smimesupport.model.PlainSmimeDetails;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.email.EmailBuilder;
import org.simplejavamail.internal.util.CertificationUtil;
import org.simplejavamail.internal.util.MiscUtil;

import javax.activation.DataSource;
import javax.mail.Message.RecipientType;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.mail.util.ByteArrayDataSource;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.security.NoSuchProviderException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static javax.mail.Message.RecipientType.BCC;
import static javax.mail.Message.RecipientType.CC;
import static javax.mail.Message.RecipientType.TO;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_BCC_ADDRESS;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_BCC_NAME;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_BOUNCETO_ADDRESS;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_BOUNCETO_NAME;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_CC_ADDRESS;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_CC_NAME;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_FROM_ADDRESS;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_FROM_NAME;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_REPLYTO_ADDRESS;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_REPLYTO_NAME;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_SUBJECT;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_TO_ADDRESS;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_TO_NAME;
import static org.simplejavamail.config.ConfigLoader.Property.SMIME_ENCRYPTION_CERTIFICATE;
import static org.simplejavamail.config.ConfigLoader.Property.SMIME_SIGNING_KEYSTORE;
import static org.simplejavamail.config.ConfigLoader.Property.SMIME_SIGNING_KEYSTORE_PASSWORD;
import static org.simplejavamail.config.ConfigLoader.Property.SMIME_SIGNING_KEY_ALIAS;
import static org.simplejavamail.config.ConfigLoader.getProperty;
import static org.simplejavamail.config.ConfigLoader.getStringProperty;
import static org.simplejavamail.config.ConfigLoader.hasProperty;
import static org.simplejavamail.email.internal.EmailException.ERROR_LOADING_PROVIDER_FOR_SMIME_SUPPORT;
import static org.simplejavamail.email.internal.EmailException.ERROR_READING_FROM_FILE;
import static org.simplejavamail.email.internal.EmailException.ERROR_READING_FROM_PEM_INPUTSTREAM;
import static org.simplejavamail.email.internal.EmailException.NAME_MISSING_FOR_EMBEDDED_IMAGE;
import static org.simplejavamail.internal.smimesupport.SmimeRecognitionUtil.isGeneratedSmimeMessageId;
import static org.simplejavamail.internal.util.MiscUtil.defaultTo;
import static org.simplejavamail.internal.util.MiscUtil.extractEmailAddresses;
import static org.simplejavamail.internal.util.MiscUtil.valueNullOrEmpty;
import static org.simplejavamail.internal.util.Preconditions.assumeNonNull;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;

/**
 * @see EmailPopulatingBuilder
 */
@SuppressWarnings({"UnusedReturnValue", "unused"})
public class EmailPopulatingBuilderImpl implements InternalEmailPopulatingBuilder {

	/**
	 * @see #fixingMessageId(String)
	 */
	private String id;
	
	/**
	 * @see #from(Recipient)
	 */
	private Recipient fromRecipient;
	
	/**
	 * @see #withReplyTo(Recipient)
	 */
	private Recipient replyToRecipient;
	
	/**
	 * @see #withBounceTo(Recipient)
	 */
	private Recipient bounceToRecipient;
	
	/**
	 * @see #withSubject(String)
	 */
	private String subject;
	
	/**
	 * @see #withPlainText(String)
	 */
	private String text;
	
	/**
	 * @see #withHTMLText(String)
	 */
	private String textHTML;

	private CalendarMethod calendarMethod;

	private String textCalendar;
	
	/**
	 * @see #to(Recipient...)
	 * @see #cc(Recipient...)
	 * @see #bcc(Recipient...)
	 */
	@NotNull
	private final Set<Recipient> recipients;
	
	/**
	 * @see #withEmbeddedImage(String, DataSource)
	 */
	@NotNull
	private final List<AttachmentResource> embeddedImages;
	
	/**
	 * @see #withAttachment(String, DataSource)
	 */
	@NotNull
	private final List<AttachmentResource> attachments;

	/**
	 * @see #withDecryptedAttachments(List)
	 */
	@NotNull
	private final List<AttachmentResource> decryptedAttachments;

	/**
	 * @see #withHeader(String, Object)
	 * @see EmailStartingBuilder#replyingTo(MimeMessage, boolean, String)
	 */
	@NotNull
	private final Map<String, String> headers;
	
	/**
	 * @see #signWithDomainKey(File, String, String)
	 */
	private File dkimPrivateKeyFile;
	
	/**
	 * @see #signWithDomainKey(InputStream, String, String)
	 */
	private InputStream dkimPrivateKeyInputStream;
	
	/**
	 * @see #signWithDomainKey(InputStream, String, String)
	 * @see #signWithDomainKey(File, String, String)
	 */
	private String dkimSigningDomain;
	
	/**
	 * @see #signWithDomainKey(InputStream, String, String)
	 * @see #signWithDomainKey(File, String, String)
	 */
	private String dkimSelector;

	/**
	 * @see #signWithSmime(Pkcs12Config)
	 * @see #signWithSmime(InputStream, String, String, String)
	 * @see #encryptWithSmime(X509Certificate)
	 * @see #encryptWithSmime(InputStream)
	 */
	private Pkcs12Config pkcs12ConfigForSmimeSigning;

	/**
	 * @see #encryptWithSmime(X509Certificate)
	 * @see #encryptWithSmime(InputStream)
	 * @see #signWithSmime(Pkcs12Config)
	 * @see #signWithSmime(InputStream, String, String, String)
	 */
	private X509Certificate x509CertificateForSmimeEncryption;
	
	/**
	 * @see #withDispositionNotificationTo()
	 * @see #withDispositionNotificationTo(Recipient)
	 */
	private boolean useDispositionNotificationTo;
	
	/**
	 * @see #withDispositionNotificationTo()
	 * @see #withDispositionNotificationTo(Recipient)
	 */
	private Recipient dispositionNotificationTo;
	
	/**
	 * @see #withReturnReceiptTo()
	 * @see #withReturnReceiptTo(Recipient)
	 */
	private boolean useReturnReceiptTo;
	
	/**
	 * @see #withReturnReceiptTo()
	 * @see #withReturnReceiptTo(Recipient)
	 */
	private Recipient returnReceiptTo;

	/**
	 * @see EmailBuilder#forwarding(MimeMessage)
	 */
	private MimeMessage emailToForward;

	/**
	 * @see EmailPopulatingBuilder#getOriginalSmimeDetails()
	 */
	@NotNull
	private OriginalSmimeDetails originalSmimeDetails;

	/**
	 * @see EmailPopulatingBuilder#getSmimeSignedEmail()
	 */
	private Email smimeSignedEmail;

	/**
	 * @see EmailPopulatingBuilder#notMergingSingleSMIMESignedAttachment()
	 */
	private boolean mergeSingleSMIMESignedAttachment = true;

	/**
	 * @see EmailPopulatingBuilder#fixingSentDate(Date)
	 */
	@Nullable
	private Date sentDate;

	/**
	 * @see EmailStartingBuilder#startingBlank()
	 */
	EmailPopulatingBuilderImpl(final boolean applyDefaults) {
		recipients = new HashSet<>();
		embeddedImages = new ArrayList<>();
		attachments = new ArrayList<>();
		decryptedAttachments = new ArrayList<>();
		headers = new HashMap<>();
		originalSmimeDetails = new PlainSmimeDetails();

		if (applyDefaults) {
			if (hasProperty(DEFAULT_FROM_ADDRESS)) {
				from(getStringProperty(DEFAULT_FROM_NAME), assumeNonNull(getStringProperty(DEFAULT_FROM_ADDRESS)));
			}
			if (hasProperty(DEFAULT_REPLYTO_ADDRESS)) {
				withReplyTo(getStringProperty(DEFAULT_REPLYTO_NAME), assumeNonNull(getStringProperty(DEFAULT_REPLYTO_ADDRESS)));
			}
			if (hasProperty(DEFAULT_BOUNCETO_ADDRESS)) {
				withBounceTo(getStringProperty(DEFAULT_BOUNCETO_NAME), assumeNonNull(getStringProperty(DEFAULT_BOUNCETO_ADDRESS)));
			}
			if (hasProperty(DEFAULT_TO_ADDRESS)) {
				if (hasProperty(DEFAULT_TO_NAME)) {
					to(getStringProperty(DEFAULT_TO_NAME), getStringProperty(DEFAULT_TO_ADDRESS));
				} else {
					to(assumeNonNull(getStringProperty(DEFAULT_TO_ADDRESS)));
				}
			}
			if (hasProperty(DEFAULT_CC_ADDRESS)) {
				if (hasProperty(DEFAULT_CC_NAME)) {
					cc(getStringProperty(DEFAULT_CC_NAME), getStringProperty(DEFAULT_CC_ADDRESS));
				} else {
					cc(assumeNonNull(getStringProperty(DEFAULT_CC_ADDRESS)));
				}
			}
			if (hasProperty(DEFAULT_BCC_ADDRESS)) {
				if (hasProperty(DEFAULT_BCC_NAME)) {
					bcc(getStringProperty(DEFAULT_BCC_NAME), getStringProperty(DEFAULT_BCC_ADDRESS));
				} else {
					bcc(assumeNonNull(getStringProperty(DEFAULT_BCC_ADDRESS)));
				}
			}
			if (hasProperty(DEFAULT_SUBJECT)) {
				withSubject((String) getProperty(DEFAULT_SUBJECT));
			}
			if (hasProperty(SMIME_SIGNING_KEYSTORE)) {
				signWithSmime(Pkcs12Config.builder()
						.pkcs12Store(assumeNonNull(getStringProperty(SMIME_SIGNING_KEYSTORE)))
						.storePassword(checkNonEmptyArgument(getStringProperty(SMIME_SIGNING_KEYSTORE_PASSWORD), "Keystore password property"))
						.keyAlias(checkNonEmptyArgument(getStringProperty(SMIME_SIGNING_KEY_ALIAS), "Key alias property"))
						.keyPassword(checkNonEmptyArgument(getStringProperty(SMIME_SIGNING_KEYSTORE_PASSWORD), "Key password property"))
						.build());
			}
			if (hasProperty(SMIME_ENCRYPTION_CERTIFICATE)) {
				encryptWithSmime(assumeNonNull(getStringProperty(SMIME_ENCRYPTION_CERTIFICATE)));
			}
		}
	}
	
	/**
	 * @see EmailPopulatingBuilder#buildEmail()
	 */
	@Override
	@Cli.ExcludeApi(reason = "This API is specifically for Java use")
	public Email buildEmail() {
		validateDkim();
		return new Email(this);
	}

	private void validateDkim() {
		if (getDkimPrivateKeyFile() != null || getDkimPrivateKeyInputStream() != null) {
			checkNonEmptyArgument(getDkimSelector(), "dkimSelector");
			checkNonEmptyArgument(getDkimSigningDomain(), "dkimSigningDomain");
			checkNonEmptyArgument(getFromRecipient(), "fromRecipient required when signing DKIM");
		}
	}

	/**
	 * @see EmailPopulatingBuilder#fixingMessageId(String)
	 */
	@Override
	public EmailPopulatingBuilder fixingMessageId(@Nullable final String id) {
		this.id = id;
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#from(String)
	 */
	@Override
	@Cli.ExcludeApi(reason = "API is subset of another API")
	public EmailPopulatingBuilder from(@NotNull final String fromAddress) {
		return from(null, fromAddress);
	}
	
	/**
	 * @see EmailPopulatingBuilder#from(String, String)
	 */
	@Override
	public EmailPopulatingBuilder from(@Nullable final String name, @NotNull final String fromAddress) {
		return from(new Recipient(name, checkNonEmptyArgument(fromAddress, "fromAddress"), null));
	}
	
	/**
	 * @see EmailPopulatingBuilder#from(String, InternetAddress)
	 */
	@Override
	public EmailPopulatingBuilder from(@Nullable final String fixedName, @NotNull final InternetAddress fromAddress) {
		checkNonEmptyArgument(fromAddress, "fromAddress");
		return from(new Recipient(fixedName, fromAddress.getAddress(), null));
	}
	
	/**
	 * @see EmailPopulatingBuilder#from(InternetAddress)
	 */
	@Override
	public EmailPopulatingBuilder from(@NotNull final InternetAddress fromAddress) {
		checkNonEmptyArgument(fromAddress, "fromAddress");
		return from(new Recipient(fromAddress.getPersonal(), fromAddress.getAddress(), null));
	}
	
	/**
	 * @see EmailPopulatingBuilder#from(Recipient)
	 */
	@Override
	public EmailPopulatingBuilder from(@NotNull final Recipient recipient) {
		checkNonEmptyArgument(recipient, "from recipient");
		this.fromRecipient = new Recipient(recipient.getName(), recipient.getAddress(), null);
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withReplyTo(String)
	 */
	@Override
	@Cli.ExcludeApi(reason = "API is subset of another API")
	public EmailPopulatingBuilder withReplyTo(@Nullable final String replyToAddress) {
		return withReplyTo(replyToAddress != null ? new Recipient(null, replyToAddress, null) : null);
	}
	
	/**
	 * @see EmailPopulatingBuilder#withReplyTo(String, String)
	 */
	@Override
	public EmailPopulatingBuilder withReplyTo(@Nullable final String fixedName, @NotNull final String replyToAddress) {
		checkNonEmptyArgument(replyToAddress, "replyToAddress");
		return withReplyTo(new Recipient(fixedName, replyToAddress, null));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withReplyTo(InternetAddress)
	 */
	@Override
	public EmailPopulatingBuilder withReplyTo(@NotNull final InternetAddress replyToAddress) {
		checkNonEmptyArgument(replyToAddress, "replyToAddress");
		return withReplyTo(new Recipient(replyToAddress.getPersonal(), replyToAddress.getAddress(), null));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withReplyTo(String, InternetAddress)
	 */
	@Override
	public EmailPopulatingBuilder withReplyTo(@Nullable final String fixedName, @NotNull final InternetAddress replyToAddress) {
		checkNonEmptyArgument(replyToAddress, "replyToAddress");
		return withReplyTo(new Recipient(fixedName, replyToAddress.getAddress(), null));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withReplyTo(Recipient)
	 */
	@Override
	public EmailPopulatingBuilder withReplyTo(@Nullable final Recipient recipient) {
		this.replyToRecipient = recipient != null ? new Recipient(recipient.getName(), recipient.getAddress(), null) : null;
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withBounceTo(String)
	 */
	@Override
	@Cli.ExcludeApi(reason = "API is subset of another API")
	public EmailPopulatingBuilder withBounceTo(@Nullable final String bounceToAddress) {
		return withBounceTo(bounceToAddress != null ? new Recipient(null, bounceToAddress, null) : null);
	}
	
	/**
	 * @see EmailPopulatingBuilder#withBounceTo(String, String)
	 */
	@Override
	public EmailPopulatingBuilder withBounceTo(@Nullable final String name, @NotNull final String bounceToAddress) {
		return withBounceTo(new Recipient(name, checkNonEmptyArgument(bounceToAddress, "bounceToAddress"), null));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withBounceTo(InternetAddress)
	 */
	@Override
	public EmailPopulatingBuilder withBounceTo(@NotNull final InternetAddress bounceToAddress) {
		checkNonEmptyArgument(bounceToAddress, "bounceToAddress");
		return withBounceTo(new Recipient(bounceToAddress.getPersonal(), bounceToAddress.getAddress(), null));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withBounceTo(String, InternetAddress)
	 */
	@Override
	@Cli.ExcludeApi(reason = "Method is not detailed enough for CLI")
	public EmailPopulatingBuilder withBounceTo(@Nullable final String name, @NotNull final InternetAddress bounceToAddress) {
		checkNonEmptyArgument(bounceToAddress, "bounceToAddress");
		return withBounceTo(new Recipient(name, bounceToAddress.getAddress(), null));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withBounceTo(Recipient)
	 */
	@Override
	public EmailPopulatingBuilder withBounceTo(@Nullable final Recipient recipient) {
		this.bounceToRecipient = recipient != null ? new Recipient(recipient.getName(), recipient.getAddress(), null) : null;
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withSubject(String)
	 */
	@Override
	public EmailPopulatingBuilder withSubject(@Nullable final String subject) {
		this.subject = subject;
		return this;
	}
	
	/**
	 * @see EmailStartingBuilder#forwarding(MimeMessage)
	 */
	@NotNull
	public InternalEmailPopulatingBuilder withForward(@Nullable final MimeMessage emailMessageToForward) {
		this.emailToForward = emailMessageToForward;
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withPlainText(File)
	 */
	@Override
	@Cli.OptionNameOverride("withPlainTextFromFile")
	public EmailPopulatingBuilder withPlainText(@NotNull final File textFile) {
		try {
			return withPlainText(MiscUtil.readFileContent(textFile));
		} catch (IOException e) {
			throw new EmailException(format(ERROR_READING_FROM_FILE, textFile), e);
		}
	}
	
	/**
	 * @see EmailPopulatingBuilder#withPlainText(String)
	 */
	@Override
	public EmailPopulatingBuilder withPlainText(@Nullable final String text) {
		this.text = text;
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#prependText(File)
	 */
	@Override
	@Cli.OptionNameOverride("prependTextFromFile")
	public EmailPopulatingBuilder prependText(@NotNull final File textFile) {
		try {
			return prependText(MiscUtil.readFileContent(textFile));
		} catch (IOException e) {
			throw new EmailException(format(ERROR_READING_FROM_FILE, textFile), e);
		}
	}
	
	/**
	 * @see EmailPopulatingBuilder#prependText(String)
	 */
	@Override
	public EmailPopulatingBuilder prependText(@NotNull final String text) {
		this.text = text + defaultTo(this.text, "");
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#appendText(File)
	 */
	@Override
	@Cli.OptionNameOverride("appendTextFromFile")
	public EmailPopulatingBuilder appendText(@NotNull final File textFile) {
		try {
			return appendText(MiscUtil.readFileContent(textFile));
		} catch (IOException e) {
			throw new EmailException(format(ERROR_READING_FROM_FILE, textFile), e);
		}
	}
	
	/**
	 * @see EmailPopulatingBuilder#appendText(String)
	 */
	@Override
	public EmailPopulatingBuilder appendText(@NotNull final String text) {
		this.text = defaultTo(this.text, "") + text;
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withHTMLText(File)
	 */
	@Override
	@Cli.OptionNameOverride("withHTMLTextFromFile")
	public EmailPopulatingBuilder withHTMLText(@NotNull final File textHTMLFile) {
		try {
			return withHTMLText(MiscUtil.readFileContent(textHTMLFile));
		} catch (IOException e) {
			throw new EmailException(format(ERROR_READING_FROM_FILE, textHTMLFile), e);
		}
	}
	
	/**
	 * @see EmailPopulatingBuilder#withHTMLText(String)
	 */
	@Override
	public EmailPopulatingBuilder withHTMLText(@Nullable final String textHTML) {
		this.textHTML = textHTML;
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#prependTextHTML(File)
	 */
	@Override
	@Cli.OptionNameOverride("prependTextHTMLFromFile")
	public EmailPopulatingBuilder prependTextHTML(@NotNull final File textHTMLFile) {
		try {
			return prependTextHTML(MiscUtil.readFileContent(textHTMLFile));
		} catch (IOException e) {
			throw new EmailException(format(ERROR_READING_FROM_FILE, textHTMLFile), e);
		}
	}
	
	/**
	 * @see EmailPopulatingBuilder#prependTextHTML(String)
	 */
	@Override
	public EmailPopulatingBuilder prependTextHTML(@NotNull final String textHTML) {
		this.textHTML = textHTML + defaultTo(this.textHTML, "");
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#appendTextHTML(File)
	 */
	@Override
	@Cli.OptionNameOverride("appendTextHTMLFromFile")
	public EmailPopulatingBuilder appendTextHTML(@NotNull final File textHTMLFile) {
		try {
			return appendTextHTML(MiscUtil.readFileContent(textHTMLFile));
		} catch (IOException e) {
			throw new EmailException(format(ERROR_READING_FROM_FILE, textHTMLFile), e);
		}
	}
	
	/**
	 * @see EmailPopulatingBuilder#appendTextHTML(String)
	 */
	@Override
	public EmailPopulatingBuilder appendTextHTML(@NotNull final String textHTML) {
		this.textHTML = defaultTo(this.textHTML, "") + textHTML;
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withCalendarText(CalendarMethod, String)
	 */
	@Override
	public EmailPopulatingBuilder withCalendarText(@NotNull final CalendarMethod calendarMethod, @NotNull final String textCalendar) {
		this.calendarMethod = calendarMethod;
		this.textCalendar = textCalendar;
		return this;
	}
	
	/*
		TO: Recipient
	 */
	
	/**
	 * @see EmailPopulatingBuilder#to(Recipient...)
	 */
	@Override
	public EmailPopulatingBuilder to(@NotNull final Recipient... recipients) {
		return withRecipients(asList(recipients), TO);
	}
	
	/**
	 * @see EmailPopulatingBuilder#to(Collection)
	 */
	@Override
	public EmailPopulatingBuilder to(@NotNull final Collection<Recipient> recipients) {
		return withRecipients(recipients, TO);
	}
	/*
		TO: String
	 */
	
	/**
	 * @see EmailPopulatingBuilder#to(String, String)
	 */
	@Override
	public EmailPopulatingBuilder to(@Nullable final String name, String oneOrMoreAddresses) {
		return withRecipients(name, true, singletonList(oneOrMoreAddresses), TO);
	}
	
	/**
	 * @see EmailPopulatingBuilder#to(String)
	 */
	@Override
	@Cli.ExcludeApi(reason = "API is subset of another API method")
	public EmailPopulatingBuilder to(@NotNull final String oneOrMoreAddresses) {
		return withRecipientsWithDefaultName(null, singletonList(oneOrMoreAddresses), TO);
	}
	
	/**
	 * @see EmailPopulatingBuilder#to(String, String...)
	 */
	@Override
	public EmailPopulatingBuilder to(@Nullable final String name, @NotNull final String... oneOrMoreAddressesEach) {
		return toWithFixedName(name, oneOrMoreAddressesEach);
	}
	
	/**
	 * @see EmailPopulatingBuilder#to(String, Collection)
	 */
	@Override
	public EmailPopulatingBuilder to(@Nullable final String name, @NotNull final Collection<String> oneOrMoreAddressesEach) {
		return toWithFixedName(name, oneOrMoreAddressesEach);
	}
	
	/**
	 * @see EmailPopulatingBuilder#toMultiple(String...)
	 */
	@Override
	public EmailPopulatingBuilder toMultiple(@NotNull final String... oneOrMoreAddressesEach) {
		return withRecipientsWithDefaultName(null, asList(oneOrMoreAddressesEach), TO);
	}
	
	/**
	 * @see EmailPopulatingBuilder#toMultiple(Collection)
	 */
	@Override
	public EmailPopulatingBuilder toMultiple(@NotNull final Collection<String> oneOrMoreAddressesEach) {
		return withRecipientsWithDefaultName(null, oneOrMoreAddressesEach, TO);
	}
	
	/**
	 * @see EmailPopulatingBuilder#toWithFixedName(String, String...)
	 */
	@Override
	public EmailPopulatingBuilder toWithFixedName(@Nullable final String name, @NotNull final String... oneOrMoreAddressesEach) {
		return withRecipientsWithFixedName(name, asList(oneOrMoreAddressesEach), TO);
	}
	
	/**
	 * @see EmailPopulatingBuilder#toWithDefaultName(String, String...)
	 */
	@Override
	public EmailPopulatingBuilder toWithDefaultName(@NotNull final String name, @NotNull final String... oneOrMoreAddressesEach) {
		return withRecipientsWithDefaultName(name, asList(oneOrMoreAddressesEach), TO);
	}
	
	/**
	 * @see EmailPopulatingBuilder#toWithFixedName(String, Collection)
	 */
	@Override
	public EmailPopulatingBuilder toWithFixedName(@Nullable final String name, @NotNull final Collection<String> oneOrMoreAddressesEach) {
		return withRecipientsWithFixedName(name, oneOrMoreAddressesEach, TO);
	}
	
	/**
	 * @see EmailPopulatingBuilder#toWithDefaultName(String, Collection)
	 */
	@Override
	public EmailPopulatingBuilder toWithDefaultName(@NotNull final String name, @NotNull final Collection<String> oneOrMoreAddressesEach) {
		return withRecipientsWithDefaultName(name, oneOrMoreAddressesEach, TO);
	}
	
	/*
		TO: InternetAddress
	 */
	
	/**
	 * @see EmailPopulatingBuilder#to(String, InternetAddress)
	 */
	@Override
	public EmailPopulatingBuilder to(@Nullable final String name, InternetAddress address) {
		return toAddressesWithFixedName(name, address);
	}
	
	/**
	 * @see EmailPopulatingBuilder#to(InternetAddress)
	 */
	@Override
	public EmailPopulatingBuilder to(@NotNull final InternetAddress address) {
		return withAddressesWithDefaultName(null, singletonList(address), TO);
	}
	
	/**
	 * @see EmailPopulatingBuilder#to(String, InternetAddress...)
	 */
	@Override
	public EmailPopulatingBuilder to(@Nullable final String name, @NotNull final InternetAddress... oneOrMoreAddressesEach) {
		return toAddressesWithFixedName(name, oneOrMoreAddressesEach);
	}
	
	/**
	 * @see EmailPopulatingBuilder#toAddresses(String, Collection)
	 */
	@Override
	public EmailPopulatingBuilder toAddresses(@Nullable final String name, @NotNull final Collection<InternetAddress> oneOrMoreAddressesEach) {
		return toAddressesWithFixedName(name, oneOrMoreAddressesEach);
	}
	
	/**
	 * @see EmailPopulatingBuilder#toMultiple(InternetAddress...)
	 */
	@Override
	public EmailPopulatingBuilder toMultiple(@NotNull final InternetAddress... oneOrMoreAddressesEach) {
		return withAddressesWithDefaultName(null, asList(oneOrMoreAddressesEach), TO);
	}
	
	/**
	 * @see EmailPopulatingBuilder#toMultipleAddresses(Collection)
	 */
	@Override
	public EmailPopulatingBuilder toMultipleAddresses(@NotNull final Collection<InternetAddress> oneOrMoreAddressesEach) {
		return withAddressesWithDefaultName(null, oneOrMoreAddressesEach, TO);
	}
	
	/**
	 * @see EmailPopulatingBuilder#toAddressesWithFixedName(String, InternetAddress...)
	 */
	@Override
	public EmailPopulatingBuilder toAddressesWithFixedName(@Nullable final String name, @NotNull final InternetAddress... oneOrMoreAddressesEach) {
		return withAddressesWithFixedName(name, asList(oneOrMoreAddressesEach), TO);
	}
	
	/**
	 * @see EmailPopulatingBuilder#toAddressesWithDefaultName(String, InternetAddress...)
	 */
	@Override
	public EmailPopulatingBuilder toAddressesWithDefaultName(@NotNull final String name, @NotNull final InternetAddress... oneOrMoreAddressesEach) {
		return withAddressesWithDefaultName(name, asList(oneOrMoreAddressesEach), TO);
	}
	
	/**
	 * @see EmailPopulatingBuilder#toAddressesWithFixedName(String, Collection)
	 */
	@Override
	public EmailPopulatingBuilder toAddressesWithFixedName(@Nullable final String name, @NotNull final Collection<InternetAddress> oneOrMoreAddressesEach) {
		return withAddressesWithFixedName(name, oneOrMoreAddressesEach, TO);
	}
	
	/**
	 * @see EmailPopulatingBuilder#toAddressesWithDefaultName(String, Collection)
	 */
	@Override
	public EmailPopulatingBuilder toAddressesWithDefaultName(@NotNull final String name, @NotNull final Collection<InternetAddress> oneOrMoreAddressesEach) {
		return withAddressesWithDefaultName(name, oneOrMoreAddressesEach, TO);
	}
	/*
		CC: Recipient
	 */
	
	/**
	 * @see EmailPopulatingBuilder#cc(Recipient...)
	 */
	@Override
	public EmailPopulatingBuilder cc(@NotNull final Recipient... recipients) {
		return withRecipients(asList(recipients), CC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#cc(Collection)
	 */
	@Override
	public EmailPopulatingBuilder cc(@NotNull final Collection<Recipient> recipients) {
		return withRecipients(recipients, CC);
	}
	/*
		CC: String
	 */
	
	/**
	 * @see EmailPopulatingBuilder#cc(String, String)
	 */
	@Override
	public EmailPopulatingBuilder cc(@Nullable final String name, String oneOrMoreAddresses) {
		return withRecipients(name, true, singletonList(oneOrMoreAddresses), CC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#cc(String)
	 */
	@Override
	@Cli.ExcludeApi(reason = "API is subset of another API method")
	public EmailPopulatingBuilder cc(@NotNull final String oneOrMoreAddresses) {
		return withRecipientsWithDefaultName(null, singletonList(oneOrMoreAddresses), CC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#cc(String, String...)
	 */
	@Override
	public EmailPopulatingBuilder cc(@Nullable final String name, @NotNull final String... oneOrMoreAddressesEach) {
		return ccWithFixedName(name, oneOrMoreAddressesEach);
	}
	
	/**
	 * @see EmailPopulatingBuilder#cc(String, Collection)
	 */
	@Override
	public EmailPopulatingBuilder cc(@Nullable final String name, @NotNull final Collection<String> oneOrMoreAddressesEach) {
		return ccWithFixedName(name, oneOrMoreAddressesEach);
	}
	
	/**
	 * @see EmailPopulatingBuilder#ccMultiple(String...)
	 */
	@Override
	public EmailPopulatingBuilder ccMultiple(@NotNull final String... oneOrMoreAddressesEach) {
		return withRecipientsWithDefaultName(null, asList(oneOrMoreAddressesEach), CC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#ccAddresses(Collection)
	 */
	@Override
	public EmailPopulatingBuilder ccAddresses(@NotNull final Collection<String> oneOrMoreAddressesEach) {
		return withRecipientsWithDefaultName(null, oneOrMoreAddressesEach, CC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#ccWithFixedName(String, String...)
	 */
	@Override
	public EmailPopulatingBuilder ccWithFixedName(@Nullable final String name, @NotNull final String... oneOrMoreAddressesEach) {
		return withRecipientsWithFixedName(name, asList(oneOrMoreAddressesEach), CC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#ccWithDefaultName(String, String...)
	 */
	@Override
	public EmailPopulatingBuilder ccWithDefaultName(@NotNull final String name, @NotNull final String... oneOrMoreAddressesEach) {
		return withRecipientsWithDefaultName(name, asList(oneOrMoreAddressesEach), CC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#ccWithFixedName(String, Collection)
	 */
	@Override
	public EmailPopulatingBuilder ccWithFixedName(@Nullable final String name, @NotNull final Collection<String> oneOrMoreAddressesEach) {
		return withRecipientsWithFixedName(name, oneOrMoreAddressesEach, CC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#ccWithDefaultName(String, Collection)
	 */
	@Override
	public EmailPopulatingBuilder ccWithDefaultName(@NotNull final String name, @NotNull final Collection<String> oneOrMoreAddressesEach) {
		return withRecipientsWithDefaultName(name, oneOrMoreAddressesEach, CC);
	}
	
	/*
		CC: InternetAddress
	 */
	
	/**
	 * @see EmailPopulatingBuilder#cc(String, InternetAddress)
	 */
	@Override
	public EmailPopulatingBuilder cc(@Nullable final String name, InternetAddress address) {
		return ccAddressesWithFixedName(name, address);
	}
	
	/**
	 * @see EmailPopulatingBuilder#cc(InternetAddress)
	 */
	@Override
	public EmailPopulatingBuilder cc(@NotNull final InternetAddress address) {
		return withAddressesWithDefaultName(null, singletonList(address), CC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#cc(String, InternetAddress...)
	 */
	@Override
	public EmailPopulatingBuilder cc(@Nullable final String name, @NotNull final InternetAddress... oneOrMoreAddressesEach) {
		return ccAddressesWithFixedName(name, oneOrMoreAddressesEach);
	}
	
	/**
	 * @see EmailPopulatingBuilder#ccAddresses(String, Collection)
	 */
	@Override
	public EmailPopulatingBuilder ccAddresses(@Nullable final String name, @NotNull final Collection<InternetAddress> oneOrMoreAddressesEach) {
		return ccAddressesWithFixedName(name, oneOrMoreAddressesEach);
	}
	
	/**
	 * @see EmailPopulatingBuilder#ccMultiple(InternetAddress...)
	 */
	@Override
	public EmailPopulatingBuilder ccMultiple(@NotNull final InternetAddress... oneOrMoreAddressesEach) {
		return withAddressesWithDefaultName(null, asList(oneOrMoreAddressesEach), CC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#ccMultipleAddresses(Collection)
	 */
	@Override
	public EmailPopulatingBuilder ccMultipleAddresses(@NotNull final Collection<InternetAddress> oneOrMoreAddressesEach) {
		return withAddressesWithDefaultName(null, oneOrMoreAddressesEach, CC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#ccAddressesWithFixedName(String, InternetAddress...)
	 */
	@Override
	public EmailPopulatingBuilder ccAddressesWithFixedName(@Nullable final String name, @NotNull final InternetAddress... oneOrMoreAddressesEach) {
		return withAddressesWithFixedName(name, asList(oneOrMoreAddressesEach), CC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#ccAddressesWithDefaultName(String, InternetAddress...)
	 */
	@Override
	public EmailPopulatingBuilder ccAddressesWithDefaultName(@NotNull final String name, @NotNull final InternetAddress... oneOrMoreAddressesEach) {
		return withAddressesWithDefaultName(name, asList(oneOrMoreAddressesEach), CC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#ccAddressesWithFixedName(String, Collection)
	 */
	@Override
	public EmailPopulatingBuilder ccAddressesWithFixedName(@Nullable final String name, @NotNull final Collection<InternetAddress> oneOrMoreAddressesEach) {
		return withAddressesWithFixedName(name, oneOrMoreAddressesEach, CC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#ccAddressesWithDefaultName(String, Collection)
	 */
	@Override
	public EmailPopulatingBuilder ccAddressesWithDefaultName(@NotNull final String name, @NotNull final Collection<InternetAddress> oneOrMoreAddressesEach) {
		return withAddressesWithDefaultName(name, oneOrMoreAddressesEach, CC);
	}
	/*
		BCC: Recipient
	 */
	
	/**
	 * @see EmailPopulatingBuilder#bcc(Recipient...)
	 */
	@Override
	public EmailPopulatingBuilder bcc(@NotNull final Recipient... recipients) {
		return withRecipients(asList(recipients), BCC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bcc(Collection)
	 */
	@Override
	public EmailPopulatingBuilder bcc(@NotNull final Collection<Recipient> recipients) {
		return withRecipients(recipients, BCC);
	}
	
	/*
		BCC: String
	 */
	
	/**
	 * @see EmailPopulatingBuilder#bcc(String, String)
	 */
	@Override
	public EmailPopulatingBuilder bcc(@Nullable final String name, String oneOrMoreAddresses) {
		return withRecipients(name, true, singletonList(oneOrMoreAddresses), BCC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bcc(String)
	 */
	@Override
	@Cli.ExcludeApi(reason = "API is subset of another API")
	public EmailPopulatingBuilder bcc(@NotNull final String oneOrMoreAddresses) {
		return withRecipientsWithDefaultName(null, singletonList(oneOrMoreAddresses), BCC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bcc(String, String...)
	 */
	@Override
	public EmailPopulatingBuilder bcc(@Nullable final String name, @NotNull final String... oneOrMoreAddressesEach) {
		return bccWithFixedName(name, oneOrMoreAddressesEach);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bcc(String, Collection)
	 */
	@Override
	public EmailPopulatingBuilder bcc(@Nullable final String name, @NotNull final Collection<String> oneOrMoreAddressesEach) {
		return bccWithFixedName(name, oneOrMoreAddressesEach);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bccMultiple(String...)
	 */
	@Override
	public EmailPopulatingBuilder bccMultiple(@NotNull final String... oneOrMoreAddressesEach) {
		return withRecipientsWithDefaultName(null, asList(oneOrMoreAddressesEach), BCC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bccAddresses(Collection)
	 */
	@Override
	public EmailPopulatingBuilder bccAddresses(@NotNull final Collection<String> oneOrMoreAddressesEach) {
		return withRecipientsWithDefaultName(null, oneOrMoreAddressesEach, BCC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bccWithFixedName(String, String...)
	 */
	@Override
	public EmailPopulatingBuilder bccWithFixedName(@Nullable final String name, @NotNull final String... oneOrMoreAddressesEach) {
		return withRecipientsWithFixedName(name, asList(oneOrMoreAddressesEach), BCC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bccWithDefaultName(String, String...)
	 */
	@Override
	public EmailPopulatingBuilder bccWithDefaultName(@NotNull final String name, @NotNull final String... oneOrMoreAddressesEach) {
		return withRecipientsWithDefaultName(name, asList(oneOrMoreAddressesEach), BCC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bccWithFixedName(String, Collection)
	 */
	@Override
	public EmailPopulatingBuilder bccWithFixedName(@Nullable final String name, @NotNull final Collection<String> oneOrMoreAddressesEach) {
		return withRecipientsWithFixedName(name, oneOrMoreAddressesEach, BCC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bccWithDefaultName(String, Collection)
	 */
	@Override
	public EmailPopulatingBuilder bccWithDefaultName(@NotNull final String name, @NotNull final Collection<String> oneOrMoreAddressesEach) {
		return withRecipientsWithDefaultName(name, oneOrMoreAddressesEach, BCC);
	}
	
	/*
		BCC: InternetAddress
	 */
	
	/**
	 * @see EmailPopulatingBuilder#bcc(String, InternetAddress)
	 */
	@Override
	public EmailPopulatingBuilder bcc(@Nullable final String name, InternetAddress address) {
		return bccAddressesWithFixedName(name, address);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bcc(InternetAddress)
	 */
	@Override
	public EmailPopulatingBuilder bcc(@NotNull final InternetAddress address) {
		return withAddressesWithDefaultName(null, singletonList(address), BCC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bcc(String, InternetAddress...)
	 */
	@Override
	public EmailPopulatingBuilder bcc(@Nullable final String name, @NotNull final InternetAddress... oneOrMoreAddressesEach) {
		return bccAddressesWithFixedName(name, oneOrMoreAddressesEach);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bccAddresses(String, Collection)
	 */
	@Override
	public EmailPopulatingBuilder bccAddresses(@Nullable final String name, @NotNull final Collection<InternetAddress> oneOrMoreAddressesEach) {
		return bccAddressesWithFixedName(name, oneOrMoreAddressesEach);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bccMultiple(InternetAddress...)
	 */
	@Override
	public EmailPopulatingBuilder bccMultiple(@NotNull final InternetAddress... oneOrMoreAddressesEach) {
		return withAddressesWithDefaultName(null, asList(oneOrMoreAddressesEach), BCC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bccMultipleAddresses(Collection)
	 */
	@Override
	public EmailPopulatingBuilder bccMultipleAddresses(@NotNull final Collection<InternetAddress> oneOrMoreAddressesEach) {
		return withAddressesWithDefaultName(null, oneOrMoreAddressesEach, BCC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bccAddressesWithFixedName(String, InternetAddress...)
	 */
	@Override
	public EmailPopulatingBuilder bccAddressesWithFixedName(@Nullable final String name, @NotNull final InternetAddress... oneOrMoreAddressesEach) {
		return withAddressesWithFixedName(name, asList(oneOrMoreAddressesEach), BCC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bccAddressesWithDefaultName(String, InternetAddress...)
	 */
	@Override
	public EmailPopulatingBuilder bccAddressesWithDefaultName(@NotNull final String name, @NotNull final InternetAddress... oneOrMoreAddressesEach) {
		return withAddressesWithDefaultName(name, asList(oneOrMoreAddressesEach), BCC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bccAddressesWithFixedName(String, Collection)
	 */
	@Override
	public EmailPopulatingBuilder bccAddressesWithFixedName(@Nullable final String name, @NotNull final Collection<InternetAddress> oneOrMoreAddressesEach) {
		return withAddressesWithFixedName(name, oneOrMoreAddressesEach, BCC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bccAddressesWithDefaultName(String, Collection)
	 */
	@Override
	public EmailPopulatingBuilder bccAddressesWithDefaultName(@NotNull final String name, @NotNull final Collection<InternetAddress> oneOrMoreAddressesEach) {
		return withAddressesWithDefaultName(name, oneOrMoreAddressesEach, BCC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#withRecipientsWithDefaultName(String, Collection, RecipientType)
	 */
	@Override
	@NotNull
	public EmailPopulatingBuilder withRecipientsWithDefaultName(@Nullable final String defaultName, @NotNull Collection<String> oneOrMoreAddressesEach, @Nullable RecipientType recipientType) {
		return withRecipients(defaultName, false, oneOrMoreAddressesEach, recipientType);
	}
	
	/**
	 * @see EmailPopulatingBuilder#withRecipientsWithFixedName(String, Collection, RecipientType)
	 */
	@Override
	@NotNull
	public EmailPopulatingBuilder withRecipientsWithFixedName(@Nullable final String fixedName, @NotNull Collection<String> oneOrMoreAddressesEach, @Nullable RecipientType recipientType) {
		return withRecipients(fixedName, true, oneOrMoreAddressesEach, recipientType);
	}
	
	/**
	 * @see EmailPopulatingBuilder#withRecipientsWithDefaultName(String, RecipientType, String...)
	 */
	@Override
	@NotNull
	public EmailPopulatingBuilder withRecipientsWithDefaultName(@Nullable String name, @Nullable RecipientType recipientType, @NotNull String... oneOrMoreAddressesEach) {
		return withRecipients(name, false, asList(oneOrMoreAddressesEach), recipientType);
	}
	
	/**
	 * @see EmailPopulatingBuilder#withRecipientsWithFixedName(String, RecipientType, String...)
	 */
	@Override
	@NotNull
	public EmailPopulatingBuilder withRecipientsWithFixedName(@Nullable String name, @Nullable RecipientType recipientType, @NotNull String... oneOrMoreAddressesEach) {
		return withRecipients(name, true, asList(oneOrMoreAddressesEach), recipientType);
	}
	
	/**
	 * @see EmailPopulatingBuilder#withRecipients(String, boolean, RecipientType, String...)
	 */
	@Override
	@NotNull
	public EmailPopulatingBuilder withRecipients(@Nullable String name, boolean fixedName, @Nullable RecipientType recipientType, @NotNull String... oneOrMoreAddressesEach) {
		return withRecipients(name, fixedName, asList(oneOrMoreAddressesEach), recipientType);
	}
	
	/**
	 * @see EmailPopulatingBuilder#withRecipients(String, boolean, Collection, RecipientType)
	 */
	@Override
	@NotNull
	public EmailPopulatingBuilder withRecipients(@Nullable String name, boolean fixedName, @NotNull Collection<String> oneOrMoreAddressesEach, @Nullable RecipientType recipientType) {
		for (String oneOrMoreAddresses : oneOrMoreAddressesEach) {
			for (String emailAddress : extractEmailAddresses(oneOrMoreAddresses)) {
				withRecipient(MiscUtil.interpretRecipient(name, fixedName, emailAddress, recipientType));
			}
		}
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withAddressesWithDefaultName(String, Collection, RecipientType)
	 */
	@Override
	@NotNull
	public EmailPopulatingBuilder withAddressesWithDefaultName(@Nullable final String defaultName, @NotNull Collection<InternetAddress> addresses, @Nullable RecipientType recipientType) {
		return withAddresses(defaultName, false, addresses, recipientType);
	}
	
	/**
	 * @see EmailPopulatingBuilder#withAddressesWithFixedName(String, Collection, RecipientType)
	 */
	@Override
	@NotNull
	public EmailPopulatingBuilder withAddressesWithFixedName(@Nullable final String fixedName, @NotNull Collection<InternetAddress> addresses, @Nullable RecipientType recipientType) {
		return withAddresses(fixedName, true, addresses, recipientType);
	}
	
	/**
	 * @see EmailPopulatingBuilder#withAddresses(String, boolean, Collection, RecipientType)
	 */
	@Override
	@NotNull
	public EmailPopulatingBuilder withAddresses(@Nullable String name, boolean fixedName, @NotNull Collection<InternetAddress> addresses, @Nullable RecipientType recipientType) {
		for (InternetAddress address : addresses) {
			String effectiveName = (fixedName || valueNullOrEmpty(address.getPersonal())) ? name : address.getPersonal();
			withRecipient(effectiveName, address.getAddress(), recipientType);
		}
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withRecipients(Collection)
	 */
	@Override
	public EmailPopulatingBuilder withRecipients(@NotNull final Collection<Recipient> recipients) {
		return withRecipients(recipients, null);
	}
	
	/**
	 * @see EmailPopulatingBuilder#withRecipients(Recipient...)
	 */
	@Override
	public EmailPopulatingBuilder withRecipients(@NotNull final Recipient... recipients) {
		return withRecipients(asList(recipients), null);
	}
	
	/**
	 * @see EmailPopulatingBuilder#withRecipients(Collection, RecipientType)
	 */
	@Override
	@NotNull
	public EmailPopulatingBuilder withRecipients(@NotNull Collection<Recipient> recipients, @Nullable RecipientType fixedRecipientType) {
		for (Recipient recipient : recipients) {
			withRecipient(recipient.getName(), recipient.getAddress(), defaultTo(fixedRecipientType, recipient.getType()));
		}
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withRecipient(String, RecipientType)
	 */
	@Override
	public EmailPopulatingBuilder withRecipient(@NotNull final String singleAddress, @Nullable final RecipientType recipientType) {
		return withRecipient(null, singleAddress, recipientType);
	}
	
	/**
	 * @see EmailPopulatingBuilder#withRecipient(String, String, RecipientType)
	 */
	@Override
	public EmailPopulatingBuilder withRecipient(@Nullable final String name, @NotNull final String singleAddress, @Nullable final RecipientType recipientType) {
		recipients.add(MiscUtil.interpretRecipient(name, true, singleAddress, recipientType));
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withRecipient(Recipient)
	 */
	@Override
	public EmailPopulatingBuilder withRecipient(@NotNull final Recipient recipient) {
		recipients.add(new Recipient(recipient.getName(), recipient.getAddress(), recipient.getType()));
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withEmbeddedImage(String, byte[], String)
	 */
	@Override
	public EmailPopulatingBuilder withEmbeddedImage(@NotNull final String name, @NotNull final byte[] data, @NotNull final String mimetype) {
		checkNonEmptyArgument(name, "name");
		checkNonEmptyArgument(data, "data");
		checkNonEmptyArgument(mimetype, "mimetype");
		
		final ByteArrayDataSource dataSource = new ByteArrayDataSource(data, mimetype);
		dataSource.setName(name);
		return withEmbeddedImage(name, dataSource);
	}
	
	/**
	 * @see EmailPopulatingBuilder#withEmbeddedImage(String, DataSource)
	 */
	@Override
	public EmailPopulatingBuilder withEmbeddedImage(@Nullable final String name, @NotNull final DataSource imagedata) {
		checkNonEmptyArgument(imagedata, "imagedata");
		if (valueNullOrEmpty(name) && valueNullOrEmpty(imagedata.getName())) {
			throw new EmailException(NAME_MISSING_FOR_EMBEDDED_IMAGE);
		}
		embeddedImages.add(new AttachmentResource(name, imagedata));
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withEmbeddedImages(List)
	 */
	@Override
	public EmailPopulatingBuilder withEmbeddedImages(@NotNull final List<AttachmentResource> embeddedImages) {
		for (final AttachmentResource embeddedImage : embeddedImages) {
			withEmbeddedImage(embeddedImage.getName(), embeddedImage.getDataSource());
		}
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#withHeaders(Map)
	 */
	@Override
	public <T> EmailPopulatingBuilder withHeaders(@NotNull final Map<String, T> headers) {
		return withHeaders(headers, false);
	}

	/**
	 * Copies headers, but if required will ignore generated Message-ID header, which should not be copied to a new message. This
	 * Message ID was never meant to be used for sending messages, but for handling nested S/MIME encrypted message attachments.
	 *
	 * @see EmailPopulatingBuilder#withHeaders(Map)
	 */
	@NotNull
	public <T> InternalEmailPopulatingBuilder withHeaders(@NotNull final Map<String, T> headers, final boolean ignoreSmimeMessageId) {
		for (Map.Entry<String, T> headerEntry : headers.entrySet()) {
			if (!ignoreSmimeMessageId || !isGeneratedSmimeMessageId(headerEntry)) {
				withHeader(headerEntry.getKey(), headerEntry.getValue());
			}
		}
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#withHeader(String, Object)
	 */
	@Override
	public EmailPopulatingBuilder withHeader(@NotNull final String name, @Nullable final Object value) {
		checkNonEmptyArgument(name, "name");
		headers.put(name, value != null ? String.valueOf(value) : null);
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withAttachment(String, byte[], String)
	 */
	@Override
	public EmailPopulatingBuilder withAttachment(@Nullable final String name, @NotNull final byte[] data, @NotNull final String mimetype) {
		checkNonEmptyArgument(data, "data");
		checkNonEmptyArgument(mimetype, "mimetype");
		final ByteArrayDataSource dataSource = new ByteArrayDataSource(data, mimetype);
		dataSource.setName(name);
		withAttachment(name, dataSource);
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withAttachment(String, DataSource)
	 */
	@Override
	public EmailPopulatingBuilder withAttachment(@Nullable final String name, @NotNull final DataSource filedata) {
		checkNonEmptyArgument(filedata, "filedata");
		attachments.add(new AttachmentResource(name, filedata));
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#withAttachments(List)
	 */
	@Override
	public EmailPopulatingBuilder withAttachments(@NotNull final List<AttachmentResource> attachments) {
		for (final AttachmentResource attachment : attachments) {
			withAttachment(attachment.getName(), attachment.getDataSource());
		}
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#getDecryptedAttachments()
	 */
	@NotNull
	@Override
	public InternalEmailPopulatingBuilder withDecryptedAttachments(@NotNull final List<AttachmentResource> attachments) {
		decryptedAttachments.addAll(attachments);
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#signWithDomainKey(byte[], String, String)
	 */
	@Override
	@Cli.ExcludeApi(reason = "delegated method is an identical api from CLI point of view")
	public EmailPopulatingBuilder signWithDomainKey(@NotNull final byte[] dkimPrivateKey, @NotNull final String signingDomain, @NotNull final String dkimSelector) {
		checkNonEmptyArgument(dkimPrivateKey, "dkimPrivateKey");
		return signWithDomainKey(new ByteArrayInputStream(dkimPrivateKey), signingDomain, dkimSelector);
	}
	
	/**
	 * @see EmailPopulatingBuilder#signWithDomainKey(String, String, String)
	 */
	@Override
	@Cli.ExcludeApi(reason = "delegated method is an identical api from CLI point of view")
	public EmailPopulatingBuilder signWithDomainKey(@NotNull final String dkimPrivateKey, @NotNull final String signingDomain, @NotNull final String dkimSelector) {
		checkNonEmptyArgument(dkimPrivateKey, "dkimPrivateKey");
		return signWithDomainKey(new ByteArrayInputStream(dkimPrivateKey.getBytes(UTF_8)), signingDomain, dkimSelector);
	}
	
	/**
	 * @see EmailPopulatingBuilder#signWithDomainKey(InputStream, String, String)
	 */
	@Override
	public EmailPopulatingBuilder signWithDomainKey(@NotNull final InputStream dkimPrivateKeyInputStream, @NotNull final String signingDomain,
													@NotNull final String dkimSelector) {
		this.dkimPrivateKeyInputStream = checkNonEmptyArgument(dkimPrivateKeyInputStream, "dkimPrivateKeyInputStream");
		this.dkimSigningDomain = checkNonEmptyArgument(signingDomain, "dkimSigningDomain");
		this.dkimSelector = checkNonEmptyArgument(dkimSelector, "dkimSelector");
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#signWithDomainKey(File, String, String)
	 */
	@Override
	@Cli.ExcludeApi(reason = "delegated method is an identical api from CLI point of view")
	public EmailPopulatingBuilder signWithDomainKey(@NotNull final File dkimPrivateKeyFile, @NotNull final String signingDomain, @NotNull final String dkimSelector) {
		this.dkimPrivateKeyFile = checkNonEmptyArgument(dkimPrivateKeyFile, "dkimPrivateKeyFile");
		this.dkimSigningDomain = checkNonEmptyArgument(signingDomain, "dkimSigningDomain");
		this.dkimSelector = checkNonEmptyArgument(dkimSelector, "dkimSelector");
		return this;
	}

	/**
	 * @param pkcs12StoreFile The file containing the keystore
	 * @param storePassword  The password to get keys from the store
	 * @param keyAlias The key we need for signing
	 * @param keyPassword The password for the key
	 *
	 * @see EmailPopulatingBuilder#signWithSmime(File, String, String, String)
	 */
	@Override
	@SuppressFBWarnings(value = "OBL_UNSATISFIED_OBLIGATION", justification = "Input stream being created should not be closed here")
	public EmailPopulatingBuilder signWithSmime(@NotNull final File pkcs12StoreFile, @NotNull final String storePassword, @NotNull final String keyAlias, @NotNull final String keyPassword) {
		try {
			return signWithSmime(new FileInputStream(pkcs12StoreFile), storePassword, keyAlias, keyPassword);
		} catch (FileNotFoundException e) {
			throw new EmailException(format(ERROR_READING_FROM_FILE, pkcs12StoreFile), e);
		}
	}

	/**
	 * @param pkcs12StoreStream The data (file) input stream containing the keystore
	 * @param storePassword  The password to get keys from the store
	 * @param keyAlias The key we need for signing
	 * @param keyPassword The password for the key
	 *
	 * @see EmailPopulatingBuilder#signWithSmime(InputStream, String, String, String)
	 */
	@Override
	public EmailPopulatingBuilder signWithSmime(@NotNull final InputStream pkcs12StoreStream, @NotNull final String storePassword, @NotNull final String keyAlias, @NotNull final String keyPassword) {
		return signWithSmime(Pkcs12Config.builder()
				.pkcs12Store(pkcs12StoreStream)
				.storePassword(storePassword)
				.keyAlias(keyAlias)
				.keyPassword(keyPassword)
				.build());
	}

	/**
	 * @see EmailPopulatingBuilder#signWithSmime(Pkcs12Config)
	 */
	@Override
	public EmailPopulatingBuilder signWithSmime(@NotNull final Pkcs12Config pkcs12Config) {
		this.pkcs12ConfigForSmimeSigning = pkcs12Config;
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#encryptWithSmime(String)
	 */
	@Override
	@SuppressFBWarnings(value = "OBL_UNSATISFIED_OBLIGATION", justification = "Input stream being created should not be closed here")
	public EmailPopulatingBuilder encryptWithSmime(@NotNull final String pemFile) {
		try {
			return encryptWithSmime(new FileInputStream(new File(pemFile)));
		} catch (FileNotFoundException e) {
			throw new EmailException(format(ERROR_READING_FROM_FILE, pemFile), e);
		}
	}

	/**
	 * @see EmailPopulatingBuilder#encryptWithSmime(File)
	 */
	@Override
	@SuppressFBWarnings(value = "OBL_UNSATISFIED_OBLIGATION", justification = "Input stream being created should not be closed here")
	public EmailPopulatingBuilder encryptWithSmime(@NotNull final File pemFile) {
		try {
			return encryptWithSmime(new FileInputStream(pemFile));
		} catch (FileNotFoundException e) {
			throw new EmailException(format(ERROR_READING_FROM_FILE, pemFile), e);
		}
	}

	/**
	 * @see EmailPopulatingBuilder#encryptWithSmime(InputStream)
	 */
	@Override
	public EmailPopulatingBuilder encryptWithSmime(@NotNull final InputStream pemStream) {
		try {
			return encryptWithSmime(CertificationUtil.readFromPem(pemStream));
		} catch (CertificateException e) {
			throw new EmailException(ERROR_READING_FROM_PEM_INPUTSTREAM, e);
		} catch (NoSuchProviderException e) {
			throw new EmailException(ERROR_LOADING_PROVIDER_FOR_SMIME_SUPPORT, e);
		}
	}

	/**
	 * @see EmailPopulatingBuilder#encryptWithSmime(X509Certificate)
	 */
	@Override
	public EmailPopulatingBuilder encryptWithSmime(@NotNull final X509Certificate x509Certificate) {
		this.x509CertificateForSmimeEncryption = x509Certificate;
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#withDispositionNotificationTo()
	 */
	@Override
	@Cli.OptionNameOverride("withDispositionNotificationToEnabled")
	public EmailPopulatingBuilder withDispositionNotificationTo() {
		this.useDispositionNotificationTo = true;
		this.dispositionNotificationTo = null;
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withDispositionNotificationTo(String)
	 */
	@Override
	@Cli.ExcludeApi(reason = "API is subset of another API")
	public EmailPopulatingBuilder withDispositionNotificationTo(@NotNull final String address) {
		checkNonEmptyArgument(address, "dispositionNotificationToAddress");
		return withDispositionNotificationTo(new Recipient(null, address, null));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withDispositionNotificationTo(String, String)
	 */
	@Override
	public EmailPopulatingBuilder withDispositionNotificationTo(@Nullable final String name, @NotNull final String address) {
		checkNonEmptyArgument(address, "dispositionNotificationToAddress");
		return withDispositionNotificationTo(new Recipient(name, address, null));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withDispositionNotificationTo(InternetAddress)
	 */
	@Override
	public EmailPopulatingBuilder withDispositionNotificationTo(@NotNull final InternetAddress address) {
		checkNonEmptyArgument(address, "dispositionNotificationToAddress");
		return withDispositionNotificationTo(new Recipient(address.getPersonal(), address.getAddress(), null));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withDispositionNotificationTo(String, InternetAddress)
	 */
	@Override
	public EmailPopulatingBuilder withDispositionNotificationTo(@Nullable final String fixedName, @NotNull final InternetAddress address) {
		checkNonEmptyArgument(address, "dispositionNotificationToAddress");
		return withDispositionNotificationTo(new Recipient(fixedName, address.getAddress(), null));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withDispositionNotificationTo(Recipient)
	 */
	@Override
	public EmailPopulatingBuilder withDispositionNotificationTo(@NotNull final Recipient recipient) {
		checkNonEmptyArgument(recipient.getAddress(), "recipient.address");
		this.useDispositionNotificationTo = true;
		this.dispositionNotificationTo = new Recipient(recipient.getName(), recipient.getAddress(), null);
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withReturnReceiptTo()
	 */
	@Override
	@Cli.OptionNameOverride("withReturnReceiptToEnabled")
	public EmailPopulatingBuilder withReturnReceiptTo() {
		this.useReturnReceiptTo = true;
		this.returnReceiptTo = null;
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withReturnReceiptTo(String)
	 */
	@Override
	@Cli.ExcludeApi(reason = "API is subset of another API")
	public EmailPopulatingBuilder withReturnReceiptTo(@NotNull final String address) {
		checkNonEmptyArgument(address, "address");
		return withReturnReceiptTo(new Recipient(null, address, null));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withReturnReceiptTo(String, String)
	 */
	@Override
	public EmailPopulatingBuilder withReturnReceiptTo(@Nullable final String name, @NotNull final String address) {
		checkNonEmptyArgument(address, "address");
		return withReturnReceiptTo(new Recipient(name, address, null));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withReturnReceiptTo(InternetAddress)
	 */
	@Override
	public EmailPopulatingBuilder withReturnReceiptTo(@NotNull final InternetAddress address) {
		checkNonEmptyArgument(address, "address");
		return withReturnReceiptTo(new Recipient(address.getPersonal(), address.getAddress(), null));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withReturnReceiptTo(String, InternetAddress)
	 */
	@Override
	public EmailPopulatingBuilder withReturnReceiptTo(@Nullable final String fixedName, @NotNull final InternetAddress address) {
		checkNonEmptyArgument(address, "address");
		return withReturnReceiptTo(new Recipient(fixedName, address.getAddress(), null));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withReturnReceiptTo(Recipient)
	 */
	@Override
	public EmailPopulatingBuilder withReturnReceiptTo(@NotNull final Recipient recipient) {
		checkNonEmptyArgument(recipient.getAddress(), "recipient.address");
		this.useReturnReceiptTo = true;
		this.returnReceiptTo = new Recipient(recipient.getName(), recipient.getAddress(), null);
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#getOriginalSmimeDetails()
	 */
	@NotNull
	@Override
	public InternalEmailPopulatingBuilder withOriginalSmimeDetails(@NotNull final OriginalSmimeDetails originalSmimeDetails) {
		this.originalSmimeDetails = originalSmimeDetails;
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#getSmimeSignedEmail()
	 */
	@NotNull
	@Override
	public InternalEmailPopulatingBuilder withSmimeSignedEmail(@NotNull final Email smimeSignedEmail) {
		this.smimeSignedEmail = smimeSignedEmail;
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#fixingSentDate(Date)
	 */
	@Override
	public EmailPopulatingBuilder fixingSentDate(@NotNull final Date sentDate) {
		this.sentDate = new Date(sentDate.getTime());
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#notMergingSingleSMIMESignedAttachment()
	 */
	@Override
	public EmailPopulatingBuilder notMergingSingleSMIMESignedAttachment() {
		this.mergeSingleSMIMESignedAttachment = false;
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#clearId()
	 */
	@Override
	public EmailPopulatingBuilder clearId() {
		this.id = null;
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#clearFromRecipient()
	 */
	@Override
	public EmailPopulatingBuilder clearFromRecipient() {
		this.fromRecipient = null;
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#clearReplyTo()
	 */
	@Override
	public EmailPopulatingBuilder clearReplyTo() {
		this.replyToRecipient = null;
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#clearBounceTo()
	 */
	@Override
	public EmailPopulatingBuilder clearBounceTo() {
		this.bounceToRecipient = null;
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#clearPlainText()
	 */
	@Override
	public EmailPopulatingBuilder clearPlainText() {
		this.text = null;
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#clearHTMLText()
	 */
	@Override
	public EmailPopulatingBuilder clearHTMLText() {
		this.textHTML = null;
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#clearSubject()
	 */
	@Override
	public EmailPopulatingBuilder clearSubject() {
		this.subject = null;
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#clearRecipients()
	 */
	@Override
	public EmailPopulatingBuilder clearRecipients() {
		this.recipients.clear();
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#clearEmbeddedImages()
	 */
	@Override
	public EmailPopulatingBuilder clearEmbeddedImages() {
		this.embeddedImages.clear();
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#clearAttachments()
	 */
	@Override
	public EmailPopulatingBuilder clearAttachments() {
		this.attachments.clear();
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#clearHeaders()
	 */
	@Override
	public EmailPopulatingBuilder clearHeaders() {
		this.headers.clear();
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#clearDkim()
	 */
	@Override
	public EmailPopulatingBuilder clearDkim() {
		this.dkimPrivateKeyFile = null;
		this.dkimPrivateKeyInputStream = null;
		this.dkimSigningDomain = null;
		this.dkimSelector = null;
		return this;
	}

	/**
	 * See {@link EmailPopulatingBuilder#clearSmime()}
	 */
	@Override
	public EmailPopulatingBuilder clearSmime() {
		this.pkcs12ConfigForSmimeSigning = null;
		this.x509CertificateForSmimeEncryption = null;
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#clearDispositionNotificationTo()
	 */
	@Override
	public EmailPopulatingBuilder clearDispositionNotificationTo() {
		this.useDispositionNotificationTo = false;
		this.dispositionNotificationTo = null;
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#clearReturnReceiptTo()
	 */
	@Override
	public EmailPopulatingBuilder clearReturnReceiptTo() {
		this.useReturnReceiptTo = false;
		this.returnReceiptTo = null;
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#fixingSentDate(Date)
	 */
	@Override
	public EmailPopulatingBuilder clearSentDate() {
		this.sentDate = null;
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#clearSMIMESignedAttachmentMergingBehavior()
	 */
	@Override
	public EmailPopulatingBuilder clearSMIMESignedAttachmentMergingBehavior() {
		this.mergeSingleSMIMESignedAttachment = true;
		return this;
	}
	
	/*
		GETTERS
	 */
	
	/**
	 * @see EmailPopulatingBuilder#getId()
	 */
	@Override
	@Nullable
	public String getId() {
		return id;
	}
	
	/**
	 * @see EmailPopulatingBuilder#getFromRecipient()
	 */
	@Override
	@Nullable
	public Recipient getFromRecipient() {
		return fromRecipient;
	}
	
	/**
	 * @see EmailPopulatingBuilder#getReplyToRecipient()
	 */
	@Override
	@Nullable
	public Recipient getReplyToRecipient() {
		return replyToRecipient;
	}
	
	/**
	 * @see EmailPopulatingBuilder#getBounceToRecipient()
	 */
	@Override
	@Nullable
	public Recipient getBounceToRecipient() {
		return bounceToRecipient;
	}
	
	/**
	 * @see EmailPopulatingBuilder#getText()
	 */
	@Override
	@Nullable
	public String getText() {
		return text;
	}
	
	/**
	 * @see EmailPopulatingBuilder#getTextHTML()
	 */
	@Override
	@Nullable
	public String getTextHTML() {
		return textHTML;
	}
	
	/**
	 * @see EmailPopulatingBuilder#getCalendarMethod()
	 */
	@Override
	@Nullable
	public CalendarMethod getCalendarMethod() {
		 return calendarMethod;
	}
	
	/**
	 * @see EmailPopulatingBuilder#getTextCalendar()
	 */
	@Override
	@Nullable
	public String getTextCalendar() {
		return textCalendar;
	}
	
	/**
	 * @see EmailPopulatingBuilder#getSubject()
	 */
	@Override
	@Nullable
	public String getSubject() {
		return subject;
	}
	
	/**
	 * @see EmailPopulatingBuilder#getRecipients()
	 */
	@NotNull
	@Override
	public List<Recipient> getRecipients() {
		return new ArrayList<>(recipients);
	}
	
	/**
	 * @see EmailPopulatingBuilder#getEmbeddedImages()
	 */
	@NotNull
	@Override
	public List<AttachmentResource> getEmbeddedImages() {
		return new ArrayList<>(embeddedImages);
	}
	
	/**
	 * @see EmailPopulatingBuilder#getAttachments()
	 */
	@NotNull
	@Override
	public List<AttachmentResource> getAttachments() {
		return new ArrayList<>(attachments);
	}

	/**
	 * @see EmailPopulatingBuilder#getDecryptedAttachments()
	 */
	@NotNull
	@Override
	public List<AttachmentResource> getDecryptedAttachments() {
		return decryptedAttachments;
	}

	/**
	 * @see EmailPopulatingBuilder#getHeaders()
	 */
	@NotNull
	@Override
	public Map<String, String> getHeaders() {
		return new HashMap<>(headers);
	}
	
	/**
	 * @see EmailPopulatingBuilder#getDkimPrivateKeyFile()
	 */
	@Override
	@Nullable
	public File getDkimPrivateKeyFile() {
		return dkimPrivateKeyFile;
	}
	
	/**
	 * @see EmailPopulatingBuilder#getDkimPrivateKeyInputStream()
	 */
	@Override
	@Nullable
	public InputStream getDkimPrivateKeyInputStream() {
		return dkimPrivateKeyInputStream;
	}
	
	/**
	 * @see EmailPopulatingBuilder#getDkimSigningDomain()
	 */
	@Override
	@Nullable
	public String getDkimSigningDomain() {
		return dkimSigningDomain;
	}
	
	/**
	 * @see EmailPopulatingBuilder#getDkimSelector()
	 */
	@Override
	@Nullable
	public String getDkimSelector() {
		return dkimSelector;
	}
	
	/**
	 * @see EmailPopulatingBuilder#isUseDispositionNotificationTo()
	 */
	@Override
	public boolean isUseDispositionNotificationTo() {
		return useDispositionNotificationTo;
	}
	
	/**
	 * @see EmailPopulatingBuilder#getDispositionNotificationTo()
	 */
	@Override
	@Nullable
	public Recipient getDispositionNotificationTo() {
		return dispositionNotificationTo;
	}
	
	/**
	 * @see EmailPopulatingBuilder#isUseReturnReceiptTo()
	 */
	@Override
	public boolean isUseReturnReceiptTo() {
		return useReturnReceiptTo;
	}
	
	/**
	 * @see EmailPopulatingBuilder#getReturnReceiptTo()
	 */
	@Override
	@Nullable
	public Recipient getReturnReceiptTo() {
		return returnReceiptTo;
	}
	
	/**
	 * @see EmailPopulatingBuilder#getEmailToForward()
	 */
	@Override
	@Nullable
	public MimeMessage getEmailToForward() {
		return emailToForward;
	}

	/**
	 * @see EmailPopulatingBuilder#getOriginalSmimeDetails()
	 */
	@NotNull
	@Override
	public OriginalSmimeDetails getOriginalSmimeDetails() {
		return originalSmimeDetails;
	}

	/**
	 * @see EmailPopulatingBuilder#getSmimeSignedEmail()
	 */
	@Nullable
	@Override
	public Email getSmimeSignedEmail() {
		return smimeSignedEmail;
	}

	/**
	 * @see EmailPopulatingBuilder#isMergeSingleSMIMESignedAttachment()
	 */
	@Override
	public boolean isMergeSingleSMIMESignedAttachment() {
		return mergeSingleSMIMESignedAttachment;
	}

	/**
	 * @see EmailPopulatingBuilder#getPkcs12ConfigForSmimeSigning()
	 */
	@Override
	@Nullable
	public Pkcs12Config getPkcs12ConfigForSmimeSigning() {
		return pkcs12ConfigForSmimeSigning;
	}

	/**
	 * @see EmailPopulatingBuilder#getX509CertificateForSmimeEncryption()
	 */
	@Override
	@Nullable
	public X509Certificate getX509CertificateForSmimeEncryption() {
		return x509CertificateForSmimeEncryption;
	}

	/**
	 * @see EmailPopulatingBuilder#fixingSentDate(Date)
	 */
	@Override
	@Nullable
	public Date getSentDate() {
		return sentDate != null ? new Date(sentDate.getTime()) : null;
	}
}