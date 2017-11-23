package org.simplejavamail.email;

import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.converter.internal.mimemessage.MimeMessageParser;
import org.simplejavamail.internal.util.MiscUtil;

import javax.activation.DataSource;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.mail.Message;
import javax.mail.MessagingException;
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
import java.util.regex.Pattern;

import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static java.util.regex.Pattern.compile;
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
 * Fluent interface Builder for building {@link Email} instances.
 *
 * @author Benny Bottema (early work also by Jared Stewart)
 */
@SuppressWarnings("UnusedReturnValue")
public class EmailBuilder {

	/**
	 * Used for replying to emails, when quoting the original email. Matches the beginning of every line.
	 *
	 * @see #asReplyTo(MimeMessage, boolean, String)
	 */
	private static final Pattern LINE_START_PATTERN = compile("(?m)^");

	/**
	 * Default simple quoting markup for email replies.
	 * <p>
	 * <code>{@value DEFAULT_QUOTING_MARKUP}</code>
	 *
	 * @see #asReplyTo(MimeMessage, boolean, String)
	 */
	private static final String DEFAULT_QUOTING_MARKUP = "<blockquote style=\"color: gray; border-left: 1px solid #4f4f4f; padding-left: " +
			"1cm\">%s</blockquote>";

	/**
	 * @see #id(String)
	 */
	private String id;
	
	/**
	 * @see #from(Recipient)
	 */
	private Recipient fromRecipient;
	
	/**
	 * @see #replyTo(Recipient)
	 */
	private Recipient replyToRecipient;
	
	/**
	 * @see #bounceTo(Recipient)
	 */
	private Recipient bounceToRecipient;
	
	/**
	 * @see #text(String)
	 */
	private String text;
	
	/**
	 * @see #textHTML(String)
	 */
	private String textHTML;
	
	/**
	 * @see #subject(String)
	 */
	private String subject;
	
	/**
	 * @see #to(Recipient...)
	 * @see #cc(Recipient...)
	 * @see #bcc(Recipient...)
	 */
	private final Set<Recipient> recipients;
	
	/**
	 * @see #embedImage(String, DataSource)
	 */
	private final List<AttachmentResource> embeddedImages;
	
	/**
	 * @see #addAttachment(String, DataSource)
	 */
	private final List<AttachmentResource> attachments;
	
	/**
	 * @see #addHeader(String, Object)
	 * @see #asReplyTo(MimeMessage, boolean, String)
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
	private String signingDomain;
	
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
	 * @see #asForwardOf(MimeMessage)
	 */
	private MimeMessage emailToForward;

	/**
	 * FIXME:
	 * describe what the constructor does related to one of the static builder starters, while referring to the common initialization (either in some super constructor, or because
	 * the static builder starters internally all start with email().
	 */
	// FIXME split up to email(), asReplyTo(), asForwardOf()
	public static EmailBuilder builder() {
		return new EmailBuilder();
	}

	/**
	 * @see EmailBuilder#builder()
	 */
	private EmailBuilder() {
		recipients = new HashSet<>();
		embeddedImages = new ArrayList<>();
		attachments = new ArrayList<>();
		headers = new HashMap<>();
		
		if (hasProperty(DEFAULT_FROM_ADDRESS)) {
			from((String) getProperty(DEFAULT_FROM_NAME), (String) getProperty(DEFAULT_FROM_ADDRESS));
		}
		if (hasProperty(DEFAULT_REPLYTO_ADDRESS)) {
			replyTo((String) getProperty(DEFAULT_REPLYTO_NAME), (String) getProperty(DEFAULT_REPLYTO_ADDRESS));
		}
		if (hasProperty(DEFAULT_BOUNCETO_ADDRESS)) {
			bounceTo((String) getProperty(DEFAULT_BOUNCETO_NAME), (String) getProperty(DEFAULT_BOUNCETO_ADDRESS));
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
			subject((String) getProperty(DEFAULT_SUBJECT));
		}
	}

	/**
	 * @return A new immutable {@link Email} instance populated with all the data set on this builder instance.
	 */
	public Email build() {
		return new Email(this);
	}
	
	/**
	 * Sets optional ID, which will be used when sending using the underlying Java Mail framework. Will be generated otherwise.
	 * <p>
	 * Note that id can only ever be filled by end-users for sending an email. This library will never fill this field when converting a MimeMessage.
	 * <p>
	 * The id-format should be conform <a href="https://tools.ietf.org/html/rfc5322#section-3.6.4">rfc5322#section-3.6.4</a>
	 */
	public EmailBuilder id(@Nullable final String id) {
		this.id = id;
		return this;
	}
	
	/**
	 * Delegates to {@link #from(String, String)} with empty name.
	 */
	public EmailBuilder from(@Nonnull final String fromAddress) {
		return from(null, fromAddress);
	}
	
	/**
	 * Delegates to {@link #from(Recipient)} with given name and email address.
	 */
	public EmailBuilder from(@Nullable final String name, @Nonnull final String fromAddress) {
		return from(new Recipient(name, checkNonEmptyArgument(fromAddress, "fromAddress"), null));
	}

