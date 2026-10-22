package org.simplejavamail.api.email;

import jakarta.activation.DataSource;
import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.config.ImageResolutionConfig;
import org.simplejavamail.api.email.config.ContentEncodingConfig;
import org.simplejavamail.api.email.config.EmailTemplateOptions;
import org.simplejavamail.api.email.config.SmtpDeliveryOptions;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;
import org.simplejavamail.api.email.config.OpenPgpEncryptionConfig;
import org.simplejavamail.api.email.config.OpenPgpSigningConfig;
import org.simplejavamail.api.email.config.SmimeEncryptionConfig;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.api.internal.clisupport.model.Cli;
import org.simplejavamail.api.internal.clisupport.model.CliBuilderApiType;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.EmailGovernance;

import java.io.File;
import java.net.URL;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static java.util.regex.Pattern.compile;

/**
 * Fill in an {@link Email} using a builder obtained from {@link EmailStartingBuilder}. For an empty message, start with
 * {@code SimpleJavaMail.fromDefaults().emailBuilder().startingBlank()}; the starting builder also offers copy, reply and forward routes.
 * If you have a configured SimpleJavaMail factory, call its {@code emailBuilder()} instead so the builder retains that factory's configuration.
 * <p>
 * Default/override suppression controls ordinary templates, not factory-scoped {@code simplejavamail.locked.} settings. Those restrictions are checked
 * when governance prepares the email, even after completing it early or disabling ordinary validation. Required recipients remain additive; conflicting
 * single-value settings are rejected. Locks on embedded-image resolution are checked before this builder reads files or URLs.
 * Collection getters return detached copies; notification destination snapshots are unmodifiable. Changing a modifiable copy does not
 * configure this builder or change an Email already built.
 * Data sources and forwarded MIME messages remain borrowed resources; their contents are not copied by building an Email.
 * <p>
 * To/Cc/Bcc, Reply-To and notification conveniences parse complete mailbox lists; From and bounce remain singular. A nonblank explicit name wins;
 * a null or blank name uses the embedded name. This applies to String and equivalent InternetAddress overloads for From, To/Cc/Bcc, Reply-To,
 * bounce and receipt-request addresses; see {@link #from(String, String)} and {@link #to(String, String)} for their parsing boundaries. Prepared Recipient values
 * retain their supplied name/address and are not reparsed. To/Cc/Bcc, {@code withRecipients}, Reply-To and delivery-recipient overrides are
 * additive: calls retain existing entries, including those populated by {@link EmailStartingBuilder#copying(Email)}. Clear a collection before
 * replacing it. From, bounce and complete notification destination choices replace their local value instead.
 * <p>
 * <strong>Note:</strong> For some reason, JavaDoc is not able to parse all {@code @link} directives used in this class' documentation. I have no idea why, if you can figure
 * it out, please let me know!
 */
@Cli.BuilderApiNode(builderApiType = CliBuilderApiType.EMAIL)
public interface EmailPopulatingBuilder {
    /** Built-in template participation; ordinary defaults apply unless the Email opts out. */
    boolean DEFAULT_IGNORE_DEFAULTS = false;
    /** Built-in template participation; ordinary overrides apply unless the Email opts out. */
    boolean DEFAULT_IGNORE_OVERRIDES = false;
    /** Built-in fallback for automatic image resolution; configured snapshots can supply another value. */
    boolean DEFAULT_EMBEDDED_IMAGE_AUTO_RESOLUTION_FOR_FILES = false;
    /** Built-in fallback for automatic image resolution; configured snapshots can supply another value. */
    boolean DEFAULT_EMBEDDED_IMAGE_AUTO_RESOLUTION_FOR_CLASSPATH_RESOURCES = false;
    /** Built-in fallback for automatic image resolution; configured snapshots can supply another value. */
    boolean DEFAULT_EMBEDDED_IMAGE_AUTO_RESOLUTION_FOR_URLS = false;
    /** Built-in fallback for automatic image resolution; configured snapshots can supply another value. */
    boolean DEFAULT_ALLOW_EMBEDDED_IMAGE_OUTSIDE_BASE_DIR = false;
    /** Built-in fallback for automatic image resolution; configured snapshots can supply another value. */
    boolean DEFAULT_ALLOW_EMBEDDED_IMAGE_OUTSIDE_BASE_CLASSPATH = false;
    /** Built-in fallback for automatic image resolution; configured snapshots can supply another value. */
    boolean DEFAULT_ALLOW_EMBEDDED_IMAGE_OUTSIDE_BASE_URL = false;
    /** Built-in fallback for automatic image resolution; configured snapshots can supply another value. */
    boolean DEFAULT_EMBEDDED_IMAGE_AUTO_RESOLUTION_MUST_BE_SUCCESSFUL = false;

    /**
     * Replaces the complete supplied ImageResolutionConfig group; earlier choices from that group are discarded.
     * Unset fields inherit this factory's captured configuration. Explicit base clears remove a captured image base.
     * Mandatory configuration locks still apply. Configuration sources and borrowed content are not read by this call.
     * @param config Immutable choices to apply.
     * @return This builder.
     */
    @Cli.ExcludeApi(reason = "The CLI uses internal typed scalar adapters")
    EmailPopulatingBuilder withImageResolutionConfig(@NotNull ImageResolutionConfig config);
    /** Returns the supplied group, including its unset choices.
     * @see #withImageResolutionConfig(ImageResolutionConfig) */
    @NotNull ImageResolutionConfig getImageResolutionConfig();
    /** Removes this group's supplied choices and restores captured inheritance.
     * @see #withImageResolutionConfig(ImageResolutionConfig) */
    @Cli.ExcludeApi(reason = "A fresh CLI builder already inherits configuration")
    EmailPopulatingBuilder useConfiguredImageResolutionConfig();

    /**
     * Replaces the complete supplied ContentEncodingConfig group; earlier choices from that group are discarded.
     * Unset fields permit ordinary Email governance and provider fallback; this does not apply defaults eagerly.
     * Mandatory configuration locks still apply. Configuration sources and borrowed content are not read by this call.
     * @param config Immutable choices to apply.
     * @return This builder.
     */
    @Cli.ExcludeApi(reason = "The CLI uses internal typed scalar adapters")
    EmailPopulatingBuilder withContentEncodingConfig(@NotNull ContentEncodingConfig config);
    /** Returns the supplied group, including its unset choices.
     * @see #withContentEncodingConfig(ContentEncodingConfig) */
    @NotNull ContentEncodingConfig getContentEncodingConfig();
    /** Removes this group's supplied choices and restores captured inheritance.
     * @see #withContentEncodingConfig(ContentEncodingConfig) */
    @Cli.ExcludeApi(reason = "A fresh CLI builder already inherits configuration")
    EmailPopulatingBuilder useConfiguredContentEncodingConfig();

    /**
     * Replaces the complete supplied TemplateOptions group; earlier choices from that group are discarded.
     * These flags and exclusions control template participation for this Email. They do not recursively govern reference templates.
     * Mandatory configuration locks still apply. Configuration sources and borrowed content are not read by this call.
     * @param config Immutable choices to apply.
     * @return This builder.
     */
    @Cli.ExcludeApi(reason = "The CLI uses internal typed scalar adapters")
    EmailPopulatingBuilder withTemplateOptions(@NotNull EmailTemplateOptions config);
    /** Returns the supplied group, including its unset choices.
     * @see #withTemplateOptions(EmailTemplateOptions) */
    @NotNull EmailTemplateOptions getTemplateOptions();
    /** Removes this group's supplied choices and restores captured inheritance.
     * @see #withTemplateOptions(EmailTemplateOptions) */
    @Cli.ExcludeApi(reason = "A fresh CLI builder already inherits configuration")
    EmailPopulatingBuilder useConfiguredTemplateOptions();

    /**
     * Replaces the complete supplied SmtpDeliveryOptions group; earlier choices from that group are discarded.
     * Unset fields permit ordinary Email governance and provider fallback; this does not apply defaults eagerly.
     * Mandatory configuration locks still apply. Configuration sources and borrowed content are not read by this call.
     * @param config Immutable choices to apply.
     * @return This builder.
     */
    @Cli.ExcludeApi(reason = "The CLI uses internal typed scalar adapters")
    EmailPopulatingBuilder withSmtpDeliveryOptions(@NotNull SmtpDeliveryOptions config);
    /** Returns the supplied group, including its unset choices.
     * @see #withSmtpDeliveryOptions(SmtpDeliveryOptions) */
    @NotNull SmtpDeliveryOptions getSmtpDeliveryOptions();
    /** Removes this group's supplied choices and restores captured inheritance.
     * @see #withSmtpDeliveryOptions(SmtpDeliveryOptions) */
    @Cli.ExcludeApi(reason = "A fresh CLI builder already inherits configuration")
    EmailPopulatingBuilder useConfiguredSmtpDeliveryOptions();

	/**
	 * Regular Expression to find all {@code <img src="...">} entries in an HTML document.It needs to cater for various things, like more whitespaces including newlines on any
	 * place, HTML is not case-sensitive and there can be arbitrary text between "IMG" and "SRC" like IDs and other things.
	 */
	Pattern IMG_SRC_PATTERN = compile("(?<imageTagStart><[Ii][Mm][Gg]\\s*[^>]*?\\s+[Ss][Rr][Cc]\\s*=\\s*[\"'])(?<src>[^\"']+?)(?<imageSrcEnd>[\"'])");

