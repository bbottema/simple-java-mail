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

/**
 * Builder starter which offers initial starting places after which control is passed on to the {@link EmailPopulatingBuilder} for filling in details.
 * <p>
 * Starter methods are all variations of one of the following:
 * <ul>
 * <li>startingBlank()</li>
 * <li>replyingTo()</li>
 * <li>replyingToAll()</li>
 * <li>forwarding()</li>
 * <li>copying()</li>
 * </ul>
 */
public class EmailBuilder {
	
	/**
	 * Configures this builder to create an email ignoring the normal (optional) defaults that apply from property config files.
	 */
	public static EmailBuilderInstance ignoringDefaults() {
		return new EmailBuilderInstance().ignoringDefaults();
	}
	
	/**
	 * Delegates to {@link EmailBuilderInstance#replyingTo(Email)}.
	 */
	public static EmailPopulatingBuilder replyingTo(@Nonnull final Email email) {
		return new EmailBuilderInstance().replyingTo(email);
	}
	
	/**
	 * Delegates to {@link EmailBuilderInstance#replyingToAll(Email)}.
	 */
	public static EmailPopulatingBuilder replyingToAll(@Nonnull final Email email) {
		return new EmailBuilderInstance().replyingToAll(email);
	}
	
	/**
	 * Delegates to {@link EmailBuilderInstance#replyingToAll(Email, String)}.
	 */
	public static EmailPopulatingBuilder replyingToAll(@Nonnull final Email email, @Nonnull final String customQuotingTemplate) {
		return new EmailBuilderInstance().replyingToAll(email, customQuotingTemplate);
	}
	
	/**
	 * Delegates to {@link EmailBuilderInstance#replyingTo(Email, String)}.
	 */
	public static EmailPopulatingBuilder replyingTo(@Nonnull final Email email, @Nonnull final String customQuotingTemplate) {
		return new EmailBuilderInstance().replyingTo(email, customQuotingTemplate);
	}
	
	/**
	 * Delegates to {@link EmailBuilderInstance#replyingTo(MimeMessage)}.
	 */
	public static EmailPopulatingBuilder replyingTo(@Nonnull final MimeMessage email) {
		return new EmailBuilderInstance().replyingTo(email);
	}
	
	/**
	 * Delegates to {@link EmailBuilderInstance#replyingToAll(MimeMessage, String)}.
	 */
	public static EmailPopulatingBuilder replyingToAll(@Nonnull final MimeMessage email, @Nonnull final String customQuotingTemplate) {
		return new EmailBuilderInstance().replyingToAll(email, customQuotingTemplate);
	}
	
	/**
	 * Delegates to {@link EmailBuilderInstance#replyingTo(MimeMessage, String)}.
	 */
	public static EmailPopulatingBuilder replyingTo(@Nonnull final MimeMessage email, @Nonnull final String customQuotingTemplate) {
		return new EmailBuilderInstance().replyingTo(email, customQuotingTemplate);
	}
	
	/**
	 * Delegates to {@link EmailBuilderInstance#replyingToAll(MimeMessage)}.
	 */
	public static EmailPopulatingBuilder replyingToAll(@Nonnull final MimeMessage email) {
		return new EmailBuilderInstance().replyingToAll(email);
	}
	
	/**
	 * Delegates to {@link EmailBuilderInstance#replyingTo(MimeMessage, boolean, String)}.
	 */
	public static EmailPopulatingBuilder replyingTo(@Nonnull final MimeMessage emailMessage, final boolean repyToAll, @Nonnull final String htmlTemplate) {
		return new EmailBuilderInstance().replyingTo(emailMessage, repyToAll, htmlTemplate);
	}
	
	/**
	 * Delegates to {@link EmailBuilderInstance#forwarding(Email)}.
	 */
	public static EmailPopulatingBuilder forwarding(@Nonnull final Email email) {
		return new EmailBuilderInstance().forwarding(email);
	}
	
