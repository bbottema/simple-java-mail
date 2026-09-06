package org.simplejavamail.api.email;

import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.internal.clisupport.model.Cli;
import org.simplejavamail.api.internal.clisupport.model.CliBuilderApiType;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static java.util.regex.Pattern.compile;

/**
 * Entry Builder API for starting new emails.
 * <p>
 * Obtain this starting builder from {@code SimpleJavaMail.emailBuilder()}. Every builder created from it retains that factory's immutable configuration
 * snapshot.
 */
@Cli.BuilderApiNode(builderApiType = CliBuilderApiType.EMAIL)
public interface EmailStartingBuilder {
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
	 * Use the ordinary EML conversion APIs instead when the message needs to be edited, completed with defaults, or cryptographically processed by Simple
	 * Java Mail.
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
	 * Delegates to {@link #replyingTo(MimeMessage, boolean, String)} with replyToAll set to <code>false</code> and a default HTML quoting
	 * template.
	 */
	EmailPopulatingBuilder replyingTo(@NotNull Email email);
	
	/**
	 * Delegates to {@link #replyingTo(MimeMessage, boolean, String)} with replyToAll set to <code>true</code> and a default HTML quoting
	 * template.
	 */
	EmailPopulatingBuilder replyingToAll(@NotNull Email email);
	
	/**
	 * Delegates to {@link #replyingTo(MimeMessage, boolean, String)} with replyToAll set to <code>true</code>.
	 *
	 * @see #DEFAULT_QUOTING_MARKUP
	 */
	EmailPopulatingBuilder replyingToAll(@NotNull Email email, @NotNull String customQuotingTemplate);
	
	/**
	 * Delegates to {@link #replyingTo(MimeMessage, boolean, String)} with replyToAll set to <code>false</code>.
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
	
	/**
	 * Primes the email with subject, quoted content, headers, originally embedded images and recipients needed for a valid RFC reply.
	 * <p>
	 * <strong>Note 1:</strong> replaces subject with "Re: &lt;original subject&gt;" (but never nested).<br>
	 * <strong>Note 2:</strong> always sets both plain text and HTML text, so if you update the content body, be sure to update HTML as well.<br>
	 * <strong>Note 3:</strong> sets body content: text is replaced with {@code "> text"} and HTML is replaced with the provided (or default) quoting markup
	 * (add your own content with {@link EmailPopulatingBuilder#prependText(String)} and {@link EmailPopulatingBuilder#prependTextHTML(String)}).
	 *
	 * @param emailMessage The message from which we harvest recipients, original content to quote (including embedded images), message ID to
	 *                     include.
	 * @param repyToAll    Indicates whether all original receivers should be included in this new reply. Also see {@link
	 *                     MimeMessage#reply(boolean)}.
	 * @param htmlTemplate HTML quoting template that should be used in the reply. Should contain the substring {@code "%s"}. Be advised that HTML is very limited in emails.
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
	 * Delegates to {@link #forwarding(MimeMessage)} with the provided {@link Email} converted to {@link MimeMessage}.
	 */
	EmailPopulatingBuilder forwarding(@NotNull Email email);
	
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
	 */
	EmailPopulatingBuilder forwarding(@NotNull MimeMessage message);
	
	/**
	 * Delegates to {@link #copying(Email)}, by converting the provided message first.
	 *
	 * @param message The MimeMessage email to convert and copy to new {@link Email}.
	 */
	EmailPopulatingBuilder copying(@NotNull MimeMessage message);
	
	/**
	 * Delegates to {@link #copying(Email)}, by building the email first.
	 *
	 * @see EmailPopulatingBuilder#buildEmail()
	 */
	EmailPopulatingBuilder copying(@NotNull EmailPopulatingBuilder emailBuilder);
	
	/**
	 * Preconfigures the builder with all the properties from the given email that are non-null.
	 */
	EmailPopulatingBuilder copying(@NotNull Email email);
}