	/**
	 * Builds an Email containing the values supplied to this builder. Use this method instead of constructing or subclassing Email directly.
	 * An Email can be an incomplete defaults/overrides template, including a signing configuration without
	 * a sender. Mailer preparation checks the completed message before sending; building a template does not validate its completeness.
	 * <strong>This is the regular use case and the common way to send emails using a {@link Mailer} instance.</strong>
	 * <p>
	 * To inspect or convert values with configured defaults already applied, use {@link #buildEmailCompletedWithDefaultsAndOverrides()}.
	 * To use a particular Mailer's templates instead, pass its {@link Mailer#getEmailGovernance()} to
	 * {@link #buildEmailCompletedWithDefaultsAndOverrides(EmailGovernance)}. Ordinary sending applies the selected Mailer's governance automatically.
	 * <p>
	 * A To/Cc/Bcc recipient's S/MIME certificate requests encryption, including a certificate supplied by a recipient-group policy.
	 * This build checks the stored email and recipient choices for conflicting S/MIME and OpenPGP requests before resolving dynamic images.
	 * The check does not load optional protection modules, validate cryptographic material or read message/attachment content.
	 * Defaults, overrides and mandatory locks are checked again when completion resolves their effective choices, before MIME protection.
	 *
	 * @throws IllegalArgumentException If S/MIME signing or encryption is requested at email level or by a To/Cc/Bcc recipient certificate,
	 *                                 and OpenPGP signing or encryption is also configured. The error identifies the conflicting choices.
	 *                                 Use {@link #clearSmime()} or {@link #clearOpenPgp()} to remove email-level choices; clearing S/MIME leaves
	 *                                 recipient certificates intact. To deliberately remove those requests, strip certificates from copied
	 *                                 recipients with {@link IRecipientsBuilder#clearingSmimeCertificates()} and replace this builder's recipients.
	 *                                 Previously built Emails and source recipients remain unchanged. Template suppression does not remove mandatory locks.
	 * @see #buildEmailCompletedWithDefaultsAndOverrides(EmailGovernance)
	 */
	@SuppressWarnings("JavadocDeclaration")
	@Cli.ExcludeApi(reason = "This API is specifically for Java use")
	Email buildEmail();

	/**
	 * Delegates to {@link #buildEmailCompletedWithDefaultsAndOverrides(EmailGovernance)} using governance created from the immutable configuration snapshot
	 * captured by this builder. This applies any email defaults configured on that snapshot without consulting later configuration loads.
	 *
	 * @throws IllegalArgumentException If the supplied or completed choices combine S/MIME and OpenPGP, as described by {@link #buildEmail()}.
	 */
	@Cli.ExcludeApi(reason = "This API is specifically for Java use")
	Email buildEmailCompletedWithDefaultsAndOverrides();

	/**
	 * Builds an Email with the supplied governance's defaults and overrides already applied, for example for conversion or inspection.
	 * The result can also be sent: each subsequent ordinary completion applies the selected Mailer's governance again. Missing scalar values
	 * can receive defaults, overrides can replace scalar values, and additive templates contribute only entries not already present.
	 * Existing duplicate occurrences are preserved; resource identity is recognized without reading borrowed content. Suppression choices
	 * survive completion and copying, and applicable locked configuration always remains binding.
	 * <p>
	 * Completion resolves outgoing content and configuration, including S/MIME, OpenPGP and DKIM settings. It does not copy historical
	 * cryptographic results, original protected bytes, received delivery reports and their nested facts, decrypted-attachment inspection
	 * lists or the retained S/MIME signed-message view. Outgoing delivery-notification requests remain eligible configuration.
	 * Completion rejects combinations of S/MIME and OpenPGP, including S/MIME encryption requested by recipient certificates.
	 * Conflicts introduced by defaults, overrides or mandatory locks fail during completion using the same metadata-only check as {@link #buildEmail()}.
	 * Cryptographic processing happens later during MIME preparation. Inspect the original converted Email for source results;
	 * ordinary copying and supported Java serialization preserve their separate history contracts.
	 * <p>
	 * Completion does not freeze the message. Use {@link #buildEmail()} for ordinary sending so the selected destination supplies its defaults
	 * directly, including in a cluster. Completing here before an ordinary send adds another template-resolution pass.
	 * Deferring completion also matters when cluster members use different sender addresses or DKIM settings for their SMTP servers.
	 * Sending a completed result keeps its materialized values as supplied values; another Mailer's default
	 * sender cannot replace an existing sender, while its override can. See {@link EmailGovernance#produceEmailApplyingDefaultsAndOverrides(Email)}
	 * for collection ordering and duplicate matching. Sending, rehearsing or exporting the original exact Email retains its authoritative-byte
	 * contract; explicitly completing an exact Email creates a composed result.
	 *
	 * @param emailGovernance The email governance to use for this email. It determines which default values and overrides to apply, what the maximum
	 *                           email size is, etc.
	 * @throws IllegalArgumentException If the supplied or completed choices combine S/MIME and OpenPGP, as described by {@link #buildEmail()}.
	 */
	@Cli.ExcludeApi(reason = "This API is specifically for Java use")
	Email buildEmailCompletedWithDefaultsAndOverrides(@NotNull EmailGovernance emailGovernance);

	/**
	 * Sets optional ID to a fixed value, which is otherwise generated by the underlying JavaMail framework when sending the email.
	 * <p>
	 * <strong>Note 1:</strong> ID is user-controlled. Only when converting an email, Simple Java Mail might fill the sent-date.
	 * <br>
	 * <strong>Note 2:</strong> The id-format should conform to <a href="https://tools.ietf.org/html/rfc5322#section-3.6.4">rfc5322#section-3.6.4</a>
	 *
	 * @param id The mime message id, example: {@code <123@456>}
	 */
	EmailPopulatingBuilder fixingMessageId(@Nullable @Cli.Optional String id);

	/**
	 * Replaces the sender using the single-mailbox interpretation of {@link #from(String, String)}, with no explicit name.
	 *
	 * @param fromAddress The sender address visible to receivers of the email.
	 */
	@Cli.ExcludeApi(reason = "API is subset of another API")
	EmailPopulatingBuilder from(@NotNull String fromAddress);

	/**
	 * Replaces the sender with one interpreted mailbox, delegating the resulting name/address to {@link #from(Recipient)}.
	 * A bare address has no display name; {@code "Alice <alice@example.org>"} supplies both. A nonblank explicit name wins over the embedded name;
	 * null or blank uses the embedded name instead. Quoted names containing commas are one mailbox.
	 * <p>
	 * Successfully parsed address lists and RFC address groups (including single-member or empty groups) are rejected rather than selecting
	 * the first address. Use the factory's {@code recipientsBuilder()} for multiple addresses. Nonempty text that cannot be parsed or produces
	 * no mailbox is retained unchanged for the configured send-time validator. Parsing does not validate deliverability or perform DNS/network
	 * checks; address validation and injection checks retain their separate send-time contracts.
	 *
	 * @param fixedName The visible sender name; if null or blank, use the name in the mailbox, if any.
	 * @param fromAddress One sender mailbox visible to receivers of the email.
	 * @throws IllegalArgumentException If the required address is missing or parsing produces several mailboxes or an RFC address group.
	 */
	EmailPopulatingBuilder from(@Nullable @Cli.Optional String fixedName, @NotNull String fromAddress);

	/**
	 * Delegates to {@link #from(Recipient)} with a new {@link Recipient} wrapped around the given fixed name and email address.
	 * Uses the same single-mailbox checks and name precedence as {@link #from(String, String)} without changing the supplied InternetAddress.
	 * @param fixedName The visible sender name; if null or blank, use the InternetAddress's personal name, if any.
	 * @param fromAddress One sender mailbox visible to receivers of the email.
	 */
	EmailPopulatingBuilder from(@Nullable @Cli.Optional String fixedName, @NotNull InternetAddress fromAddress);

	/**
	 * Delegates to {@link #from(Recipient)} with a new {@link Recipient} wrapped around the given email address.
	 * Uses its personal name and the single-mailbox checks of {@link #from(String, String)} without changing the supplied InternetAddress.
	 * @param fromAddress One sender mailbox visible to receivers of the email.
	 */
	EmailPopulatingBuilder from(@NotNull InternetAddress fromAddress);

	/**
	 * Sets the address of the sender of this email with given {@link Recipient} (ignoring its {@link Message.RecipientType} if provided).
	 * Replaces the previous sender; copies the supplied name/address without reparsing them.
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
	EmailPopulatingBuilder from(@NotNull Recipient recipient);

	/**
	 * Appends every supplied Reply-To mailbox, retaining existing entries, order and duplicates. Delegates to {@link #withReplyTo(List)}
	 * with the list parsing of {@link #to(String, String)} and no explicit name.
	 *
	 * @param replyToAddress The address that receivers will get when they reply to the email.
	 */
	@Cli.ExcludeApi(reason = "API is subset of another API")
	EmailPopulatingBuilder withReplyTo(@NotNull String replyToAddress);

	/**
	 * Appends every supplied Reply-To mailbox, retaining existing entries, order and duplicates. Delegates to {@link #withReplyTo(List)}
	 * with the list parsing and explicit-name precedence of {@link #to(String, String)}.
	 *
	 * @param fixedName Name receivers see when replying; if null or blank, use the embedded mailbox name, if any.
	 * @param replyToAddress One mailbox that receivers will get when they reply to the email.
	 */
	EmailPopulatingBuilder withReplyTo(@Nullable @Cli.Optional String fixedName, @NotNull String replyToAddress);

	/**
	 * Appends every supplied Reply-To mailbox, retaining existing entries, order and duplicates. Delegates to {@link #withReplyTo(List)}
	 * with the supplied personal name and the list parsing of {@link #to(String, String)}.
	 * @param replyToAddress One reply destination; the supplied InternetAddress is not changed.
	 */
	EmailPopulatingBuilder withReplyTo(@NotNull InternetAddress replyToAddress);

	/**
	 * Appends every supplied Reply-To mailbox, retaining existing entries, order and duplicates. Delegates to {@link #withReplyTo(List)}
	 * with the list parsing and name precedence of {@link #to(String, String)}.
	 * @param fixedName Name receivers see when replying; if null or blank, use the supplied personal name, if any.
	 * @param replyToAddress One reply destination; the supplied InternetAddress is not changed.
	 */
	EmailPopulatingBuilder withReplyTo(@Nullable @Cli.Optional String fixedName, @NotNull InternetAddress replyToAddress);

	/**
	 * Appends the supplied name/address without reparsing them, ignoring its recipient type. Existing Reply-To entries, order and duplicates
	 * remain, including on a copied Email. Use {@code clearReplyTo().withReplyTo(recipient)} to replace the local destinations.
	 * @param recipient Preconfigured reply destination with optional name and mandatory address.
	 * @see #withReplyTo(List)
	 */
	EmailPopulatingBuilder withReplyTo(@NotNull Recipient recipient);

