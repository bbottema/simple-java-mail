package org.simplejavamail.api.email;

import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.config.OpenPgpEncryptionConfig;
import org.simplejavamail.api.email.config.OpenPgpSigningConfig;
import org.simplejavamail.api.internal.clisupport.model.Cli;
import org.simplejavamail.api.internal.clisupport.model.CliBuilderApiType;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static java.util.regex.Pattern.compile;

/**
 * Choose how to start an Email: begin with an empty message, copy an existing Email, or prepare a reply or forward.
 * Obtain this starting builder with {@code SimpleJavaMail.fromDefaults().emailBuilder()}.
 * <p>
 * If you have a configured SimpleJavaMail factory, call its {@code emailBuilder()} instead. Every builder created from that starting point retains
 * the factory's immutable configuration snapshot.
 */
@Cli.BuilderApiNode(builderApiType = CliBuilderApiType.EMAIL)
public interface EmailStartingBuilder {
	/**
	 * Hash used for OpenPGP signatures unless another supported algorithm is selected.
	 * @see OpenPgpSigningConfig.OpenPgpSigningConfigBuilder#hashAlgorithm(OpenPgpSigningConfig.HashAlgorithm)
	 */
	OpenPgpSigningConfig.HashAlgorithm DEFAULT_OPENPGP_SIGNING_HASH_ALGORITHM = OpenPgpSigningConfig.HashAlgorithm.SHA256;

	/**
	 * Cipher used for OpenPGP message encryption unless another supported algorithm is selected.
	 * @see OpenPgpEncryptionConfig.OpenPgpEncryptionConfigBuilder#symmetricAlgorithm(OpenPgpEncryptionConfig.SymmetricAlgorithm)
	 */
	OpenPgpEncryptionConfig.SymmetricAlgorithm DEFAULT_OPENPGP_ENCRYPTION_SYMMETRIC_ALGORITHM = OpenPgpEncryptionConfig.SymmetricAlgorithm.AES_256;

	/**
	 * Whether conversion to Email eagerly reads attachment content. Disabling this retains lazy data sources for later reading.
	 * @see org.simplejavamail.api.converter.ConfiguredEmailConverter.IncomingConversion#withFetchAttachmentData(boolean)
	 */
	boolean DEFAULT_FETCH_ATTACHMENT_DATA = true;

	/** Default permission for helper reuse of confidential content: protection is required.
	 * @see ReplyOptions.Builder#withUnencryptedContentAllowed(boolean)
	 * @see ForwardOptions.Builder#withUnencryptedContentAllowed(boolean)
	 */
	boolean DEFAULT_ALLOW_UNENCRYPTED_CONTENT = false;

	/**
	 * Used for replying to email, when quoting the original email. Matches the beginning of every line.
	 * <p>
	 * <strong>Pattern used</strong>: {@code "(?m)^"}
	 *
	 * @see #replyingTo(MimeMessage, boolean, String)
	 */
	Pattern LINE_START_PATTERN = compile("(?m)^");
	
	/**
	 * Default simple quoting markup for email replies:
	 * <p>
	 * <code>{@value}</code>
	 *
	 * @see #replyingTo(MimeMessage, boolean, String)
	 */
	String DEFAULT_QUOTING_MARKUP = "<blockquote style=\"color: gray; border-left: 1px solid #4f4f4f; padding-left: " +
			"1cm\">%s</blockquote>";

	/**
	 * Most common use case for creating a new email. Starts with an empty email; no defaults or overrides are applied while building it.
	 * <p>
	 * Defaults and overrides remain eligible for application later, when a Mailer prepares the email for sending or when completion is requested
	 * explicitly. Configure that later behavior on the returned {@link EmailPopulatingBuilder}.
	 *
	 * @return A new {@link EmailPopulatingBuilder} to populate and configure.
	 */
	EmailPopulatingBuilder startingBlank();

