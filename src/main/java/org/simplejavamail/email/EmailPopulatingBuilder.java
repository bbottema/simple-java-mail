package org.simplejavamail.email;

import org.simplejavamail.email.EmailBuilder.EmailBuilderInstance;
import org.simplejavamail.internal.clisupport.annotation.Cli;
import org.simplejavamail.internal.clisupport.model.CliBuilderApiType;
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
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static javax.mail.Message.RecipientType.BCC;
import static javax.mail.Message.RecipientType.CC;
import static javax.mail.Message.RecipientType.TO;
import static org.simplejavamail.internal.util.MiscUtil.defaultTo;
import static org.simplejavamail.internal.util.MiscUtil.extractEmailAddresses;
import static org.simplejavamail.internal.util.MiscUtil.valueNullOrEmpty;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;
import static org.simplejavamail.util.ConfigLoader.Property.DEFAULT_BCC_ADDRESS;
import static org.simplejavamail.util.ConfigLoader.Property.DEFAULT_BCC_NAME;
import static org.simplejavamail.util.ConfigLoader.Property.DEFAULT_BOUNCETO_ADDRESS;
import static org.simplejavamail.util.ConfigLoader.Property.DEFAULT_BOUNCETO_NAME;
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
 * Fluent interface Builder for populating {@link Email} instances. An instance of this builder can only be obtained through one of the builder
 * starters on {@link EmailBuilder}.
 * <p>
 * NOTE: for some reason, JavaDoc is not able to parse all {@code @link} directives used in this class' documentation. I have no idea why, if you can figure
 * it out, please let me know!
 */
@Cli.BuilderApiNode(builderApiType = CliBuilderApiType.EMAIL)
@SuppressWarnings({"UnusedReturnValue", "WeakerAccess", "unused"})
public class EmailPopulatingBuilder {
	
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
	 * @see #withHeader(String, Object)
	 * @see EmailBuilderInstance#replyingTo(MimeMessage, boolean, String)
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
	 * @see EmailBuilderInstance#startingBlank()
	 */
	EmailPopulatingBuilder(final boolean applyDefaults) {
		recipients = new HashSet<>();
		embeddedImages = new ArrayList<>();
		attachments = new ArrayList<>();
		headers = new HashMap<>();
		
		if (applyDefaults) {
			if (hasProperty(DEFAULT_FROM_ADDRESS)) {
				from((String) getProperty(DEFAULT_FROM_NAME), (String) getProperty(DEFAULT_FROM_ADDRESS));
			}
			if (hasProperty(DEFAULT_REPLYTO_ADDRESS)) {
				withReplyTo((String) getProperty(DEFAULT_REPLYTO_NAME), (String) getProperty(DEFAULT_REPLYTO_ADDRESS));
			}
			if (hasProperty(DEFAULT_BOUNCETO_ADDRESS)) {
				withBounceTo((String) getProperty(DEFAULT_BOUNCETO_NAME), (String) getProperty(DEFAULT_BOUNCETO_ADDRESS));
			}
			if (hasProperty(DEFAULT_TO_ADDRESS)) {
				if (hasProperty(DEFAULT_TO_NAME)) {
					to((String) getProperty(DEFAULT_TO_NAME), (String) getProperty(DEFAULT_TO_ADDRESS));
				} else {
					to((String) getProperty(DEFAULT_TO_ADDRESS));
				}
			}
			if (hasProperty(DEFAULT_CC_ADDRESS)) {
				if (hasProperty(DEFAULT_CC_NAME)) {
					cc((String) getProperty(DEFAULT_CC_NAME), (String) getProperty(DEFAULT_CC_ADDRESS));
				} else {
					cc((String) getProperty(DEFAULT_CC_ADDRESS));
				}
			}
			if (hasProperty(DEFAULT_BCC_ADDRESS)) {
				if (hasProperty(DEFAULT_BCC_NAME)) {
					bcc((String) getProperty(DEFAULT_BCC_NAME), (String) getProperty(DEFAULT_BCC_ADDRESS));
				} else {
					bcc((String) getProperty(DEFAULT_BCC_ADDRESS));
				}
			}
			if (hasProperty(DEFAULT_SUBJECT)) {
				withSubject((String) getProperty(DEFAULT_SUBJECT));
			}
		}
	}
	
	/**
	 * Delegates to {@link Email#Email(EmailPopulatingBuilder)} with <code>this</code> as argument.
	 */
	@Cli.ExcludeApi(reason = "This API is specifically for Java use")
	public Email buildEmail() {
		return new Email(this);
	}
	
	/**
	 * Sets optional ID to a fixed value, which is otherwise generated by the underlying JavaMail framework when sending the email.
	 * <p>
	 * Note that id can only ever be filled by end-users for sending an email. This library will never fill this field when converting a MimeMessage.
	 * <p>
	 * The id-format should be conform <a href="https://tools.ietf.org/html/rfc5322#section-3.6.4">rfc5322#section-3.6.4</a>
	 *
	 * @param id The mime message id, something like {@code "<123@456>"}
	 */
	public EmailPopulatingBuilder fixingMessageId(@Nullable @Cli.OptionValue(name="id", helpLabel = "MIMEMESSAGE_ID", example = "\"<123@456>\"") final String id) {
		this.id = id;
		return this;
	}

	/**
	 * Delegates to {@link #from(String, String)} with empty name.
	 *
	 * @param fromAddress The sender address visible to receivers of the email.
	 */
	@Cli.ExcludeApi(reason = "API is subset of another API")
	public EmailPopulatingBuilder from(@Nonnull final String fromAddress) {
		return from(null, fromAddress);
	}
	
	/**
	 * Delegates to {@link #from(Recipient)} with a new {@link Recipient} wrapped around the given name and email address.
	 *
	 * @param name The name that will be visible to the receivers of this email.
	 * @param fromAddress The address that will be visible to the receivers of this email.
	 */
	public EmailPopulatingBuilder from(
			@Cli.OptionValue(name = "name", required = false, helpLabel = "STRING", description = "The name of the sender") @Nullable final String name,
			@Cli.OptionValue(name = "fromAddress", helpLabel = "STRING", description = "The email address of the sender") @Nonnull final String fromAddress) {
		return from(new Recipient(name, checkNonEmptyArgument(fromAddress, "fromAddress"), null));
	}
	
	/**
	 * Delegates to {@link #from(Recipient)} with a new {@link Recipient} wrapped around the given fixed name and email address.
	 */
	public EmailPopulatingBuilder from(@Nullable final String fixedName, @Nonnull final InternetAddress fromAddress) {
		checkNonEmptyArgument(fromAddress, "fromAddress");
		return from(new Recipient(fixedName, fromAddress.getAddress(), null));
	}
	
	/**
	 * Delegates to {@link #from(Recipient)} with a new {@link Recipient} wrapped around the given email address.
	 */
	public EmailPopulatingBuilder from(@Nonnull final InternetAddress fromAddress) {
		checkNonEmptyArgument(fromAddress, "fromAddress");
		return from(new Recipient(fromAddress.getPersonal(), fromAddress.getAddress(), null));
	}
	
	/**
	 * Sets the address of the sender of this email with given {@link Recipient} (ignoring its {@link RecipientType} if provided).
	 * <p>
	 * Can be used in conjunction with one of the {@code replyTo(...)} methods, which is then prioritized by email clients when replying to this
	 * email.
	 *
	 * @param recipient Preconfigured recipient which includes optional name and mandatory email address.
	 *
	 * @see #from(String, String)
	 * @see #from(String)
	 * @see #withReplyTo(Recipient)
	 */
	public EmailPopulatingBuilder from(@Nonnull final Recipient recipient) {
		checkNonEmptyArgument(recipient, "from recipient");
		this.fromRecipient = new Recipient(recipient.getName(), recipient.getAddress(), null);
		return this;
	}
	
	/**
	 * Delegates to {@link #withReplyTo(Recipient)} with a  new {@link Recipient} wrapped around the given email address (or null if missing).
	 *
	 * @param replyToAddress The address that receivers will get when they reply to the email.
	 */
	@Cli.ExcludeApi(reason = "API is subset of another API")
	public EmailPopulatingBuilder withReplyTo(@Nullable final String replyToAddress) {
		return withReplyTo(replyToAddress != null ? new Recipient(null, replyToAddress, null) : null);
	}
	
	/**
	 * Delegates to {@link #withReplyTo(Recipient)} with a new {@link Recipient} wrapped around the given fixed name and email address.
	 *
	 * @param fixedName Optional name that receivers will get when they reply to the email.
	 * @param replyToAddress The address that receivers will get when they reply to the email. Any name included in the address will be ignored.
	 */
	public EmailPopulatingBuilder withReplyTo(@Nullable final String fixedName, @Nonnull final String replyToAddress) {
		checkNonEmptyArgument(replyToAddress, "replyToAddress");
		return withReplyTo(new Recipient(fixedName, replyToAddress, null));
	}
	