	/**
	 * Appends the supplied <em>replyTo</em> destinations, copying their names/addresses without reparsing and ignoring their
	 * {@link Message.RecipientType}. Existing entries are retained, including those already populated by copying an Email.
	 * Repeated calls and lists preserve iteration order and duplicate occurrences. An empty list is a no-op, not a clear.
	 * <p>
	 * If provided, email clients should prioritize the <em>replyTo</em> recipient over the <em>from</em> recipient when replying to this email.
	 * To replace destinations on a copy, use {@code mail.emailBuilder().copying(original).clearReplyTo().withReplyTo("support@example.org")}.
	 * The original Email is unchanged; configured defaults, overrides and mandatory locks still apply later.
	 *
	 * @param recipients Preconfigured recipients which each includes optional name and mandatory email address.
	 *
	 * @see #withReplyTo(String, String)
	 * @see #clearReplyTo()
	 */
	EmailPopulatingBuilder withReplyTo(@NotNull List<Recipient> recipients);

	/**
	 * Delegates to {@link #withBounceTo(Recipient)} with a new {@link Recipient} wrapped around the email address (or null if missing).
	 * Replaces the previous bounce address. Non-null input uses the single-mailbox interpretation of {@link #from(String, String)};
	 * null clears the local choice.
	 *
	 * @param bounceToAddress The SMTP envelope sender to which delivery failures are normally returned.
	 */
	@Cli.ExcludeApi(reason = "API is subset of another API")
	@SuppressWarnings("unused")
	EmailPopulatingBuilder withBounceTo(@Nullable @Cli.Optional String bounceToAddress);

	/**
	 * Delegates to {@link #withBounceTo(Recipient)} with a new {@link Recipient} wrapped around the given name and email address.
	 * Replaces the previous bounce address using the single-mailbox checks and name precedence of {@link #from(String, String)}.
	 * The name is retained on the {@link Email}, but it has no meaning in the SMTP envelope and is not sent with the address.
	 *
	 * @param name Name retained with the configured recipient; null/blank uses the embedded name. Ignored when setting the SMTP envelope sender.
	 * @param bounceToAddress The SMTP envelope sender to which delivery failures are normally returned.
	 */
	EmailPopulatingBuilder withBounceTo(@Nullable @Cli.Optional String name, @NotNull String bounceToAddress);

	/**
	 * Delegates to {@link #withBounceTo(Recipient)} with a new {@link Recipient} wrapped around the given address.
	 * Replaces the previous bounce address using its personal name and the single-mailbox checks of {@link #from(String, String)}.
	 * @param bounceToAddress One envelope sender; the supplied InternetAddress is not changed.
	 */
	@SuppressWarnings("UnusedReturnValue")
	EmailPopulatingBuilder withBounceTo(@NotNull InternetAddress bounceToAddress);

	/**
	 * Delegates to {@link #withBounceTo(Recipient)} with a new {@link Recipient} wrapped around the given fixed name and address.
	 * Replaces the previous bounce address using the single-mailbox checks and name precedence of {@link #from(String, String)}.
	 * @param name Name retained with the envelope address; null/blank uses the supplied personal name. Not sent in the SMTP envelope.
	 * @param bounceToAddress One envelope sender; the supplied InternetAddress is not changed.
	 */
	@Cli.ExcludeApi(reason = "Method is not detailed enough for CLI")
	@SuppressWarnings("unused")
	EmailPopulatingBuilder withBounceTo(@Nullable @Cli.Optional String name, @NotNull InternetAddress bounceToAddress);

	/**
	 * Sets the SMTP envelope sender ({@code MAIL FROM}) for this email using the given {@link Recipient}. The recipient's name and
	 * {@link Message.RecipientType} are not part of the SMTP envelope.
	 * Replaces the previous local choice; copies the supplied name/address without reparsing them.
	 * <p>
	 * Delivery failures are normally returned to this address. The value is applied to this message only and does not modify the mailer's shared
	 * {@link jakarta.mail.Session}. It is separate from {@link #withReplyTo(Recipient)}: {@code Reply-To} is a message header used by mail clients
	 * when a recipient replies, while the envelope sender is used by mail servers during delivery. A receiving mail system may record the envelope
	 * sender in a {@code Return-Path} header.
	 *
	 * @param recipient Recipient containing the envelope address, or {@code null} to clear it.
	 *
	 * @see #withBounceTo(String, String)
	 */
	EmailPopulatingBuilder withBounceTo(@Nullable @Cli.Optional Recipient recipient);

	/**
	 * Sets the optional subject of this email.
	 *
	 * @param subject Optional text to be used in the subject field of the email.
	 */
	EmailPopulatingBuilder withSubject(@Nullable @Cli.Optional String subject);

	/**
	 * Delegates to {@link #withPlainText(String)}.
	 *
	 * @param textFile Plain text to set as email body (overwrites any previous plain text body). If no HTML body is included as well, plain text
	 *                would be used instead by the email client.
	 */
	@Cli.OptionNameOverride("withPlainTextFromFile")
	EmailPopulatingBuilder withPlainText(@NotNull File textFile);

	/**
	 * Reads the complete UTF-8 file and delegates to {@link #withPlainText(String)}. Any stream opened for the path is closed before this method returns.
	 *
	 * @param textPath Plain text to set as email body (overwrites any previous plain text body).
	 * @return This builder.
	 */
	@Cli.ExcludeApi(reason = "The File overload already provides the same CLI file conversion")
	EmailPopulatingBuilder withPlainText(@NotNull Path textPath);

	/**
	 * Sets the optional email message body in plain text.
	 * <p>
	 * Both text and HTML can be provided, which will  be offered to the email client as alternative content. Email clients that support it, will
	 * favor HTML over plain text and ignore the text body completely.
	 *
	 * @param text Plain text to set as email body (overwrites any previous plain text body). If no HTML body is included as well, plain text
	 *                would be used instead by the email client.
	 *
	 * @see #withPlainText(File)
	 * @see #prependPlainText(String)
	 * @see #appendPlainText(String)
	 */
	EmailPopulatingBuilder withPlainText(@Nullable @Cli.Optional String text);

	/**
	 * Delegates to {@link #prependPlainText(String)}.
	 *
	 * @param textFile The plain text to prepend to whatever plain text is already there.
	 */
	@Cli.OptionNameOverride("prependTextFromFile")
	@SuppressWarnings("unused")
	EmailPopulatingBuilder prependPlainText(@NotNull File textFile);

	/**
	 * Reads the complete UTF-8 file and delegates to {@link #prependPlainText(String)}. Any stream opened for the path is closed before this method returns.
	 *
	 * @param textPath The plain text to prepend to whatever plain text is already there.
	 * @return This builder.
	 */
	@Cli.ExcludeApi(reason = "The File overload already provides the same CLI file conversion")
	EmailPopulatingBuilder prependPlainText(@NotNull Path textPath);

	/**
	 * Prepends text to the current plain text body (or starts it if plain text body is missing).
	 *
	 * @param text The plain text to prepend to whatever plain text is already there.
	 *
	 * @see #prependPlainText(File)
	 * @see #appendPlainText(String)
	 * @see #withPlainText(String)
	 */
	@Cli.OptionNameOverride("prependText")
	EmailPopulatingBuilder prependPlainText(@NotNull String text);

	/**
	 * Delegates to {@link #appendPlainText(String)}.
	 *
	 * @param textFile The plain text to append to whatever plain text is already there.
	 */
	@Cli.OptionNameOverride("appendTextFromFile")
	@SuppressWarnings("unused")
	EmailPopulatingBuilder appendPlainText(@NotNull File textFile);

	/**
	 * Reads the complete UTF-8 file and delegates to {@link #appendPlainText(String)}. Any stream opened for the path is closed before this method returns.
	 *
	 * @param textPath The plain text to append to whatever plain text is already there.
	 * @return This builder.
	 */
	@Cli.ExcludeApi(reason = "The File overload already provides the same CLI file conversion")
	EmailPopulatingBuilder appendPlainText(@NotNull Path textPath);

	/**
	 * Appends text to the current plain text body (or starts it if plain text body is missing).
	 *
	 * @param text The plain text to append to whatever plain text is already there.
	 *
	 * @see #appendPlainText(File)
	 * @see #prependPlainText(String)
	 * @see #withPlainText(String)
	 */
	@Cli.OptionNameOverride("appendText")
	EmailPopulatingBuilder appendPlainText(@NotNull String text);

	/**
	 * Delegates to {@link #withHTMLText(String)}.
	 *
	 * @param textHTMLFile HTML text to set as email body (overwrites any previous HTML text body). If no HTML body is included, plain text
	 *                would be used instead by the email client if provided.
	 */
	@Cli.OptionNameOverride("withHTMLTextFromFile")
	EmailPopulatingBuilder withHTMLText(@NotNull File textHTMLFile);

	/**
	 * Reads the complete UTF-8 file and delegates to {@link #withHTMLText(String)}. Any stream opened for the path is closed before this method returns.
	 *
	 * @param textHTMLPath HTML text to set as email body (overwrites any previous HTML text body).
	 * @return This builder.
	 */
	@Cli.ExcludeApi(reason = "The File overload already provides the same CLI file conversion")
	EmailPopulatingBuilder withHTMLText(@NotNull Path textHTMLPath);

	/**
	 * Sets the optional email message body in HTML text.
	 * <p>
	 * Both text and HTML can be provided, which will  be offered to the email client as alternative content. Email clients that support it, will
	 * favor HTML over plain text and ignore the text body completely.
	 *
	 * @param textHTML HTML text to set as email body (overwrites any previous HTML text body). If no HTML body is included, plain text
	 *                would be used instead by the email client if provided.
	 *
	 * @see #withHTMLText(File)
	 * @see #prependHTMLText(String)
	 * @see #appendHTMLText(String)
	 */
	EmailPopulatingBuilder withHTMLText(@Nullable @Cli.Optional String textHTML);

	/**
	 * Delegates to {@link #prependHTMLText(String)}.
	 *
	 * @param textHTMLFile The HTML text to prepend to whatever is already there in the body.
	 */
	@Cli.OptionNameOverride("prependTextHTMLFromFile")
	@SuppressWarnings("unused")
	EmailPopulatingBuilder prependHTMLText(@NotNull File textHTMLFile);