	/**
	 * Sets the address of the sender of this email with given {@link Recipient} (ignoring its {@link javax.mail.Message.RecipientType} if provided).
	 * <p>
	 * Can be used in conjunction with one of the {@code replyTo(...)} methods, which is then prioritized by email clients when replying to this email.
	 *
	 * @param recipient Preconfigured recipient which includes optional name and mandatory email address.
	 *
	 * @see #from(String, String)
	 * @see #from(String)
	 * @see #replyTo(Recipient)
	 */
	public EmailBuilder from(@Nonnull final Recipient recipient) {
		checkNonEmptyArgument(recipient, "recipient");
		this.fromRecipient = new Recipient(recipient.getName(), recipient.getAddress(), null);
		return this;
	}
	
	/**
	 * Delegates to {@link #replyTo(Recipient)} with given name and email address.
	 */
	public EmailBuilder replyTo(@Nullable final String name, @Nonnull final String replyToAddress) {
		return replyTo(new Recipient(name, checkNonEmptyArgument(replyToAddress, "replyToAddress"), null));
	}

	/**
	 * Sets the <em>replyTo</em> address of this email with given {@link Recipient} (ignoring its {@link javax.mail.Message.RecipientType} if provided).
	 * <p>
	 * If provided, email clients should prioritize the <em>replyTo</em> recipient over the <em>from</em> recipient when replying to this email.
	 *
	 * @param recipient Preconfigured recipient which includes optional name and mandatory email address.
	 *
	 * @see #replyTo(String, String)
	 */
	public EmailBuilder replyTo(@Nonnull final Recipient recipient) {
		checkNonEmptyArgument(recipient, "replyToRecipient");
		this.replyToRecipient = new Recipient(recipient.getName(), recipient.getAddress(), null);
		return this;
	}

	/**
	 * Delegates to {@link #bounceTo(Recipient)} with given name and email address.
	 */
	public EmailBuilder bounceTo(@Nullable final String name, @Nonnull final String bounceToAddress) {
		return bounceTo(new Recipient(name, checkNonEmptyArgument(bounceToAddress, "bounceToAddress"), null));
	}

	/**
	 * Sets the <em>bounceTo</em> address of this email with given {@link Recipient} (ignoring its {@link javax.mail.Message.RecipientType} if provided).
	 * <p>
	 * If provided, SMTP server should return bounced emails to this address. This is also known as the {@code Return-Path} (or <em>Envelope FROM</em>).
	 *
	 * @param recipient Preconfigured recipient which includes optional name and mandatory email address.
	 *
	 * @see #bounceTo(String, String)
	 */
	public EmailBuilder bounceTo(@Nonnull final Recipient recipient) {
		checkNonEmptyArgument(recipient, "bounceToRecipient");
		this.bounceToRecipient = new Recipient(recipient.getName(), recipient.getAddress(), null);
		return this;
	}
	
	/**
	 * Sets the {@link #subject} of this email.
	 */
	public EmailBuilder subject(@Nonnull final String subject) {
		this.subject = checkNonEmptyArgument(subject, "subject");
		return this;
	}

	/**
	 * Sets the optional email message body in plain text.
	 * <p>
	 * Both text and HTML can be provided, which will  be offered to the email client as alternative content. Email clients that support it, will favor HTML
	 * over plain text and ignore the text body completely.
	 *
	 * @see #prependText(String)
	 * @see #appendText(String)
	 */
	public EmailBuilder text(@Nullable final String text) {
		this.text = text;
		return this;
	}

	/**
	 * Prepends text to the current plain text body (or starts it if plain text body is missing).
	 *
	 * @see #text(String)
	 */
	public EmailBuilder prependText(@Nonnull final String text) {
		this.text = text + defaultTo(this.text, "");
		return this;
	}

	/**
	 * Appends text to the current plain text body (or starts it if plain text body is missing).
	 *
	 * @see #text(String)
	 */
	public EmailBuilder appendText(@Nonnull final String text) {
		this.text = defaultTo(this.text, "") + text;
		return this;
	}

	/**
	 * Sets the optional email message body in HTML text.
	 * <p>
	 * Both text and HTML can be provided, which will  be offered to the email client as alternative content. Email clients that support it, will favor HTML
	 * over plain text and ignore the text body completely.
	 *
	 * @see #prependTextHTML(String)
	 * @see #appendTextHTML(String)
	 */
	public EmailBuilder textHTML(@Nullable final String textHTML) {
		this.textHTML = textHTML;
		return this;
	}

	/**
	 * Prepends HTML text to the current HTML text body (or starts it if HTML text body is missing).
	 *
	 * @see #textHTML(String)
	 */
	public EmailBuilder prependTextHTML(@Nonnull final String textHTML) {
		this.textHTML = textHTML + defaultTo(this.textHTML, "");
		return this;
	}

	/**
	 * Appends HTML text to the current HTML text body (or starts it if HTML text body is missing).
	 *
	 * @see #textHTML(String)
	 */
	public EmailBuilder appendTextHTML(@Nonnull final String textHTML) {
		this.textHTML = defaultTo(this.textHTML, "") + textHTML;
		return this;
	}

	/**
	 * Delegates to {@link #to(Collection)}.
	 */
	public EmailBuilder to(@Nonnull final Recipient... recipientsToAdd) {
		return to(asList(recipientsToAdd));
	}