	/**
	 * Delegates to {@link #withReplyTo(Recipient)} with a  new {@link Recipient} wrapped around the given address.
	 */
	public EmailPopulatingBuilder withReplyTo(@Nonnull final InternetAddress replyToAddress) {
		checkNonEmptyArgument(replyToAddress, "replyToAddress");
		return withReplyTo(new Recipient(replyToAddress.getPersonal(), replyToAddress.getAddress(), null));
	}
	
	/**
	 * Delegates to {@link #withReplyTo(Recipient)} with a new {@link Recipient} wrapped around the given fixed name and address.
	 */
	public EmailPopulatingBuilder withReplyTo(@Nullable final String fixedName, @Nonnull final InternetAddress replyToAddress) {
		checkNonEmptyArgument(replyToAddress, "replyToAddress");
		return withReplyTo(new Recipient(fixedName, replyToAddress.getAddress(), null));
	}
	
	/**
	 * Sets the <em>replyTo</em> address of this email with given {@link Recipient} (ignoring its {@link RecipientType} if provided).
	 * <p>
	 * If provided, email clients should prioritize the <em>replyTo</em> recipient over the <em>from</em> recipient when replying to this email.
	 *
	 * @param recipient Preconfigured recipient which includes optional name and mandatory email address.
	 *
	 * @see #withReplyTo(String, String)
	 */
	public EmailPopulatingBuilder withReplyTo(@Nullable final Recipient recipient) {
		this.replyToRecipient = recipient != null ? new Recipient(recipient.getName(), recipient.getAddress(), null) : null;
		return this;
	}
	
	/**
	 * Delegates to {@link #withBounceTo(Recipient)} with a new {@link Recipient} wrapped around the email address (or null if missing).
	 *
	 * @param bounceToAddress The address of the receiver of the bounced email
	 */
	@Cli.ExcludeApi(reason = "API is subset of another API")
	public EmailPopulatingBuilder withBounceTo(@Nullable final String bounceToAddress) {
		return withBounceTo(bounceToAddress != null ? new Recipient(null, bounceToAddress, null) : null);
	}
	
	/**
	 * Delegates to {@link #withBounceTo(Recipient)} with a new {@link Recipient} wrapped around the given name and email address.
	 *
	 * @param name Name of the receiver of the bounced email
	 * @param bounceToAddress The address of the receiver of the bounced email
	 */
	public EmailPopulatingBuilder withBounceTo(@Nullable final String name, @Nonnull final String bounceToAddress) {
		return withBounceTo(new Recipient(name, checkNonEmptyArgument(bounceToAddress, "bounceToAddress"), null));
	}
	
	/**
	 * Delegates to {@link #withBounceTo(Recipient)} with a new {@link Recipient} wrapped around the given address.
	 */
	public EmailPopulatingBuilder withBounceTo(@Nonnull final InternetAddress bounceToAddress) {
		checkNonEmptyArgument(bounceToAddress, "bounceToAddress");
		return withBounceTo(new Recipient(bounceToAddress.getPersonal(), bounceToAddress.getAddress(), null));
	}
	
	/**
	 * Delegates to {@link #withBounceTo(Recipient)} with a new {@link Recipient} wrapped around the given fixed name and address.
	 */
	@Cli.ExcludeApi(reason = "Method is not detailed enough for CLI")
	public EmailPopulatingBuilder withBounceTo(@Nullable final String name, @Nonnull final InternetAddress bounceToAddress) {
		checkNonEmptyArgument(bounceToAddress, "bounceToAddress");
		return withBounceTo(new Recipient(name, bounceToAddress.getAddress(), null));
	}
	
	/**
	 * Sets the <em>bounceTo</em> address of this email with given {@link Recipient} (ignoring its {@link RecipientType} if provided).
	 * <p>
	 * If provided, SMTP server should return bounced emails to this address. This is also known as the {@code Return-Path} (or <em>Envelope
	 * FROM</em>).
	 *
	 * @param recipient Preconfigured recipient which includes optional name and mandatory email address.
	 *
	 * @see #withBounceTo(String, String)
	 */
	public EmailPopulatingBuilder withBounceTo(@Nullable final Recipient recipient) {
		this.bounceToRecipient = recipient != null ? new Recipient(recipient.getName(), recipient.getAddress(), null) : null;
		return this;
	}
	
	/**
	 * Sets the optional subject of this email.
	 *
	 * @param subject Optional text to be used in the subject field of the email.
	 */
	public EmailPopulatingBuilder withSubject(@Nullable final String subject) {
		this.subject = subject;
		return this;
	}
	
	/**
	 * @see EmailBuilderInstance#forwarding(MimeMessage)
	 */
	EmailPopulatingBuilder withForward(@Nullable final MimeMessage emailMessageToForward) {
		this.emailToForward = emailMessageToForward;
		return this;
	}
	
	/**
	 * Sets the optional email message body in plain text.
	 * <p>
	 * Both text and HTML can be provided, which will  be offered to the email client as alternative content. Email clients that support it, will
	 * favor HTML over plain text and ignore the text body completely.
	 *
	 * @param text Plain text to set as email body (overwrites any previous plain text body). If no HTML body is included as well, plain text
	 *                would be used instead by the email client.
	 *
	 * @see #prependText(String)
	 * @see #appendText(String)
	 */
	public EmailPopulatingBuilder withPlainText(@Nullable final String text) {
		this.text = text;
		return this;
	}
	
	/**
	 * Prepends text to the current plain text body (or starts it if plain text body is missing).
	 *
	 * @param text The plain text to prepend to whatever plain text is already there.
	 *
	 * @see #withPlainText(String)
	 */
	public EmailPopulatingBuilder prependText(@Nonnull final String text) {
		this.text = text + defaultTo(this.text, "");
		return this;
	}
	
	/**
	 * Appends text to the current plain text body (or starts it if plain text body is missing).
	 *
	 * @param text The plain text to append to whatever plain text is already there.
	 *
	 * @see #withPlainText(String)
	 */
	public EmailPopulatingBuilder appendText(@Nonnull final String text) {
		this.text = defaultTo(this.text, "") + text;
		return this;
	}
	
	/**
	 * Sets the optional email message body in HTML text.
	 * <p>
	 * Both text and HTML can be provided, which will  be offered to the email client as alternative content. Email clients that support it, will
	 * favor HTML over plain text and ignore the text body completely.
	 *
	 * @param textHTML HTML text to set as email body (overwrites any previous HTML text body). If no HTML body is included, plain text
	 *                would be used instead by the email client if provided.
	 *
	 * @see #prependTextHTML(String)
	 * @see #appendTextHTML(String)
	 */
	public EmailPopulatingBuilder withHTMLText(@Nullable final String textHTML) {
		this.textHTML = textHTML;
		return this;
	}
	
	/**
	 * Prepends HTML text to the current HTML text body (or starts it if HTML text body is missing).
	 *
	 * @param textHTML The HTML text to prepend to whatever is already there in the body.
	 *
	 * @see #withHTMLText(String)
	 */
	public EmailPopulatingBuilder prependTextHTML(@Nonnull final String textHTML) {
		this.textHTML = textHTML + defaultTo(this.textHTML, "");
		return this;
	}
	
	/**
	 * Appends HTML text to the current HTML text body (or starts it if HTML text body is missing).
	 *
	 * @param textHTML The HTML text to append to whatever is already there in the body.
	 *
	 * @see #withHTMLText(String)
	 */
	public EmailPopulatingBuilder appendTextHTML(@Nonnull final String textHTML) {
		this.textHTML = defaultTo(this.textHTML, "") + textHTML;
		return this;
	}

	/**
	 * Sets the optional calendar details that clients such as Outlook might be able to handle. Will be set as alternative bodypart similar to
	 * {@link #withPlainText(String)} and {@link #withHTMLText(String)}.
	 * <p>
	 * The body is free form text, which you should can produce with a library such as
	 * <a href="https://github.com/ical4j/ical4j/wiki/Examples">ical4j</a>. Also see the Test demo app in Simple Java Mail's source for a working
	 * example.
	 */
	public EmailPopulatingBuilder withCalendarText(@Nonnull final CalendarMethod calendarMethod, @Nonnull final String textCalendar) {
		this.calendarMethod = calendarMethod;
		this.textCalendar = textCalendar;
		return this;
	}
	
	/*
		TO: Recipient
	 */
	
	/**
	 * Delegates to {@link #withRecipients(Collection, RecipientType)} with {@link RecipientType#TO}.
	 */
	public EmailPopulatingBuilder to(@Nonnull final Recipient... recipients) {
		return withRecipients(asList(recipients), TO);
	}

	/**
	 * Delegates to {@link #withRecipients(Collection, RecipientType)} with {@link RecipientType#TO}.
	 */
	public EmailPopulatingBuilder to(@Nonnull final Collection<Recipient> recipients) {
		return withRecipients(recipients, TO);
	}
	/*
		TO: String
	 */
	
	/**
	 * Alias for {@link #toWithFixedName(String, String...)}.
	 *
	 * @param name The optional name of the TO receiver(s) of the email. If multiples addresses are provided, all addresses will be in this same name.
	 * @param oneOrMoreAddresses Single RFC2822 address or delimited list of RFC2822 addresses of TO receiver(s). Any names included are ignored if a name was provided.
	 */
	public EmailPopulatingBuilder to(@Nullable final String name, String oneOrMoreAddresses) {
		return toWithFixedName(name, oneOrMoreAddresses);
	}
	