	/**
	 * Reads the complete UTF-8 file and delegates to {@link #prependHTMLText(String)}. Any stream opened for the path is closed before this method returns.
	 *
	 * @param textHTMLPath The HTML text to prepend to whatever is already there in the body.
	 * @return This builder.
	 */
	@Cli.ExcludeApi(reason = "The File overload already provides the same CLI file conversion")
	EmailPopulatingBuilder prependHTMLText(@NotNull Path textHTMLPath);

	/**
	 * Prepends HTML text to the current HTML text body (or starts it if HTML text body is missing).
	 *
	 * @param textHTML The HTML text to prepend to whatever is already there in the body.
	 *
	 * @see #prependHTMLText(File)
	 * @see #appendHTMLText(String)
	 * @see #withHTMLText(String)
	 */
	@Cli.OptionNameOverride("prependTextHTML")
	EmailPopulatingBuilder prependHTMLText(@NotNull String textHTML);

	/**
	 * Delegates to {@link #appendHTMLText(String)}.
	 *
	 * @param textHTMLFile The HTML text to append to whatever is already there in the body.
	 */
	@Cli.OptionNameOverride("appendTextHTMLFromFile")
	@SuppressWarnings("unused")
	EmailPopulatingBuilder appendHTMLText(@NotNull File textHTMLFile);

	/**
	 * Reads the complete UTF-8 file and delegates to {@link #appendHTMLText(String)}. Any stream opened for the path is closed before this method returns.
	 *
	 * @param textHTMLPath The HTML text to append to whatever is already there in the body.
	 * @return This builder.
	 */
	@Cli.ExcludeApi(reason = "The File overload already provides the same CLI file conversion")
	EmailPopulatingBuilder appendHTMLText(@NotNull Path textHTMLPath);

	/**
	 * Appends HTML text to the current HTML text body (or starts it if HTML text body is missing).
	 *
	 * @param textHTML The HTML text to append to whatever is already there in the body.
	 *
	 * @see #appendHTMLText(File)
	 * @see #prependHTMLText(String)
	 * @see #withHTMLText(String)
	 */
	@Cli.OptionNameOverride("appendTextHTML")
	EmailPopulatingBuilder appendHTMLText(@NotNull String textHTML);

	/**
	 * Sets the optional calendar details that clients such as Outlook might be able to handle. Will be set as alternative bodypart similar to
	 * {@link #withPlainText(String)} and {@link #withHTMLText(String)}.
	 *
	 * @param calendarMethod An RFC-2446 VEVENT calendar component method. Example: {@code PUBLISH, REQUEST, REPLY, ADD, CANCEL, REFRESH, COUNTER, DECLINECOUNTER}
	 * @param textCalendar free form text, which you can produce with a library such as
	 *                        <a href="https://github.com/ical4j/ical4j/wiki/Examples">ical4j</a>.
	 *
	 * @see "The Test demo app in Simple Java Mail's source for a working example."
	 */
	EmailPopulatingBuilder withCalendarText(@NotNull CalendarMethod calendarMethod, @NotNull String textCalendar);

	/**
	 * Appends the provided recipients, preserving their supplied name, address, type, S/MIME certificate and notification preferences without reparsing.
	 * An absent type defaults to To for this visible recipient use; the supplied Recipient remains unchanged.
	 * Existing recipients, including those from copying an Email, remain in order. Repeated calls retain duplicates; an empty array adds nothing.
	 * Use {@link #clearRecipients()} before adding replacements. The provided array's structure is not retained.
	 * <p>
	 * If the recipients are reused and their certificate state should not be carried into this email, first pass them through
	 * {@link IRecipientsBuilder#clearingSmimeCertificates()}.
	 */
	EmailPopulatingBuilder withRecipients(@NotNull Recipient @NotNull ...recipients);

	/**
	 * Appends TO recipients from a mailbox or complete mailbox list, retaining existing recipients, order and duplicates.
	 * Uses the list parsing of {@link #to(String, String)}; each mailbox's embedded name is retained.
	 * @param address Bare or named mailboxes, including comma- or semicolon-delimited lists and address groups.
	 * @see #to(String, String)
	 */
	@Cli.ExcludeApi(reason = "The existing recipient CLI options provide recipient parsing")
	EmailPopulatingBuilder to(@NotNull String address);

	/**
	 * Appends TO recipients from a mailbox or complete mailbox list, retaining existing recipients, their metadata, order and duplicates,
	 * including on a copied Email.
	 * Parses every mailbox from a comma- or semicolon-delimited list and flattens address groups.
	 * A nonblank explicit name overrides every embedded name; null/blank retains each embedded name. Quoted punctuation,
	 * comments, escapes, input order and duplicate occurrences are retained. Unparseable nonempty input remains for the configured send-time validator.
	 * Parsing follows the same mailbox-list rules as {@link IRecipientsBuilder}; this two-String form always means name plus complete address list.
	 * Use the factory's plural {@code recipientsBuilder()} for collection input, default-name policies or recipient-specific metadata,
	 * then {@link #withRecipients(Collection)} for its prepared values. Clear the local recipient collection before adding replacements.
	 * @param name Explicit name; null or blank uses the embedded mailbox name, if any.
	 * @param address Bare or named mailboxes, including comma- or semicolon-delimited lists and address groups.
	 * @see #clearRecipients()
	 */
	@Cli.ExcludeApi(reason = "The existing recipient CLI options provide recipient parsing")
	EmailPopulatingBuilder to(@Nullable String name, @NotNull String address);

	/**
	 * Appends CC recipients from a mailbox or complete mailbox list, retaining existing recipients, order and duplicates.
	 * Uses the list parsing of {@link #to(String, String)}, retaining every mailbox and its embedded name.
	 * @param address Bare or named mailboxes, including comma- or semicolon-delimited lists and address groups.
	 * @see #cc(String, String)
	 */
	@Cli.ExcludeApi(reason = "The existing recipient CLI options provide recipient parsing")
	EmailPopulatingBuilder cc(@NotNull String address);

	/**
	 * Appends CC recipients from a mailbox or complete mailbox list with an optional name, retaining existing recipients, order and duplicates,
	 * including on a copied Email.
	 * Uses the same list parsing and explicit-name precedence as {@link #to(String, String)}.
	 * @param name Explicit name; null or blank uses the embedded mailbox name, if any.
	 * @param address Bare or named mailboxes, including comma- or semicolon-delimited lists and address groups.
	 * @see #to(String, String)
	 */
	@Cli.ExcludeApi(reason = "The existing recipient CLI options provide recipient parsing")
	EmailPopulatingBuilder cc(@Nullable String name, @NotNull String address);

	/**
	 * Appends BCC recipients from a mailbox or complete mailbox list, retaining existing recipients, order and duplicates.
	 * Uses the list parsing of {@link #to(String, String)}, retaining every mailbox and its embedded name.
	 * @param address Bare or named mailboxes, including comma- or semicolon-delimited lists and address groups.
	 * @see #bcc(String, String)
	 */
	@Cli.ExcludeApi(reason = "The existing recipient CLI options provide recipient parsing")
	EmailPopulatingBuilder bcc(@NotNull String address);

	/**
	 * Appends BCC recipients from a mailbox or complete mailbox list with an optional name, retaining existing recipients, order and duplicates,
	 * including on a copied Email.
	 * Uses the same list parsing and explicit-name precedence as {@link #to(String, String)}.
	 * @param name Explicit name; null or blank uses the embedded mailbox name, if any.
	 * @param address Bare or named mailboxes, including comma- or semicolon-delimited lists and address groups.
	 * @see #to(String, String)
	 */
	@Cli.ExcludeApi(reason = "The existing recipient CLI options provide recipient parsing")
	EmailPopulatingBuilder bcc(@Nullable String name, @NotNull String address);

	/**
	 * Appends the provided recipients, preserving their supplied name, address, type, S/MIME certificate and notification preferences without reparsing.
	 * An absent type defaults to To for this visible recipient use; the supplied Recipient remains unchanged.
	 * Existing recipients, including those from copying an Email, remain in order. Repeated calls retain duplicates; an empty collection adds nothing.
	 * Use {@link #clearRecipients()} before adding replacements. The provided collection's structure is not retained.
	 * <p>
	 * If the recipients are reused and their certificate state should not be carried into this email, first pass them through
	 * {@link IRecipientsBuilder#clearingSmimeCertificates()}.
	 */
	EmailPopulatingBuilder withRecipients(@NotNull Collection<Recipient> recipients);

	/**
	 * Delegates to {@link #withEmbeddedImage(String, DataSource)}, with an owned, read-only byte source created using the provided name, data and
	 * mimetype.
	 *
	 * @param name     The name of the image as being referred to from the message content body (e.g. 'signature').
	 * @param data     The byte data of the image to be embedded; copied immediately so later array changes do not alter the image.
	 * @param mimetype The content type of the given data (e.g. "image/gif" or "image/jpeg").
	 */
	EmailPopulatingBuilder withEmbeddedImage(@NotNull String name, byte@NotNull[] data, @NotNull String mimetype);

	/**
	 * Delegates to {@link #withPreEncodedEmbeddedImage(String, DataSource, ContentTransferEncoding)} with an owned, read-only byte source created
	 * using the provided name, data and mimetype.
	 *
	 * @param name                              The name of the image as being referred to from the message content body (e.g. 'signature').
	 * @param data                              The already encoded image data.
	 * @param mimetype                          The content type of the given data (e.g. "image/gif" or "image/jpeg").
	 * @param preEncodedContentTransferEncoding The encoder that was already applied to the data.
	 */
	@Cli.ExcludeApi(reason = "pre-encoded MIME payloads are an advanced byte-stream concern")
	EmailPopulatingBuilder withPreEncodedEmbeddedImage(@NotNull String name, byte@NotNull[] data, @NotNull String mimetype, @NotNull ContentTransferEncoding preEncodedContentTransferEncoding);

	/**
	 * Adds image data to this email that can be referred to from the email HTML body. For adding images as attachment, refer to {@link
	 * #withAttachment(String, DataSource)} instead.
	 * <p>
	 * The provided {@link DataSource} is assumed to be of mimetype png, jpg or whatever the email client supports as valid image embedded in HTML
	 * content.
	 *
	 * @param name      The name of the image as being referred to from the message content body (e.g. 'src="cid:yourImageName"'). If not provided, the
	 *                  name of the given data source is used instead.
	 * @param imagedata The image data.
	 *
	 * @see EmailPopulatingBuilder#withEmbeddedImage(String, byte[], String)
	 * @see EmailPopulatingBuilder#withEmbeddedImages(List)
	 */
	EmailPopulatingBuilder withEmbeddedImage(@Nullable @Cli.Optional String name, @NotNull DataSource imagedata);