	/**
	 * Starts an advanced, exact email from an already finalized EML representation. Use this when even a semantically equivalent MIME rebuild would be
	 * incorrect, such as when relaying archived mail or submitting output that is already protected by DKIM, S/MIME, or OpenPGP/MIME.
	 * <p>
	 * The byte array is copied immediately and remains authoritative when the returned {@link Email} is converted, rehearsed, or sent. Exact EML must be
	 * non-empty, parseable by Jakarta Mail, use canonical CRLF line endings, and end in CRLF. SMTP-envelope recipients must be supplied separately on the
	 * returned builder.
	 * <p>
	 * Building automatically populates S/MIME and OpenPGP inspection facts using available incoming-message support, without changing the source bytes.
	 * It does not select receive keys. Unestablished outcomes remain UNKNOWN; established missing-key and failure results retain their own statuses.
	 * Missing optional inspection modules do not by themselves prevent exact submission, and later getters are passive.
	 * <p>
	 * Use ordinary EML conversion when the message needs to be edited, completed with defaults, opened with explicitly supplied receive keys, or newly
	 * signed or encrypted. Exact inspection does not replace the raw parsed body, headers or attachments with an opened content view.
	 *
	 * @param emlBytes Complete EML bytes to preserve exactly. In the CLI, this argument is the path of the EML file to read.
	 * @return A constrained builder for the explicit SMTP envelope of the exact message.
	 */
	ExactEmailBuilder startingFromExactEml(@Cli.BinaryFile byte @NotNull [] emlBytes);

	/**
	 * Starts an exact email with the same semantics as {@link #startingFromExactEml(byte[])}, consuming the stream immediately without closing it.
	 * <p>
	 * The CLI exposes the byte-array overload and reads its argument from a file, avoiding ambiguous stream ownership.
	 *
	 * @param emlInputStream Stream containing the complete EML representation. The stream is consumed immediately but is not closed.
	 * @return A constrained builder for the explicit SMTP envelope of the exact message.
	 */
	@Cli.ExcludeApi(reason = "The byte array overload provides CLI file conversion without transferring stream ownership")
	ExactEmailBuilder startingFromExactEml(@NotNull InputStream emlInputStream);

	/**
	 * Starts an exact email with the same semantics as {@link #startingFromExactEml(byte[])}, reading the complete EML from the supplied path.
	 * Any stream opened for the path is closed before this method returns.
	 * <p>
	 * The CLI exposes the byte-array overload and already interprets its argument as a file path.
	 *
	 * @param emlPath Path to the complete EML representation.
	 * @return A constrained builder for the explicit SMTP envelope of the exact message.
	 */
	@Cli.ExcludeApi(reason = "The byte array overload already provides the same CLI file conversion")
	ExactEmailBuilder startingFromExactEml(@NotNull Path emlPath);
	
	/**
	 * Primes a reply to the selected sender from the Email's readable content, using the default HTML quoting template and confidentiality safeguard.
	 * Outgoing protection is not applied while obtaining the quote. The new reply needs its own outgoing protection choices when confidential
	 * content is automatically included; it does not inherit the source's keys. Exact input retains its authoritative receive-side interpretation.
	 *
	 * @see #replyingTo(Email, ReplyOptions)
	 */
	EmailPopulatingBuilder replyingTo(@NotNull Email email);

	/**
	 * Prepares a reply from the selected logical message, with the supplied quote and disclosure choices.
	 * Quoted content remains readable/editable in the draft. Automatically reused content recovered from encryption requires suitable
	 * outgoing encryption at finalized MIME/EML or sending, unless these options deliberately allow unencrypted output.
	 * Permission never disables configured encryption. The new Subject uses a known public counterpart or a neutral subject when the
	 * selected subject is confidential. Copies preserve this draft's choices; a new reply uses its own options.
	 * Private received threading identifiers are not automatically exposed in the new outer headers; changing the current Message-ID
	 * does not make retained private ancestry public. Explicitly replaced or cleared threading lists follow their new supplied choices.
	 * If a recognized protected authored header view could not be read, automatic destinations and ancestry remain unresolved.
	 * Readable content and a safe subject remain available; supply the reply's recipients explicitly.
	 * @see ReplyOptions.Builder#withQuotingTemplate(String)
	 * @see ReplyOptions.Builder#withUnencryptedContentAllowed(boolean)
	 * @see EmailPopulatingBuilder#clearPlainText()
	 * @see EmailPopulatingBuilder#clearHTMLText()
	 */
	@Cli.ExcludeApi(reason = "Operation options are supplied by the internal scalar CLI adapter")
	EmailPopulatingBuilder replyingTo(@NotNull Email email, @NotNull ReplyOptions options);
	
	/**
	 * Primes a reply-all from the composed Email's readable content, with the protection boundary of {@link #replyingTo(Email)}
	 * and the default HTML quoting template.
	 */
	EmailPopulatingBuilder replyingToAll(@NotNull Email email);