	/**
	 * Delegates to {@link EmailBuilderInstance#forwarding(MimeMessage)}.
	 */
	public static EmailPopulatingBuilder forwarding(@Nonnull final MimeMessage emailMessage) {
		return new EmailBuilderInstance().forwarding(emailMessage);
	}
	
	/**
	 * Delegates to {@link EmailBuilderInstance#copying(MimeMessage)}.
	 */
	public static EmailPopulatingBuilder copying(@Nonnull final MimeMessage message) {
		return new EmailBuilderInstance().copying(message);
	}
	
	/**
	 * Delegates to {@link EmailBuilderInstance#copying(EmailPopulatingBuilder)}.
	 */
	public static EmailPopulatingBuilder copying(@Nonnull final EmailPopulatingBuilder emailBuilder) {
		return new EmailBuilderInstance().copying(emailBuilder);
	}
	
	/**
	 * Delegates to {@link EmailBuilderInstance#copying(Email)}.
	 */
	public static EmailPopulatingBuilder copying(@Nonnull final Email email) {
		return new EmailBuilderInstance().copying(email);
	}
	
	/**
	 * Delegates to {@link EmailBuilderInstance#startingBlank()}.
	 */
	public static EmailPopulatingBuilder startingBlank() {
		return new EmailBuilderInstance().startingBlank();
	}
	
	private EmailBuilder() {
	}
	
	/**
	 * Offers the same API as {@link org.simplejavamail.mailer.MailerBuilder}, but as an instance (so we can keep track of state). This is because
	 * {@link #ignoringDefaults()} is the only method that doesn't return a {@link EmailPopulatingBuilder} but returns to the inital API instead.
	 * <p>
	 * As with the EmailBuilder, every other method returns an {@link EmailPopulatingBuilder}.
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
		@SuppressWarnings("JavaDoc")
		static final String DEFAULT_QUOTING_MARKUP = "<blockquote style=\"color: gray; border-left: 1px solid #4f4f4f; padding-left: " +
				"1cm\">%s</blockquote>";
		
		/**
		 * Flag used when creating a new {@link EmailPopulatingBuilder} indicating whether to use property defaults or to ignore them.
		 * <p>
		 * Flag can be disabled using {@link #ignoringDefaults()}.
		 */
		private boolean applyDefaults = true;
		
		private EmailBuilderInstance() {
		}
		
		/**
		 * Configures this builder to create an email ignoring the normal (optional) defaults that apply from property config files.
		 */
		EmailBuilderInstance ignoringDefaults() {
			applyDefaults = false;
			return this;
		}
		
		/**
		 * Most common use case for creating a new email. Starts with an empty email, populated with defaults when set through config properties (if
		 * not disabled using {@link EmailBuilderInstance#ignoringDefaults()}.
		 * <p>
		 * <strong>Note:</strong> Any builder method called after this will override the default value.
		 *
		 * @return A new {@link EmailBuilderInstance} to further populate the email with.
		 */
		public EmailPopulatingBuilder startingBlank() {
			return new EmailPopulatingBuilder(applyDefaults);
		}
		
		/**
		 * Delegates to {@link #replyingTo(MimeMessage, boolean, String)} with replyToAll set to <code>false</code> and a default HTML quoting
		 * template.
		 */
		public EmailPopulatingBuilder replyingTo(@Nonnull final Email email) {
			return replyingTo(EmailConverter.emailToMimeMessage(email), false, DEFAULT_QUOTING_MARKUP);
		}
		
		/**
		 * Delegates to {@link #replyingTo(MimeMessage, boolean, String)} with replyToAll set to <code>true</code> and a default HTML quoting
		 * template.
		 */
		public EmailPopulatingBuilder replyingToAll(@Nonnull final Email email) {
			return replyingTo(EmailConverter.emailToMimeMessage(email), true, DEFAULT_QUOTING_MARKUP);
		}
		
