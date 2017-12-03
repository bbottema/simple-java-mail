package org.simplejavamail.email;

import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.converter.internal.mimemessage.MimeMessageParser;

import javax.annotation.Nonnull;
import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import java.util.regex.Pattern;

import static java.lang.String.format;
import static java.util.regex.Pattern.compile;
import static org.simplejavamail.internal.util.MiscUtil.defaultTo;

public class EmailBuilder {
	public static EmailBuilderInstance ignoringDefaults() {
		return new EmailBuilderInstance().ignoringDefaults();
	}
	
	public static EmailPopulatingBuilder replyingTo(@Nonnull final Email email) {
		return new EmailBuilderInstance().replyingTo(email);
	}
	
	public static EmailPopulatingBuilder replyingToAll(@Nonnull final Email email) {
		return new EmailBuilderInstance().replyingToAll(email);
	}
	
	public static EmailPopulatingBuilder replyingToAll(@Nonnull final Email email, @Nonnull final String customQuotingTemplate) {
		return new EmailBuilderInstance().replyingToAll(email, customQuotingTemplate);
	}
	
	public static EmailPopulatingBuilder replyingTo(@Nonnull final Email email, @Nonnull final String customQuotingTemplate) {
		return new EmailBuilderInstance().replyingTo(email, customQuotingTemplate);
	}
	
	public static EmailPopulatingBuilder replyingTo(@Nonnull final MimeMessage email) {
		return new EmailBuilderInstance().replyingTo(email);
	}
	
	public static EmailPopulatingBuilder replyingToAll(@Nonnull final MimeMessage email, @Nonnull final String customQuotingTemplate) {
		return new EmailBuilderInstance().replyingToAll(email, customQuotingTemplate);
	}
	
	public static EmailPopulatingBuilder replyingTo(@Nonnull final MimeMessage email, @Nonnull final String customQuotingTemplate) {
		return new EmailBuilderInstance().replyingTo(email, customQuotingTemplate);
	}
	
	public static EmailPopulatingBuilder replyingToAll(@Nonnull final MimeMessage email) {
		return new EmailBuilderInstance().replyingToAll(email);
	}
	
	public static EmailPopulatingBuilder replyingTo(@Nonnull final MimeMessage emailMessage, final boolean repyToAll, @Nonnull final String htmlTemplate) {
		return new EmailBuilderInstance().replyingTo(emailMessage, repyToAll, htmlTemplate);
	}
	
	public static EmailPopulatingBuilder forwarding(@Nonnull final Email email) {
		return new EmailBuilderInstance().forwarding(email);
	}
	
	public static EmailPopulatingBuilder forwarding(@Nonnull final MimeMessage emailMessage) {
		return new EmailBuilderInstance().forwarding(emailMessage);
	}
	
	public static EmailPopulatingBuilder copying() {
		return new EmailBuilderInstance().copying();
	}
	
	public static EmailPopulatingBuilder startingBlank() {
		return new EmailBuilderInstance().startingBlank();
	}
	
	/**
	 * {@link EmailBuilder#ignoringDefaults()} needs to return the same interface, so we need all methods on both class (static) and instance. Since a
	 * class cannot have a static method and an instance method of the same name, {@link EmailBuilderInstance} provides the instance interface..
	 */
	public static final class EmailBuilderInstance {
		
		/**
		 * Used for replying to emails, when quoting the original email. Matches the beginning of every line.
		 * <p>
		 * <strong>Pattern used</strong>: {@code "(?m)^"}
		 *
		 * @see #replyingTo(MimeMessage, boolean, String)
		 */
		static final Pattern LINE_START_PATTERN = compile("(?m)^");
		
		/**
		 * Default simple quoting markup for email replies.
		 * <p>
		 * <code>{@value DEFAULT_QUOTING_MARKUP}</code>
		 *
		 * @see #replyingTo(MimeMessage, boolean, String)
		 */
		static final String DEFAULT_QUOTING_MARKUP = "<blockquote style=\"color: gray; border-left: 1px solid #4f4f4f; padding-left: " +
				"1cm\">%s</blockquote>";
		
		private boolean applyDefaults = true;
		
		public EmailBuilderInstance ignoringDefaults() {
			applyDefaults = false;
			return this;
		}
		
		/**
		 * Delegates to {@link #replyingTo(MimeMessage, boolean, String)} with replyToAll set to <code>false</code> and a default HTML quoting
		 * template.
		 */
		EmailPopulatingBuilder replyingTo(@Nonnull final Email email) {
			return replyingTo(EmailConverter.emailToMimeMessage(email), false, DEFAULT_QUOTING_MARKUP);
		}
		
		/**
		 * Delegates to {@link #replyingTo(MimeMessage, boolean, String)} with replyToAll set to <code>true</code> and a default HTML quoting
		 * template.
		 */
		EmailPopulatingBuilder replyingToAll(@Nonnull final Email email) {
			return replyingTo(EmailConverter.emailToMimeMessage(email), true, DEFAULT_QUOTING_MARKUP);
		}
		
		/**
		 * Delegates to {@link #replyingTo(MimeMessage, boolean, String)} with replyToAll set to <code>true</code>.
		 *
		 * @see #DEFAULT_QUOTING_MARKUP
		 */
		EmailPopulatingBuilder replyingToAll(@Nonnull final Email email, @Nonnull final String customQuotingTemplate) {
			return replyingTo(EmailConverter.emailToMimeMessage(email), true, customQuotingTemplate);
		}
		
