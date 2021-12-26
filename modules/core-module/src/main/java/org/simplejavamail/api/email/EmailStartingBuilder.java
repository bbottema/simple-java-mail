package org.simplejavamail.api.email;

import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.internal.clisupport.model.Cli;
import org.simplejavamail.api.internal.clisupport.model.CliBuilderApiType;

import java.util.regex.Pattern;

import static java.util.regex.Pattern.compile;

/**
 * Entry Builder API for starting starting new emails.
 */
@Cli.BuilderApiNode(builderApiType = CliBuilderApiType.EMAIL)
public interface EmailStartingBuilder {
	/**
	 * Used for replying to emails, when quoting the original email. Matches the beginning of every line.
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
	 * Configures this builder to create an email ignoring the normal (optional) defaults that apply from property config files.
	 */
	EmailStartingBuilder ignoringDefaults();
	
	/**
	 * Most common use case for creating a new email. Starts with an empty email, populated with defaults when set through config properties (if
	 * not disabled using {@link EmailStartingBuilder#ignoringDefaults()}.
	 * <p>
	 * <strong>Note:</strong> Any builder method called after this will override the default value.
	 *
	 * @return A new {@link EmailStartingBuilder} to further populate the email with.
	 */
	EmailPopulatingBuilder startingBlank();
	
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
	 * @param htmlTemplate HTML quoting template that should be used in the reply. Should contains the substring {@code "%s"}. Be advised that HTML is very limited in emails.
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