	/** Reply-all with the logical destinations and disclosure boundary of {@link #replyingTo(Email, ReplyOptions)}.
	 * Selected To/Cc recipients follow the ordinary reply-all rules; Bcc recipients are not automatically added.
	 */
	@Cli.ExcludeApi(reason = "Operation options are supplied by the internal scalar CLI adapter")
	EmailPopulatingBuilder replyingToAll(@NotNull Email email, @NotNull ReplyOptions options);
	
	/**
	 * Primes a reply-all with a custom HTML quote, using the readable-content and protection boundary of {@link #replyingTo(Email)}.
	 *
	 * @see #DEFAULT_QUOTING_MARKUP
	 */
	EmailPopulatingBuilder replyingToAll(@NotNull Email email, @NotNull String customQuotingTemplate);
	
	/**
	 * Primes a reply to the sender with a custom HTML quote, using the readable-content and protection boundary of {@link #replyingTo(Email)}.
	 */
	EmailPopulatingBuilder replyingTo(@NotNull Email email, @NotNull String customQuotingTemplate);
	
	/**
	 * Delegates to {@link #replyingTo(MimeMessage, boolean, String)} with replyToAll set to <code>false</code> and a default HTML quoting
	 * template.
	 *
	 * @param message MimeMessage to reply to with new email.
	 */
	@Cli.OptionNameOverride("replyingToSenderWithDefaultQuoteMarkup")
	EmailPopulatingBuilder replyingTo(@NotNull MimeMessage message);

	/** Converts the supplied message before deriving a reply using the logical view and options described by {@link #replyingTo(Email, ReplyOptions)}. */
	@Cli.ExcludeApi(reason = "Operation options are supplied by the internal scalar CLI adapter")
	EmailPopulatingBuilder replyingTo(@NotNull MimeMessage message, @NotNull ReplyOptions options);
	
	/**
	 * Delegates to {@link #replyingTo(MimeMessage, boolean, String)} with replyToAll set to <code>false</code>.
	 *
	 * @param message MimeMessage to reply to with new email.
	 * @param customQuotingTemplate HTML quoting template that should be used in the reply. Should include the substring {@code "%s"},
	 *                                    or else the original email is not embedded in the reply.
	 */
	@Cli.OptionNameOverride("replyingToSender")
	EmailPopulatingBuilder replyingTo(@NotNull MimeMessage message, @NotNull String customQuotingTemplate);
	
	/**
	 * Delegates to {@link #replyingTo(MimeMessage, boolean, String)} with replyToAll set to <code>true</code>.
	 *
	 * @param message The email to include as replied-to-email and who's receivers all will receive the new reply email.
	 * @param customQuotingTemplate HTML quoting template that should be used in the reply. Should include the substring {@code "%s"},
	 *                                    or else the original email is not embedded in the reply.
	 *
	 * @see #DEFAULT_QUOTING_MARKUP
	 */
	EmailPopulatingBuilder replyingToAll(@NotNull MimeMessage message, @NotNull String customQuotingTemplate);
	
	/**
	 * Delegates to {@link #replyingTo(MimeMessage, boolean, String)} with replyToAll set to <code>true</code> and a default HTML quoting
	 * template.
	 *
	 * @param message The email to include as replied-to-email and who's receivers all will receive the new reply email.
	 *
	 * @see #DEFAULT_QUOTING_MARKUP
	 */
	@Cli.OptionNameOverride("replyingToAllWithDefaultQuoteMarkup")
	EmailPopulatingBuilder replyingToAll(@NotNull MimeMessage message);

	/** Converts the supplied message before deriving reply-all using the logical view and options described by {@link #replyingTo(Email, ReplyOptions)}. */
	@Cli.ExcludeApi(reason = "Operation options are supplied by the internal scalar CLI adapter")
	EmailPopulatingBuilder replyingToAll(@NotNull MimeMessage message, @NotNull ReplyOptions options);
	