	/**
	 * Adds already encoded image data to this email that can be referred to from the email HTML body.
	 *
	 * @param name                              The name of the image as being referred to from the message content body (e.g. 'src="cid:yourImageName"'). If not provided, the
	 *                                          name of the given data source is used instead.
	 * @param imagedata                         The already encoded image data.
	 * @param preEncodedContentTransferEncoding The encoder that was already applied to the data.
	 */
	@Cli.ExcludeApi(reason = "pre-encoded MIME payloads are an advanced byte-stream concern")
	EmailPopulatingBuilder withPreEncodedEmbeddedImage(@Nullable @Cli.Optional String name, @NotNull DataSource imagedata, @NotNull ContentTransferEncoding preEncodedContentTransferEncoding);

	/**
	 * Adds a constructed embedded image, retaining its content source and metadata. Its Content-ID may differ from its filename;
	 * HTML references the Content-ID as {@code cid:value}. If absent, the resource name supplies the Content-ID.
	 * The resource must have a name, source name or Content-ID. No content stream is opened.
	 * @param embeddedImage The image resource, normally created with {@link AttachmentResource#builder()}.
	 * @see #withEmbeddedImages(List)
	 */
	@Cli.ExcludeApi(reason = "The CLI uses the DataSource convenience")
	EmailPopulatingBuilder withEmbeddedImage(@NotNull AttachmentResource embeddedImage);

	/**
	 * Delegates to {@link #withEmbeddedImage(AttachmentResource)} for each embedded image, retaining resource identity and order.
	 */
	EmailPopulatingBuilder withEmbeddedImages(@NotNull List<AttachmentResource> embeddedImages);

	/**
	 * Delegates to {@link #withHeader(String, Object)} for each header in the provided {@code Map}.
	 */
	<T> EmailPopulatingBuilder withHeaders(@NotNull Map<String, Collection<T>> headers);

	/**
	 * Delegates to {@link #withHeader(String, Object, boolean)} with <em>replaceHeader</em> set to {@code false}.
	 *
	 * @param name  The name of the header. Example: <code>withHeader("X-Priority", 2)</code>
	 * @param value The value of the header. Non-null values are stored using {@link String#valueOf(Object)}; null is retained in metadata
	 *              and produces a present empty header when converting or sending, just as an explicit empty String does.
	 */
	EmailPopulatingBuilder withHeader(@NotNull String name, @Nullable @Cli.Optional Object value);

	/**
	 * Adds a custom header to the email. Names ignore capitalization, so {@code X-Route} and {@code x-route} refer to the same header.
	 * Pass {@code true} for {@code replaceHeader} when the new value should replace all earlier values for that header; otherwise it is added
	 * to the existing collection if it is not already present. Non-null values are stored as Strings. Null remains a distinct stored choice
	 * and produces a present empty header when converting or sending, just as an explicit empty String does; it does not omit the header.
	 *
	 * @param name  The name of the header. Example: <code>withHeader("X-Priority", 2)</code>
	 * @param value The value of the header. Non-null values are stored using {@link String#valueOf(Object)}; null is retained in metadata
	 *              and produces a present empty header when converting or sending, just as an explicit empty String does.
	 * @param replaceHeader Whether to add the value to an existing collection of values (if any) or create a new collection with only the given value.
	 *
	 * @see #withHeaders(Map)
	 */
	@Cli.ExcludeApi(reason = "this is a rare case, so let's not crowd the CLI with it")
	EmailPopulatingBuilder withHeader(@NotNull String name, @Nullable @Cli.Optional Object value, boolean replaceHeader);

	/**
	 * Adds an attachment from bytes, copied immediately so subsequent array changes do not affect the Email.
	 * @param name Optional filename; null falls back to the source name or a generated name.
	 * @param data The content bytes; empty attachments are permitted.
	 * @param mimetype The MIME content type, for example {@code application/pdf}.
	 * @see #withAttachment(AttachmentResource)
	 */
	EmailPopulatingBuilder withAttachment(@Nullable @Cli.Optional String name, byte@NotNull[] data, @NotNull String mimetype);

	/**
	 * Delegates to {@link #withPreEncodedAttachment(String, DataSource, ContentTransferEncoding)} with an owned, read-only byte source created
	 * using the provided name, data and mimetype.
	 *
	 * @param name                              Optional name of the attachment (e.g. 'filename.ext'). If omitted, the internal name of the datasource is used. If that too is empty, a name will be generated
	 *                                          using {@link java.util.UUID}.
	 * @param data                              The already encoded attachment data.
	 * @param mimetype                          The content type of the given data (e.g. "plain/text", "image/gif" or "application/pdf").
	 * @param preEncodedContentTransferEncoding The encoder that was already applied to the data.
	 */
	@Cli.ExcludeApi(reason = "pre-encoded MIME payloads are an advanced byte-stream concern")
	EmailPopulatingBuilder withPreEncodedAttachment(@Nullable @Cli.Optional String name, byte@NotNull[] data, @NotNull String mimetype, @NotNull ContentTransferEncoding preEncodedContentTransferEncoding);

	/**
	 * Adds an attachment using a borrowed, repeatable source. Content remains application-owned and must stay available and stable while in use.
	 *
	 * @param name                    Optional name of the attachment (e.g. 'filename.ext'). If omitted, the internal name of the datasource is used. If that too is empty, a name will be generated
	 *                                using {@link java.util.UUID}.
	 * @param filedata                The attachment data.
	 * @see AttachmentResource#builder()
	 * @see #withAttachment(AttachmentResource)
	 */
	EmailPopulatingBuilder withAttachment(@Nullable @Cli.Optional String name, @NotNull DataSource filedata);

	/**
	 * Attaches the file at the supplied path using its filename as the attachment name. Content remains borrowed and is read only when needed.
	 * @see #withAttachment(String, Path)
	 */
	@Cli.ExcludeApi(reason = "The DataSource attachment overload already supports CLI file arguments")
	EmailPopulatingBuilder withAttachment(@NotNull Path path);

	/**
	 * Attaches the file at the supplied path with an optional display name; null uses the path's filename.
	 * Content type is determined from the source filename through Jakarta Activation's default filename map, independently of the display name.
	 * <p>
	 * Building and copying an Email do not open or read the file. Each read opens a fresh, read-only stream, including for paths from non-default
	 * filesystems. The application must keep the path's filesystem open and its content available and stable while sending or rendering.
	 * Missing or unreadable files fail when consumed. Serialization consumes the content using the ordinary attachment snapshot contract.
	 * @see #withAttachment(String, DataSource)
	 * @see #clearAttachments()
	 */
	@Cli.ExcludeApi(reason = "The DataSource attachment overload already supports CLI file arguments")
	EmailPopulatingBuilder withAttachment(@Nullable String name, @NotNull Path path);

	/**
	 * Adds already encoded attachment data to the email message.
	 * <p>
	 * Use this when the provided {@link DataSource} already returns data encoded using the given MIME {@code Content-Transfer-Encoding}; Simple Java Mail
	 * will preserve the payload instead of encoding it again.
	 *
	 * @param name                              Optional name of the attachment (e.g. 'filename.ext'). If omitted, the internal name of the datasource is used. If that too is empty, a name will be generated
	 *                                          using {@link java.util.UUID}.
	 * @param filedata                          The already encoded attachment data.
	 * @param preEncodedContentTransferEncoding The encoder that was already applied to the data.
	 */
	@Cli.ExcludeApi(reason = "pre-encoded MIME payloads are an advanced byte-stream concern")
	EmailPopulatingBuilder withPreEncodedAttachment(@Nullable @Cli.Optional String name, @NotNull DataSource filedata, @NotNull ContentTransferEncoding preEncodedContentTransferEncoding);

	/**
	 * Adds a constructed attachment, retaining its content source and named MIME metadata without reading content.
	 * Attachments appear as downloadable or inline files according to the receiving client; for HTML images use
	 * {@link #withEmbeddedImage(AttachmentResource)}. Repeated explicit additions retain duplicates and order.
	 * @param attachment The attachment resource, normally created with {@link AttachmentResource#builder()}.
	 * @see #withAttachments(List)
	 */
	@Cli.ExcludeApi(reason = "The CLI uses its internal scalar attachment adapters")
	EmailPopulatingBuilder withAttachment(@NotNull AttachmentResource attachment);

	/**
	 * Delegates to {@link #withAttachment(AttachmentResource)} for each attachment, retaining resource identity and order.
	 */
	EmailPopulatingBuilder withAttachments(@NotNull List<AttachmentResource> attachments);

	/**
	 * Primes this email for signing with a DKIM domain key. Actual signing is done when sending using a <code>Mailer</code>.
	 * <p>
	 * <strong>Note:</strong> this only works in combination with the {@value org.simplejavamail.internal.modules.DKIMModule#NAME}.
	 * <p>
	 * The supplied configuration also chooses <em>useLengthParam</em>, <em>signingAlgorithm</em>,
	 * <em>headerCanonicalization</em> and <em>bodyCanonicalizationAlgorithm</em>.
	 *
	 * @see <a href="https://postmarkapp.com/guides/dkim">more on DKIM 1</a>
	 * @see <a href="https://github.com/markenwerk/java-utils-mail-dkim">more on DKIM 2</a>
	 * @see <a href="http://www.gettingemaildelivered.com/dkim-explained-how-to-set-up-and-use-domainkeys-identified-mail-effectively">more on DKIM 3</a>
	 * @see <a href="https://en.wikipedia.org/wiki/DomainKeys_Identified_Mail">more on DKIM 4</a>
	 * @see <a href="https://github.com/bbottema/dkim-verify">List of explanations all tags in a <em>DKIM-Signature</em> header</a>
	 * @param dkimConfig Signing key, domain, selector, header exclusions and optional DKIM preferences.
	 * @see DkimConfig
	 */
	@Cli.ExcludeApi(reason = "The CLI uses its internal key-input adapter")
	EmailPopulatingBuilder signWithDomainKey(@NotNull DkimConfig dkimConfig);

