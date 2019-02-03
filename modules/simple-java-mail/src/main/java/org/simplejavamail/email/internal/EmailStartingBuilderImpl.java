package org.simplejavamail.email.internal;

import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EmailStartingBuilder;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.converter.internal.mimemessage.MimeMessageParser;
import org.simplejavamail.email.EmailBuilder;
import org.simplejavamail.email.internal.EmailException;
import org.simplejavamail.email.internal.EmailPopulatingBuilderImpl;

import javax.annotation.Nonnull;
import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;

import static java.lang.String.format;
import static org.simplejavamail.internal.util.MiscUtil.defaultTo;
import static org.simplejavamail.internal.util.Preconditions.assumeNonNull;

/**
 * @see EmailStartingBuilder
 */
public final class EmailStartingBuilderImpl implements EmailStartingBuilder {
	
	/**
	 * Flag used when creating a new {@link EmailPopulatingBuilderImpl} indicating whether to use property defaults or to ignore them.
	 * <p>
	 * Flag can be disabled using {@link #ignoringDefaults()}.
	 */
	private boolean applyDefaults = true;
	
	/**
	 * @deprecated Used internally. Don't use this. Use one of the {@link EmailBuilder#startingBlank()} instead.
	 */
	@Deprecated
	@SuppressWarnings("DeprecatedIsStillUsed")
	public EmailStartingBuilderImpl() {
	}
	
	/**
	 * @see EmailStartingBuilder#ignoringDefaults()
	 */
	@Override
	public EmailStartingBuilder ignoringDefaults() {
		applyDefaults = false;
		return this;
	}
	
	/**
	 * @see EmailStartingBuilder#startingBlank()
	 */
	@Override
	public EmailPopulatingBuilder startingBlank() {
		return new EmailPopulatingBuilderImpl(applyDefaults);
	}
	
	/**
	 * @see EmailStartingBuilder#replyingTo(Email)
	 */
	@Override
	public EmailPopulatingBuilder replyingTo(@Nonnull final Email email) {
		return replyingTo(EmailConverter.emailToMimeMessage(email), false, EmailStartingBuilder.DEFAULT_QUOTING_MARKUP);
	}
	
	/**
	 * @see EmailStartingBuilder#replyingToAll(Email)
	 */
	@Override
	public EmailPopulatingBuilder replyingToAll(@Nonnull final Email email) {
		return replyingTo(EmailConverter.emailToMimeMessage(email), true, EmailStartingBuilder.DEFAULT_QUOTING_MARKUP);
	}
	
	/**
	 * @see EmailStartingBuilder#replyingToAll(Email, String)
	 */
	@Override
	public EmailPopulatingBuilder replyingToAll(@Nonnull final Email email, @Nonnull final String customQuotingTemplate) {
		return replyingTo(EmailConverter.emailToMimeMessage(email), true, customQuotingTemplate);
	}
	
	/**
	 * @see EmailStartingBuilder#replyingTo(Email, String)
	 */
	@Override
	public EmailPopulatingBuilder replyingTo(@Nonnull final Email email, @Nonnull final String customQuotingTemplate) {
		return replyingTo(EmailConverter.emailToMimeMessage(email), false, customQuotingTemplate);
	}
	
	/**
	 * @see EmailStartingBuilder#replyingTo(MimeMessage)
	 */
	@Override
	public EmailPopulatingBuilder replyingTo(@Nonnull final MimeMessage message) {
		return replyingTo(message, false, EmailStartingBuilder.DEFAULT_QUOTING_MARKUP);
	}
	
	/**
	 * @see EmailStartingBuilder#replyingTo(MimeMessage, String)
	 */
	@Override
	public EmailPopulatingBuilder replyingTo(@Nonnull final MimeMessage message, @Nonnull final String customQuotingTemplate) {
		return replyingTo(message, false, customQuotingTemplate);
	}
	
	/**
	 * @see EmailStartingBuilder#replyingToAll(MimeMessage, String)
	 */
	@Override
	public EmailPopulatingBuilder replyingToAll(@Nonnull final MimeMessage message, @Nonnull final String customQuotingTemplate) {
		return replyingTo(message, true, customQuotingTemplate);
	}
	
	/**
	 * @see EmailStartingBuilder#replyingToAll(MimeMessage)
	 */
	@Override
	public EmailPopulatingBuilder replyingToAll(@Nonnull final MimeMessage message) {
		return replyingTo(message, true, EmailStartingBuilder.DEFAULT_QUOTING_MARKUP);
	}
	
	/**
	 * @see EmailStartingBuilder#replyingTo(MimeMessage, boolean, String)
	 */
	@Override
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
				.withPlainText(EmailStartingBuilder.LINE_START_PATTERN.matcher(defaultTo(repliedTo.getPlainText(), "")).replaceAll("> "))
				.withHTMLText(format(htmlTemplate, defaultTo(repliedTo.getHTMLText(), "")))
				.withHeaders(generatedReply.getHeaders())
				.withEmbeddedImages(repliedTo.getEmbeddedImages());
	}
	
	/**
	 * @see EmailStartingBuilder#forwarding(Email)
	 */
	@Override
	public EmailPopulatingBuilder forwarding(@Nonnull final Email email) {
		return forwarding(EmailConverter.emailToMimeMessage(email));
	}
	
	/**
	 * @see EmailStartingBuilder#forwarding(MimeMessage)
	 */
	@Override
	public EmailPopulatingBuilder forwarding(@Nonnull final MimeMessage message) {
		return ((EmailPopulatingBuilderImpl) startingBlank())
				.withForward(message)
				.withSubject("Fwd: " + MimeMessageParser.parseSubject(message));
	}
	
	/**
	 * @see EmailStartingBuilder#copying(MimeMessage)
	 */
	@Override
	public EmailPopulatingBuilder copying(@Nonnull final MimeMessage message) {
		return copying(EmailConverter.mimeMessageToEmail(message));
	}
	
	/**
	 * @see EmailStartingBuilder#copying(EmailPopulatingBuilder)
	 */
	@Override
	public EmailPopulatingBuilder copying(@Nonnull final EmailPopulatingBuilder emailBuilder) {
		return copying(emailBuilder.buildEmail());
	}
	
	/**
	 * @see EmailStartingBuilder#copying(Email)
	 */
	@Override
	public EmailPopulatingBuilder copying(@Nonnull final Email email) {
		EmailPopulatingBuilderImpl builder = new EmailPopulatingBuilderImpl(applyDefaults);
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
			builder.signWithDomainKey(email.getDkimPrivateKeyFile(), assumeNonNull(email.getDkimSigningDomain()), assumeNonNull(email.getDkimSelector()));
		}
		if (email.getDkimPrivateKeyInputStream() != null) {
			builder.signWithDomainKey(email.getDkimPrivateKeyInputStream(), assumeNonNull(email.getDkimSigningDomain()), assumeNonNull(email.getDkimSelector()));
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
