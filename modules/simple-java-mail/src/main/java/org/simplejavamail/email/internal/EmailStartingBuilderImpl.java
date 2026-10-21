package org.simplejavamail.email.internal;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EmailProperty;
import org.simplejavamail.api.email.EmailStartingBuilder;
import org.simplejavamail.api.email.ExactEmailBuilder;
import org.simplejavamail.api.converter.ConfiguredEmailConverter;
import org.simplejavamail.converter.internal.ConfiguredEmailConverterImpl;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.converter.internal.ExactEmailBuilderImpl;
import org.simplejavamail.converter.internal.mimemessage.MimeMessageParser;
import org.simplejavamail.config.SimpleJavaMailConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static org.simplejavamail.internal.util.MiscUtil.defaultTo;
import static org.simplejavamail.internal.util.MiscUtil.readInputStreamToBytes;

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
	 * @see EmailStartingBuilder#startingFromExactEml(byte[])
	 */
	@Override
	public ExactEmailBuilder startingFromExactEml(final byte @NotNull [] emlBytes) {
		return new ExactEmailBuilderImpl(config, emlBytes);
	}

	/**
	 * @see EmailStartingBuilder#startingFromExactEml(InputStream)
	 */
	@Override
	public ExactEmailBuilder startingFromExactEml(@NotNull final InputStream emlInputStream) {
		try {
			return startingFromExactEml(readInputStreamToBytes(requireNonNull(emlInputStream, "emlInputStream")));
		} catch (final IOException readFailure) {
			throw new EmailException("Unable to read exact EML from InputStream", readFailure);
		}
	}

	/**
	 * @see EmailStartingBuilder#startingFromExactEml(Path)
	 */
	@Override
	public ExactEmailBuilder startingFromExactEml(@NotNull final Path emlPath) {
		try {
			return startingFromExactEml(Files.readAllBytes(requireNonNull(emlPath, "emlPath")));
		} catch (final IOException readFailure) {
			throw new EmailException(format("Unable to read exact EML from path [%s]", emlPath), readFailure);
		}
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

		final Email repliedTo = converter().fromMimeMessage(emailMessage).toEmail();
		final Email generatedReply = converter().fromMimeMessage(replyMessage).toEmail();

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
		return copying(converter().fromMimeMessage(message).toEmail());
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
		final InternalEmail internalEmail = InternalEmail.requireInternalEmail(email);
		final Email mergedSmimeEmail = internalEmail.wasMergedWithSmimeSignedMessage() ? email.getSmimeSignedEmail() : null;
		EmailPopulatingBuilder builder = newPopulatingBuilder();
		((InternalEmailPopulatingBuilder) builder).ignoringDefaults(email.isIgnoreDefaults());
		((InternalEmailPopulatingBuilder) builder).ignoringOverrides(email.isIgnoreOverrides());
		if (email.getPropertiesNotToApplyDefaultValueFor() != null) {
			((InternalEmailPopulatingBuilder) builder).dontApplyDefaultValueFor(email.getPropertiesNotToApplyDefaultValueFor().toArray(new EmailProperty[0]));
		}
		if (email.getPropertiesNotToApplyOverrideValueFor() != null) {
			((InternalEmailPopulatingBuilder) builder).dontApplyOverrideValueFor(email.getPropertiesNotToApplyOverrideValueFor().toArray(new EmailProperty[0]));
		}

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
			((InternalEmailPopulatingBuilder) builder).withDeliveryStatusNotification(email.getDeliveryStatusNotification());
		}
		if (email.isTlsRequiredForOnwardDelivery()) {
			((InternalEmailPopulatingBuilder) builder).withTlsRequiredForOnwardDelivery();
		}
		if (email.getSendingToAcceptedRecipients() != null) {
			((InternalEmailPopulatingBuilder) builder).withSendingToAcceptedRecipients(email.getSendingToAcceptedRecipients());
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
		if (!email.getOverrideReceivers().isEmpty()) {
			builder.withOverrideReceivers(email.getOverrideReceivers());
		}
		builder.withEmbeddedImages(withoutMergedResources(email.getEmbeddedImages(),
				mergedSmimeEmail == null ? 0 : mergedSmimeEmail.getEmbeddedImages().size()));
		builder.withAttachments(withoutMergedResources(email.getAttachments(),
				mergedSmimeEmail == null ? 0 : mergedSmimeEmail.getAttachments().size()));
		if (email.getContentTransferEncoding() != null) {
			((InternalEmailPopulatingBuilder) builder).withContentTransferEncoding(email.getContentTransferEncoding());
		}
		if (email.getPlainTextContentTransferEncoding() != null) {
			((InternalEmailPopulatingBuilder) builder).withPlainTextContentTransferEncoding(email.getPlainTextContentTransferEncoding());
		}
		if (email.getHTMLTextContentTransferEncoding() != null) {
			((InternalEmailPopulatingBuilder) builder).withHTMLTextContentTransferEncoding(email.getHTMLTextContentTransferEncoding());
		}
		if (email.getCalendarTextContentTransferEncoding() != null) {
			((InternalEmailPopulatingBuilder) builder).withCalendarTextContentTransferEncoding(email.getCalendarTextContentTransferEncoding());
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
		copyDispositionNotification(email, builder);
		copyReturnReceipt(email, builder);
		if (email.getCalendarMethod() != null) {
			builder.withCalendarText(email.getCalendarMethod(), requireNonNull(email.getCalendarText(), "CalendarText"));
		}
		if (email.getEmailToForward() != null) {
			((InternalEmailPopulatingBuilder) builder).withForward(email.getEmailToForward());
		}
		((InternalEmailPopulatingBuilder) builder).withDecryptedAttachments(withoutMergedResources(email.getDecryptedAttachments(),
				mergedSmimeEmail == null ? 0 : mergedSmimeEmail.getDecryptedAttachments().size()));
		if (email.getSmimeSignedEmail() != null) {
			((InternalEmailPopulatingBuilder) builder).withSmimeSignedEmail(email.getSmimeSignedEmail());
		}
		((InternalEmailPopulatingBuilder) builder).withOriginalSmimeDetails(email.getOriginalSmimeDetails());
		((InternalEmailPopulatingBuilder) builder).withOriginalOpenPgpDetails(email.getOriginalOpenPgpDetails());
		((InternalEmailPopulatingBuilder) builder).withDeliveryStatusReport(email.getDeliveryStatusReport());

		if (!internalEmail.wasMergedWithSmimeSignedMessage()) {
			builder.notMergingSingleSMIMESignedAttachment();
		}
		return builder;
	}

	/**
	 * Email appends the nested S/MIME resources when merging. Reconstruct the outer prefix before the next build merges again,
	 * preserving every explicit occurrence and resource identity without comparing or reading borrowed content.
	 */
	private static <T> List<T> withoutMergedResources(final List<T> resources, final int mergedCount) {
		return resources.subList(0, resources.size() - mergedCount);
	}

	private static void copyDispositionNotification(final Email email, final EmailPopulatingBuilder builder) {
		if (Boolean.FALSE.equals(email.getUseDispositionNotificationTo())) {
			((InternalEmailPopulatingBuilder) builder).withDispositionNotificationToState(false, null);
		} else if (!email.getDispositionNotificationToRecipients().isEmpty()) {
			builder.withDispositionNotificationTo(email.getDispositionNotificationToRecipients());
		} else if (Boolean.TRUE.equals(email.getUseDispositionNotificationTo())) {
			builder.withDispositionNotificationTo();
		}
	}

	private static void copyReturnReceipt(final Email email, final EmailPopulatingBuilder builder) {
		if (Boolean.FALSE.equals(email.getUseReturnReceiptTo())) {
			((InternalEmailPopulatingBuilder) builder).withReturnReceiptToState(false, null);
		} else if (!email.getReturnReceiptToRecipients().isEmpty()) {
			builder.withReturnReceiptTo(email.getReturnReceiptToRecipients());
		} else if (Boolean.TRUE.equals(email.getUseReturnReceiptTo())) {
			builder.withReturnReceiptTo();
		}
	}

	private ConfiguredEmailConverter converter() {
		return new ConfiguredEmailConverterImpl(config);
	}

	private EmailPopulatingBuilderImpl newPopulatingBuilder() {
		return new EmailPopulatingBuilderImpl(config);
	}
}