	/**
	 * Signs this email with an <a href="https://tools.ietf.org/html/rfc5751">S/MIME</a> signature, so the receiving client
	 * can verify whether the email content was tampered with.
	 * <p>
	 * <strong>Note:</strong> this only works in combination with the {@value org.simplejavamail.internal.modules.SMIMEModule#NAME}.<br>
	 * <strong>Note:</strong> You can also configure your <code>Mailer</code> instance do sign all emails by default (also has better performance).
	 * <p>
	 * Requires a non-null configuration. Use {@link #clearSmimeSigning()} to remove the local signing choice,
	 * or {@link #clearSmime()} to remove both local S/MIME choices.
	 *
	 * @param smimeSigningConfig The signing configuration to retain.
	 * @throws IllegalArgumentException If the configuration is null; the current choice remains unchanged.
	 * @see <a href="https://en.wikipedia.org/wiki/S/MIME">S/MIME on Wikipedia</a>
	 * @see <a href="https://www.globalsign.com/en/blog/what-is-s-mime/">Primer on S/MIME</a>
	 * @see SmimeSigningConfig
	 */
	@Cli.ExcludeApi(reason = "delegated method contains CLI compatible arguments")
	EmailPopulatingBuilder signWithSmime(@NotNull SmimeSigningConfig smimeSigningConfig);

	/**
	 * Encrypts this email with a X509 certificate according to the <a href="https://tools.ietf.org/html/rfc5751">S/MIME spec</a>
	 * signature.
	 * <p>
	 * You can sign this email with the public key you received from your recipient. The recipient then is the only person that
	 * can decrypt the email with his or her private key.
	 * <p>
	 * <strong>Note:</strong> this only works in combination with the {@value org.simplejavamail.internal.modules.SMIMEModule#NAME}.
	 * <p>
	 * Requires a non-null configuration. Use {@link #clearSmimeEncryption()} to remove the local encryption choice,
	 * or {@link #clearSmime()} to remove both local S/MIME choices. These clear operations retain recipient certificates.
	 *
	 * @param smimeEncryptionConfig The recipient's public key to use for encryption.
	 * @throws IllegalArgumentException If the configuration is null; the current choice remains unchanged.
	 *
	 * @see <a href="https://en.wikipedia.org/wiki/S/MIME">S/MIME on Wikipedia</a>
	 * @see <a href="https://www.globalsign.com/en/blog/what-is-s-mime/">Primer on S/MIME</a>
	 * @see <a href="https://github.com/markenwerk/java-utils-mail-smime">Underlying library's documentation</a>
	 *
	 * @see SmimeEncryptionConfig
	 */
	@Cli.ExcludeApi(reason = "delegated method contains CLI compatible arguments")
	EmailPopulatingBuilder encryptWithSmime(@NotNull SmimeEncryptionConfig smimeEncryptionConfig);

	/**
	 * Signs this email as RFC 3156 {@code multipart/signed}. OpenPGP and S/MIME cannot be configured on the same email.
	 * The optional {@value org.simplejavamail.internal.modules.OpenPgpModule#NAME} must be present when converting or sending.
	 * <p>
	 * To reuse this choice, put it on an Email supplied through {@link org.simplejavamail.api.mailer.MailerGenericBuilder#withEmailDefaults(Email)} or
	 * {@link org.simplejavamail.api.mailer.MailerGenericBuilder#withEmailOverrides(Email)}. The complete signing configuration is replaced, not combined:
	 * overrides win over this email, followed by defaults. Suppress incoming signing configuration with
	 * {@link EmailTemplateOptions.Builder#dontApplyDefaultValueFor(EmailProperty...)} or {@link EmailTemplateOptions.Builder#dontApplyOverrideValueFor(EmailProperty...)} and
	 * {@link EmailProperty#OPENPGP_SIGNING_CONFIG}.
	 */
	@Cli.ExcludeApi(reason = "OpenPGP key material is Java-only")
	EmailPopulatingBuilder signWithOpenPgp(@NotNull OpenPgpSigningConfig openPgpSigningConfig);

	/**
	 * Encrypts this email as RFC 3156 {@code multipart/encrypted}, including all configured recipient keys.
	 * OpenPGP and S/MIME cannot be configured on the same email.
	 * <p>
	 * Email defaults and overrides can supply this choice too. They replace the complete encryption configuration, including its recipient keys;
	 * keys from different configurations are never combined. Overrides win over this email, followed by defaults. Suppress incoming encryption
	 * configuration with {@link EmailTemplateOptions.Builder#dontApplyDefaultValueFor(EmailProperty...)} or {@link EmailTemplateOptions.Builder#dontApplyOverrideValueFor(EmailProperty...)} and
	 * {@link EmailProperty#OPENPGP_ENCRYPTION_CONFIG}.
	 */
	@Cli.ExcludeApi(reason = "OpenPGP key material is Java-only")
	EmailPopulatingBuilder encryptWithOpenPgp(@NotNull OpenPgpEncryptionConfig openPgpEncryptionConfig);

	/**
	 * When the S/MIME module is loaded, S/MIME signed / encrypted attachments are decrypted and kept in a separate list. However,
	 * if it is a single attachment and the actual attachment has mimetype "message/rfc822", it is assumed to be the message
	 * itself and by default will be merged with the top level email (basically overriding body, headers and attachments).
	 * <br>
	 * This API disables this behavior and stricly keeps all attachments as-is (still decrypted, but not merged with the email).
	 * <p>
	 * <strong>Note:</strong> this only works in combination with the {@value org.simplejavamail.internal.modules.SMIMEModule#NAME}.
	 */
	EmailPopulatingBuilder notMergingSingleSMIMESignedAttachment();

	/**
	 * Requests a read receipt by adding the {@code Disposition-Notification-To} header. The destinations default to all
	 * Reply-To mailboxes when present, or to From otherwise. Resolution is deferred until completion or sending.
	 *
	 * @see #withDispositionNotificationTo(Recipient)
	 */
	@Cli.OptionNameOverride("withDispositionNotificationToEnabled")
	EmailPopulatingBuilder withDispositionNotificationTo();

	/**
	 * Replaces the destinations with every mailbox parsed from the supplied address list, retaining embedded names.
	 * @see #withDispositionNotificationTo(Collection)
	 * Uses the list parsing and embedded-name behavior of {@link #to(String, String)}.
	 *
	 * @param address One mailbox or a list of notification destinations
	 */
	@Cli.ExcludeApi(reason = "API is subset of another API")
	EmailPopulatingBuilder withDispositionNotificationTo(@NotNull String address);

	/**
	 * Replaces the destinations with every parsed mailbox; a nonblank supplied name overrides every embedded name.
	 * @see #withDispositionNotificationTo(Collection)
	 * Uses the list parsing and name precedence of {@link #to(String, String)}.
	 *
	 * @param name Optional notification receiver name; null/blank uses the embedded mailbox name, if any.
	 * @param address One mailbox or a list of notification destinations
	 */
	@SuppressWarnings("unused")
	EmailPopulatingBuilder withDispositionNotificationTo(@Nullable @Cli.Optional String name, @NotNull String address);

	/**
	 * Replaces the destinations with every mailbox parsed from the supplied address list, retaining embedded names.
	 * @see #withDispositionNotificationTo(Collection)
	 * Uses the supplied personal name and the list parsing of {@link #to(String, String)}.
	 * @param address A mailbox or group; the supplied InternetAddress is not changed.
	 */
	@SuppressWarnings("UnusedReturnValue")
	EmailPopulatingBuilder withDispositionNotificationTo(@NotNull InternetAddress address);

	/**
	 * Replaces the destinations with every parsed mailbox; a nonblank supplied name overrides every embedded name.
	 * @see #withDispositionNotificationTo(Collection)
	 * Uses the list parsing and name precedence of {@link #to(String, String)}.
	 * @param fixedName Optional notification receiver name; null/blank uses the supplied personal name, if any.
	 * @param address A mailbox or group; the supplied InternetAddress is not changed.
	 */
	@SuppressWarnings("unused")
	EmailPopulatingBuilder withDispositionNotificationTo(@Nullable @Cli.Optional String fixedName, @NotNull InternetAddress address);

	/**
	 * Indicates this email should use the <a href="https://www.rfc-editor.org/rfc/rfc8098.html">MDN header "Disposition-Notification-To"</a> with the given
	 * preconfigured {@link Recipient}. This header requests a receipt from the recipient's mail client to signal that the email has been read.
	 * <p>
	 * The receiving mail client or user controls whether a receipt is returned.
	 * Replaces the previous local address; copies the supplied name/address without reparsing them and ignores its recipient type.
	 *
	 * @see #withDispositionNotificationTo()
	 * @see #withDispositionNotificationTo(String)
	 * @see #withDispositionNotificationTo(String, String)
	 */
	EmailPopulatingBuilder withDispositionNotificationTo(@NotNull Recipient recipient);

	/**
	 * Replaces this request's destinations with the supplied mailboxes and enables the request. The collection is copied;
	 * names and addresses are retained, while recipient type and delivery metadata do not apply to notification headers.
	 * An empty collection defers destination resolution to all Reply-To mailboxes, or From when Reply-To is absent.
	 * Defaults and overrides select a complete destination list rather than adding destinations from different templates.
	 * This does not add SMTP delivery recipients or guarantee that a notification will arrive.
	 *
	 * @param recipients Notification destinations; entries and their addresses must be non-null and nonempty.
	 * @return This builder.
	 */
	@Cli.ExcludeApi(reason = "The String overloads provide CLI mailbox-list input")
	EmailPopulatingBuilder withDispositionNotificationTo(@NotNull Collection<Recipient> recipients);

	/**
	 * Requests a receipt notification by adding the {@code Return-Receipt-To} header. The destinations default to all
	 * Reply-To mailboxes when present, or to From otherwise. Resolution is deferred until completion or sending.
	 * <p>
	 * For more detailed information, refer to {@link #withReturnReceiptTo(Recipient)}.
	 */
	@Cli.OptionNameOverride("withReturnReceiptToEnabled")
	EmailPopulatingBuilder withReturnReceiptTo();

