package org.simplejavamail.email.internal;

import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EmailStartingBuilder;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.converter.internal.mimemessage.MimeMessageParser;
import org.simplejavamail.email.EmailBuilder;

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
	public EmailPopulatingBuilder replyingTo(@NotNull final Email email) {
		return replyingTo(EmailConverter.emailToMimeMessage(email), false, EmailStartingBuilder.DEFAULT_QUOTING_MARKUP);
	}
	
	/**
	 * @see EmailStartingBuilder#replyingToAll(Email)
	 */
	@Override
	public EmailPopulatingBuilder replyingToAll(@NotNull final Email email) {
		return replyingTo(EmailConverter.emailToMimeMessage(email), true, EmailStartingBuilder.DEFAULT_QUOTING_MARKUP);
	}
	
	/**
	 * @see EmailStartingBuilder#replyingToAll(Email, String)
	 */
	@Override
	public EmailPopulatingBuilder replyingToAll(@NotNull final Email email, @NotNull final String customQuotingTemplate) {
		return replyingTo(EmailConverter.emailToMimeMessage(email), true, customQuotingTemplate);
	}
	
	/**
	 * @see EmailStartingBuilder#replyingTo(Email, String)
	 */
	@Override
	public EmailPopulatingBuilder replyingTo(@NotNull final Email email, @NotNull final String customQuotingTemplate) {
		return replyingTo(EmailConverter.emailToMimeMessage(email), false, customQuotingTemplate);
	}
	
	/**
	 * @see EmailStartingBuilder#replyingTo(MimeMessage)
	 */
	@Override
	public EmailPopulatingBuilder replyingTo(@NotNull final MimeMessage message) {
		return replyingTo(message, false, EmailStartingBuilder.DEFAULT_QUOTING_MARKUP);
	}
	
	/**
	 * @see EmailStartingBuilder#replyingTo(MimeMessage, String)
	 */
	@Override
	public EmailPopulatingBuilder replyingTo(@NotNull final MimeMessage message, @NotNull final String customQuotingTemplate) {
		return replyingTo(message, false, customQuotingTemplate);
	}
	
	/**
	 * @see EmailStartingBuilder#replyingToAll(MimeMessage, String)
	 */
	@Override
	public EmailPopulatingBuilder replyingToAll(@NotNull final MimeMessage message, @NotNull final String customQuotingTemplate) {
		return replyingTo(message, true, customQuotingTemplate);
	}
	
	/**
	 * @see EmailStartingBuilder#replyingToAll(MimeMessage)
	 */
	@Override
	public EmailPopulatingBuilder replyingToAll(@NotNull final MimeMessage message) {
		return replyingTo(message, true, EmailStartingBuilder.DEFAULT_QUOTING_MARKUP);
	}
	
	/**
	 * @see EmailStartingBuilder#replyingTo(MimeMessage, boolean, String)
	 */
	@Override
	public EmailPopulatingBuilder replyingTo(@NotNull final MimeMessage emailMessage, final boolean repyToAll, @NotNull final String htmlTemplate) {
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
	public EmailPopulatingBuilder forwarding(@NotNull final Email email) {
		return forwarding(EmailConverter.emailToMimeMessage(email));
	}
	
	/**
	 * @see EmailStartingBuilder#forwarding(MimeMessage)
	 */
	@Override
	public EmailPopulatingBuilder forwarding(@NotNull final MimeMessage message) {
		return ((InternalEmailPopulatingBuilder) startingBlank())
				.withForward(message)
				.withSubject("Fwd: " + MimeMessageParser.parseSubject(message));
	}
	
	/**
	 * @see EmailStartingBuilder#copying(MimeMessage)
	 */
	@Override
	public EmailPopulatingBuilder copying(@NotNull final MimeMessage message) {
		return copying(EmailConverter.mimeMessageToEmail(message));
	}
	
	/**
	 * @see EmailStartingBuilder#copying(EmailPopulatingBuilder)
	 */
	@Override
	public EmailPopulatingBuilder copying(@NotNull final EmailPopulatingBuilder emailBuilder) {
		return copying(emailBuilder.buildEmail());
	}
	
	/**
	 * @see EmailStartingBuilder#copying(Email)
	 */
	@SuppressWarnings({ "CastCanBeRemovedNarrowingVariableType", "deprecation" })
	@Override
	public EmailPopulatingBuilder copying(@NotNull final Email email) {
		EmailPopulatingBuilder builder = new EmailPopulatingBuilderImpl(applyDefaults);
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
		builder.withRecipients(email.getRecipients());
		builder.withEmbeddedImages(email.getEmbeddedImages());
		builder.withAttachments(email.getAttachments());
		((InternalEmailPopulatingBuilder) builder).withHeaders(email.getHeaders(), true);
		if (email.getSentDate() != null) {
			builder.fixingSentDate(email.getSentDate());
		}
		if (email.getDkimPrivateKeyData() != null) {
			builder.signWithDomainKey(email.getDkimPrivateKeyData(), assumeNonNull(email.getDkimSigningDomain()), assumeNonNull(email.getDkimSelector()));
		}
		if (email.getDispositionNotificationTo() != null) {
			builder.withDispositionNotificationTo(email.getDispositionNotificationTo());
		}
		if (email.getReturnReceiptTo() != null) {
			builder.withReturnReceiptTo(email.getReturnReceiptTo());
		}
		if (email.getCalendarMethod() != null) {
			builder.withCalendarText(email.getCalendarMethod(), email.getCalendarText());
		}
		if (email.getEmailToForward() != null) {
			((InternalEmailPopulatingBuilder) builder).withForward(email.getEmailToForward());
		}
		((InternalEmailPopulatingBuilder) builder).withDecryptedAttachments(email.getDecryptedAttachments());
		if (email.getSmimeSignedEmail() != null) {
			((InternalEmailPopulatingBuilder) builder).withSmimeSignedEmail(email.getSmimeSignedEmail());
		}
		((InternalEmailPopulatingBuilder) builder).withOriginalSmimeDetails(email.getOriginalSmimeDetails());
		if (!email.wasMergedWithSmimeSignedMessage()) {
			builder.notMergingSingleSMIMESignedAttachment();
		}
		return builder;
	}
}
