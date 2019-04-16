package org.simplejavamail.email.internal;

import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.CalendarMethod;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EmailStartingBuilder;
import org.simplejavamail.api.email.OriginalSMimeDetails;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.internal.clisupport.model.Cli;
import org.simplejavamail.email.EmailBuilder;
import org.simplejavamail.internal.util.MiscUtil;

import javax.activation.DataSource;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.mail.Message.RecipientType;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.mail.util.ByteArrayDataSource;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
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
import static org.simplejavamail.internal.util.MiscUtil.defaultTo;
import static org.simplejavamail.internal.util.MiscUtil.extractEmailAddresses;
import static org.simplejavamail.internal.util.MiscUtil.valueNullOrEmpty;
import static org.simplejavamail.internal.util.Preconditions.assumeNonNull;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;
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
import static org.simplejavamail.config.ConfigLoader.getProperty;
import static org.simplejavamail.config.ConfigLoader.hasProperty;

/**
 * @see EmailPopulatingBuilder
 */
@SuppressWarnings({"UnusedReturnValue", "unused"})
public class EmailPopulatingBuilderImpl implements EmailPopulatingBuilder {
	
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
	private final Set<Recipient> recipients;
	
	/**
	 * @see #withEmbeddedImage(String, DataSource)
	 */
	private final List<AttachmentResource> embeddedImages;
	
	/**
	 * @see #withAttachment(String, DataSource)
	 */
	private final List<AttachmentResource> attachments;

	/**
	 * @see #withDecryptedAttachments(List)
	 */
	private final List<AttachmentResource> decryptedAttachments;

	/**
	 * @see #withHeader(String, Object)
	 * @see EmailStartingBuilder#replyingTo(MimeMessage, boolean, String)
	 */
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
	 * @see EmailPopulatingBuilder#getOriginalSMimeDetails()
	 */
	private OriginalSMimeDetails originalSMimeDetails;