	/**
	 * Adds new {@link Recipient} instances to the list on account of name, address with recipient type {@link Message.RecipientType#TO}.
	 *
	 * @param recipientsToAdd The recipients whose name and address to use
	 * @see #to(String...)
	 * @see #to(String)
	 * @see #to(String, String)
	 * @see #to(String, String...)
	 * @see #to(Recipient...)
	 */
	public EmailBuilder to(@Nonnull final Collection<Recipient> recipientsToAdd) {
		for (final Recipient recipient : checkNonEmptyArgument(recipientsToAdd, "recipientsToAdd")) {
			recipients.add(new Recipient(recipient.getName(), recipient.getAddress(), Message.RecipientType.TO));
		}
		return this;
	}
	
	/**
	 * Delegates to {@link #to(String, String)} while omitting the name used for the recipient(s).
	 */
	public EmailBuilder to(@Nonnull final String emailAddressList) {
		return to(null, emailAddressList);
	}
	
	/**
	 * Adds a new {@link Recipient} instances to the list on account of given name, address with recipient type {@link Message.RecipientType#TO}.
	 * List can be comma ',' or semicolon ';' separated.
	 *
	 * @param name             The name of the recipient(s).
	 * @param emailAddressList The emailaddresses of the recipients (will be singular in most use cases).
	 *
	 * @see #to(Collection)
	 * @see #to(String...)
	 * @see #to(String)
	 * @see #to(String, String...)
	 * @see #to(Recipient...)
	 */
	public EmailBuilder to(@Nullable final String name, @Nonnull final String emailAddressList) {
		checkNonEmptyArgument(emailAddressList, "emailAddressList");
		return addCommaOrSemicolonSeparatedEmailAddresses(name, emailAddressList, Message.RecipientType.TO);
	}

	/**
	 * Delegates to {@link #to(String, String...)} with empty name.
	 */
	public EmailBuilder to(@Nonnull final String... emailAddresses) {
		return to(null, emailAddresses);
	}

	/**
	 * Adds new {@link Recipient} instances to the list on account of given name, address with recipient type {@link Message.RecipientType#TO}.
	 *
	 * @param name           The name to use for each given address.
	 * @param emailAddresses The recipients whose address to use for both name and address
	 *
	 * @see #to(Collection)
	 * @see #to(String...)
	 * @see #to(String)
	 * @see #to(String, String...)
	 * @see #to(Recipient...)
	 */
	public EmailBuilder to(@Nullable final String name, @Nonnull final String... emailAddresses) {
		for (final String emailAddress : checkNonEmptyArgument(emailAddresses, "emailAddresses")) {
			recipients.add(new Recipient(name, emailAddress, Message.RecipientType.TO));
		}
		return this;
	}

	@Nonnull
	private EmailBuilder addCommaOrSemicolonSeparatedEmailAddresses(@Nullable final String name, @Nonnull final String emailAddressList, @Nonnull final Message.RecipientType type) {
		checkNonEmptyArgument(type, "type");
		for (final String emailAddress : extractEmailAddresses(checkNonEmptyArgument(emailAddressList, "emailAddressList"))) {
			recipients.add(Email.interpretRecipientData(name, emailAddress, type));
		}
		return this;
	}

	/**
	 * Delegates to {@link #cc(String, String...)} with empty name.
	 */
	@SuppressWarnings("QuestionableName")
	public EmailBuilder cc(@Nonnull final String... emailAddresses) {
		return cc(null, emailAddresses);
	}

	/**
	 * Adds new {@link Recipient} instances to the list on account of given name, address with recipient type {@link Message.RecipientType#CC}.
	 *
	 * @param emailAddresses The recipients whose address to use for both name and address
	 *
	 * @see #cc(Recipient...)
	 * @see #cc(Collection)
	 * @see #cc(String...)
	 * @see #cc(String)
	 * @see #cc(String, String)
	 */
	@SuppressWarnings("QuestionableName")
	public EmailBuilder cc(@Nullable final String name, @Nonnull final String... emailAddresses) {
		for (final String emailAddress : checkNonEmptyArgument(emailAddresses, "emailAddresses")) {
			recipients.add(new Recipient(name, emailAddress, Message.RecipientType.CC));
		}
		return this;
	}
	
	
	/**
	 * Delegates to {@link #cc(String, String)} while omitting the name for the CC recipient(s).
	 */
	@SuppressWarnings("QuestionableName")
	public EmailBuilder cc(@Nonnull final String emailAddressList) {
		return cc(null, emailAddressList);
	}
	
	/**
	 * Adds a new {@link Recipient} instances to the list on account of empty name, address with recipient type {@link Message.RecipientType#CC}. List can be
	 * comma ',' or semicolon ';' separated.
	 *
	 * @param name             The name of the recipient(s).
	 * @param emailAddressList The recipients whose address to use for both name and address
	 *
	 * @see #cc(Recipient...)
	 * @see #cc(Collection)
	 * @see #cc(String...)
	 * @see #cc(String)
	 * @see #cc(String, String...)
	 */
	@SuppressWarnings("QuestionableName")
	public EmailBuilder cc(@Nullable final String name, @Nonnull final String emailAddressList) {
		checkNonEmptyArgument(emailAddressList, "emailAddressList");
		return addCommaOrSemicolonSeparatedEmailAddresses(name, emailAddressList, Message.RecipientType.CC);
	}