		/**
		 * Delegates to {@link #replyingTo(MimeMessage, boolean, String)} with replyToAll set to <code>true</code>.
		 *
		 * @see #DEFAULT_QUOTING_MARKUP
		 */
		public EmailPopulatingBuilder replyingToAll(@Nonnull final Email email, @Nonnull final String customQuotingTemplate) {
			return replyingTo(EmailConverter.emailToMimeMessage(email), true, customQuotingTemplate);
		}
		
		/**
		 * Delegates to {@link #replyingTo(MimeMessage, boolean, String)} with replyToAll set to <code>false</code>.
		 */
		public EmailPopulatingBuilder replyingTo(@Nonnull final Email email, @Nonnull final String customQuotingTemplate) {
			return replyingTo(EmailConverter.emailToMimeMessage(email), false, customQuotingTemplate);
		}
		
		/**
		 * Delegates to {@link #replyingTo(MimeMessage, boolean, String)} with replyToAll set to <code>false</code> and a default HTML quoting
		 * template.
		 */
		public EmailPopulatingBuilder replyingTo(@Nonnull final MimeMessage email) {
			return replyingTo(email, false, DEFAULT_QUOTING_MARKUP);
		}
		
		/**
		 * Delegates to {@link #replyingTo(MimeMessage, boolean, String)} with replyToAll set to <code>true</code>.
		 *
		 * @see #DEFAULT_QUOTING_MARKUP
		 */
		public EmailPopulatingBuilder replyingToAll(@Nonnull final MimeMessage email, @Nonnull final String customQuotingTemplate) {
			return replyingTo(email, true, customQuotingTemplate);
		}
		
		/**
		 * Delegates to {@link #replyingTo(MimeMessage, boolean, String)} with replyToAll set to <code>false</code>.
		 */
		public EmailPopulatingBuilder replyingTo(@Nonnull final MimeMessage email, @Nonnull final String customQuotingTemplate) {
			return replyingTo(email, false, customQuotingTemplate);
		}
		
		/**
		 * Delegates to {@link #replyingTo(MimeMessage, boolean, String)} with replyToAll set to <code>true</code> and a default HTML quoting
		 * template.
		 *
		 * @see #DEFAULT_QUOTING_MARKUP
		 */
		public EmailPopulatingBuilder replyingToAll(@Nonnull final MimeMessage email) {
			return replyingTo(email, true, DEFAULT_QUOTING_MARKUP);
		}
		
		/**
		 * Primes the email with all subject, headers, originally embedded images and recipients needed for a valid RFC reply.
		 * <p>
		 * <strong>Note:</strong> replaces subject with "Re: &lt;original subject&gt;" (but never nested).<br>
		 * <strong>Note:</strong> Make sure you set the content before using this API or else the quoted content is lost. Replaces body (text is
		 * replaced with "> text" and HTML is replaced with the provided or default quoting markup.
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
		public EmailPopulatingBuilder replyingTo(@Nonnull final MimeMessage emailMessage, final boolean repyToAll, @Nonnull final String htmlTemplate) {
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
					.withPlainText(LINE_START_PATTERN.matcher(defaultTo(repliedTo.getPlainText(), "")).replaceAll("> "))
					.withHTMLText(format(htmlTemplate, defaultTo(repliedTo.getPlainText(), "")))
					.withHeaders(generatedReply.getHeaders())
					.withEmbeddedImages(repliedTo.getEmbeddedImages());
		}
		
		/**
		 * Delegates to {@link #forwarding(MimeMessage)} with the provided {@link Email} converted to {@link MimeMessage}.
		 *
		 * @see EmailConverter#emailToMimeMessage(Email)
		 */
		public EmailPopulatingBuilder forwarding(@Nonnull final Email email) {
			return forwarding(EmailConverter.emailToMimeMessage(email));
		}
		