	/**
	 * Replaces the destinations with every mailbox parsed from the supplied address list, retaining embedded names.
	 * @see #withReturnReceiptTo(Collection)
	 * Uses the list parsing and embedded-name behavior of {@link #to(String, String)}.
	 *
	 * @param address One mailbox or a list of receipt destinations
	 */
	@Cli.ExcludeApi(reason = "API is subset of another API")
	EmailPopulatingBuilder withReturnReceiptTo(@NotNull String address);

	/**
	 * Replaces the destinations with every parsed mailbox; a nonblank supplied name overrides every embedded name.
	 * @see #withReturnReceiptTo(Collection)
	 * Uses the list parsing and name precedence of {@link #to(String, String)}.
	 *
	 * @param name Receipt receiver name; null/blank uses the embedded mailbox name, if any.
	 * @param address One mailbox or a list of receipt destinations
	 */
	EmailPopulatingBuilder withReturnReceiptTo(@Nullable @Cli.Optional String name, @NotNull String address);

	/**
	 * Replaces the destinations with every mailbox parsed from the supplied address list, retaining embedded names.
	 * @see #withReturnReceiptTo(Collection)
	 * Uses the supplied personal name and the list parsing of {@link #to(String, String)}.
	 * @param address A mailbox or group; the supplied InternetAddress is not changed.
	 */
	@SuppressWarnings("UnusedReturnValue")
	EmailPopulatingBuilder withReturnReceiptTo(@NotNull InternetAddress address);

	/**
	 * Replaces the destinations with every parsed mailbox; a nonblank supplied name overrides every embedded name.
	 * @see #withReturnReceiptTo(Collection)
	 * Uses the list parsing and name precedence of {@link #to(String, String)}.
	 * @param fixedName Receipt receiver name; null/blank uses the supplied personal name, if any.
	 * @param address A mailbox or group; the supplied InternetAddress is not changed.
	 */
	@SuppressWarnings("unused")
	EmailPopulatingBuilder withReturnReceiptTo(@Nullable @Cli.Optional String fixedName, @NotNull InternetAddress address);

	/**
	 * Indicates that this email should use the {@code Return-Receipt-To} request header with the preconfigured {@link Recipient}. Supporting mail servers or
	 * clients may use this header to return a delivery or read notification, depending on their implementation.
	 * <p>
	 * The receiving system controls whether a receipt is returned.
	 * Replaces the previous local address; copies the supplied name/address without reparsing them and ignores its recipient type.
	 */
	EmailPopulatingBuilder withReturnReceiptTo(@NotNull Recipient recipient);

	/**
	 * Replaces this request's destinations with the supplied mailboxes and enables the request. The collection is copied;
	 * names and addresses are retained, while recipient type and delivery metadata do not apply to notification headers.
	 * An empty collection defers destination resolution to all Reply-To mailboxes, or From when Reply-To is absent.
	 * Defaults and overrides select a complete destination list rather than adding destinations from different templates.
	 * This does not add SMTP delivery recipients or guarantee that a notification will arrive.
	 *
	 * @param recipients Notification destinations; entries and their addresses must be non-null and nonempty.
	 * @return This builder.
	 */
	@Cli.ExcludeApi(reason = "The String overloads provide CLI mailbox-list input")
	EmailPopulatingBuilder withReturnReceiptTo(@NotNull Collection<Recipient> recipients);

	/**
	 * Appends delivery-recipient overrides, retaining existing entries, order and duplicates, including on a copied Email.
	 * Delegates to {@link #withOverrideReceivers(List)}; the nonempty requirement and clear-before-replacement behavior apply here too.
	 * @param recipients At least one prepared delivery recipient; names/addresses and metadata are not reparsed.
	 */
	EmailPopulatingBuilder withOverrideReceivers(@NotNull Recipient ...recipients);

	/**
	 * When provided, Simple Java Mail will ignore all recipients set on the email and instead send to the provided recipients only (when calling
	 * {@link jakarta.mail.Transport#sendMessage(Message, Address[])}). The actual Message will still contain the original recipients, but won't receive a
	 * copy. This is useful for testing purposes.
	 * Appends to the existing delivery-recipient overrides, retaining order, duplicate occurrences and supplied recipient metadata without reparsing.
	 * Repeated calls accumulate, including on a copied Email. Use {@link #clearOverrideReceivers()} before adding replacements;
	 * an empty list is rejected rather than clearing existing entries. The provided list's structure is not retained.
	 * @param recipients Nonempty list of prepared delivery recipients.
	 * @throws IllegalArgumentException If the list is null or empty.
	 */
	EmailPopulatingBuilder withOverrideReceivers(@NotNull List<Recipient> recipients);

	/**
	 * When an email is sent it is converted to a MimeMessage at which time the sent-date is filled with the current date. With this method
	 * this can be fixed to a date of choice.
	 * <p>
	 * <strong>Note:</strong> the <em>sent</em> date is user-controlled. Only when converting an email, Simple Java Mail might fill the sent-date.
	 *
	 * @param sentDate The date to use as sent date.
	 */
	EmailPopulatingBuilder fixingSentDate(@NotNull Date sentDate);

	/**
	 * Fixes the caller-controlled sent date from an {@link Instant}. Email storage remains millisecond-based, so sub-millisecond precision is discarded.
	 *
	 * @param sentDate The instant to use as sent date.
	 * @return This builder.
	 * @see #fixingSentDate(Date)
	 */
	@Cli.ExcludeApi(reason = "The Date overload already provides the same CLI value")
	EmailPopulatingBuilder fixingSentDate(@NotNull Instant sentDate);

	/**
	 * Resets <em>id</em> to empty.
	 */
	@SuppressWarnings("unused")
	EmailPopulatingBuilder clearId();

	/**
	 * Resets <em>fromRecipient</em> to empty.
	 */
	@SuppressWarnings("unused")
	EmailPopulatingBuilder clearFromRecipient();

	/**
	 * Resets <em>replyToRecipients</em> to empty.
	 * @see #withReplyTo(List)
	 */
	@SuppressWarnings("unused")
	EmailPopulatingBuilder clearReplyTo();

	/**
	 * Resets <em>bounceToRecipient</em> to empty.
	 */
	@SuppressWarnings("UnusedReturnValue")
	EmailPopulatingBuilder clearBounceTo();

	/**
	 * Resets <em>text</em> to empty.
	 */
	EmailPopulatingBuilder clearPlainText();

	/**
	 * Resets <em>textHTML</em> to empty.
	 */
	EmailPopulatingBuilder clearHTMLText();

	/**
	 * Resets <em>calendarText</em> to empty.
	 */
	EmailPopulatingBuilder clearCalendarText();

	/**
	 * Resets <em>subject</em> to empty.
	 */
	@SuppressWarnings("unused")
	EmailPopulatingBuilder clearSubject();

	/**
	 * Resets <em>recipients</em> to empty.
	 * @see #withRecipients(Collection)
	 */
	@SuppressWarnings("unused")
	EmailPopulatingBuilder clearRecipients();

	/**
	 * Resets <em>overrideReceivers</em> to empty.
	 *
	 * @see #withOverrideReceivers(List)
	 */
	EmailPopulatingBuilder clearOverrideReceivers();

	/**
	 * Resets <em>embeddedImages</em> to empty.
	 */
	@SuppressWarnings("unused")
	EmailPopulatingBuilder clearEmbeddedImages();

	/**
	 * Resets <em>attachments</em> to empty.
	 */
	@SuppressWarnings("unused")
	EmailPopulatingBuilder clearAttachments();

	/**
	 * Resets <em>headers</em> to empty.
	 */
	@SuppressWarnings("unused")
	EmailPopulatingBuilder clearHeaders();

	/**
	 * Resets all dkim properties to empty.
	 * <p>
	 * This clears only the current Email's choice, so governance can still supply signing defaults or overrides. To suppress those on a submitted email,
	 * use {@link org.simplejavamail.api.email.config.EmailTemplateOptions.Builder#dontApplyDefaultValueFor} and/or {@link org.simplejavamail.api.email.config.EmailTemplateOptions.Builder#dontApplyOverrideValueFor} with {@link EmailProperty#DKIM_SIGNING_CONFIG}.
	 * Clearing DKIM on a copied defaults template omits signing from that template without changing its other fields.
	 */
	@SuppressWarnings("unused")
	EmailPopulatingBuilder clearDkim();

	/**
	 * For signing and encrypting this email when sending, resets the email-level S/MIME signing and encryption properties to empty.
	 * <p>
	 * This does not clear S/MIME certificates already present on {@link Recipient} instances added to this email. To clear recipient-level certificate state
	 * while reusing recipients, pass those recipients through {@link IRecipientsBuilder#clearingSmimeCertificates()} before adding
	 * them to the email.
	 * Ordinary Email governance can supply the cleared signing or encryption choice again, and retained recipient certificates can still request
	 * encryption. This operation therefore does not disable S/MIME across those other scopes.
	 * <p>
	 * <strong>Note:</strong> this only works in combination with the {@value org.simplejavamail.internal.modules.SMIMEModule#NAME}.
	 *
	 * @see #signWithSmime(SmimeSigningConfig)
	 * @see #encryptWithSmime(SmimeEncryptionConfig)
	 * @see IRecipientsBuilder#clearingSmimeCertificates()
	 */
	@SuppressWarnings("unused")
	EmailPopulatingBuilder clearSmime();

	/**
	 * Removes only this email's S/MIME signing choice, retaining encryption and recipient certificates. Governance can supply signing again.
	 * @see #signWithSmime(SmimeSigningConfig)
	 */
	EmailPopulatingBuilder clearSmimeSigning();

	/**
	 * Removes only this email's S/MIME encryption choice, retaining signing and recipient certificates. Governance can supply encryption again.
	 * @see #encryptWithSmime(SmimeEncryptionConfig)
	 */
	EmailPopulatingBuilder clearSmimeEncryption();

	/**
	 * Removes this email's OpenPGP signing and encryption choices. Defaults or overrides can still supply them when the email is prepared.
	 * To prevent that, suppress {@link EmailProperty#OPENPGP_SIGNING_CONFIG} and {@link EmailProperty#OPENPGP_ENCRYPTION_CONFIG} instead.
	 *
	 * @see #signWithOpenPgp(OpenPgpSigningConfig)
	 * @see #encryptWithOpenPgp(OpenPgpEncryptionConfig)
	 */
	@SuppressWarnings("unused")
	EmailPopulatingBuilder clearOpenPgp();