	/**
	 * Delegates to {@link #withRecipientsWithDefaultName(String, Collection, RecipientType)} with {@link RecipientType#TO} and empty default name.
	 *
	 * @param oneOrMoreAddresses Single RFC2822 address or delimited list of RFC2822 addresses.
	 */
	@Cli.ExcludeApi(reason = "API is subset of another API method")
	public EmailPopulatingBuilder to(
			@Cli.OptionValue(name = "oneOrMoreAddresses", helpLabel = "STRING", description = "Single RFC2822 address or delimited list of RFC2822 addresses.",
			example = { "lolly.pop@pretzelfun.com", "Lolly Pop<lolly.pop@pretzelfun.com>", "a1@b1.c1,a2@b2.c2,a3@b3.c3", "a1@b1.c1;a2@b2.c2;a3@b3.c3" })
			@Nonnull final String oneOrMoreAddresses) {
		return withRecipientsWithDefaultName(null, singletonList(oneOrMoreAddresses), TO);
	}
	
	/**
	 * Alias for {@link #toWithFixedName(String, String...)}.
	 */
	public EmailPopulatingBuilder to(@Nullable final String name, @Nonnull final String... oneOrMoreAddressesEach) {
		return toWithFixedName(name, oneOrMoreAddressesEach);
	}
	
	/**
	 * Alias for {@link #toWithFixedName(String, Collection)}.
	 */
	public EmailPopulatingBuilder to(@Nullable final String name, @Nonnull final Collection<String> oneOrMoreAddressesEach) {
		return toWithFixedName(name, oneOrMoreAddressesEach);
	}
	
	/**
	 * Delegates to {@link #withRecipientsWithDefaultName(String, Collection, RecipientType)} with {@link RecipientType#TO} and empty default name.
	 */
	public EmailPopulatingBuilder toMultiple(@Nonnull final String... oneOrMoreAddressesEach) {
		return withRecipientsWithDefaultName(null, asList(oneOrMoreAddressesEach), TO);
	}

	/**
	 * Delegates to {@link #withRecipientsWithDefaultName(String, Collection, RecipientType)} with {@link RecipientType#TO} and empty default name.
	 */
	public EmailPopulatingBuilder toMultiple(@Nonnull final Collection<String> oneOrMoreAddressesEach) {
		return withRecipientsWithDefaultName(null, oneOrMoreAddressesEach, TO);
	}

	/**
	 * Delegates to {@link #withRecipientsWithFixedName(String, Collection, RecipientType)} with {@link RecipientType#TO}.
	 */
	public EmailPopulatingBuilder toWithFixedName(@Nullable final String name, @Nonnull final String... oneOrMoreAddressesEach) {
		return withRecipientsWithFixedName(name, asList(oneOrMoreAddressesEach), TO);
	}
	
	/**
	 * Delegates to {@link #withRecipientsWithDefaultName(String, Collection, RecipientType)} with {@link RecipientType#TO}.
	 */
	public EmailPopulatingBuilder toWithDefaultName(@Nonnull final String name, @Nonnull final String... oneOrMoreAddressesEach) {
		return withRecipientsWithDefaultName(name, asList(oneOrMoreAddressesEach), TO);
	}
	
	/**
	 * Delegates to {@link #withRecipientsWithFixedName(String, Collection, RecipientType)} with {@link RecipientType#TO}.
	 */
	public EmailPopulatingBuilder toWithFixedName(@Nullable final String name, @Nonnull final Collection<String> oneOrMoreAddressesEach) {
		return withRecipientsWithFixedName(name, oneOrMoreAddressesEach, TO);
	}
	
	/**
	 * Delegates to {@link #withRecipientsWithDefaultName(String, Collection, RecipientType)} with {@link RecipientType#TO}.
	 */
	public EmailPopulatingBuilder toWithDefaultName(@Nonnull final String name, @Nonnull final Collection<String> oneOrMoreAddressesEach) {
		return withRecipientsWithDefaultName(name, oneOrMoreAddressesEach, TO);
	}
	/*
		TO: InternetAddress
	 */
	
	/**
	 * Alias for {@link #toAddressesWithFixedName(String, InternetAddress...)}.
	 */
	public EmailPopulatingBuilder to(@Nullable final String name, InternetAddress address) {
		return toAddressesWithFixedName(name, address);
	}
	
	/**
	 * Delegates to {@link #withAddressesWithDefaultName(String, Collection, RecipientType)} with {@link RecipientType#TO} and empty default name.
	 */
	public EmailPopulatingBuilder to(@Nonnull final InternetAddress address) {
		return withAddressesWithDefaultName(null, singletonList(address), TO);
	}
	
	/**
	 * Alias for {@link #toAddressesWithFixedName(String, InternetAddress...)}.
	 */
	public EmailPopulatingBuilder to(@Nullable final String name, @Nonnull final InternetAddress... oneOrMoreAddressesEach) {
		return toAddressesWithFixedName(name, oneOrMoreAddressesEach);
	}
	
	/**
	 * Alias for {@link #toAddressesWithFixedName(String, Collection)}.
	 */
	public EmailPopulatingBuilder toAddresses(@Nullable final String name, @Nonnull final Collection<InternetAddress> oneOrMoreAddressesEach) {
		return toAddressesWithFixedName(name, oneOrMoreAddressesEach);
	}
	
	/**
	 * Delegates to {@link #withAddressesWithDefaultName(String, Collection, RecipientType)} with {@link RecipientType#TO} and empty default name.
	 */
	public EmailPopulatingBuilder toMultiple(@Nonnull final InternetAddress... oneOrMoreAddressesEach) {
		return withAddressesWithDefaultName(null, asList(oneOrMoreAddressesEach), TO);
	}
	
	/**
	 * Delegates to {@link #withAddressesWithDefaultName(String, Collection, RecipientType)} with {@link RecipientType#TO} and empty default name.
	 */
	public EmailPopulatingBuilder toMultipleAddresses(@Nonnull final Collection<InternetAddress> oneOrMoreAddressesEach) {
		return withAddressesWithDefaultName(null, oneOrMoreAddressesEach, TO);
	}
	
	/**
	 * Delegates to {@link #withAddressesWithFixedName(String, Collection, RecipientType)} with {@link RecipientType#TO}.
	 */
	public EmailPopulatingBuilder toAddressesWithFixedName(@Nullable final String name, @Nonnull final InternetAddress... oneOrMoreAddressesEach) {
		return withAddressesWithFixedName(name, asList(oneOrMoreAddressesEach), TO);
	}
	
	/**
	 * Delegates to {@link #withAddressesWithDefaultName(String, Collection, RecipientType)} with {@link RecipientType#TO}.
	 */
	public EmailPopulatingBuilder toAddressesWithDefaultName(@Nonnull final String name, @Nonnull final InternetAddress... oneOrMoreAddressesEach) {
		return withAddressesWithDefaultName(name, asList(oneOrMoreAddressesEach), TO);
	}
	
	/**
	 * Delegates to {@link #withAddressesWithFixedName(String, Collection, RecipientType)} with {@link RecipientType#TO}.
	 */
	public EmailPopulatingBuilder toAddressesWithFixedName(@Nullable final String name, @Nonnull final Collection<InternetAddress> oneOrMoreAddressesEach) {
		return withAddressesWithFixedName(name, oneOrMoreAddressesEach, TO);
	}
	
	/**
	 * Delegates to {@link #withAddressesWithDefaultName(String, Collection, RecipientType)} with {@link RecipientType#TO}.
	 */
	public EmailPopulatingBuilder toAddressesWithDefaultName(@Nonnull final String name, @Nonnull final Collection<InternetAddress> oneOrMoreAddressesEach) {
		return withAddressesWithDefaultName(name, oneOrMoreAddressesEach, TO);
	}
	/*
		CC: Recipient
	 */
	
	/**
	 * Delegates to {@link #withRecipients(Collection, RecipientType)} with {@link RecipientType#CC}.
	 */
	public EmailPopulatingBuilder cc(@Nonnull final Recipient... recipients) {
		return withRecipients(asList(recipients), CC);
	}
	
	/**
	 * Delegates to {@link #withRecipients(Collection, RecipientType)} with {@link RecipientType#CC}.
	 */
	public EmailPopulatingBuilder cc(@Nonnull final Collection<Recipient> recipients) {
		return withRecipients(recipients, CC);
	}
	/*
		CC: String
	 */
	
	/**
	 * Alias for {@link #ccWithFixedName(String, String...)}.
	 *
	 * @param name The optional name of the CC receiver(s) of the email. If multiples addresses are provided, all addresses will be in this same name.
	 * @param oneOrMoreAddresses Single RFC2822 address or delimited list of RFC2822 addresses of CC receiver(s). Any names included are ignored if a name was provided.
	 */
	public EmailPopulatingBuilder cc(@Nullable final String name, String oneOrMoreAddresses) {
		return ccWithFixedName(name, oneOrMoreAddresses);
	}
	
