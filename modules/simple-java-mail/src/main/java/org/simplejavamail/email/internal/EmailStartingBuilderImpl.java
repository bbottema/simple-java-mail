package org.simplejavamail.email.internal;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EmailStartingBuilder;
import org.simplejavamail.converter.ConfiguredEmailConverter;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.converter.internal.mimemessage.MimeMessageParser;
import org.simplejavamail.config.SimpleJavaMailConfig;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static org.simplejavamail.internal.util.MiscUtil.defaultTo;

/**
 * @see EmailStartingBuilder
 */
public final class EmailStartingBuilderImpl implements EmailStartingBuilder {

	private final SimpleJavaMailConfig config;
	
	public EmailStartingBuilderImpl(@NotNull final SimpleJavaMailConfig config) {
		this.config = requireNonNull(config, "config");
	}

	/**
	 * @see EmailStartingBuilder#startingBlank()
	 */
	@Override
	public EmailPopulatingBuilder startingBlank() {
		return newPopulatingBuilder();
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

		final Email repliedTo = converter().mimeMessageToEmailBuilder(emailMessage).buildEmail();
		final Email generatedReply = converter().mimeMessageToEmailBuilder(replyMessage).buildEmail();

		return startingBlank()
				.withSubject(generatedReply.getSubject())
				.withRecipients(generatedReply.getRecipients())
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
		return copying(converter().mimeMessageToEmailBuilder(message).buildEmail());
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
	@SuppressWarnings({"deprecation" })
	@Override
	public EmailPopulatingBuilder copying(@NotNull final Email email) {
		EmailPopulatingBuilder builder = newPopulatingBuilder();

		if (email.getId() != null) {
			builder.fixingMessageId(email.getId());
		}
		if (email.getFromRecipient() != null) {
			builder.from(email.getFromRecipient());
		}
		builder.withReplyTo(email.getReplyToRecipients());
		if (email.getBounceToRecipient() != null) {
			builder.withBounceTo(email.getBounceToRecipient());
		}
		if (email.getDeliveryStatusNotification() != null) {
			builder.withDeliveryStatusNotification(email.getDeliveryStatusNotification());
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
		if (email.getContentTransferEncoding() != null) {
			builder.withContentTransferEncoding(email.getContentTransferEncoding());
		}
		if (email.getPlainTextContentTransferEncoding() != null) {
			builder.withPlainTextContentTransferEncoding(email.getPlainTextContentTransferEncoding());
		}
		if (email.getHTMLTextContentTransferEncoding() != null) {
			builder.withHTMLTextContentTransferEncoding(email.getHTMLTextContentTransferEncoding());
		}
		if (email.getCalendarTextContentTransferEncoding() != null) {
			builder.withCalendarTextContentTransferEncoding(email.getCalendarTextContentTransferEncoding());
		}
		((InternalEmailPopulatingBuilder) builder).withHeaders(email.getHeaders(), true);
		if (email.getSentDate() != null) {
			builder.fixingSentDate(email.getSentDate());
		}
		if (email.getSmimeSigningConfig() != null) {
			builder.signWithSmime(email.getSmimeSigningConfig());
		}
		if (email.getSmimeEncryptionConfig() != null) {
			builder.encryptWithSmime(email.getSmimeEncryptionConfig());
		}
		if (email.getOpenPgpSigningConfig() != null) {
			builder.signWithOpenPgp(email.getOpenPgpSigningConfig());
		}
		if (email.getOpenPgpEncryptionConfig() != null) {
			builder.encryptWithOpenPgp(email.getOpenPgpEncryptionConfig());
		}
		if (email.getDkimConfig() != null) {
			builder.signWithDomainKey(email.getDkimConfig());
		}
		if (email.getDispositionNotificationTo() != null) {
			builder.withDispositionNotificationTo(email.getDispositionNotificationTo());
		}
		if (email.getReturnReceiptTo() != null) {
			builder.withReturnReceiptTo(email.getReturnReceiptTo());
		}
		if (email.getCalendarMethod() != null) {
			builder.withCalendarText(email.getCalendarMethod(), requireNonNull(email.getCalendarText(), "CalendarText"));
		}
		if (email.getEmailToForward() != null) {
			((InternalEmailPopulatingBuilder) builder).withForward(email.getEmailToForward());
		}
		((InternalEmailPopulatingBuilder) builder).withDecryptedAttachments(email.getDecryptedAttachments());
		if (email.getSmimeSignedEmail() != null) {
			((InternalEmailPopulatingBuilder) builder).withSmimeSignedEmail(email.getSmimeSignedEmail());
		}
		((InternalEmailPopulatingBuilder) builder).withOriginalSmimeDetails(email.getOriginalSmimeDetails());
		((InternalEmailPopulatingBuilder) builder).withOriginalOpenPgpDetails(email.getOriginalOpenPgpDetails());

		if (!(email instanceof InternalEmail)) {
			throw new AssertionError("Email is not of type InternalEmail, this should not be possible");
		}

		if (!((InternalEmail) email).wasMergedWithSmimeSignedMessage()) {
			builder.notMergingSingleSMIMESignedAttachment();
		}
		return builder;
	}

	private ConfiguredEmailConverter converter() {
		return new ConfiguredEmailConverter(config);
	}

	private EmailPopulatingBuilderImpl newPopulatingBuilder() {
		return new EmailPopulatingBuilderImpl(config);
	}
}