	/**
	 * @see EmailStartingBuilder#startingBlank()
	 */
	EmailPopulatingBuilderImpl(final boolean applyDefaults) {
		recipients = new HashSet<>();
		embeddedImages = new ArrayList<>();
		attachments = new ArrayList<>();
		decryptedAttachments = new ArrayList<>();
		headers = new HashMap<>();
		
		if (applyDefaults) {
			if (hasProperty(DEFAULT_FROM_ADDRESS)) {
				from((String) getProperty(DEFAULT_FROM_NAME), assumeNonNull((String) getProperty(DEFAULT_FROM_ADDRESS)));
			}
			if (hasProperty(DEFAULT_REPLYTO_ADDRESS)) {
				withReplyTo((String) getProperty(DEFAULT_REPLYTO_NAME), assumeNonNull((String) getProperty(DEFAULT_REPLYTO_ADDRESS)));
			}
			if (hasProperty(DEFAULT_BOUNCETO_ADDRESS)) {
				withBounceTo((String) getProperty(DEFAULT_BOUNCETO_NAME), assumeNonNull((String) getProperty(DEFAULT_BOUNCETO_ADDRESS)));
			}
			if (hasProperty(DEFAULT_TO_ADDRESS)) {
				if (hasProperty(DEFAULT_TO_NAME)) {
					to((String) getProperty(DEFAULT_TO_NAME), (String) getProperty(DEFAULT_TO_ADDRESS));
				} else {
					to(assumeNonNull((String) getProperty(DEFAULT_TO_ADDRESS)));
				}
			}
			if (hasProperty(DEFAULT_CC_ADDRESS)) {
				if (hasProperty(DEFAULT_CC_NAME)) {
					cc((String) getProperty(DEFAULT_CC_NAME), (String) getProperty(DEFAULT_CC_ADDRESS));
				} else {
					cc(assumeNonNull((String) getProperty(DEFAULT_CC_ADDRESS)));
				}
			}
			if (hasProperty(DEFAULT_BCC_ADDRESS)) {
				if (hasProperty(DEFAULT_BCC_NAME)) {
					bcc((String) getProperty(DEFAULT_BCC_NAME), (String) getProperty(DEFAULT_BCC_ADDRESS));
				} else {
					bcc(assumeNonNull((String) getProperty(DEFAULT_BCC_ADDRESS)));
				}
			}
			if (hasProperty(DEFAULT_SUBJECT)) {
				withSubject((String) getProperty(DEFAULT_SUBJECT));
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
	public EmailPopulatingBuilder from(@Nonnull final String fromAddress) {
		return from(null, fromAddress);
	}
	
	/**
	 * @see EmailPopulatingBuilder#from(String, String)
	 */
	@Override
	public EmailPopulatingBuilder from(@Nullable final String name, @Nonnull final String fromAddress) {
		return from(new Recipient(name, checkNonEmptyArgument(fromAddress, "fromAddress"), null));
	}
	
	/**
	 * @see EmailPopulatingBuilder#from(String, InternetAddress)
	 */
	@Override
	public EmailPopulatingBuilder from(@Nullable final String fixedName, @Nonnull final InternetAddress fromAddress) {
		checkNonEmptyArgument(fromAddress, "fromAddress");
		return from(new Recipient(fixedName, fromAddress.getAddress(), null));
	}
	
	/**
	 * @see EmailPopulatingBuilder#from(InternetAddress)
	 */
	@Override
	public EmailPopulatingBuilder from(@Nonnull final InternetAddress fromAddress) {
		checkNonEmptyArgument(fromAddress, "fromAddress");
		return from(new Recipient(fromAddress.getPersonal(), fromAddress.getAddress(), null));
	}
	
	/**
	 * @see EmailPopulatingBuilder#from(Recipient)
	 */
	@Override
	public EmailPopulatingBuilder from(@Nonnull final Recipient recipient) {
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
	public EmailPopulatingBuilder withReplyTo(@Nullable final String fixedName, @Nonnull final String replyToAddress) {
		checkNonEmptyArgument(replyToAddress, "replyToAddress");
		return withReplyTo(new Recipient(fixedName, replyToAddress, null));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withReplyTo(InternetAddress)
	 */
	@Override
	public EmailPopulatingBuilder withReplyTo(@Nonnull final InternetAddress replyToAddress) {
		checkNonEmptyArgument(replyToAddress, "replyToAddress");
		return withReplyTo(new Recipient(replyToAddress.getPersonal(), replyToAddress.getAddress(), null));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withReplyTo(String, InternetAddress)
	 */
	@Override
	public EmailPopulatingBuilder withReplyTo(@Nullable final String fixedName, @Nonnull final InternetAddress replyToAddress) {
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
	public EmailPopulatingBuilder withBounceTo(@Nullable final String name, @Nonnull final String bounceToAddress) {
		return withBounceTo(new Recipient(name, checkNonEmptyArgument(bounceToAddress, "bounceToAddress"), null));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withBounceTo(InternetAddress)
	 */
	@Override
	public EmailPopulatingBuilder withBounceTo(@Nonnull final InternetAddress bounceToAddress) {
		checkNonEmptyArgument(bounceToAddress, "bounceToAddress");
		return withBounceTo(new Recipient(bounceToAddress.getPersonal(), bounceToAddress.getAddress(), null));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withBounceTo(String, InternetAddress)
	 */
	@Override
	@Cli.ExcludeApi(reason = "Method is not detailed enough for CLI")
	public EmailPopulatingBuilder withBounceTo(@Nullable final String name, @Nonnull final InternetAddress bounceToAddress) {
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
	EmailPopulatingBuilder withForward(@Nullable final MimeMessage emailMessageToForward) {
		this.emailToForward = emailMessageToForward;
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withPlainText(File)
	 */
	@Override
	@Cli.OptionNameOverride("withPlainTextFromFile")
	public EmailPopulatingBuilder withPlainText(@Nonnull final File textFile) {
		try {
			return withPlainText(MiscUtil.readFileContent(textFile));
		} catch (IOException e) {
			throw new EmailException(format(EmailException.ERROR_READING_FROM_FILE, textFile), e);
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
	public EmailPopulatingBuilder prependText(@Nonnull final File textFile) {
		try {
			return prependText(MiscUtil.readFileContent(textFile));
		} catch (IOException e) {
			throw new EmailException(format(EmailException.ERROR_READING_FROM_FILE, textFile), e);
		}
	}
	
	/**
	 * @see EmailPopulatingBuilder#prependText(String)
	 */
	@Override
	public EmailPopulatingBuilder prependText(@Nonnull final String text) {
		this.text = text + defaultTo(this.text, "");
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#appendText(File)
	 */
	@Override
	@Cli.OptionNameOverride("appendTextFromFile")
	public EmailPopulatingBuilder appendText(@Nonnull final File textFile) {
		try {
			return appendText(MiscUtil.readFileContent(textFile));
		} catch (IOException e) {
			throw new EmailException(format(EmailException.ERROR_READING_FROM_FILE, textFile), e);
		}
	}
	
	/**
	 * @see EmailPopulatingBuilder#appendText(String)
	 */
	@Override
	public EmailPopulatingBuilder appendText(@Nonnull final String text) {
		this.text = defaultTo(this.text, "") + text;
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withHTMLText(File)
	 */
	@Override
	@Cli.OptionNameOverride("withHTMLTextFromFile")
	public EmailPopulatingBuilder withHTMLText(@Nonnull final File textHTMLFile) {
		try {
			return withHTMLText(MiscUtil.readFileContent(textHTMLFile));
		} catch (IOException e) {
			throw new EmailException(format(EmailException.ERROR_READING_FROM_FILE, textHTMLFile), e);
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
	public EmailPopulatingBuilder prependTextHTML(@Nonnull final File textHTMLFile) {
		try {
			return prependTextHTML(MiscUtil.readFileContent(textHTMLFile));
		} catch (IOException e) {
			throw new EmailException(format(EmailException.ERROR_READING_FROM_FILE, textHTMLFile), e);
		}
	}
	
	/**
	 * @see EmailPopulatingBuilder#prependTextHTML(String)
	 */
	@Override
	public EmailPopulatingBuilder prependTextHTML(@Nonnull final String textHTML) {
		this.textHTML = textHTML + defaultTo(this.textHTML, "");
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#appendTextHTML(File)
	 */
	@Override
	@Cli.OptionNameOverride("appendTextHTMLFromFile")
	public EmailPopulatingBuilder appendTextHTML(@Nonnull final File textHTMLFile) {
		try {
			return appendTextHTML(MiscUtil.readFileContent(textHTMLFile));
		} catch (IOException e) {
			throw new EmailException(format(EmailException.ERROR_READING_FROM_FILE, textHTMLFile), e);
		}
	}
	
	/**
	 * @see EmailPopulatingBuilder#appendTextHTML(String)
	 */
	@Override
	public EmailPopulatingBuilder appendTextHTML(@Nonnull final String textHTML) {
		this.textHTML = defaultTo(this.textHTML, "") + textHTML;
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withCalendarText(CalendarMethod, String)
	 */
	@Override
	public EmailPopulatingBuilder withCalendarText(@Nonnull final CalendarMethod calendarMethod, @Nonnull final String textCalendar) {
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
	public EmailPopulatingBuilder to(@Nonnull final Recipient... recipients) {
		return withRecipients(asList(recipients), TO);
	}
	
	/**
	 * @see EmailPopulatingBuilder#to(Collection)
	 */
	@Override
	public EmailPopulatingBuilder to(@Nonnull final Collection<Recipient> recipients) {
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
	public EmailPopulatingBuilder to(@Nonnull final String oneOrMoreAddresses) {
		return withRecipientsWithDefaultName(null, singletonList(oneOrMoreAddresses), TO);
	}
	
	/**
	 * @see EmailPopulatingBuilder#to(String, String...)
	 */
	@Override
	public EmailPopulatingBuilder to(@Nullable final String name, @Nonnull final String... oneOrMoreAddressesEach) {
		return toWithFixedName(name, oneOrMoreAddressesEach);
	}
	
	/**
	 * @see EmailPopulatingBuilder#to(String, Collection)
	 */
	@Override
	public EmailPopulatingBuilder to(@Nullable final String name, @Nonnull final Collection<String> oneOrMoreAddressesEach) {
		return toWithFixedName(name, oneOrMoreAddressesEach);
	}
	
	/**
	 * @see EmailPopulatingBuilder#toMultiple(String...)
	 */
	@Override
	public EmailPopulatingBuilder toMultiple(@Nonnull final String... oneOrMoreAddressesEach) {
		return withRecipientsWithDefaultName(null, asList(oneOrMoreAddressesEach), TO);
	}
	
	/**
	 * @see EmailPopulatingBuilder#toMultiple(Collection)
	 */
	@Override
	public EmailPopulatingBuilder toMultiple(@Nonnull final Collection<String> oneOrMoreAddressesEach) {
		return withRecipientsWithDefaultName(null, oneOrMoreAddressesEach, TO);
	}
	
	/**
	 * @see EmailPopulatingBuilder#toWithFixedName(String, String...)
	 */
	@Override
	public EmailPopulatingBuilder toWithFixedName(@Nullable final String name, @Nonnull final String... oneOrMoreAddressesEach) {
		return withRecipientsWithFixedName(name, asList(oneOrMoreAddressesEach), TO);
	}
	
	/**
	 * @see EmailPopulatingBuilder#toWithDefaultName(String, String...)
	 */
	@Override
	public EmailPopulatingBuilder toWithDefaultName(@Nonnull final String name, @Nonnull final String... oneOrMoreAddressesEach) {
		return withRecipientsWithDefaultName(name, asList(oneOrMoreAddressesEach), TO);
	}
	
	/**
	 * @see EmailPopulatingBuilder#toWithFixedName(String, Collection)
	 */
	@Override
	public EmailPopulatingBuilder toWithFixedName(@Nullable final String name, @Nonnull final Collection<String> oneOrMoreAddressesEach) {
		return withRecipientsWithFixedName(name, oneOrMoreAddressesEach, TO);
	}
	
	/**
	 * @see EmailPopulatingBuilder#toWithDefaultName(String, Collection)
	 */
	@Override
	public EmailPopulatingBuilder toWithDefaultName(@Nonnull final String name, @Nonnull final Collection<String> oneOrMoreAddressesEach) {
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
	public EmailPopulatingBuilder to(@Nonnull final InternetAddress address) {
		return withAddressesWithDefaultName(null, singletonList(address), TO);
	}
	
	/**
	 * @see EmailPopulatingBuilder#to(String, InternetAddress...)
	 */
	@Override
	public EmailPopulatingBuilder to(@Nullable final String name, @Nonnull final InternetAddress... oneOrMoreAddressesEach) {
		return toAddressesWithFixedName(name, oneOrMoreAddressesEach);
	}
	
	/**
	 * @see EmailPopulatingBuilder#toAddresses(String, Collection)
	 */
	@Override
	public EmailPopulatingBuilder toAddresses(@Nullable final String name, @Nonnull final Collection<InternetAddress> oneOrMoreAddressesEach) {
		return toAddressesWithFixedName(name, oneOrMoreAddressesEach);
	}
	
	/**
	 * @see EmailPopulatingBuilder#toMultiple(InternetAddress...)
	 */
	@Override
	public EmailPopulatingBuilder toMultiple(@Nonnull final InternetAddress... oneOrMoreAddressesEach) {
		return withAddressesWithDefaultName(null, asList(oneOrMoreAddressesEach), TO);
	}
	
	/**
	 * @see EmailPopulatingBuilder#toMultipleAddresses(Collection)
	 */
	@Override
	public EmailPopulatingBuilder toMultipleAddresses(@Nonnull final Collection<InternetAddress> oneOrMoreAddressesEach) {
		return withAddressesWithDefaultName(null, oneOrMoreAddressesEach, TO);
	}
	
	/**
	 * @see EmailPopulatingBuilder#toAddressesWithFixedName(String, InternetAddress...)
	 */
	@Override
	public EmailPopulatingBuilder toAddressesWithFixedName(@Nullable final String name, @Nonnull final InternetAddress... oneOrMoreAddressesEach) {
		return withAddressesWithFixedName(name, asList(oneOrMoreAddressesEach), TO);
	}
	
	/**
	 * @see EmailPopulatingBuilder#toAddressesWithDefaultName(String, InternetAddress...)
	 */
	@Override
	public EmailPopulatingBuilder toAddressesWithDefaultName(@Nonnull final String name, @Nonnull final InternetAddress... oneOrMoreAddressesEach) {
		return withAddressesWithDefaultName(name, asList(oneOrMoreAddressesEach), TO);
	}
	
	/**
	 * @see EmailPopulatingBuilder#toAddressesWithFixedName(String, Collection)
	 */
	@Override
	public EmailPopulatingBuilder toAddressesWithFixedName(@Nullable final String name, @Nonnull final Collection<InternetAddress> oneOrMoreAddressesEach) {
		return withAddressesWithFixedName(name, oneOrMoreAddressesEach, TO);
	}
	
	/**
	 * @see EmailPopulatingBuilder#toAddressesWithDefaultName(String, Collection)
	 */
	@Override
	public EmailPopulatingBuilder toAddressesWithDefaultName(@Nonnull final String name, @Nonnull final Collection<InternetAddress> oneOrMoreAddressesEach) {
		return withAddressesWithDefaultName(name, oneOrMoreAddressesEach, TO);
	}
	/*
		CC: Recipient
	 */
	
	/**
	 * @see EmailPopulatingBuilder#cc(Recipient...)
	 */
	@Override
	public EmailPopulatingBuilder cc(@Nonnull final Recipient... recipients) {
		return withRecipients(asList(recipients), CC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#cc(Collection)
	 */
	@Override
	public EmailPopulatingBuilder cc(@Nonnull final Collection<Recipient> recipients) {
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
	public EmailPopulatingBuilder cc(@Nonnull final String oneOrMoreAddresses) {
		return withRecipientsWithDefaultName(null, singletonList(oneOrMoreAddresses), CC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#cc(String, String...)
	 */
	@Override
	public EmailPopulatingBuilder cc(@Nullable final String name, @Nonnull final String... oneOrMoreAddressesEach) {
		return ccWithFixedName(name, oneOrMoreAddressesEach);
	}
	
	/**
	 * @see EmailPopulatingBuilder#cc(String, Collection)
	 */
	@Override
	public EmailPopulatingBuilder cc(@Nullable final String name, @Nonnull final Collection<String> oneOrMoreAddressesEach) {
		return ccWithFixedName(name, oneOrMoreAddressesEach);
	}
	
	/**
	 * @see EmailPopulatingBuilder#ccMultiple(String...)
	 */
	@Override
	public EmailPopulatingBuilder ccMultiple(@Nonnull final String... oneOrMoreAddressesEach) {
		return withRecipientsWithDefaultName(null, asList(oneOrMoreAddressesEach), CC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#ccAddresses(Collection)
	 */
	@Override
	public EmailPopulatingBuilder ccAddresses(@Nonnull final Collection<String> oneOrMoreAddressesEach) {
		return withRecipientsWithDefaultName(null, oneOrMoreAddressesEach, CC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#ccWithFixedName(String, String...)
	 */
	@Override
	public EmailPopulatingBuilder ccWithFixedName(@Nullable final String name, @Nonnull final String... oneOrMoreAddressesEach) {
		return withRecipientsWithFixedName(name, asList(oneOrMoreAddressesEach), CC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#ccWithDefaultName(String, String...)
	 */
	@Override
	public EmailPopulatingBuilder ccWithDefaultName(@Nonnull final String name, @Nonnull final String... oneOrMoreAddressesEach) {
		return withRecipientsWithDefaultName(name, asList(oneOrMoreAddressesEach), CC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#ccWithFixedName(String, Collection)
	 */
	@Override
	public EmailPopulatingBuilder ccWithFixedName(@Nullable final String name, @Nonnull final Collection<String> oneOrMoreAddressesEach) {
		return withRecipientsWithFixedName(name, oneOrMoreAddressesEach, CC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#ccWithDefaultName(String, Collection)
	 */
	@Override
	public EmailPopulatingBuilder ccWithDefaultName(@Nonnull final String name, @Nonnull final Collection<String> oneOrMoreAddressesEach) {
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
	public EmailPopulatingBuilder cc(@Nonnull final InternetAddress address) {
		return withAddressesWithDefaultName(null, singletonList(address), CC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#cc(String, InternetAddress...)
	 */
	@Override
	public EmailPopulatingBuilder cc(@Nullable final String name, @Nonnull final InternetAddress... oneOrMoreAddressesEach) {
		return ccAddressesWithFixedName(name, oneOrMoreAddressesEach);
	}
	
	/**
	 * @see EmailPopulatingBuilder#ccAddresses(String, Collection)
	 */
	@Override
	public EmailPopulatingBuilder ccAddresses(@Nullable final String name, @Nonnull final Collection<InternetAddress> oneOrMoreAddressesEach) {
		return ccAddressesWithFixedName(name, oneOrMoreAddressesEach);
	}
	
	/**
	 * @see EmailPopulatingBuilder#ccMultiple(InternetAddress...)
	 */
	@Override
	public EmailPopulatingBuilder ccMultiple(@Nonnull final InternetAddress... oneOrMoreAddressesEach) {
		return withAddressesWithDefaultName(null, asList(oneOrMoreAddressesEach), CC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#ccMultipleAddresses(Collection)
	 */
	@Override
	public EmailPopulatingBuilder ccMultipleAddresses(@Nonnull final Collection<InternetAddress> oneOrMoreAddressesEach) {
		return withAddressesWithDefaultName(null, oneOrMoreAddressesEach, CC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#ccAddressesWithFixedName(String, InternetAddress...)
	 */
	@Override
	public EmailPopulatingBuilder ccAddressesWithFixedName(@Nullable final String name, @Nonnull final InternetAddress... oneOrMoreAddressesEach) {
		return withAddressesWithFixedName(name, asList(oneOrMoreAddressesEach), CC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#ccAddressesWithDefaultName(String, InternetAddress...)
	 */
	@Override
	public EmailPopulatingBuilder ccAddressesWithDefaultName(@Nonnull final String name, @Nonnull final InternetAddress... oneOrMoreAddressesEach) {
		return withAddressesWithDefaultName(name, asList(oneOrMoreAddressesEach), CC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#ccAddressesWithFixedName(String, Collection)
	 */
	@Override
	public EmailPopulatingBuilder ccAddressesWithFixedName(@Nullable final String name, @Nonnull final Collection<InternetAddress> oneOrMoreAddressesEach) {
		return withAddressesWithFixedName(name, oneOrMoreAddressesEach, CC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#ccAddressesWithDefaultName(String, Collection)
	 */
	@Override
	public EmailPopulatingBuilder ccAddressesWithDefaultName(@Nonnull final String name, @Nonnull final Collection<InternetAddress> oneOrMoreAddressesEach) {
		return withAddressesWithDefaultName(name, oneOrMoreAddressesEach, CC);
	}
	/*
		BCC: Recipient
	 */
	
	/**
	 * @see EmailPopulatingBuilder#bcc(Recipient...)
	 */
	@Override
	public EmailPopulatingBuilder bcc(@Nonnull final Recipient... recipients) {
		return withRecipients(asList(recipients), BCC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bcc(Collection)
	 */
	@Override
	public EmailPopulatingBuilder bcc(@Nonnull final Collection<Recipient> recipients) {
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
	public EmailPopulatingBuilder bcc(@Nonnull final String oneOrMoreAddresses) {
		return withRecipientsWithDefaultName(null, singletonList(oneOrMoreAddresses), BCC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bcc(String, String...)
	 */
	@Override
	public EmailPopulatingBuilder bcc(@Nullable final String name, @Nonnull final String... oneOrMoreAddressesEach) {
		return bccWithFixedName(name, oneOrMoreAddressesEach);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bcc(String, Collection)
	 */
	@Override
	public EmailPopulatingBuilder bcc(@Nullable final String name, @Nonnull final Collection<String> oneOrMoreAddressesEach) {
		return bccWithFixedName(name, oneOrMoreAddressesEach);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bccMultiple(String...)
	 */
	@Override
	public EmailPopulatingBuilder bccMultiple(@Nonnull final String... oneOrMoreAddressesEach) {
		return withRecipientsWithDefaultName(null, asList(oneOrMoreAddressesEach), BCC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bccAddresses(Collection)
	 */
	@Override
	public EmailPopulatingBuilder bccAddresses(@Nonnull final Collection<String> oneOrMoreAddressesEach) {
		return withRecipientsWithDefaultName(null, oneOrMoreAddressesEach, BCC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bccWithFixedName(String, String...)
	 */
	@Override
	public EmailPopulatingBuilder bccWithFixedName(@Nullable final String name, @Nonnull final String... oneOrMoreAddressesEach) {
		return withRecipientsWithFixedName(name, asList(oneOrMoreAddressesEach), BCC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bccWithDefaultName(String, String...)
	 */
	@Override
	public EmailPopulatingBuilder bccWithDefaultName(@Nonnull final String name, @Nonnull final String... oneOrMoreAddressesEach) {
		return withRecipientsWithDefaultName(name, asList(oneOrMoreAddressesEach), BCC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bccWithFixedName(String, Collection)
	 */
	@Override
	public EmailPopulatingBuilder bccWithFixedName(@Nullable final String name, @Nonnull final Collection<String> oneOrMoreAddressesEach) {
		return withRecipientsWithFixedName(name, oneOrMoreAddressesEach, BCC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bccWithDefaultName(String, Collection)
	 */
	@Override
	public EmailPopulatingBuilder bccWithDefaultName(@Nonnull final String name, @Nonnull final Collection<String> oneOrMoreAddressesEach) {
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
	public EmailPopulatingBuilder bcc(@Nonnull final InternetAddress address) {
		return withAddressesWithDefaultName(null, singletonList(address), BCC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bcc(String, InternetAddress...)
	 */
	@Override
	public EmailPopulatingBuilder bcc(@Nullable final String name, @Nonnull final InternetAddress... oneOrMoreAddressesEach) {
		return bccAddressesWithFixedName(name, oneOrMoreAddressesEach);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bccAddresses(String, Collection)
	 */
	@Override
	public EmailPopulatingBuilder bccAddresses(@Nullable final String name, @Nonnull final Collection<InternetAddress> oneOrMoreAddressesEach) {
		return bccAddressesWithFixedName(name, oneOrMoreAddressesEach);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bccMultiple(InternetAddress...)
	 */
	@Override
	public EmailPopulatingBuilder bccMultiple(@Nonnull final InternetAddress... oneOrMoreAddressesEach) {
		return withAddressesWithDefaultName(null, asList(oneOrMoreAddressesEach), BCC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bccMultipleAddresses(Collection)
	 */
	@Override
	public EmailPopulatingBuilder bccMultipleAddresses(@Nonnull final Collection<InternetAddress> oneOrMoreAddressesEach) {
		return withAddressesWithDefaultName(null, oneOrMoreAddressesEach, BCC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bccAddressesWithFixedName(String, InternetAddress...)
	 */
	@Override
	public EmailPopulatingBuilder bccAddressesWithFixedName(@Nullable final String name, @Nonnull final InternetAddress... oneOrMoreAddressesEach) {
		return withAddressesWithFixedName(name, asList(oneOrMoreAddressesEach), BCC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bccAddressesWithDefaultName(String, InternetAddress...)
	 */
	@Override
	public EmailPopulatingBuilder bccAddressesWithDefaultName(@Nonnull final String name, @Nonnull final InternetAddress... oneOrMoreAddressesEach) {
		return withAddressesWithDefaultName(name, asList(oneOrMoreAddressesEach), BCC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bccAddressesWithFixedName(String, Collection)
	 */
	@Override
	public EmailPopulatingBuilder bccAddressesWithFixedName(@Nullable final String name, @Nonnull final Collection<InternetAddress> oneOrMoreAddressesEach) {
		return withAddressesWithFixedName(name, oneOrMoreAddressesEach, BCC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#bccAddressesWithDefaultName(String, Collection)
	 */
	@Override
	public EmailPopulatingBuilder bccAddressesWithDefaultName(@Nonnull final String name, @Nonnull final Collection<InternetAddress> oneOrMoreAddressesEach) {
		return withAddressesWithDefaultName(name, oneOrMoreAddressesEach, BCC);
	}
	
	/**
	 * @see EmailPopulatingBuilder#withRecipientsWithDefaultName(String, Collection, RecipientType)
	 */
	@Override
	@Nonnull
	public EmailPopulatingBuilder withRecipientsWithDefaultName(@Nullable final String defaultName, @Nonnull Collection<String> oneOrMoreAddressesEach, @Nullable RecipientType recipientType) {
		return withRecipients(defaultName, false, oneOrMoreAddressesEach, recipientType);
	}
	
	/**
	 * @see EmailPopulatingBuilder#withRecipientsWithFixedName(String, Collection, RecipientType)
	 */
	@Override
	@Nonnull
	public EmailPopulatingBuilder withRecipientsWithFixedName(@Nullable final String fixedName, @Nonnull Collection<String> oneOrMoreAddressesEach, @Nullable RecipientType recipientType) {
		return withRecipients(fixedName, true, oneOrMoreAddressesEach, recipientType);
	}
	
	/**
	 * @see EmailPopulatingBuilder#withRecipientsWithDefaultName(String, RecipientType, String...)
	 */
	@Override
	@Nonnull
	public EmailPopulatingBuilder withRecipientsWithDefaultName(@Nullable String name, @Nullable RecipientType recipientType, @Nonnull String... oneOrMoreAddressesEach) {
		return withRecipients(name, false, asList(oneOrMoreAddressesEach), recipientType);
	}
	
	/**
	 * @see EmailPopulatingBuilder#withRecipientsWithFixedName(String, RecipientType, String...)
	 */
	@Override
	@Nonnull
	public EmailPopulatingBuilder withRecipientsWithFixedName(@Nullable String name, @Nullable RecipientType recipientType, @Nonnull String... oneOrMoreAddressesEach) {
		return withRecipients(name, true, asList(oneOrMoreAddressesEach), recipientType);
	}
	
	/**
	 * @see EmailPopulatingBuilder#withRecipients(String, boolean, RecipientType, String...)
	 */
	@Override
	@Nonnull
	public EmailPopulatingBuilder withRecipients(@Nullable String name, boolean fixedName, @Nullable RecipientType recipientType, @Nonnull String... oneOrMoreAddressesEach) {
		return withRecipients(name, fixedName, asList(oneOrMoreAddressesEach), recipientType);
	}
	
	/**
	 * @see EmailPopulatingBuilder#withRecipients(String, boolean, Collection, RecipientType)
	 */
	@Override
	@Nonnull
	public EmailPopulatingBuilder withRecipients(@Nullable String name, boolean fixedName, @Nonnull Collection<String> oneOrMoreAddressesEach, @Nullable RecipientType recipientType) {
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
	@Nonnull
	public EmailPopulatingBuilder withAddressesWithDefaultName(@Nullable final String defaultName, @Nonnull Collection<InternetAddress> addresses, @Nullable RecipientType recipientType) {
		return withAddresses(defaultName, false, addresses, recipientType);
	}
	
	/**
	 * @see EmailPopulatingBuilder#withAddressesWithFixedName(String, Collection, RecipientType)
	 */
	@Override
	@Nonnull
	public EmailPopulatingBuilder withAddressesWithFixedName(@Nullable final String fixedName, @Nonnull Collection<InternetAddress> addresses, @Nullable RecipientType recipientType) {
		return withAddresses(fixedName, true, addresses, recipientType);
	}
	
	/**
	 * @see EmailPopulatingBuilder#withAddresses(String, boolean, Collection, RecipientType)
	 */
	@Override
	@Nonnull
	public EmailPopulatingBuilder withAddresses(@Nullable String name, boolean fixedName, @Nonnull Collection<InternetAddress> addresses, @Nullable RecipientType recipientType) {
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
	public EmailPopulatingBuilder withRecipients(@Nonnull final Collection<Recipient> recipients) {
		return withRecipients(recipients, null);
	}
	
	/**
	 * @see EmailPopulatingBuilder#withRecipients(Recipient...)
	 */
	@Override
	public EmailPopulatingBuilder withRecipients(@Nonnull final Recipient... recipients) {
		return withRecipients(asList(recipients), null);
	}
	
	/**
	 * @see EmailPopulatingBuilder#withRecipients(Collection, RecipientType)
	 */
	@Override
	@Nonnull
	public EmailPopulatingBuilder withRecipients(@Nonnull Collection<Recipient> recipients, @Nullable RecipientType fixedRecipientType) {
		for (Recipient recipient : recipients) {
			withRecipient(recipient.getName(), recipient.getAddress(), defaultTo(fixedRecipientType, recipient.getType()));
		}
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withRecipient(String, RecipientType)
	 */
	@Override
	public EmailPopulatingBuilder withRecipient(@Nonnull final String singleAddress, @Nullable final RecipientType recipientType) {
		return withRecipient(null, singleAddress, recipientType);
	}
	
	/**
	 * @see EmailPopulatingBuilder#withRecipient(String, String, RecipientType)
	 */
	@Override
	public EmailPopulatingBuilder withRecipient(@Nullable final String name, @Nonnull final String singleAddress, @Nullable final RecipientType recipientType) {
		recipients.add(MiscUtil.interpretRecipient(name, true, singleAddress, recipientType));
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withRecipient(Recipient)
	 */
	@Override
	public EmailPopulatingBuilder withRecipient(@Nonnull final Recipient recipient) {
		recipients.add(new Recipient(recipient.getName(), recipient.getAddress(), recipient.getType()));
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withEmbeddedImage(String, byte[], String)
	 */
	@Override
	public EmailPopulatingBuilder withEmbeddedImage(@Nonnull final String name, @Nonnull final byte[] data, @Nonnull final String mimetype) {
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
	public EmailPopulatingBuilder withEmbeddedImage(@Nullable final String name, @Nonnull final DataSource imagedata) {
		checkNonEmptyArgument(imagedata, "imagedata");
		if (valueNullOrEmpty(name) && valueNullOrEmpty(imagedata.getName())) {
			throw new EmailException(EmailException.NAME_MISSING_FOR_EMBEDDED_IMAGE);
		}
		embeddedImages.add(new AttachmentResource(name, imagedata));
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withEmbeddedImages(List)
	 */
	@Override
	public EmailPopulatingBuilder withEmbeddedImages(@Nonnull final List<AttachmentResource> embeddedImages) {
		for (final AttachmentResource embeddedImage : embeddedImages) {
			withEmbeddedImage(embeddedImage.getName(), embeddedImage.getDataSource());
		}
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withHeaders(Map)
	 */
	@Override
	public <T> EmailPopulatingBuilder withHeaders(@Nonnull final Map<String, T> headers) {
		for (Map.Entry<String, T> headerEntry : headers.entrySet()) {
			withHeader(headerEntry.getKey(), headerEntry.getValue());
		}
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withHeader(String, Object)
	 */
	@Override
	public EmailPopulatingBuilder withHeader(@Nonnull final String name, @Nullable final Object value) {
		checkNonEmptyArgument(name, "name");
		headers.put(name, value != null ? String.valueOf(value) : null);
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withAttachment(String, byte[], String)
	 */
	@Override
	public EmailPopulatingBuilder withAttachment(@Nullable final String name, @Nonnull final byte[] data, @Nonnull final String mimetype) {
		checkNonEmptyArgument(data, "data");
		checkNonEmptyArgument(mimetype, "mimetype");
		final ByteArrayDataSource dataSource = new ByteArrayDataSource(data, mimetype);
		dataSource.setName(MiscUtil.encodeText(name));
		withAttachment(name, dataSource);
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#withAttachment(String, DataSource)
	 */
	@Override
	public EmailPopulatingBuilder withAttachment(@Nullable final String name, @Nonnull final DataSource filedata) {
		checkNonEmptyArgument(filedata, "filedata");
		attachments.add(new AttachmentResource(MiscUtil.encodeText(name), filedata));
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#withAttachments(List)
	 */
	@Override
	public EmailPopulatingBuilder withAttachments(@Nonnull final List<AttachmentResource> attachments) {
		for (final AttachmentResource attachment : attachments) {
			withAttachment(attachment.getName(), attachment.getDataSource());
		}
		return this;
	}

	/**
	 * For internal use only.
	 *
	 * @see EmailPopulatingBuilder#getDecryptedAttachments()
	 */
	public EmailPopulatingBuilder withDecryptedAttachments(@Nonnull final List<AttachmentResource> attachments) {
		decryptedAttachments.addAll(attachments);
		return this;
	}
	
	/**
	 * @see EmailPopulatingBuilder#signWithDomainKey(byte[], String, String)
	 */
	@Override
	@Cli.ExcludeApi(reason = "delegated method is an identical api from CLI point of view")
	public EmailPopulatingBuilder signWithDomainKey(@Nonnull final byte[] dkimPrivateKey, @Nonnull final String signingDomain, @Nonnull final String dkimSelector) {
		checkNonEmptyArgument(dkimPrivateKey, "dkimPrivateKey");
		return signWithDomainKey(new ByteArrayInputStream(dkimPrivateKey), signingDomain, dkimSelector);
	}
	
	/**
	 * @see EmailPopulatingBuilder#signWithDomainKey(String, String, String)
	 */
	@Override
	@Cli.ExcludeApi(reason = "delegated method is an identical api from CLI point of view")
	public EmailPopulatingBuilder signWithDomainKey(@Nonnull final String dkimPrivateKey, @Nonnull final String signingDomain, @Nonnull final String dkimSelector) {
		checkNonEmptyArgument(dkimPrivateKey, "dkimPrivateKey");
		return signWithDomainKey(new ByteArrayInputStream(dkimPrivateKey.getBytes(UTF_8)), signingDomain, dkimSelector);
	}
	
	/**
	 * @see EmailPopulatingBuilder#signWithDomainKey(InputStream, String, String)
	 */
	@Override
	public EmailPopulatingBuilder signWithDomainKey(@Nonnull final InputStream dkimPrivateKeyInputStream, @Nonnull final String signingDomain,
													@Nonnull final String dkimSelector) {
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
	public EmailPopulatingBuilder signWithDomainKey(@Nonnull final File dkimPrivateKeyFile, @Nonnull final String signingDomain, @Nonnull final String dkimSelector) {
		this.dkimPrivateKeyFile = checkNonEmptyArgument(dkimPrivateKeyFile, "dkimPrivateKeyFile");
		this.dkimSigningDomain = checkNonEmptyArgument(signingDomain, "dkimSigningDomain");
		this.dkimSelector = checkNonEmptyArgument(dkimSelector, "dkimSelector");
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
	public EmailPopulatingBuilder withDispositionNotificationTo(@Nonnull final String address) {
		checkNonEmptyArgument(address, "dispositionNotificationToAddress");
		return withDispositionNotificationTo(new Recipient(null, address, null));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withDispositionNotificationTo(String, String)
	 */
	@Override
	public EmailPopulatingBuilder withDispositionNotificationTo(@Nullable final String name, @Nonnull final String address) {
		checkNonEmptyArgument(address, "dispositionNotificationToAddress");
		return withDispositionNotificationTo(new Recipient(name, address, null));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withDispositionNotificationTo(InternetAddress)
	 */
	@Override
	public EmailPopulatingBuilder withDispositionNotificationTo(@Nonnull final InternetAddress address) {
		checkNonEmptyArgument(address, "dispositionNotificationToAddress");
		return withDispositionNotificationTo(new Recipient(address.getPersonal(), address.getAddress(), null));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withDispositionNotificationTo(String, InternetAddress)
	 */
	@Override
	public EmailPopulatingBuilder withDispositionNotificationTo(@Nullable final String fixedName, @Nonnull final InternetAddress address) {
		checkNonEmptyArgument(address, "dispositionNotificationToAddress");
		return withDispositionNotificationTo(new Recipient(fixedName, address.getAddress(), null));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withDispositionNotificationTo(Recipient)
	 */
	@Override
	public EmailPopulatingBuilder withDispositionNotificationTo(@Nonnull final Recipient recipient) {
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
	public EmailPopulatingBuilder withReturnReceiptTo(@Nonnull final String address) {
		checkNonEmptyArgument(address, "address");
		return withReturnReceiptTo(new Recipient(null, address, null));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withReturnReceiptTo(String, String)
	 */
	@Override
	public EmailPopulatingBuilder withReturnReceiptTo(@Nullable final String name, @Nonnull final String address) {
		checkNonEmptyArgument(address, "address");
		return withReturnReceiptTo(new Recipient(name, address, null));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withReturnReceiptTo(InternetAddress)
	 */
	@Override
	public EmailPopulatingBuilder withReturnReceiptTo(@Nonnull final InternetAddress address) {
		checkNonEmptyArgument(address, "address");
		return withReturnReceiptTo(new Recipient(address.getPersonal(), address.getAddress(), null));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withReturnReceiptTo(String, InternetAddress)
	 */
	@Override
	public EmailPopulatingBuilder withReturnReceiptTo(@Nullable final String fixedName, @Nonnull final InternetAddress address) {
		checkNonEmptyArgument(address, "address");
		return withReturnReceiptTo(new Recipient(fixedName, address.getAddress(), null));
	}
	
	/**
	 * @see EmailPopulatingBuilder#withReturnReceiptTo(Recipient)
	 */
	@Override
	public EmailPopulatingBuilder withReturnReceiptTo(@Nonnull final Recipient recipient) {
		checkNonEmptyArgument(recipient.getAddress(), "recipient.address");
		this.useReturnReceiptTo = true;
		this.returnReceiptTo = new Recipient(recipient.getName(), recipient.getAddress(), null);
		return this;
	}

	/**
	 * @see EmailPopulatingBuilder#getOriginalSMimeDetails()
	 */
	public EmailPopulatingBuilder withOriginalSMimeDetails(final OriginalSMimeDetails originalSMimeDetails) {
		this.originalSMimeDetails = originalSMimeDetails;
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
	@Override
	public List<Recipient> getRecipients() {
		return new ArrayList<>(recipients);
	}
	
	/**
	 * @see EmailPopulatingBuilder#getEmbeddedImages()
	 */
	@Override
	public List<AttachmentResource> getEmbeddedImages() {
		return new ArrayList<>(embeddedImages);
	}
	
	/**
	 * @see EmailPopulatingBuilder#getAttachments()
	 */
	@Override
	public List<AttachmentResource> getAttachments() {
		return new ArrayList<>(attachments);
	}

	/**
	 * @see EmailPopulatingBuilder#getDecryptedAttachments()
	 */
	@Override
	public List<AttachmentResource> getDecryptedAttachments() {
		return decryptedAttachments;
	}

	/**
	 * @see EmailPopulatingBuilder#getHeaders()
	 */
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
	 * @see EmailPopulatingBuilder#getOriginalSMimeDetails()
	 */
	@Nullable
	@Override
	public OriginalSMimeDetails getOriginalSMimeDetails() {
		return originalSMimeDetails;
	}
}