	/**
	 * Delegates to {@link #withRecipientsWithDefaultName(String, Collection, RecipientType)} with {@link RecipientType#CC} and empty default name.
	 *
	 * @param oneOrMoreAddresses Single RFC2822 address or delimited list of RFC2822 addresses.
	 */
	@Cli.ExcludeApi(reason = "API is subset of another API method")
	public EmailPopulatingBuilder cc(@Nonnull final String oneOrMoreAddresses) {
		return withRecipientsWithDefaultName(null, singletonList(oneOrMoreAddresses), CC);
	}
	
	/**
	 * Alias for {@link #ccWithFixedName(String, String...)}.
	 */
	public EmailPopulatingBuilder cc(@Nullable final String name, @Nonnull final String... oneOrMoreAddressesEach) {
		return ccWithFixedName(name, oneOrMoreAddressesEach);
	}
	
	/**
	 * Alias for {@link #ccWithFixedName(String, Collection)}.
	 */
	public EmailPopulatingBuilder cc(@Nullable final String name, @Nonnull final Collection<String> oneOrMoreAddressesEach) {
		return ccWithFixedName(name, oneOrMoreAddressesEach);
	}
	
	/**
	 * Delegates to {@link #withRecipientsWithDefaultName(String, Collection, RecipientType)} with {@link RecipientType#CC} and empty default name.
	 */
	public EmailPopulatingBuilder ccMultiple(@Nonnull final String... oneOrMoreAddressesEach) {
		return withRecipientsWithDefaultName(null, asList(oneOrMoreAddressesEach), CC);
	}
	
	/**
	 * Delegates to {@link #withRecipientsWithDefaultName(String, Collection, RecipientType)} with {@link RecipientType#CC} and empty default name.
	 */
	public EmailPopulatingBuilder ccAddresses(@Nonnull final Collection<String> oneOrMoreAddressesEach) {
		return withRecipientsWithDefaultName(null, oneOrMoreAddressesEach, CC);
	}
	
	/**
	 * Delegates to {@link #withRecipientsWithFixedName(String, Collection, RecipientType)} with {@link RecipientType#CC}.
	 */
	public EmailPopulatingBuilder ccWithFixedName(@Nullable final String name, @Nonnull final String... oneOrMoreAddressesEach) {
		return withRecipientsWithFixedName(name, asList(oneOrMoreAddressesEach), CC);
	}
	
	/**
	 * Delegates to {@link #withRecipientsWithDefaultName(String, Collection, RecipientType)} with {@link RecipientType#CC}.
	 */
	public EmailPopulatingBuilder ccWithDefaultName(@Nonnull final String name, @Nonnull final String... oneOrMoreAddressesEach) {
		return withRecipientsWithDefaultName(name, asList(oneOrMoreAddressesEach), CC);
	}
	
	/**
	 * Delegates to {@link #withRecipientsWithFixedName(String, Collection, RecipientType)} with {@link RecipientType#CC}.
	 */
	public EmailPopulatingBuilder ccWithFixedName(@Nullable final String name, @Nonnull final Collection<String> oneOrMoreAddressesEach) {
		return withRecipientsWithFixedName(name, oneOrMoreAddressesEach, CC);
	}
	
	/**
	 * Delegates to {@link #withRecipientsWithDefaultName(String, Collection, RecipientType)} with {@link RecipientType#CC}.
	 */
	public EmailPopulatingBuilder ccWithDefaultName(@Nonnull final String name, @Nonnull final Collection<String> oneOrMoreAddressesEach) {
		return withRecipientsWithDefaultName(name, oneOrMoreAddressesEach, CC);
	}
	/*
		CC: InternetAddress
	 */
	
	/**
	 * Alias for {@link #ccAddressesWithFixedName(String, InternetAddress...)}.
	 */
	public EmailPopulatingBuilder cc(@Nullable final String name, InternetAddress address) {
		return ccAddressesWithFixedName(name, address);
	}
	
	/**
	 * Delegates to {@link #withAddressesWithDefaultName(String, Collection, RecipientType)} with {@link RecipientType#CC} and empty default name.
	 */
	public EmailPopulatingBuilder cc(@Nonnull final InternetAddress address) {
		return withAddressesWithDefaultName(null, singletonList(address), CC);
	}
	
	/**
	 * Alias for {@link #ccAddressesWithFixedName(String, InternetAddress...)}.
	 */
	public EmailPopulatingBuilder cc(@Nullable final String name, @Nonnull final InternetAddress... oneOrMoreAddressesEach) {
		return ccAddressesWithFixedName(name, oneOrMoreAddressesEach);
	}
	
	/**
	 * Alias for {@link #ccAddressesWithFixedName(String, Collection)}.
	 */
	public EmailPopulatingBuilder ccAddresses(@Nullable final String name, @Nonnull final Collection<InternetAddress> oneOrMoreAddressesEach) {
		return ccAddressesWithFixedName(name, oneOrMoreAddressesEach);
	}
	
	/**
	 * Delegates to {@link #withAddressesWithDefaultName(String, Collection, RecipientType)} with {@link RecipientType#CC} and empty default name.
	 */
	public EmailPopulatingBuilder ccMultiple(@Nonnull final InternetAddress... oneOrMoreAddressesEach) {
		return withAddressesWithDefaultName(null, asList(oneOrMoreAddressesEach), CC);
	}
	
	/**
	 * Delegates to {@link #withAddressesWithDefaultName(String, Collection, RecipientType)} with {@link RecipientType#CC} and empty default name.
	 */
	public EmailPopulatingBuilder ccMultipleAddresses(@Nonnull final Collection<InternetAddress> oneOrMoreAddressesEach) {
		return withAddressesWithDefaultName(null, oneOrMoreAddressesEach, CC);
	}
	
	/**
	 * Delegates to {@link #withAddressesWithFixedName(String, Collection, RecipientType)} with {@link RecipientType#CC}.
	 */
	public EmailPopulatingBuilder ccAddressesWithFixedName(@Nullable final String name, @Nonnull final InternetAddress... oneOrMoreAddressesEach) {
		return withAddressesWithFixedName(name, asList(oneOrMoreAddressesEach), CC);
	}
	
	/**
	 * Delegates to {@link #withAddressesWithDefaultName(String, Collection, RecipientType)} with {@link RecipientType#CC}.
	 */
	public EmailPopulatingBuilder ccAddressesWithDefaultName(@Nonnull final String name, @Nonnull final InternetAddress... oneOrMoreAddressesEach) {
		return withAddressesWithDefaultName(name, asList(oneOrMoreAddressesEach), CC);
	}
	
	/**
	 * Delegates to {@link #withAddressesWithFixedName(String, Collection, RecipientType)} with {@link RecipientType#CC}.
	 */
	public EmailPopulatingBuilder ccAddressesWithFixedName(@Nullable final String name, @Nonnull final Collection<InternetAddress> oneOrMoreAddressesEach) {
		return withAddressesWithFixedName(name, oneOrMoreAddressesEach, CC);
	}
	
	/**
	 * Delegates to {@link #withAddressesWithDefaultName(String, Collection, RecipientType)} with {@link RecipientType#CC}.
	 */
	public EmailPopulatingBuilder ccAddressesWithDefaultName(@Nonnull final String name, @Nonnull final Collection<InternetAddress> oneOrMoreAddressesEach) {
		return withAddressesWithDefaultName(name, oneOrMoreAddressesEach, CC);
	}
	/*
		BCC: Recipient
	 */
	
	/**
	 * Delegates to {@link #withRecipients(Collection, RecipientType)} with {@link RecipientType#BCC}.
	 **/
	public EmailPopulatingBuilder bcc(@Nonnull final Recipient... recipients) {
		return withRecipients(asList(recipients), BCC);
	}
	
	/**
	 * Delegates to {@link #withRecipients(Collection, RecipientType)} with {@link RecipientType#BCC}.
	 **/
	public EmailPopulatingBuilder bcc(@Nonnull final Collection<Recipient> recipients) {
		return withRecipients(recipients, BCC);
	}
	
	/*
		BCC: String
	 */
	
	/**
	 * Alias for {@link #bccWithFixedName(String, String...)}.
	 *
	 * @param name The optional name of the BCC receiver(s) of the email. If multiples addresses are provided, all addresses will be in this same name.
	 * @param oneOrMoreAddresses Single RFC2822 address or delimited list of RFC2822 addresses of BCC receiver(s). Any names included are ignored if a name was provided.
	 */
	public EmailPopulatingBuilder bcc(@Nullable final String name, String oneOrMoreAddresses) {
		return bccWithFixedName(name, oneOrMoreAddresses);
	}
	
	/**
	 * Delegates to {@link #withRecipientsWithDefaultName(String, Collection, RecipientType)} with {@link RecipientType#BCC} and empty default name.
	 *
	 * @param oneOrMoreAddresses Single RFC2822 address or delimited list of RFC2822 addresses.
	 */
	@Cli.ExcludeApi(reason = "API is subset of another API")
	public EmailPopulatingBuilder bcc(@Nonnull final String oneOrMoreAddresses) {
		return withRecipientsWithDefaultName(null, singletonList(oneOrMoreAddresses), BCC);
	}
	