	/**
	 * Primes the email with subject, quoted content, headers, originally embedded images and recipients needed for a valid RFC reply.
	 * <p>
	 * <strong>Note 1:</strong> replaces subject with "Re: &lt;original subject&gt;" (but never nested).<br>
	 * <strong>Note 2:</strong> sets both plain text and HTML text. A missing alternative is generated once while priming the reply:
	 * plain text is safely escaped into HTML with line breaks, and HTML becomes readable plain text with paragraph/list breaks,
	 * link destinations and image alternative text. Script and style content is omitted from generated plain text; no network resources are fetched.
	 * Existing alternatives retain their independent content. Later body changes remain independent, so update both when adding your answer.<br>
	 * <strong>Note 3:</strong> sets body content: text is replaced with {@code "> text"} and HTML is replaced with the provided (or default) quoting markup
	 * (add your own content with {@link EmailPopulatingBuilder#prependPlainText(String)} and {@link EmailPopulatingBuilder#prependHTMLText(String)}).
	 *
	 * @param emailMessage The message from which we harvest recipients, original content to quote (including embedded images), message ID to
	 *                     include.
	 * @param repyToAll    Indicates whether all original receivers should be included in this new reply. Also see {@link
	 *                     MimeMessage#reply(boolean)}.
	 * @param htmlTemplate HTML quoting template applied once to the original or generated HTML. Should contain the substring {@code "%s"}.
	 *                     Be advised that HTML is very limited in emails.
	 *
	 * @see #replyingTo(Email)
	 * @see #replyingTo(Email, String)
	 * @see #replyingTo(MimeMessage)
	 * @see #replyingTo(MimeMessage, String)
	 * @see #replyingToAll(Email)
	 * @see #replyingToAll(Email, String)
	 * @see #replyingToAll(MimeMessage)
	 * @see #replyingToAll(MimeMessage, String)
	 * @see <a href="https://javaee.github.io/javamail/FAQ#reply">Official JavaMail FAQ on replying</a>
	 * @see MimeMessage#reply(boolean)
	 */
	EmailPopulatingBuilder replyingTo(@NotNull MimeMessage emailMessage, boolean repyToAll, @NotNull String htmlTemplate);
	
	/**
	 * Constructs a new outer forward carrying the Email's current interpreted content, including edits and the ordinary selected
	 * attachment payloads. This does not preserve input bytes or existing signature validity. Automatically included confidential content
	 * requires suitable outgoing encryption at finalized output; use {@link #forwarding(Email, ForwardOptions)} for deliberate disclosure.
	 * Internal reconstruction does not require inner encryption when suitable encryption will cover the completed outer message.
	 */
	EmailPopulatingBuilder forwarding(@NotNull Email email);

	/** Interpreted forwarding with deliberate operation options.
	 * @see #forwarding(Email)
	 * @see ForwardOptions.Builder#withUnencryptedContentAllowed(boolean)
	 */
	@Cli.ExcludeApi(reason = "Operation options are supplied by the internal scalar CLI adapter")
	EmailPopulatingBuilder forwarding(@NotNull Email email, @NotNull ForwardOptions options);

	/**
	 * Constructs a new outer forward with the complete retained original message as an attachment, preserving its captured format
	 * (including EML versus native MSG), payload and protection. Edits to the represented Email do not change this historical payload.
	 * EML originals use an encoded binary file carrier with their EML filename, so SMTP line handling cannot change the retained bytes.
	 * Its carrier media type can therefore be {@code application/octet-stream}; the attached file remains the captured EML.
	 * The outer subject uses a known public counterpart or a neutral subject when needed. Forwarding an encrypted original does not
	 * grant a new recipient access. An intact encrypted original can be carried in an otherwise public outer message, but an original
	 * extracted through a parent's encryption can itself contain confidential plaintext (including child-message headers). Such reused
	 * original content still requires suitable outgoing encryption or explicit conversion-local disclosure permission. Signing alone
	 * does not supply confidentiality. Copies/edits retain this original-input obligation; explicitly removing the attachment removes it.
	 * For known confidential reuse, new carrier filename/description/Content-ID metadata is neutralized while retained original bytes,
	 * format and inspection metadata stay unchanged. The new sender and recipients remain
	 * caller/governance choices; this is not exact SMTP relay.
	 * @throws IllegalStateException If complete appropriate original input was not retained; no reconstructed fallback is substituted.
	 * @see EmailInspection#getOriginalMessage()
	 * @see org.simplejavamail.api.converter.ConfiguredEmailConverter.EmailConversion#allowUnencryptedContent()
	 */
	@Cli.ExcludeApi(reason = "Requires an Email with captured original input rather than a caller-supplied MIME object")
	EmailPopulatingBuilder forwardingOriginal(@NotNull Email email);
	