	/**
	 * Removes only this email's OpenPGP signing choice, retaining encryption. Governance can supply signing again.
	 * @see #signWithOpenPgp(OpenPgpSigningConfig)
	 */
	EmailPopulatingBuilder clearOpenPgpSigning();

	/**
	 * Removes only this email's OpenPGP encryption choice, retaining signing. Governance can supply encryption again.
	 * @see #encryptWithOpenPgp(OpenPgpEncryptionConfig)
	 */
	EmailPopulatingBuilder clearOpenPgpEncryption();

	/**
	 * Clears the local read-receipt request and destination to their unset defaults. The request getter returns null and the destination list is empty afterwards.
	 * Normal Email defaults or overrides may request a receipt again during governance; this does not explicitly disable inherited requests.
	 * Use template suppression to exclude such contributions. No configuration sources are reloaded.
	 *
	 * @see #withDispositionNotificationTo()
	 * @see #withTemplateOptions(EmailTemplateOptions)
	 */
	@SuppressWarnings("unused")
	EmailPopulatingBuilder clearDispositionNotificationTo();

	/**
	 * Clears the local return-receipt request and destination to their unset defaults. The request getter returns null and the destination list is empty afterwards.
	 * Normal Email defaults or overrides may request a receipt again during governance; this does not explicitly disable inherited requests.
	 * Use template suppression to exclude such contributions. No configuration sources are reloaded.
	 *
	 * @see #withReturnReceiptTo()
	 * @see #withTemplateOptions(EmailTemplateOptions)
	 */
	@SuppressWarnings("unused")
	EmailPopulatingBuilder clearReturnReceiptTo();

	/**
	 * Clears the fixed <em>sent-date</em> so that the current date is used again at the time of sending.
	 */
	@SuppressWarnings("unused")
	EmailPopulatingBuilder clearSentDate();

	/**
	 * When reading and converting an email, this flag makes the behavior revert to the default merging
	 * behavior for single S/MIME signed attachments, which is that it <em>is</em> merged into the root message.
	 * <p>
	 * This can be useful when copying an {@link Email} that was not merged, to merge its signed content into the replacement.
	 * Use {@link #notMergingSingleSMIMESignedAttachment()} to keep the signed content separate instead.
	 * <p>
	 * <strong>Note:</strong> this only works in combination with the {@value org.simplejavamail.internal.modules.SMIMEModule#NAME}.
	 */
	EmailPopulatingBuilder clearSMIMESignedAttachmentMergingBehavior();

	/**
	 * @see #fixingMessageId(String)
	 */
	@Nullable
	String getId();

	/**
	 * @see #from(Recipient)
	 */
	@Nullable
	Recipient getFromRecipient();

	/**
	 * @see #withReplyTo(Recipient)
	 */
	@NotNull
	List<Recipient> getReplyToRecipients();

	/**
	 * @see #withBounceTo(Recipient)
	 */
	@Nullable
	Recipient getBounceToRecipient();

	/**
	 * @see #withPlainText(String)
	 */
	@Nullable
	String getPlainText();

	/**
	 * @see #withHTMLText(String)
	 */
	@Nullable
	String getHTMLText();

	/**
	 * @see #withCalendarText(CalendarMethod, String)
	 */
	@Nullable
	CalendarMethod getCalendarMethod();

	/**
	 * @see #withCalendarText(CalendarMethod, String)
	 */
	@Nullable
	String getCalendarText();

	/**
	 * @see #withSubject(String)
	 */
	@Nullable
	String getSubject();

	/**
	 * @see #withRecipients(Recipient...)
	 * @see #withRecipients(Collection)
	 */
	@NotNull
	List<Recipient> getRecipients();

	/**
	 * @see #withEmbeddedImage(String, DataSource)
	 */
	@NotNull
	List<AttachmentResource> getEmbeddedImages();

	/**
	 * @see #withAttachment(String, DataSource)
	 */
	@NotNull
	List<AttachmentResource> getAttachments();

	/**
	 * If the S/MIME library is loaded, this method returns a copy list of the attachments, but with any signed
	 * attachments replaced with decrypted ones.
	 * <p>
	 * This is a historical inspection list retained by conversion, ordinary copying and supported Java serialization. Defaults/overrides
	 * completion does not carry it into the result. Ordinary outgoing attachments remain governed independently.
	 * <p>
	 * <strong>Note:</strong> this only works in combination with the {@value org.simplejavamail.internal.modules.SMIMEModule#NAME}.
	 */
	@NotNull
	List<AttachmentResource> getDecryptedAttachments();

	/**
	 * @see #withHeader(String, Object)
	 * @see EmailStartingBuilder#replyingTo(MimeMessage, boolean, String)
	 */
	@NotNull
	Map<String, Collection<String>> getHeaders();

	/**
	 * @see #signWithDomainKey(DkimConfig)
	 * @see org.simplejavamail.api.email.config.DkimConfig.DkimConfigBuilder#dkimPrivateKeyData(byte[])
	 */
	@Nullable
	DkimConfig getDkimConfig();

	/**
	 * @see #withDispositionNotificationTo()
	 * @see #withDispositionNotificationTo(Recipient)
	 */
	@Nullable
	Boolean getUseDispositionNotificationTo();

	/**
	 * @see #withDispositionNotificationTo()
	 * @see #withDispositionNotificationTo(Recipient)
	 * @return Immutable destination snapshot; empty when absent or awaiting completion.
	 */
	@NotNull
	List<Recipient> getDispositionNotificationToRecipients();

	/**
	 * @see #withReturnReceiptTo()
	 * @see #withReturnReceiptTo(Recipient)
	 */
	@Nullable
	Boolean getUseReturnReceiptTo();

	/**
	 * @see #withReturnReceiptTo()
	 * @see #withReturnReceiptTo(Recipient)
	 * @return Immutable destination snapshot; empty when absent or awaiting completion.
	 */
	@NotNull
	List<Recipient> getReturnReceiptToRecipients();

	/**
	 * @see #withOverrideReceivers(Recipient...)
	 */
	@NotNull
	List<Recipient> getOverrideReceivers();

	/**
	 * @see EmailStartingBuilder#forwarding(MimeMessage)
	 */
	@Nullable
	MimeMessage getEmailToForward();

	/**
	 * If this Email instance was the result of a conversion in which the source message was S/MIME signed / encrypted,
	 * this field will be filled for historical purpose.
	 * <p>
	 * For example, you can use it to determine if the message was encrypted or signed and also who did the signing.
	 * <p>
	 * These results describe the converted input. Defaults/overrides completion does not copy them or their original protected bytes;
	 * it resolves the outgoing settings separately. Ordinary copying and supported Java serialization retain the source history.
	 * <p>
	 * Basic MIME recognition is available without the {@value org.simplejavamail.internal.modules.SMIMEModule#NAME}.
	 * Protected input then exposes unknown verification and decryption results rather than claiming plain content.
	 * Cryptographic inspection requires the module; see {@link OriginalSmimeDetails} for the result states.
	 */
	@NotNull
	OriginalSmimeDetails getOriginalSmimeDetails();

	/**
	 * Returns historical facts from a received delivery notification. Defaults/overrides completion omits this report and its nested facts;
	 * outgoing delivery-notification requests are separate configuration.
	 * @see Email#getDeliveryStatusReport()
	 */
	@Nullable
	DeliveryStatusReport getDeliveryStatusReport();

	/**
	 * If the Email instance currently being built was the result of a conversion in which the source message was S/MIME
	 * signed / encrypted, this field can contain the retained parsed Email for its signed content.
	 * <p>
	 * By default, this message is merged into the parent email, as it is actually the same message (this behavior can be
	 * turned off with {@link #notMergingSingleSMIMESignedAttachment()}).
	 * <p>
	 * This retained view and its merge bookkeeping are conversion history. Ordinary copying and supported Java serialization preserve
	 * them; defaults/overrides completion does not. Content already merged into ordinary body, header and resource fields remains outgoing content.
	 * <p>
	 * <strong>Note:</strong> this only works in combination with the {@value org.simplejavamail.internal.modules.SMIMEModule#NAME}.
	 */
	@Nullable
	Email getSmimeSignedEmail();

	/**
	 * @see #notMergingSingleSMIMESignedAttachment()
	 */
	boolean isMergeSingleSMIMESignedAttachment();

	/**
	 * @see EmailPopulatingBuilder#signWithSmime(SmimeSigningConfig)
	 * @see org.simplejavamail.api.email.config.SmimeSigningConfig.SmimeSigningConfigBuilder#pkcs12Config(File, String, String, String)
	 */
	@Nullable
	SmimeSigningConfig getSmimeSigningConfig();

	/**
	 * @see EmailPopulatingBuilder#encryptWithSmime(SmimeEncryptionConfig)
	 * @see org.simplejavamail.api.email.config.SmimeEncryptionConfig.SmimeEncryptionConfigBuilder#x509Certificate(File)
	 */
	@Nullable
	SmimeEncryptionConfig getSmimeEncryptionConfig();

	/** @see #signWithOpenPgp(OpenPgpSigningConfig) */
	@Nullable
	OpenPgpSigningConfig getOpenPgpSigningConfig();

	/** @see #encryptWithOpenPgp(OpenPgpEncryptionConfig) */
	@Nullable
	OpenPgpEncryptionConfig getOpenPgpEncryptionConfig();

	/**
	 * Returns historical OpenPGP inspection results for the converted input. Defaults/overrides completion does not copy these results
	 * or their original protected bytes; outgoing OpenPGP configuration is resolved separately. Ordinary copying and supported Java
	 * serialization retain the source history.
	 */
	@NotNull
	OriginalOpenPgpDetails getOriginalOpenPgpDetails();

	/**
	 * @see EmailPopulatingBuilder#fixingSentDate(Date)
	 */
	@Nullable
	Date getSentDate();

	/**
	 * Returns the fixed sent date as an {@link Instant}, or {@code null} when no date was configured.
	 *
	 * @see #getSentDate()
	 * @see #fixingSentDate(Instant)
	 */
	@Nullable
	Instant getSentDateAsInstant();
}