	/**
	 * Alias for {@link #bccWithFixedName(String, String...)}.
	 */
	public EmailPopulatingBuilder bcc(@Nullable final String name, @Nonnull final String... oneOrMoreAddressesEach) {
		return bccWithFixedName(name, oneOrMoreAddressesEach);
	}
	
	/**
	 * Alias for {@link #bccWithFixedName(String, Collection)}.
	 */
	public EmailPopulatingBuilder bcc(@Nullable final String name, @Nonnull final Collection<String> oneOrMoreAddressesEach) {
		return bccWithFixedName(name, oneOrMoreAddressesEach);
	}
	
	/**
	 * Delegates to {@link #withRecipientsWithDefaultName(String, Collection, RecipientType)} with {@link RecipientType#BCC} and empty default name.
	 */
	public EmailPopulatingBuilder bccMultiple(@Nonnull final String... oneOrMoreAddressesEach) {
		return withRecipientsWithDefaultName(null, asList(oneOrMoreAddressesEach), BCC);
	}
	
	/**
	 * Delegates to {@link #withRecipientsWithDefaultName(String, Collection, RecipientType)} with {@link RecipientType#BCC} and empty default name.
	 */
	public EmailPopulatingBuilder bccAddresses(@Nonnull final Collection<String> oneOrMoreAddressesEach) {
		return withRecipientsWithDefaultName(null, oneOrMoreAddressesEach, BCC);
	}
	
	/**
	 * Delegates to {@link #withRecipientsWithFixedName(String, Collection, RecipientType)} with {@link RecipientType#BCC}.
	 */
	public EmailPopulatingBuilder bccWithFixedName(@Nullable final String name, @Nonnull final String... oneOrMoreAddressesEach) {
		return withRecipientsWithFixedName(name, asList(oneOrMoreAddressesEach), BCC);
	}
	
	/**
	 * Delegates to {@link #withRecipientsWithDefaultName(String, Collection, RecipientType)} with {@link RecipientType#BCC}.
	 */
	public EmailPopulatingBuilder bccWithDefaultName(@Nonnull final String name, @Nonnull final String... oneOrMoreAddressesEach) {
		return withRecipientsWithDefaultName(name, asList(oneOrMoreAddressesEach), BCC);
	}
	
	/**
	 * Delegates to {@link #withRecipientsWithFixedName(String, Collection, RecipientType)} with {@link RecipientType#BCC}.
	 */
	public EmailPopulatingBuilder bccWithFixedName(@Nullable final String name, @Nonnull final Collection<String> oneOrMoreAddressesEach) {
		return withRecipientsWithFixedName(name, oneOrMoreAddressesEach, BCC);
	}
	
	/**
	 * Delegates to {@link #withRecipientsWithDefaultName(String, Collection, RecipientType)} with {@link RecipientType#BCC}.
	 */
	public EmailPopulatingBuilder bccWithDefaultName(@Nonnull final String name, @Nonnull final Collection<String> oneOrMoreAddressesEach) {
		return withRecipientsWithDefaultName(name, oneOrMoreAddressesEach, BCC);
	}
	/*
		BCC: InternetAddress
	 */
	
	/**
	 * Alias for {@link #bccAddressesWithFixedName(String, InternetAddress...)}.
	 */
	public EmailPopulatingBuilder bcc(@Nullable final String name, InternetAddress address) {
		return bccAddressesWithFixedName(name, address);
	}
	
	/**
	 * Delegates to {@link #withAddressesWithDefaultName(String, Collection, RecipientType)} with {@link RecipientType#BCC} and empty default name.
	 */
	public EmailPopulatingBuilder bcc(@Nonnull final InternetAddress address) {
		return withAddressesWithDefaultName(null, singletonList(address), BCC);
	}
	
	/**
	 * Alias for {@link #bccAddressesWithFixedName(String, InternetAddress...)}.
	 */
	public EmailPopulatingBuilder bcc(@Nullable final String name, @Nonnull final InternetAddress... oneOrMoreAddressesEach) {
		return bccAddressesWithFixedName(name, oneOrMoreAddressesEach);
	}
	
	/**
	 * Alias for {@link #bccAddressesWithFixedName(String, Collection)}.
	 */
	public EmailPopulatingBuilder bccAddresses(@Nullable final String name, @Nonnull final Collection<InternetAddress> oneOrMoreAddressesEach) {
		return bccAddressesWithFixedName(name, oneOrMoreAddressesEach);
	}
	
	/**
	 * Delegates to {@link #withAddressesWithDefaultName(String, Collection, RecipientType)} with {@link RecipientType#BCC} and empty default name.
	 */
	public EmailPopulatingBuilder bccMultiple(@Nonnull final InternetAddress... oneOrMoreAddressesEach) {
		return withAddressesWithDefaultName(null, asList(oneOrMoreAddressesEach), BCC);
	}
	
	/**
	 * Delegates to {@link #withAddressesWithDefaultName(String, Collection, RecipientType)} with {@link RecipientType#BCC} and empty default name.
	 */
	public EmailPopulatingBuilder bccMultipleAddresses(@Nonnull final Collection<InternetAddress> oneOrMoreAddressesEach) {
		return withAddressesWithDefaultName(null, oneOrMoreAddressesEach, BCC);
	}
	
	/**
	 * Delegates to {@link #withAddressesWithFixedName(String, Collection, RecipientType)} with {@link RecipientType#BCC}.
	 */
	public EmailPopulatingBuilder bccAddressesWithFixedName(@Nullable final String name, @Nonnull final InternetAddress... oneOrMoreAddressesEach) {
		return withAddressesWithFixedName(name, asList(oneOrMoreAddressesEach), BCC);
	}
	
	/**
	 * Delegates to {@link #withAddressesWithDefaultName(String, Collection, RecipientType)} with {@link RecipientType#BCC}.
	 */
	public EmailPopulatingBuilder bccAddressesWithDefaultName(@Nonnull final String name, @Nonnull final InternetAddress... oneOrMoreAddressesEach) {
		return withAddressesWithDefaultName(name, asList(oneOrMoreAddressesEach), BCC);
	}
	
	/**
	 * Delegates to {@link #withAddressesWithFixedName(String, Collection, RecipientType)} with {@link RecipientType#BCC}.
	 */
	public EmailPopulatingBuilder bccAddressesWithFixedName(@Nullable final String name, @Nonnull final Collection<InternetAddress> oneOrMoreAddressesEach) {
		return withAddressesWithFixedName(name, oneOrMoreAddressesEach, BCC);
	}
	
	/**
	 * Delegates to {@link #withAddressesWithDefaultName(String, Collection, RecipientType)} with {@link RecipientType#BCC}.
	 */
	public EmailPopulatingBuilder bccAddressesWithDefaultName(@Nonnull final String name, @Nonnull final Collection<InternetAddress> oneOrMoreAddressesEach) {
		return withAddressesWithDefaultName(name, oneOrMoreAddressesEach, BCC);
	}
	
	/**
	 * Delegates to {@link #withRecipients(String, boolean, Collection, RecipientType)}, leaving existing names in tact and defaulting when missing.
	 */
	@Nonnull
	public EmailPopulatingBuilder withRecipientsWithDefaultName(@Nullable final String defaultName, @Nonnull Collection<String> oneOrMoreAddressesEach, @Nullable RecipientType recipientType) {
		return withRecipients(defaultName, false, oneOrMoreAddressesEach, recipientType);
	}
	
	/**
	 * Delegates to {@link #withRecipients(String, boolean, Collection, RecipientType)}, assigning or overwriting existing names with the provided.
	 * name.
	 */
	@Nonnull
	public EmailPopulatingBuilder withRecipientsWithFixedName(@Nullable final String fixedName, @Nonnull Collection<String> oneOrMoreAddressesEach, @Nullable RecipientType recipientType) {
		return withRecipients(fixedName, true, oneOrMoreAddressesEach, recipientType);
	}
	
	/**
	 * Delegates to {@link #withRecipients(String, boolean, Collection, RecipientType)}.
	 */
	@Nonnull
	public EmailPopulatingBuilder withRecipientsWithDefaultName(@Nullable String name, @Nullable RecipientType recipientType, @Nonnull String... oneOrMoreAddressesEach) {
		return withRecipients(name, false, asList(oneOrMoreAddressesEach), recipientType);
	}
	
	/**
	 * Delegates to {@link #withRecipients(String, boolean, Collection, RecipientType)}.
	 */
	@Nonnull
	public EmailPopulatingBuilder withRecipientsWithFixedName(@Nullable String name, @Nullable RecipientType recipientType, @Nonnull String... oneOrMoreAddressesEach) {
		return withRecipients(name, true, asList(oneOrMoreAddressesEach), recipientType);
	}
	
	/**
	 * Delegates to {@link #withRecipients(String, boolean, Collection, RecipientType)}.
	 */
	@Nonnull
	public EmailPopulatingBuilder withRecipients(@Nullable String name, boolean fixedName, @Nullable RecipientType recipientType, @Nonnull String... oneOrMoreAddressesEach) {
		return withRecipients(name, fixedName, asList(oneOrMoreAddressesEach), recipientType);
	}
	