	/**
	 * Primes the email to be build with proper subject and include the forwarded email as "message/rfc822" bodypart (valid RFC forward).
	 * <p>
	 * <strong>Note 1</strong>: replaces subject with "Fwd: &lt;original subject&gt;" (nesting enabled).<br>
	 * <strong>Note 2</strong>: {@code Content-Disposition} will be left empty so the receiving email client can decide how to handle display
	 * (most will show inline, some will show as attachment instead).
	 *
	 * @param message The message to be included in the new forwarding email.
	 *
	 * @see <a href="https://javaee.github.io/javamail/FAQ#forward">Official JavaMail FAQ on forwarding</a>
	 * @see <a href="https://blogs.technet.microsoft.com/exchange/2011/04/21/mixed-ing-it-up-multipartmixed-messages-and-you/">More reading
	 * material</a>
	 * @see #forwarding(Email)
	 * @see EmailPopulatingBuilder#clearEmailToForward()
	 */
	EmailPopulatingBuilder forwarding(@NotNull MimeMessage message);

	/** Caller-supplied MIME forwarding with deliberate disclosure choices; no original-wire-byte claim is made.
	 * @see #forwarding(MimeMessage)
	 * @see #forwarding(Email, ForwardOptions)
	 */
	@Cli.ExcludeApi(reason = "Operation options are supplied by the internal scalar CLI adapter")
	EmailPopulatingBuilder forwarding(@NotNull MimeMessage message, @NotNull ForwardOptions options);
	
	/**
	 * Delegates to {@link #copying(Email)}, by converting the provided message first.
	 * The resulting builder already contains the converted collections. Later additive calls retain those entries unless cleared;
	 * for example, {@code clearReplyTo().withReplyTo("support@example.org")} replaces the local reply destinations.
	 * Editing the copy does not modify the supplied MimeMessage. This starts editable composition, not preservation of its original MIME bytes
	 * or a guarantee that any already applied signature remains valid; see {@link #copying(Email)}.
	 *
	 * @param message The MimeMessage email to convert and copy to new {@link Email}.
	 */
	EmailPopulatingBuilder copying(@NotNull MimeMessage message);
	
	/**
	 * Delegates to {@link #copying(Email)}, by building the email first.
	 * Copied collections are already populated: later additive calls append unless cleared. Editing this copy does not change the source builder
	 * or an Email already built from it. This starts editable composition with the byte/signature caveats of {@link #copying(Email)}.
	 *
	 * @see EmailPopulatingBuilder#buildEmail()
	 */
	EmailPopulatingBuilder copying(@NotNull EmailPopulatingBuilder emailBuilder);
	
	/**
	 * Preconfigures the builder with all the properties from the given email that are non-null.
	 * This includes its choices to ignore defaults or overrides and its per-property suppression settings.
	 * Visible recipients and delivery-recipient overrides remain separate; the copy retains their order, repeated recipients and recipient options.
	 * Collections, including Reply-To, are already populated. Later additive calls such as {@code to}, {@code withRecipients}, {@code withReplyTo}
	 * and resource additions retain existing entries unless explicitly cleared; an empty additive Reply-To/recipient collection does nothing.
	 * For example, {@code mail.emailBuilder().copying(original).clearReplyTo().withReplyTo("support@example.org")} replaces the local reply
	 * destinations. Editing the copy leaves the original Email unchanged; ordinary defaults, overrides and mandatory locks still apply later.
	 * <p>
	 * Attachment content remains borrowed. Copying an exact Email starts editable composition rather than preserving its authoritative MIME bytes.
	 * Neither original byte identity nor the validity of already applied signatures is guaranteed. Copying retains their received evidence;
	 * reconstructed MIME omits obsolete delivery/authentication signature headers and uses separately configured outgoing protection.
	 * Reuse the original exact Email for unchanged resubmission. To construct a new exact envelope, use
	 * {@link #startingFromExactEml(byte[])} with the authoritative finalized input and supply explicit envelope recipients on the exact builder.
	 * Unchanged selected fields retain their incoming inspection associations; an explicit setter replaces that field's current evidence,
	 * even when the supplied value compares equal. Complete retained originals remain historical input after edits. Helper-derived
	 * confidentiality requirements and deliberate draft permission survive copying independently from incoming protection facts.
	 *
	 * @see EmailPopulatingBuilder#clearOverrideReceivers()
	 * @throws IllegalArgumentException If the Email was constructed directly or through an unsupported application subclass instead of the library's builder.
	 */
	EmailPopulatingBuilder copying(@NotNull Email email);
}