		/**
		 * Primes the email to build with proper subject and inline forwarded email needed for a valid RFC forward. Also includes the original email
		 * intact, to be rendered by the email client as 'forwarded email'.
		 * <p>
		 * <strong>Note 1</strong>: replaces subject with "Fwd: &lt;original subject&gt;" (nesting enabled).
		 * <p>
		 * <strong>Note 2</strong>: {@code Content-Disposition} will be left empty so the receiving email client can decide how to handle display
		 * (most will show inline, some will show as attachment instead).
		 *
		 * @see <a href="https://javaee.github.io/javamail/FAQ#forward">Official JavaMail FAQ on forwarding</a>
		 * @see <a href="https://blogs.technet.microsoft.com/exchange/2011/04/21/mixed-ing-it-up-multipartmixed-messages-and-you/">More reading
		 * material</a>
		 * @see #forwarding(Email)
		 */
		public EmailPopulatingBuilder forwarding(@Nonnull final MimeMessage emailMessage) {
			return startingBlank()
					.withForward(emailMessage)
					.withSubject("Fwd: " + MimeMessageParser.parseSubject(emailMessage));
		}
		
		/**
		 * Delegates to {@link #copying(Email)}, by converting the email first.
		 *
		 * @see EmailConverter#mimeMessageToEmail(MimeMessage)
		 */
		public EmailPopulatingBuilder copying(@Nonnull final MimeMessage message) {
			return copying(EmailConverter.mimeMessageToEmail(message));
		}
		
		/**
		 * Delegates to {@link #copying(Email)}, by building the email first.
		 *
		 * @see EmailPopulatingBuilder#buildEmail()
		 */
		public EmailPopulatingBuilder copying(@Nonnull final EmailPopulatingBuilder emailBuilder) {
			return copying(emailBuilder.buildEmail());
		}
		
		/**
		 * Preconfigures the builder with all the properties from the given email that are non-null.
		 */
		public EmailPopulatingBuilder copying(@Nonnull final Email email) {
			EmailPopulatingBuilder builder = new EmailPopulatingBuilder(applyDefaults);
			if (email.getId() != null) {
				builder.fixingMessageId(email.getId());
			}
			if (email.getFromRecipient() != null) {
				builder.from(email.getFromRecipient());
			}
			if (email.getReplyToRecipient() != null) {
				builder.withReplyTo(email.getReplyToRecipient());
			}
			if (email.getBounceToRecipient() != null) {
				builder.withBounceTo(email.getBounceToRecipient());
			}
			if (email.getPlainText() != null) {
				builder.withPlainText(email.getPlainText());
			}
			if (email.getHTMLText() != null) {
				builder.withHTMLText(email.getHTMLText());
			}
			if (email.getSubject() != null) {
				builder.withSubject(email.getSubject());
			}
			if (email.getRecipients() != null) {
				builder.withRecipients(email.getRecipients());
			}
			if (email.getEmbeddedImages() != null) {
				builder.withEmbeddedImages(email.getEmbeddedImages());
			}
			if (email.getAttachments() != null) {
				builder.withAttachments(email.getAttachments());
			}
			if (email.getHeaders() != null) {
				builder.withHeaders(email.getHeaders());
			}
			if (email.getDkimPrivateKeyFile() != null) {
				builder.signWithDomainKey(email.getDkimPrivateKeyFile(), email.getDkimSigningDomain(), email.getDkimSelector());
			}
			if (email.getDkimPrivateKeyInputStream() != null) {
				builder.signWithDomainKey(email.getDkimPrivateKeyInputStream(), email.getDkimSigningDomain(), email.getDkimSelector());
			}
			if (email.getDispositionNotificationTo() != null) {
				builder.withDispositionNotificationTo(email.getDispositionNotificationTo());
			}
			if (email.getReturnReceiptTo() != null) {
				builder.withReturnReceiptTo(email.getReturnReceiptTo());
			}
			if (email.getEmailToForward() != null) {
				builder.withForward(email.getEmailToForward());
			}
			return builder;
		}
	}
}