	/**
	 * Delegates to {@link #withRecipient(Recipient)} for each address found in not just the collection, but also in every individual address string.
	 *
	 * @param fixedName              Indicates whether the provided name should be applied to all addresses, or only to those where a name is
	 *                               missing.
	 * @param oneOrMoreAddressesEach Collection of addresses. Each entry itself can be a delimited list of RFC2822 addresses.
	 */
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
	 * Delegates to {@link #withAddresses(String, boolean, Collection, RecipientType)}, leaving existing names in tact and defaulting when missing.
	 */
	@Nonnull
	public EmailPopulatingBuilder withAddressesWithDefaultName(@Nullable final String defaultName, @Nonnull Collection<InternetAddress> addresses, @Nullable RecipientType recipientType) {
		return withAddresses(defaultName, false, addresses, recipientType);
	}
	
	/**
	 * Delegates to {@link #withAddresses(String, boolean, Collection, RecipientType)}, assigning or overwriting existing names with the provided.
	 */
	@Nonnull
	public EmailPopulatingBuilder withAddressesWithFixedName(@Nullable final String fixedName, @Nonnull Collection<InternetAddress> addresses, @Nullable RecipientType recipientType) {
		return withAddresses(fixedName, true, addresses, recipientType);
	}
	
	/**
	 * Delegates to {@link #withRecipient(String, String, RecipientType)} for each address in the provided collection.
	 *
	 * @param fixedName Indicates whether the provided name should be applied to all addresses, or only to those where a name is missing.
	 */
	@Nonnull
	public EmailPopulatingBuilder withAddresses(@Nullable String name, boolean fixedName, @Nonnull Collection<InternetAddress> addresses, @Nullable RecipientType recipientType) {
		for (InternetAddress address : addresses) {
			String effectiveName = (fixedName || valueNullOrEmpty(address.getPersonal())) ? name : address.getPersonal();
			withRecipient(effectiveName, address.getAddress(), recipientType);
		}
		return this;
	}
	
	/**
	 * Delegates to {@link #withRecipients(Collection, RecipientType)} with {@link RecipientType} left empty (so it will use the original values).
	 */
	public EmailPopulatingBuilder withRecipients(@Nonnull final Collection<Recipient> recipients) {
		return withRecipients(recipients, null);
	}
	
	/**
	 * Delegates to {@link #withRecipients(Collection, RecipientType)} with {@link RecipientType} left empty (so it will use the original values).
	 */
	public EmailPopulatingBuilder withRecipients(@Nonnull final Recipient... recipients) {
		return withRecipients(asList(recipients), null);
	}
	
	/**
	 * Delegates to {@link #withRecipient(String, String, RecipientType)} for each recipient in the provided collection, optionally fixing the
	 * recipientType for all recipients to the provided type.
	 *
	 * @param fixedRecipientType Optional. Fixes all recipients to the given type. If omitted, the types are not removed, but kept as-is.
	 */
	@Nonnull
	public EmailPopulatingBuilder withRecipients(@Nonnull Collection<Recipient> recipients, @Nullable RecipientType fixedRecipientType) {
		for (Recipient recipient : recipients) {
			withRecipient(recipient.getName(), recipient.getAddress(), defaultTo(fixedRecipientType, recipient.getType()));
		}
		return this;
	}
	
	/**
	 * Delegates to {@link #withRecipient(String, String, RecipientType)} with the name omitted.
	 */
	public EmailPopulatingBuilder withRecipient(@Nonnull final String singleAddress, @Nullable final RecipientType recipientType) {
		return withRecipient(null, singleAddress, recipientType);
	}
	
	/**
	 * Adds a new {@link Recipient} instance with the given name, address and {@link RecipientType}.
	 * <p>
	 * Note that the email address must be a single address according to RFC2822 format. Name can be provided explicitly or as part of the RFC2822 email
	 * address or omitted completely. If provided as method argument, the name overrides any nested name.
	 *
	 * @param name          Optional explicit name. Can be included in the email address instead, or omitted completely. A name will show as {@code
	 *                      "Name Here <address@domain.com>"}
	 * @param singleAddress A single address according to RFC2822 format with or without personal name.
	 * @param recipientType Optional type of recipient. This is needed for TO, CC and BCC, but not for <em>bounceTo</em>, <em>returnReceiptTo</em>,
	 *                      <em>replyTo</em>, <em>from</em> etc.
	 */
	public EmailPopulatingBuilder withRecipient(@Nullable final String name, @Nonnull final String singleAddress, @Nullable final RecipientType recipientType) {
		recipients.add(MiscUtil.interpretRecipient(name, true, singleAddress, recipientType));
		return this;
	}
	
	/**
	 * Adds a new {@link Recipient} instance as copy of the provided recipient (copying name, address and {@link RecipientType}).
	 * <p>
	 * Note that the email address must be a single address according to RFC2822 format. Name can be provided explicitly or as part of the RFC2822 email
	 * address or omitted completely.
	 */
	public EmailPopulatingBuilder withRecipient(@Nonnull final Recipient recipient) {
		recipients.add(new Recipient(recipient.getName(), recipient.getAddress(), recipient.getType()));
		return this;
	}
	
	/**
	 * Delegates to {@link #withEmbeddedImage(String, DataSource)}, with a named {@link ByteArrayDataSource} created using the provided name, data and
	 * mimetype.
	 *
	 * @param name     The name of the image as being referred to from the message content body (eg. 'signature').
	 * @param data     The byte data of the image to be embedded.
	 * @param mimetype The content type of the given data (eg. "image/gif" or "image/jpeg").
	 */
	public EmailPopulatingBuilder withEmbeddedImage(@Nonnull final String name, @Nonnull final byte[] data, @Nonnull final String mimetype) {
		checkNonEmptyArgument(name, "name");
		checkNonEmptyArgument(data, "data");
		checkNonEmptyArgument(mimetype, "mimetype");
		
		final ByteArrayDataSource dataSource = new ByteArrayDataSource(data, mimetype);
		dataSource.setName(name);
		return withEmbeddedImage(name, dataSource);
	}
	
	/**
	 * Adds image data to this email that can be referred to from the email HTML body. For adding images as attachment, refer to {@link
	 * #withAttachment(String, DataSource)} instead.
	 * <p>
	 * The provided {@link DataSource} is assumed to be of mimetype png, jpg or whatever the email client supports as valid image embedded in HTML
	 * content.
	 *
	 * @param name      The name of the image as being referred to from the message content body (eg. 'src="cid:yourImageName"'). If not provided, the
	 *                  name of the given data source is used instead.
	 * @param imagedata The image data.
	 *
	 * @see EmailPopulatingBuilder#withEmbeddedImage(String, byte[], String)
	 * @see EmailPopulatingBuilder#withEmbeddedImages(List)
	 */
	public EmailPopulatingBuilder withEmbeddedImage(@Nullable final String name, @Nonnull final DataSource imagedata) {
		checkNonEmptyArgument(imagedata, "imagedata");
		if (valueNullOrEmpty(name) && valueNullOrEmpty(imagedata.getName())) {
			throw new EmailException(EmailException.NAME_MISSING_FOR_EMBEDDED_IMAGE);
		}
		embeddedImages.add(new AttachmentResource(name, imagedata));
		return this;
	}
	
	/**
	 * Delegates to {@link #withEmbeddedImage(String, DataSource)} for each embedded image.
	 */
	public EmailPopulatingBuilder withEmbeddedImages(@Nonnull final List<AttachmentResource> embeddedImages) {
		for (final AttachmentResource embeddedImage : embeddedImages) {
			withEmbeddedImage(embeddedImage.getName(), embeddedImage.getDataSource());
		}
		return this;
	}
	
	/**
	 * Delegates to {@link #withHeader(String, Object)} for each header in the provided {@code Map}.
	 */
	public <T> EmailPopulatingBuilder withHeaders(@Nonnull final Map<String, T> headers) {
		for (Map.Entry<String, T> headerEntry : headers.entrySet()) {
			withHeader(headerEntry.getKey(), headerEntry.getValue());
		}
		return this;
	}
	
	/**
	 * Adds a header to the {@link #headers} list. The value is stored as a <code>String</code>. example: <code>email.addHeader("X-Priority",
	 * 2)</code>
	 *
	 * @param name  The name of the header.
	 * @param value The value of the header, which will be stored using {@link String#valueOf(Object)}.
	 *
	 * @see #withHeaders(Map)
	 */
	public EmailPopulatingBuilder withHeader(@Nonnull final String name, @Nullable final Object value) {
		checkNonEmptyArgument(name, "name");
		headers.put(name, value != null ? String.valueOf(value) : null);
		return this;
	}
	
	/**
	 * Delegates to {@link #withAttachment(String, DataSource)}, with a named {@link ByteArrayDataSource} created using the provided name, data and
	 * mimetype.
	 *
	 * @param name     Optional name of the attachment (eg. 'filename.ext'). If omitted, the internal name of the datasource is used. If that too is
	 *                 empty, a name will be generated using {@link java.util.UUID}.
	 * @param data     The binary data of the attachment.
	 * @param mimetype The content type of the given data (eg. "plain/text", "image/gif" or "application/pdf").
	 *
	 * @see #withAttachment(String, DataSource)
	 * @see #withAttachments(List)
	 */
	public EmailPopulatingBuilder withAttachment(@Nullable final String name, @Nonnull final byte[] data, @Nonnull final String mimetype) {
		checkNonEmptyArgument(data, "data");
		checkNonEmptyArgument(mimetype, "mimetype");
		final ByteArrayDataSource dataSource = new ByteArrayDataSource(data, mimetype);
		dataSource.setName(MiscUtil.encodeText(name));
		withAttachment(name, dataSource);
		return this;
	}
	
	/**
	 * Adds an attachment to the email message, which will be shown in the email client as seperate files available for download or inline display if
	 * the client supports it (for example, most browsers these days display PDF's in a popup).
	 * <p>
	 * Note: for embedding images instead of attaching them for download, refer to {@link #withEmbeddedImage(String, DataSource)} instead.
	 *
	 * @param name     Optional name of the attachment (eg. 'filename.ext'). If omitted, the internal name of the datasource is used. If that too is
	 *                 empty, a name will be generated using {@link java.util.UUID}.
	 * @param filedata The attachment data.
	 *
	 * @see #withAttachment(String, byte[], String)
	 * @see #withAttachments(List)
	 */
	public EmailPopulatingBuilder withAttachment(@Nullable final String name, @Nonnull final DataSource filedata) {
		checkNonEmptyArgument(filedata, "filedata");
		attachments.add(new AttachmentResource(MiscUtil.encodeText(name), filedata));
		return this;
	}
	
	/**
	 * Delegates to {@link #withAttachment(String, DataSource)} for each attachment.
	 */
	public EmailPopulatingBuilder withAttachments(@Nonnull final List<AttachmentResource> attachments) {
		for (final AttachmentResource attachment : attachments) {
			withAttachment(attachment.getName(), attachment.getDataSource());
		}
		return this;
	}
	
	/**
	 * Delegates to {@link #signWithDomainKey(InputStream, String, String)} with a {@link ByteArrayInputStream} wrapped around the prodived {@code
	 * dkimPrivateKey} data.
	 */
	public EmailPopulatingBuilder signWithDomainKey(@Nonnull final byte[] dkimPrivateKey, @Nonnull final String signingDomain, @Nonnull final String dkimSelector) {
		checkNonEmptyArgument(dkimPrivateKey, "dkimPrivateKey");
		return signWithDomainKey(new ByteArrayInputStream(dkimPrivateKey), signingDomain, dkimSelector);
	}
	
	/**
	 * Delegates to {@link #signWithDomainKey(InputStream, String, String)} with a {@link ByteArrayInputStream} wrapped around the prodived {@code
	 * dkimPrivateKey} string converted to UTF_8 byte array.
	 */
	@Cli.ExcludeApi(reason = "delegated method is a superset of this method")
	public EmailPopulatingBuilder signWithDomainKey(@Nonnull final String dkimPrivateKey, @Nonnull final String signingDomain, @Nonnull final String dkimSelector) {
		checkNonEmptyArgument(dkimPrivateKey, "dkimPrivateKey");
		return signWithDomainKey(new ByteArrayInputStream(dkimPrivateKey.getBytes(UTF_8)), signingDomain, dkimSelector);
	}
	
	/**
	 * Primes this email for signing with a DKIM domain key. Actual signing is done when sending using a <code>Mailer</code>.
	 * <p>
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
	 * @param dkimSelector              Additional domain specifier.
	 *
	 * @see #signWithDomainKey(byte[], String, String)
	 * @see #signWithDomainKey(String, String, String)
	 * @see #signWithDomainKey(File, String, String)
	 */
	public EmailPopulatingBuilder signWithDomainKey(@Nonnull final InputStream dkimPrivateKeyInputStream, @Nonnull final String signingDomain,
													@Nonnull final String dkimSelector) {
		this.dkimPrivateKeyInputStream = checkNonEmptyArgument(dkimPrivateKeyInputStream, "dkimPrivateKeyInputStream");
		this.dkimSigningDomain = checkNonEmptyArgument(signingDomain, "dkimSigningDomain");
		this.dkimSelector = checkNonEmptyArgument(dkimSelector, "dkimSelector");
		return this;
	}
	
	/**
	 * As {@link #signWithDomainKey(InputStream, String, String)}, but with a File reference that is later read as {@code InputStream}.
	 */
	public EmailPopulatingBuilder signWithDomainKey(@Nonnull final File dkimPrivateKeyFile, @Nonnull final String signingDomain, @Nonnull final String dkimSelector) {
		this.dkimPrivateKeyFile = checkNonEmptyArgument(dkimPrivateKeyFile, "dkimPrivateKeyFile");
		this.dkimSigningDomain = checkNonEmptyArgument(signingDomain, "dkimSigningDomain");
		this.dkimSelector = checkNonEmptyArgument(dkimSelector, "dkimSelector");
		return this;
	}
	
	/**
	 * Indicates that we want to use the NPM flag {@link #dispositionNotificationTo}. The actual address will default to the {@link #replyToRecipient}
	 * first if set or else {@link #fromRecipient} (the final address is determined when sending this email).
	 *
	 * @see #withDispositionNotificationTo(Recipient)
	 */
	@Cli.OptionNameOverride("withDispositionNotificationToEnabled")
	public EmailPopulatingBuilder withDispositionNotificationTo() {
		this.useDispositionNotificationTo = true;
		this.dispositionNotificationTo = null;
		return this;
	}
	
	/**
	 * Delegates to {@link #withDispositionNotificationTo(Recipient)} with a new {@link Recipient} wrapped around the provided address.
	 *
	 * @param address The address of the receiver of the notification
	 */
	@Cli.ExcludeApi(reason = "API is subset of another API")
	public EmailPopulatingBuilder withDispositionNotificationTo(@Nonnull final String address) {
		checkNonEmptyArgument(address, "dispositionNotificationToAddress");
		return withDispositionNotificationTo(new Recipient(null, address, null));
	}
	
	/**
	 * Delegates to {@link #withDispositionNotificationTo(Recipient)} with a new {@link Recipient} wrapped around the provided name and address.
	 *
	 * @param name Optional name of the receiver of the notification
	 * @param address The address of the receiver of the notification
	 */
	public EmailPopulatingBuilder withDispositionNotificationTo(@Nullable final String name, @Nonnull final String address) {
		checkNonEmptyArgument(address, "dispositionNotificationToAddress");
		return withDispositionNotificationTo(new Recipient(name, address, null));
	}
	
	/**
	 * Delegates to {@link #withDispositionNotificationTo(Recipient)} with a new {@link Recipient} wrapped around the provided address.
	 */
	public EmailPopulatingBuilder withDispositionNotificationTo(@Nonnull final InternetAddress address) {
		checkNonEmptyArgument(address, "dispositionNotificationToAddress");
		return withDispositionNotificationTo(new Recipient(address.getPersonal(), address.getAddress(), null));
	}
	
	/**
	 * Delegates to {@link #withDispositionNotificationTo(Recipient)} with a new {@link Recipient} wrapped around the provided fixed name and address.
	 */
	public EmailPopulatingBuilder withDispositionNotificationTo(@Nullable final String fixedName, @Nonnull final InternetAddress address) {
		checkNonEmptyArgument(address, "dispositionNotificationToAddress");
		return withDispositionNotificationTo(new Recipient(fixedName, address.getAddress(), null));
	}
	
	/**
	 * Indicates the this email should use the <a href="https://tools.ietf.org/html/rfc8098">NPM flag "Disposition-Notification-To"</a> with the given
	 * preconfigred {@link Recipient}. This flag can be used to request a return receipt from the recipient to signal that the recipient has read the
	 * email.
	 * <p>
	 * This flag may be ignored by SMTP clients (for example gmail ignores it completely, while the Google Apps business suite honors it).
	 *
	 * @see #withDispositionNotificationTo()
	 * @see #withDispositionNotificationTo(String)
	 * @see #withDispositionNotificationTo(String, String)
	 */
	public EmailPopulatingBuilder withDispositionNotificationTo(@Nonnull final Recipient recipient) {
		checkNonEmptyArgument(recipient.getAddress(), "recipient.address");
		this.useDispositionNotificationTo = true;
		this.dispositionNotificationTo = new Recipient(recipient.getName(), recipient.getAddress(), null);
		return this;
	}
	
	/**
	 * Indicates that we want to use the flag {@link #returnReceiptTo}. The actual address will default to the {@link #replyToRecipient} first if set
	 * or else {@link #fromRecipient} (the final address is determined when sending the email).
	 * <p>
	 * For more detailed information, refer to {@link #withReturnReceiptTo(Recipient)}.
	 */
	@Cli.OptionNameOverride("withReturnReceiptToEnabled")
	public EmailPopulatingBuilder withReturnReceiptTo() {
		this.useReturnReceiptTo = true;
		this.returnReceiptTo = null;
		return this;
	}
	
	/**
	 * Delegates to {@link #withReturnReceiptTo(Recipient)} with a new {@link Recipient} wrapped around the provided address.
	 *
	 * @param address The address of the receiver of the bounced email
	 */
	@Cli.ExcludeApi(reason = "API is subset of another API")
	public EmailPopulatingBuilder withReturnReceiptTo(@Nonnull final String address) {
		checkNonEmptyArgument(address, "address");
		return withReturnReceiptTo(new Recipient(null, address, null));
	}
	
	/**
	 * Delegates to {@link #withReturnReceiptTo(Recipient)} with a new {@link Recipient} wrapped around the provided name and address.
	 *
	 * @param name Name of the receiver of the receipt notification
	 * @param address The address of the receiver of the receipt notification
	 */
	public EmailPopulatingBuilder withReturnReceiptTo(@Nullable final String name, @Nonnull final String address) {
		checkNonEmptyArgument(address, "address");
		return withReturnReceiptTo(new Recipient(name, address, null));
	}
	
	/**
	 * Delegates to {@link #withReturnReceiptTo(Recipient)} with a new {@link Recipient} wrapped around the provided address.
	 */
	public EmailPopulatingBuilder withReturnReceiptTo(@Nonnull final InternetAddress address) {
		checkNonEmptyArgument(address, "address");
		return withReturnReceiptTo(new Recipient(address.getPersonal(), address.getAddress(), null));
	}
	
	/**
	 * Delegates to {@link #withReturnReceiptTo(Recipient)} with a new {@link Recipient} wrapped around the provided fixed name and address.
	 */
	public EmailPopulatingBuilder withReturnReceiptTo(@Nullable final String fixedName, @Nonnull final InternetAddress address) {
		checkNonEmptyArgument(address, "address");
		return withReturnReceiptTo(new Recipient(fixedName, address.getAddress(), null));
	}
	
	/**
	 * Indicates that this email should use the <a href="https://en.wikipedia.org/wiki/Return_receipt">RRT flag "Return-Receipt-To"</a> with the
	 * preconfigured {@link Recipient}. This flag can be used to request a notification from the SMTP server recipient to signal that the recipient
	 * has read the email.
	 * <p>
	 * This flag is rarely used, but your mail server / client might implement this flag to automatically send back a notification that the email was
	 * received on the mail server or opened in the client, depending on the chosen implementation.
	 */
	public EmailPopulatingBuilder withReturnReceiptTo(@Nonnull final Recipient recipient) {
		checkNonEmptyArgument(recipient.getAddress(), "recipient.address");
		this.useReturnReceiptTo = true;
		this.returnReceiptTo = new Recipient(recipient.getName(), recipient.getAddress(), null);
		return this;
	}
	
	/**
	 * Resets {@link #id} to empty.
	 */
	public EmailPopulatingBuilder clearId() {
		this.id = null;
		return this;
	}
	
	/**
	 * Resets {@link #fromRecipient} to empty.
	 */
	public EmailPopulatingBuilder clearFromRecipient() {
		this.fromRecipient = null;
		return this;
	}
	
	/**
	 * Resets {@link #replyToRecipient} to empty.
	 */
	public EmailPopulatingBuilder clearReplyTo() {
		this.replyToRecipient = null;
		return this;
	}
	
	/**
	 * Resets {@link #bounceToRecipient} to empty.
	 */
	public EmailPopulatingBuilder clearBounceTo() {
		this.bounceToRecipient = null;
		return this;
	}
	
	/**
	 * Resets {@link #text} to empty.
	 */
	public EmailPopulatingBuilder clearPlainText() {
		this.text = null;
		return this;
	}
	
	/**
	 * Resets {@link #textHTML} to empty.
	 */
	public EmailPopulatingBuilder clearHTMLText() {
		this.textHTML = null;
		return this;
	}
	
	/**
	 * Resets {@link #subject} to empty.
	 */
	public EmailPopulatingBuilder clearSubject() {
		this.subject = null;
		return this;
	}
	
	/**
	 * Resets {@link #recipients} to empty.
	 */
	public EmailPopulatingBuilder clearRecipients() {
		this.recipients.clear();
		return this;
	}
	
	/**
	 * Resets {@link #embeddedImages} to empty.
	 */
	public EmailPopulatingBuilder clearEmbeddedImages() {
		this.embeddedImages.clear();
		return this;
	}
	
	/**
	 * Resets {@link #attachments} to empty.
	 */
	public EmailPopulatingBuilder clearAttachments() {
		this.attachments.clear();
		return this;
	}
	
	/**
	 * Resets {@link #headers} to empty.
	 */
	public EmailPopulatingBuilder clearHeaders() {
		this.headers.clear();
		return this;
	}
	
	/**
	 * Resets all dkim properties to empty.
	 */
	public EmailPopulatingBuilder clearDkim() {
		this.dkimPrivateKeyFile = null;
		this.dkimPrivateKeyInputStream = null;
		this.dkimSigningDomain = null;
		this.dkimSelector = null;
		return this;
	}
	
	/**
	 * Resets {@link #dispositionNotificationTo} to empty.
	 */
	public EmailPopulatingBuilder clearDispositionNotificationTo() {
		this.useDispositionNotificationTo = false;
		this.dispositionNotificationTo = null;
		return this;
	}
	
	/**
	 * Resets {@link #returnReceiptTo} to empty.
	 */
	public EmailPopulatingBuilder clearReturnReceiptTo() {
		this.useReturnReceiptTo = false;
		this.returnReceiptTo = null;
		return this;
	}
	
	/*
		GETTERS
	 */
	
	/**
	 * @see #fixingMessageId(String)
	 */
	public String getId() {
		return id;
	}
	
	/**
	 * @see #from(Recipient)
	 */
	public Recipient getFromRecipient() {
		return fromRecipient;
	}
	
	/**
	 * @see #withReplyTo(Recipient)
	 */
	public Recipient getReplyToRecipient() {
		return replyToRecipient;
	}
	
	/**
	 * @see #withBounceTo(Recipient)
	 */
	public Recipient getBounceToRecipient() {
		return bounceToRecipient;
	}
	
	/**
	 * @see #withPlainText(String)
	 */
	public String getText() {
		return text;
	}
	
	/**
	 * @see #withHTMLText(String)
	 */
	public String getTextHTML() {
		return textHTML;
	}

	/**
	 * @see #withCalendarText(CalendarMethod, String)
	 */
	public CalendarMethod getCalendarMethod () {
		 return calendarMethod;
	}

	public String getTextCalendar () {
		return textCalendar;
	}

	/**
	 * @see #withSubject(String)
	 */
	public String getSubject() {
		return subject;
	}
	
	/**
	 * @see #to(Recipient...)
	 * @see #cc(Recipient...)
	 * @see #bcc(Recipient...)
	 */
	public List<Recipient> getRecipients() {
		return new ArrayList<>(recipients);
	}
	
	/**
	 * @see #withEmbeddedImage(String, DataSource)
	 */
	public List<AttachmentResource> getEmbeddedImages() {
		return new ArrayList<>(embeddedImages);
	}
	
	/**
	 * @see #withAttachment(String, DataSource)
	 */
	public List<AttachmentResource> getAttachments() {
		return new ArrayList<>(attachments);
	}
	
	/**
	 * @see #withHeader(String, Object)
	 * @see EmailBuilderInstance#replyingTo(MimeMessage, boolean, String)
	 */
	public Map<String, String> getHeaders() {
		return new HashMap<>(headers);
	}
	
	/**
	 * @see #signWithDomainKey(File, String, String)
	 */
	public File getDkimPrivateKeyFile() {
		return dkimPrivateKeyFile;
	}
	
	/**
	 * @see #signWithDomainKey(InputStream, String, String)
	 */
	public InputStream getDkimPrivateKeyInputStream() {
		return dkimPrivateKeyInputStream;
	}
	
	/**
	 * @see #signWithDomainKey(InputStream, String, String)
	 * @see #signWithDomainKey(File, String, String)
	 */
	public String getDkimSigningDomain() {
		return dkimSigningDomain;
	}
	
	/**
	 * @see #signWithDomainKey(InputStream, String, String)
	 * @see #signWithDomainKey(File, String, String)
	 */
	public String getDkimSelector() {
		return dkimSelector;
	}
	
	/**
	 * @see #withDispositionNotificationTo()
	 * @see #withDispositionNotificationTo(Recipient)
	 */
	public boolean isUseDispositionNotificationTo() {
		return useDispositionNotificationTo;
	}
	
	/**
	 * @see #withDispositionNotificationTo()
	 * @see #withDispositionNotificationTo(Recipient)
	 */
	public Recipient getDispositionNotificationTo() {
		return dispositionNotificationTo;
	}
	
	/**
	 * @see #withReturnReceiptTo()
	 * @see #withReturnReceiptTo(Recipient)
	 */
	public boolean isUseReturnReceiptTo() {
		return useReturnReceiptTo;
	}
	
	/**
	 * @see #withReturnReceiptTo()
	 * @see #withReturnReceiptTo(Recipient)
	 */
	public Recipient getReturnReceiptTo() {
		return returnReceiptTo;
	}
	
	/**
	 * @see EmailBuilder#forwarding(MimeMessage)
	 */
	public MimeMessage getEmailToForward() {
		return emailToForward;
	}
}