	/**
	 * Delegates to {@link #cc(Collection)}.
	 */
	@SuppressWarnings("QuestionableName")
	public EmailBuilder cc(@Nonnull final Recipient... recipientsToAdd) {
		return cc(asList(recipientsToAdd));
	}

	/**
	 * Adds new {@link Recipient} instances to the list on account of name, address with recipient type {@link Message.RecipientType#CC}.
	 *
	 * @param recipientsToAdd The recipients whose name and address to use
	 *
	 * @see #cc(Recipient...)
	 * @see #cc(String...)
	 * @see #cc(String)
	 * @see #cc(String, String)
	 * @see #cc(String, String...)
	 */
	@SuppressWarnings("QuestionableName")
	public EmailBuilder cc(@Nonnull final Collection<Recipient> recipientsToAdd) {
		for (final Recipient recipient : checkNonEmptyArgument(recipientsToAdd, "recipientsToAdd")) {
			recipients.add(new Recipient(recipient.getName(), recipient.getAddress(), Message.RecipientType.CC));
		}
		return this;
	}

	/**
	 * Delegates to {@link #bcc(String, String...)} with empty name.
	 */
	@SuppressWarnings("QuestionableName")
	public EmailBuilder bcc(@Nonnull final String... emailAddresses) {
		return bcc(null, emailAddresses);
	}

	/**
	 * Adds new {@link Recipient} instances to the list on account of given name, address with recipient type {@link Message.RecipientType#BCC}.
	 *
	 * @param name           The optional default name to use when a provided address doesn't include it.
	 * @param emailAddresses One or more addresses which all have the same name.
	 *
	 * @see #bcc(Collection)
	 * @see #bcc(Recipient...)
	 * @see #bcc(String...)
	 * @see #bcc(String)
	 * @see #bcc(String, String)
	 */
	@SuppressWarnings("QuestionableName")
	public EmailBuilder bcc(@Nullable final String name, @Nonnull final String... emailAddresses) {
		for (final String emailAddress : checkNonEmptyArgument(emailAddresses, "emailAddresses")) {
			recipients.add(Email.interpretRecipientData(name, emailAddress, Message.RecipientType.BCC));
		}
		return this;
	}
	
	/**
	 * Delegates to {@link #bcc(String, String)} while omitting the name for the BCC recipient(s).
	 */
	@SuppressWarnings("QuestionableName")
	public EmailBuilder bcc(@Nonnull final String emailAddressList) {
		return bcc(null, emailAddressList);
	}
	
	/**
	 * Adds a new {@link Recipient} instances to the list on account of empty name, address with recipient type {@link Message.RecipientType#BCC}. List can be
	 * comma ',' or semicolon ';' separated.
	 *
	 * @param name             The optional default name to use when a provided addresses doesn't include a name.
	 * @param emailAddressList The recipients whose address to parse, while only using the provided name if not provided for an address.
	 *
	 * @see #bcc(Collection)
	 * @see #bcc(Recipient...)
	 * @see #bcc(String...)
	 * @see #bcc(String)
	 * @see #bcc(String, String...)
	 */
	@SuppressWarnings("QuestionableName")
	public EmailBuilder bcc(@Nullable final String name, @Nonnull final String emailAddressList) {
		checkNonEmptyArgument(emailAddressList, "emailAddressList");
		return addCommaOrSemicolonSeparatedEmailAddresses(name, emailAddressList, Message.RecipientType.BCC);
	}

	/**
	 * Delegates to {@link #bcc(Collection)}.
	 */
	@SuppressWarnings("QuestionableName")
	public EmailBuilder bcc(@Nonnull final Recipient... recipientsToAdd) {
		return bcc(asList(recipientsToAdd));
	}

	/**
	 * Adds new {@link Recipient} instances to the list on account of name, address with recipient type {@link Message.RecipientType#BCC}.
	 *
	 * @param recipientsToAdd The recipients whose name and address to use
	 *
	 * @see #bcc(Recipient...)
	 * @see #bcc(String...)
	 * @see #bcc(String)
	 * @see #bcc(String, String...)
	 * @see #bcc(String, String)
	 */
	@SuppressWarnings("QuestionableName")
	public EmailBuilder bcc(@Nonnull final Collection<Recipient> recipientsToAdd) {
		for (final Recipient recipient : checkNonEmptyArgument(recipientsToAdd, "recipientsToAdd")) {
			recipients.add(new Recipient(recipient.getName(), recipient.getAddress(), Message.RecipientType.BCC));
		}
		return this;
	}