		/**
		 * Delegates to {@link #replyingTo(MimeMessage, boolean, String)} with replyToAll set to <code>false</code>.
		 */
		EmailPopulatingBuilder replyingTo(@Nonnull final Email email, @Nonnull final String customQuotingTemplate) {
			return replyingTo(EmailConverter.emailToMimeMessage(email), false, customQuotingTemplate);
		}
		
		/**
		 * Delegates to {@link #replyingTo(MimeMessage, boolean, String)} with replyToAll set to <code>false</code> and a default HTML quoting
		 * template.
		 */
		EmailPopulatingBuilder replyingTo(@Nonnull final MimeMessage email) {
			return replyingTo(email, false, DEFAULT_QUOTING_MARKUP);
		}
		
		/**
		 * Delegates to {@link #replyingTo(MimeMessage, boolean, String)} with replyToAll set to <code>true</code>.
		 *
		 * @see #DEFAULT_QUOTING_MARKUP
		 */
		EmailPopulatingBuilder replyingToAll(@Nonnull final MimeMessage email, @Nonnull final String customQuotingTemplate) {
			return replyingTo(email, true, customQuotingTemplate);
		}
		
		/**
		 * Delegates to {@link #replyingTo(MimeMessage, boolean, String)} with replyToAll set to <code>false</code>.
		 */
		EmailPopulatingBuilder replyingTo(@Nonnull final MimeMessage email, @Nonnull final String customQuotingTemplate) {
			return replyingTo(email, false, customQuotingTemplate);
		}
		
		/**
		 * Delegates to {@link #replyingTo(MimeMessage, boolean, String)} with replyToAll set to <code>true</code> and a default HTML quoting
		 * template.
		 *
		 * @see #DEFAULT_QUOTING_MARKUP
		 */
		EmailPopulatingBuilder replyingToAll(@Nonnull final MimeMessage email) {
			return replyingTo(email, true, DEFAULT_QUOTING_MARKUP);
		}
		
		/**
		 * Primes the email with all subject, headers, originally embedded images and recipients needed for a valid RFC reply.
		 * <p>
		 * <strong>Note:</strong> replaces subject with "Re: &lt;original subject&gt;" (but never nested).<br>
		 * <p>
		 * <strong>Note:</strong> Make sure you set the content before using this API or else the quoted content is lost. Replaces body (text is replaced
		 * with "> text" and HTML is replaced with the provided or default quoting markup.
		 *
		 * @param emailMessage The message from which we harvest recipients, original content to quote (including embedded images), message ID to
		 *                     include.
		 * @param repyToAll    Indicates whether all original receivers should be included in this new reply. Also see {@link
		 *                     MimeMessage#reply(boolean)}.
		 * @param htmlTemplate A valid HTML that contains the string {@code "%s"}. Be advised that HTML is very limited in emails.
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
		 * @see javax.mail.internet.MimeMessage#reply(boolean)
		 */
		EmailPopulatingBuilder replyingTo(@Nonnull final MimeMessage emailMessage, final boolean repyToAll, @Nonnull final String htmlTemplate) {
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
			
			return startingBlank()
					.withSubject(generatedReply.getSubject())
					.to(generatedReply.getRecipients())
					.withPlainText(LINE_START_PATTERN.matcher(defaultTo(repliedTo.getText(), "")).replaceAll("> "))
					.withHTMLText(format(htmlTemplate, defaultTo(repliedTo.getText(), "")))
					.withHeaders(generatedReply.getHeaders())
					.withEmbeddedImages(repliedTo.getEmbeddedImages());
		}
		
		/**
		 * Delegates to {@link #forwarding(MimeMessage)} with the provided {@link Email} converted to {@link MimeMessage}.
		 *
		 * @see EmailConverter#emailToMimeMessage(Email)
		 */
		EmailPopulatingBuilder forwarding(@Nonnull final Email email) {
			return forwarding(EmailConverter.emailToMimeMessage(email));
		}
		
		/**
		 * Primes the email to build with proper subject and inline forwarded email needed for a valid RFC forward. Also includes the original email
		 * intact, to be rendered by the email client as 'forwarded email'.
		 * <p>
		 * <strong>Note 1</strong>: replaces subject with "Fwd: &lt;original subject&gt;" (nesting enabled).
		 * <p>
		 * <strong>Note 2</strong>: {@code Content-Disposition} will be left empty so the receiving email client can decide how to handle display (most
		 * will show inline, some will show as attachment instead).
		 *
		 * @see <a href="https://javaee.github.io/javamail/FAQ#forward">Official JavaMail FAQ on forwarding</a>
		 * @see <a href="https://blogs.technet.microsoft.com/exchange/2011/04/21/mixed-ing-it-up-multipartmixed-messages-and-you/">More reading
		 * material</a>
		 * @see #forwarding(Email)
		 */
		EmailPopulatingBuilder forwarding(@Nonnull final MimeMessage emailMessage) {
			return startingBlank()
					.withForward(emailMessage)
					.withSubject("Fwd: " + MimeMessageParser.parseSubject(emailMessage));
		}
		
		public EmailPopulatingBuilder copying() {
			return new EmailPopulatingBuilder(applyDefaults);
		}
		
		public EmailPopulatingBuilder startingBlank() {
			return new EmailPopulatingBuilder(applyDefaults);
		}
	}
}