	/**
	 * Delegates to {@link #embedImage(String, DataSource)}, with a named {@link ByteArrayDataSource} created using the provided name, data and mimetype.
	 *
	 * @param name     The name of the image as being referred to from the message content body (eg. 'signature').
	 * @param data     The byte data of the image to be embedded.
	 * @param mimetype The content type of the given data (eg. "image/gif" or "image/jpeg").
	 */
	public EmailBuilder embedImage(@Nonnull final String name, @Nonnull final byte[] data, @Nonnull final String mimetype) {
		checkNonEmptyArgument(name, "name");
		checkNonEmptyArgument(data, "data");
		checkNonEmptyArgument(mimetype, "mimetype");
		
		final ByteArrayDataSource dataSource = new ByteArrayDataSource(data, mimetype);
		dataSource.setName(name);
		return embedImage(name, dataSource);
	}

	/**
	 * Adds image data to this email that can be referred to from the email HTML body. For adding images as attachment, refer to {@link #addAttachment(String,
	 * DataSource)} instead.
	 * <p>
	 * The provided {@link DataSource} is assumed to be of mimetype png, jpg or whatever the email client supports as valid image embedded in HTML content.
	 *
	 * @param name      The name of the image as being referred to from the message content body (eg. 'src="cid:yourImageName"'). If not provided, the name of
	 *                  the given data source is used instead.
	 * @param imagedata The image data.
	 *
	 * @see EmailBuilder#embedImage(String, byte[], String)
	 * @see EmailBuilder#withEmbeddedImages(List)
	 */
	@SuppressWarnings("WeakerAccess")
	public EmailBuilder embedImage(@Nullable final String name, @Nonnull final DataSource imagedata) {
		checkNonEmptyArgument(imagedata, "imagedata");
		if (valueNullOrEmpty(name) && valueNullOrEmpty(imagedata.getName())) {
			throw new EmailException(EmailException.NAME_MISSING_FOR_EMBEDDED_IMAGE);
		}
		embeddedImages.add(new AttachmentResource(name, imagedata));
		return this;
	}

	/**
	 * Delegates to {@link #embedImage(String, DataSource)} for each embedded image.
	 */
	private EmailBuilder withEmbeddedImages(@Nonnull final List<AttachmentResource> embeddedImages) {
		for (final AttachmentResource embeddedImage : embeddedImages) {
			embedImage(embeddedImage.getName(), embeddedImage.getDataSource());
		}
		return this;
	}

	/**
	 * Delegates to {@link #addHeader(String, Object)} for each header in the provided {@code Map}.
	 */
	@SuppressWarnings("WeakerAccess")
	public <T> EmailBuilder withHeaders(@Nonnull final Map<String, T> headers) {
		for (Map.Entry<String, T> headerEntry: headers.entrySet()) {
			addHeader(headerEntry.getKey(), headerEntry.getValue());
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
	public EmailBuilder addHeader(@Nonnull final String name, @Nonnull final Object value) {
		checkNonEmptyArgument(name, "name");
		checkNonEmptyArgument(value, "value");
		headers.put(name, String.valueOf(value));
		return this;
	}

	/**
	 * Delegates to {@link #addAttachment(String, DataSource)}, with a named {@link ByteArrayDataSource} created using the provided name, data and mimetype.
	 *
	 * @param name     The name of the attachment (eg. filename including extension, like 'filename.ext').
	 * @param data     The binary data of the attachment.
	 * @param mimetype The content type of the given data (eg. "plain/text", "image/gif" or "application/pdf").
	 *
	 * @see #addAttachment(String, DataSource)
	 * @see #withAttachments(List)
	 */
	public EmailBuilder addAttachment(@Nullable final String name, @Nonnull final byte[] data, @Nonnull final String mimetype) {
		checkNonEmptyArgument(data, "data");
		checkNonEmptyArgument(mimetype, "mimetype");
		final ByteArrayDataSource dataSource = new ByteArrayDataSource(data, mimetype);
		dataSource.setName(MiscUtil.encodeText(name));
		addAttachment(name, dataSource);
		return this;
	}

	/**
	 * Adds an attachment to the email message, which will be shown in the email client as seperate files available for download or inline display if the client
	 * supports it (for example, most browsers these days display PDF's in a popup).
	 * <p>
	 * Note: for embedding images instead of attaching them for download, refer to {@link #embedImage(String, DataSource)} instead.
	 *
	 * @param name     Optional name of the attachment (eg. 'filename.ext').
	 * @param filedata The attachment data.
	 *
	 * @see #addAttachment(String, byte[], String)
	 * @see #withAttachments(List)
	 */
	public EmailBuilder addAttachment(@Nullable final String name, @Nonnull final DataSource filedata) {
		checkNonEmptyArgument(filedata, "filedata");
		attachments.add(new AttachmentResource(MiscUtil.encodeText(name), filedata));
		return this;
	}

	/**
	 * Delegates to {@link #addAttachment(String, DataSource)} for each attachment.
	 */
	public EmailBuilder withAttachments(@Nonnull final List<AttachmentResource> attachments) {
		for (final AttachmentResource attachment : attachments) {
			addAttachment(attachment.getName(), attachment.getDataSource());
		}
		return this;
	}

	/**
	 * Delegates to {@link #signWithDomainKey(InputStream, String, String)} with a {@link ByteArrayInputStream} wrapped around the prodived {@code
	 * dkimPrivateKey} data.
	 */
	public EmailBuilder signWithDomainKey(@Nonnull final byte[] dkimPrivateKey, @Nonnull final String signingDomain, @Nonnull final String dkimSelector) {
		checkNonEmptyArgument(dkimPrivateKey, "dkimPrivateKey");
		return signWithDomainKey(new ByteArrayInputStream(dkimPrivateKey), signingDomain, dkimSelector);
	}
	
	/**
	 * Delegates to {@link #signWithDomainKey(InputStream, String, String)} with a {@link ByteArrayInputStream} wrapped around the prodived {@code
	 * dkimPrivateKey} string converted to UTF_8 byte array.
	 */
	public EmailBuilder signWithDomainKey(@Nonnull final String dkimPrivateKey, @Nonnull final String signingDomain, @Nonnull final String dkimSelector) {
		checkNonEmptyArgument(dkimPrivateKey, "dkimPrivateKey");
		return signWithDomainKey(new ByteArrayInputStream(dkimPrivateKey.getBytes(UTF_8)), signingDomain, dkimSelector);
	}

	/**
	 * Sets all info needed for signing with DKIM (key, domain, selector), using an input stream for private key data. This data is used once the email is sent,
	 * using the DKIM library.
	 *
	 * @see #signWithDomainKey(byte[], String, String)
	 * @see #signWithDomainKey(String, String, String)
	 * @see #signWithDomainKey(File, String, String)
	 */
	public EmailBuilder signWithDomainKey(@Nonnull final InputStream dkimPrivateKeyInputStream, @Nonnull final String signingDomain,
										  @Nonnull final String dkimSelector) {
		this.dkimPrivateKeyInputStream = checkNonEmptyArgument(dkimPrivateKeyInputStream, "dkimPrivateKeyInputStream");
		this.signingDomain = checkNonEmptyArgument(signingDomain, "signingDomain");
		this.dkimSelector = checkNonEmptyArgument(dkimSelector, "dkimSelector");
		return this;
	}

	/**
	 * Sets all info needed for DKIM (key, domain, selector), using a file reference for private key data. This file is resolved once the email is sent, using
	 * the DKIM library.
	 *
	 * @see #signWithDomainKey(InputStream, String, String)
	 */
	public EmailBuilder signWithDomainKey(@Nonnull final File dkimPrivateKeyFile, @Nonnull final String signingDomain, @Nonnull final String dkimSelector) {
		this.dkimPrivateKeyFile = checkNonEmptyArgument(dkimPrivateKeyFile, "dkimPrivateKeyFile");
		this.signingDomain = checkNonEmptyArgument(signingDomain, "signingDomain");
		this.dkimSelector = checkNonEmptyArgument(dkimSelector, "dkimSelector");
		return this;
	}
	
	/**
	 * Indicates that we want to use the NPM flag {@link #dispositionNotificationTo}. The actual address will default to the {@link #replyToRecipient}
	 * first if set or else {@link #fromRecipient} (the final address is determined when sending this email).
	 *
	 * @see #withDispositionNotificationTo(Recipient)
	 */
	public EmailBuilder withDispositionNotificationTo() {
		this.useDispositionNotificationTo = true;
		this.dispositionNotificationTo = null;
		return this;
	}
	
	/**
	 * Delegates to {@link #withDispositionNotificationTo(Recipient)} with a new {@link Recipient} wrapped around the provided address.
	 */
	public EmailBuilder withDispositionNotificationTo(@Nonnull final String address) {
		checkNonEmptyArgument(address, "dispositionNotificationToAddress");
		return withDispositionNotificationTo(new Recipient(null, address, null));
	}

	/**
	 * Delegates to {@link #withDispositionNotificationTo(Recipient)} with a new {@link Recipient} wrapped around the provided name and address.
	 */
	public EmailBuilder withDispositionNotificationTo(@Nullable final String name, @Nonnull final String address) {
		checkNonEmptyArgument(address, "dispositionNotificationToAddress");
		return withDispositionNotificationTo(new Recipient(name, address, null));
	}

	/**
	 * Indicates the this email should use the <a href="https://tools.ietf.org/html/rfc8098">NPM flag "Disposition-Notification-To"</a> with the given
	 * preconfigred {@link Recipient}. This flag can be used to request a return receipt from the recipient to signal that the recipient has read the email.
	 * <p>
	 * This flag may be ignored by SMTP clients (for example gmail ignores it completely, while the Google Apps business suite honors it).
	 *
	 * @see #withDispositionNotificationTo()
	 * @see #withDispositionNotificationTo(String)
	 * @see #withDispositionNotificationTo(String, String)
	 */
	public EmailBuilder withDispositionNotificationTo(@Nonnull final Recipient recipient) {
		checkNonEmptyArgument(recipient.getAddress(), "recipient.address");
		this.useDispositionNotificationTo = true;
		this.dispositionNotificationTo = new Recipient(recipient.getName(), recipient.getAddress(), null);
		return this;
	}

	/**
	 * Indicates that we want to use the flag {@link #returnReceiptTo}. The actual address will default to the {@link #replyToRecipient} first if set or else
	 * {@link #fromRecipient} (the final address is determined when sending the email).
	 * <p>
	 * For more detailed information, refer to {@link #withReturnReceiptTo(Recipient)}.
	 */
	public EmailBuilder withReturnReceiptTo() {
		this.useReturnReceiptTo = true;
		this.returnReceiptTo = null;
		return this;
	}
	
	/**
	 * Delegates to {@link #withReturnReceiptTo(Recipient)} with a new {@link Recipient} wrapped around the provided address.
	 */
	public EmailBuilder withReturnReceiptTo(@Nonnull final String address) {
		checkNonEmptyArgument(address, "address");
		return withReturnReceiptTo(new Recipient(null, address, null));
	}
	
	/**
	 * Delegates to {@link #withReturnReceiptTo(Recipient)} with a new {@link Recipient} wrapped around the provided name and address.
	 */
	public EmailBuilder withReturnReceiptTo(@Nullable final String name, @Nonnull final String address) {
		checkNonEmptyArgument(address, "address");
		return withReturnReceiptTo(new Recipient(name, address, null));
	}

	/**
	 * Indicates that this email should use the <a href="https://en.wikipedia.org/wiki/Return_receipt">RRT flag "Return-Receipt-To"</a> with the preconfigured
	 * {@link Recipient}. This flag can be used to request a notification from the SMTP server recipient to signal that the recipient has read the email.
	 * <p>
	 * This flag is rarely used, but your mail server / client might implement this flag to automatically send back a notification that the email was received
	 * on the mail server or opened in the client, depending on the chosen implementation.
	 */
	public EmailBuilder withReturnReceiptTo(@Nonnull final Recipient recipient) {
		checkNonEmptyArgument(recipient.getAddress(), "recipient.address");
		this.useReturnReceiptTo = true;
		this.returnReceiptTo = new Recipient(recipient.getName(), recipient.getAddress(), null);
		return this;
	}
	
	/**
	 * Delegates to {@link #asReplyTo(MimeMessage, boolean, String)} with replyToAll set to <code>false</code> and a default HTML quoting
	 * template.
	 */
	public EmailBuilder asReplyTo(@Nonnull final Email email) {
		return asReplyTo(EmailConverter.emailToMimeMessage(email), false, DEFAULT_QUOTING_MARKUP);
	}
	
	/**
	 * Delegates to {@link #asReplyTo(MimeMessage, boolean, String)} with replyToAll set to <code>true</code> and a default HTML quoting
	 * template.
	 */
	public EmailBuilder asReplyToAll(@Nonnull final Email email) {
		return asReplyTo(EmailConverter.emailToMimeMessage(email), true, DEFAULT_QUOTING_MARKUP);
	}
	
	/**
	 * Delegates to {@link #asReplyTo(MimeMessage, boolean, String)} with replyToAll set to <code>true</code>.
	 *
	 * @see EmailBuilder#DEFAULT_QUOTING_MARKUP
	 */
	public EmailBuilder asReplyToAll(@Nonnull final Email email, @Nonnull final String customQuotingTemplate) {
		return asReplyTo(EmailConverter.emailToMimeMessage(email), true, customQuotingTemplate);
	}
	
	/**
	 * Delegates to {@link #asReplyTo(MimeMessage, boolean, String)} with replyToAll set to <code>false</code>.
	 */
	public EmailBuilder asReplyTo(@Nonnull final Email email, @Nonnull final String customQuotingTemplate) {
		return asReplyTo(EmailConverter.emailToMimeMessage(email), false, customQuotingTemplate);
	}
	
	/**
	 * Delegates to {@link #asReplyTo(MimeMessage, boolean, String)} with replyToAll set to <code>false</code> and a default HTML quoting
	 * template.
	 */
	public EmailBuilder asReplyTo(@Nonnull final MimeMessage email) {
		return asReplyTo(email, false, DEFAULT_QUOTING_MARKUP);
	}
	
	/**
	 * Delegates to {@link #asReplyTo(MimeMessage, boolean, String)} with replyToAll set to <code>true</code>.
	 *
	 * @see EmailBuilder#DEFAULT_QUOTING_MARKUP
	 */
	public EmailBuilder asReplyToAll(@Nonnull final MimeMessage email, @Nonnull final String customQuotingTemplate) {
		return asReplyTo(email, true, customQuotingTemplate);
	}
	
	/**
	 * Delegates to {@link #asReplyTo(MimeMessage, boolean, String)} with replyToAll set to <code>false</code>.
	 */
	public EmailBuilder asReplyTo(@Nonnull final MimeMessage email, @Nonnull final String customQuotingTemplate) {
		return asReplyTo(email, false, customQuotingTemplate);
	}
	
	/**
	 * Delegates to {@link #asReplyTo(MimeMessage, boolean, String)} with replyToAll set to <code>true</code> and a default HTML quoting
	 * template.
	 *
	 * @see EmailBuilder#DEFAULT_QUOTING_MARKUP
	 */
	public EmailBuilder asReplyToAll(@Nonnull final MimeMessage email) {
		return asReplyTo(email, true, DEFAULT_QUOTING_MARKUP);
	}

	/**
	 * Primes the email with all subject, headers, originally embedded images and recipients needed for a valid RFC reply.
	 * <p>
	 * <strong>Note:</strong> replaces subject with "Re: &lt;original subject&gt;" (but never nested).<br>
	 * <p>
	 * <strong>Note:</strong> Make sure you set the content before using this API or else the quoted content is lost. Replaces body (text is replaced with ">
	 * text" and HTML is replaced with the provided or default quoting markup.
	 *
	 * @param emailMessage The message from which we harvest recipients, original content to quote (including embedded images), message ID to include.
	 * @param repyToAll    Indicates whether all original receivers should be included in this new reply. Also see {@link MimeMessage#reply(boolean)}.
	 * @param htmlTemplate A valid HTML that contains the string {@code "%s"}. Be advised that HTML is very limited in emails.
	 *
	 * @see #asReplyTo(Email)
	 * @see #asReplyTo(Email, String)
	 * @see #asReplyTo(MimeMessage)
	 * @see #asReplyTo(MimeMessage, String)
	 * @see #asReplyToAll(Email)
	 * @see #asReplyToAll(Email, String)
	 * @see #asReplyToAll(MimeMessage)
	 * @see #asReplyToAll(MimeMessage, String)
	 * @see <a href="https://javaee.github.io/javamail/FAQ#reply">Official JavaMail FAQ on replying</a>
	 * @see javax.mail.internet.MimeMessage#reply(boolean)
	 */
	public EmailBuilder asReplyTo(@Nonnull final MimeMessage emailMessage, final boolean repyToAll, @Nonnull final String htmlTemplate) {
		final MimeMessage replyMessage;
		try {
			replyMessage = (MimeMessage) emailMessage.reply(repyToAll);
			replyMessage.setText("ignore");
			replyMessage.setFrom("ignore@ignore.ignore");
		} catch (final MessagingException e) {
			throw new EmailException("was unable to parse mimemessage to produce a reply for", e);
		}
		
		final Email repliedTo = EmailConverter.mimeMessageToEmail(emailMessage);
		final Email generatedReply = EmailConverter.mimeMessageToEmail(replyMessage);

		return this
				.subject(generatedReply.getSubject())
				.to(generatedReply.getRecipients())
				.text(valueNullOrEmpty(repliedTo.getText()) ? text : text + LINE_START_PATTERN.matcher(repliedTo.getText()).replaceAll("> "))
				.textHTML(valueNullOrEmpty(repliedTo.getTextHTML()) ? textHTML : textHTML + format(htmlTemplate, repliedTo.getTextHTML()))
				.withHeaders(generatedReply.getHeaders())
				.withEmbeddedImages(repliedTo.getEmbeddedImages());
	}
	
	/**
	 * Delegates to {@link #asForwardOf(MimeMessage)} with the provided {@link Email} converted to {@link MimeMessage}.
	 *
	 * @see EmailConverter#emailToMimeMessage(Email)
	 */
	public EmailBuilder asForwardOf(@Nonnull final Email email) {
		return asForwardOf(EmailConverter.emailToMimeMessage(email));
	}

	/**
	 * Primes the email to build with proper subject and inline forwarded email needed for a valid RFC forward. Also includes the original email intact, to be
	 * rendered by the email client as 'forwarded email'.
	 * <p>
	 * <strong>Note 1</strong>: replaces subject with "Fwd: &lt;original subject&gt;" (nesting enabled).
	 * <p>
	 * <strong>Note 2</strong>: {@code Content-Disposition} will be left empty so the receiving email client can decide how to handle display (most will show
	 * inline, some will show as attachment instead).
	 *
	 * @see <a href="https://javaee.github.io/javamail/FAQ#forward">Official JavaMail FAQ on forwarding</a>
	 * @see <a href="https://blogs.technet.microsoft.com/exchange/2011/04/21/mixed-ing-it-up-multipartmixed-messages-and-you/">More reading material</a>
	 * @see #asForwardOf(Email)
	 */
	public EmailBuilder asForwardOf(@Nonnull final MimeMessage emailMessage) {
		this.emailToForward = emailMessage;
		return subject("Fwd: " + MimeMessageParser.parseSubject(emailMessage));
	}
	
	/*
		GETTERS
	 */

	/**
	 * @see #id(String)
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
	 * @see #replyTo(Recipient)
	 */
	public Recipient getReplyToRecipient() {
		return replyToRecipient;
	}

	/**
	 * @see #bounceTo(Recipient)
	 */
	public Recipient getBounceToRecipient() {
		return bounceToRecipient;
	}

	/**
	 * @see #text(String)
	 */
	public String getText() {
		return text;
	}

	/**
	 * @see #textHTML(String)
	 */
	public String getTextHTML() {
		return textHTML;
	}

	/**
	 * @see #subject(String)
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
	 * @see #embedImage(String, DataSource)
	 */
	public List<AttachmentResource> getEmbeddedImages() {
		return new ArrayList<>(embeddedImages);
	}

	/**
	 * @see #addAttachment(String, DataSource)
	 */
	public List<AttachmentResource> getAttachments() {
		return new ArrayList<>(attachments);
	}

	/**
	 * @see #addHeader(String, Object)
	 * @see #asReplyTo(MimeMessage, boolean, String)
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
	public String getSigningDomain() {
		return signingDomain;
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
	 * @see #asForwardOf(MimeMessage)
	 */
	public MimeMessage getEmailToForward() {
		return emailToForward;
	}
}