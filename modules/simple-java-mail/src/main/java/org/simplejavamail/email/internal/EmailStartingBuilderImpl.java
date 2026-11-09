package org.simplejavamail.email.internal;

import org.simplejavamail.internal.smime.FactorySmimeRuntime;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.NotNull;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;
import org.jsoup.nodes.TextNode;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EmailProperty;
import org.simplejavamail.api.email.EmailStartingBuilder;
import org.simplejavamail.api.email.ExactEmailBuilder;
import org.simplejavamail.api.converter.ConfiguredEmailConverter;
import org.simplejavamail.converter.internal.ConfiguredEmailConverterImpl;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.converter.internal.ExactEmailBuilderImpl;
import org.simplejavamail.converter.internal.mimemessage.MimeMessageParser;
import org.simplejavamail.converter.internal.mimemessage.MimeMessageHelper;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.simplejavamail.internal.util.JakartaMailImplementation;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static org.simplejavamail.internal.util.MiscUtil.defaultTo;
import static org.simplejavamail.internal.util.MiscUtil.readInputStreamToBytes;

/**
 * @see EmailStartingBuilder
 */
public final class EmailStartingBuilderImpl implements EmailStartingBuilder {

	private final SimpleJavaMailConfig config;
	private final FactorySmimeRuntime smimeRuntime;
	
	public EmailStartingBuilderImpl(@NotNull final SimpleJavaMailConfig config) {
		this(config, new FactorySmimeRuntime(config));
	}

	public EmailStartingBuilderImpl(@NotNull final SimpleJavaMailConfig config, @NotNull final FactorySmimeRuntime smimeRuntime) {
		this.config = requireNonNull(config, "config");
		this.smimeRuntime = requireNonNull(smimeRuntime, "smimeRuntime");
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
		return replyingTo(email, false, EmailStartingBuilder.DEFAULT_QUOTING_MARKUP);
	}
	
	/**
	 * @see EmailStartingBuilder#replyingToAll(Email)
	 */
	@Override
	public EmailPopulatingBuilder replyingToAll(@NotNull final Email email) {
		return replyingTo(email, true, EmailStartingBuilder.DEFAULT_QUOTING_MARKUP);
	}
	
	/**
	 * @see EmailStartingBuilder#replyingToAll(Email, String)
	 */
	@Override
	public EmailPopulatingBuilder replyingToAll(@NotNull final Email email, @NotNull final String customQuotingTemplate) {
		return replyingTo(email, true, customQuotingTemplate);
	}
	
	/**
	 * @see EmailStartingBuilder#replyingTo(Email, String)
	 */
	@Override
	public EmailPopulatingBuilder replyingTo(@NotNull final Email email, @NotNull final String customQuotingTemplate) {
		return replyingTo(email, false, customQuotingTemplate);
	}

	private EmailPopulatingBuilder replyingTo(final Email email, final boolean replyToAll, final String htmlTemplate) {
		if (InternalEmail.requireInternalEmail(email).isExactEml()) {
			return replyingTo(converter().fromEmail(email).toMimeMessage(), replyToAll, htmlTemplate);
		}
		try {
			final MimeMessage headers = MimeMessageHelper.createReplyHeaders(email, JakartaMailImplementation.createSession(new Properties()));
			return primeReply(createReplyMessage(headers, replyToAll), email, MimeMessageHelper.replyEmbeddedImages(email), htmlTemplate);
		} catch (final MessagingException | UnsupportedEncodingException e) {
			throw new EmailException("was unable to prepare email headers for a reply", e);
		}
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
		final MimeMessage replyMessage = createReplyMessage(emailMessage, repyToAll);
		final Email repliedTo = converter().fromMimeMessage(emailMessage).toEmail();
		return primeReply(replyMessage, repliedTo, repliedTo.getEmbeddedImages(), htmlTemplate);
	}

	private static MimeMessage createReplyMessage(final MimeMessage source, final boolean replyToAll) {
		try {
			final MimeMessage replyMessage = (MimeMessage) source.reply(replyToAll);
			replyMessage.setText("ignore");
			replyMessage.setFrom("ignore@ignore.ignore");
			return replyMessage;
		} catch (final MessagingException e) {
			throw new EmailException("was unable to parse mimemessage to produce a reply for", e);
		}
	}

	private EmailPopulatingBuilder primeReply(final MimeMessage replyMessage, final Email repliedTo,
			final List<AttachmentResource> embeddedImages, final String htmlTemplate) {
		final Email generatedReply = converter().fromMimeMessage(replyMessage).toEmail();

		return startingBlank()
				.withSubject(generatedReply.getSubject())
				.withRecipients(generatedReply.getRecipients())
				.withPlainText(EmailStartingBuilder.LINE_START_PATTERN.matcher(plainTextForReply(repliedTo)).replaceAll("> "))
				.withHTMLText(format(htmlTemplate, htmlTextForReply(repliedTo)))
				.withHeaders(generatedReply.getHeaders())
				.withEmbeddedImages(embeddedImages);
	}

	private static String plainTextForReply(final Email original) {
		if (original.getPlainText() != null) {
			return original.getPlainText();
		}
		if (original.getHTMLText() == null) {
			return "";
		}
		final Element body = Jsoup.parse(original.getHTMLText()).body();
		body.select("script, style").remove();
		body.select("a[href]").stream().filter(link -> !link.attr("href").isBlank())
				.forEach(link -> link.appendText(" <" + link.attr("href") + ">"));
		body.select("img[alt]").forEach(image -> image.before(new TextNode(" " + image.attr("alt") + " ")));
		body.getAllElements().stream().filter(element -> element != body && element.isBlock())
				.forEach(element -> element.before(new TextNode("\n")).after(new TextNode("\n")));
		return body.wholeText().replaceAll("\n{3,}", "\n\n").strip();
	}

	private static String htmlTextForReply(final Email original) {
		return original.getHTMLText() != null ? original.getHTMLText()
				: Entities.escape(defaultTo(original.getPlainText(), "")).replace("\r\n", "\n").replace('\r', '\n').replace("\n", "<br>");
	}
	
	/**
	 * @see EmailStartingBuilder#forwarding(Email)
	 */
	@Override
	public EmailPopulatingBuilder forwarding(@NotNull final Email email) {
		final org.simplejavamail.api.converter.EmailConversionResult<MimeMessage> converted = converter().fromEmail(email).toMimeMessageResult();
		if (!converted.hasValue()) throw converted.getFailure();
		final EmailPopulatingBuilder builder = forwarding(converted.getValue());
		if (!converted.getProblems().isEmpty()) {
			((InternalEmailPopulatingBuilder) builder).retainInspection(sourceForForward(converted), converted.getProblems());
		}
		return builder;
	}

	/** New conversion observations belong to this forward; the source Email's unrelated received history does not. */
	private static org.simplejavamail.api.email.EmailSourceData sourceForForward(
			final org.simplejavamail.api.converter.EmailConversionResult<MimeMessage> converted) {
		final java.util.Set<java.util.UUID> origins = new java.util.HashSet<>();
		for (org.simplejavamail.api.email.EmailProblem problem : converted.getProblems()) origins.add(problem.getOrigin().getReference());
		final java.util.List<org.simplejavamail.api.email.EmailSourceHeader> headers = new java.util.ArrayList<>();
		final java.util.List<org.simplejavamail.api.email.EmailSourceProperty> properties = new java.util.ArrayList<>();
		final java.util.List<org.simplejavamail.api.email.EmailSourcePart> parts = new java.util.ArrayList<>();
		final java.util.List<org.simplejavamail.api.email.EmailSourceData> derived = new java.util.ArrayList<>();
		for (org.simplejavamail.api.email.EmailSourceData source : converted.getSources()) {
			if (source.getOrigin() != null && origins.contains(source.getOrigin().getReference())) {
				headers.addAll(source.getHeaders());
				properties.addAll(source.getProperties());
				parts.addAll(source.getParts());
				derived.addAll(source.getDerivedSources());
			}
		}
		// Preserve direct links even if an operation has a located observation without a containing source frame.
		for (org.simplejavamail.api.email.EmailProblem problem : converted.getProblems()) {
			for (org.simplejavamail.api.email.EmailSourceElement element : problem.getSources()) {
				if (element instanceof org.simplejavamail.api.email.EmailSourcePart && !parts.contains(element)) {
					parts.add((org.simplejavamail.api.email.EmailSourcePart) element);
				} else if (element instanceof org.simplejavamail.api.email.EmailSourceHeader && !headers.contains(element)) {
					headers.add((org.simplejavamail.api.email.EmailSourceHeader) element);
				} else if (element instanceof org.simplejavamail.api.email.EmailSourceProperty && !properties.contains(element)) {
					properties.add((org.simplejavamail.api.email.EmailSourceProperty) element);
				}
			}
		}
		return org.simplejavamail.internal.email.EmailInspection.source(converted.getProblems().get(0).getOrigin(),
				org.simplejavamail.api.email.EmailSourceData.Format.MIME, org.simplejavamail.api.email.EmailSourceData.Representation.GENERATED,
				headers, properties, parts, derived);
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
		final EmailPopulatingBuilderImpl builder = newPopulatingBuilder();
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
		builder.withEmbeddedImages(email.getEmbeddedImages());
		builder.withAttachments(email.getAttachments());
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
		// A merged copy already represents the inner headers; preserve them now instead of restoring them during a later merge.
		builder.withHeaders(email.getHeaders(), !internalEmail.wasMergedWithSmimeSignedMessage());
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
		builder.withDecryptedAttachments(email.getDecryptedAttachments());
		if (email.getSmimeSignedEmail() != null) {
			((InternalEmailPopulatingBuilder) builder).withSmimeSignedEmail(email.getSmimeSignedEmail());
		}
		((InternalEmailPopulatingBuilder) builder).withOriginalSmimeDetails(email.getOriginalSmimeDetails());
		((InternalEmailPopulatingBuilder) builder).withOriginalOpenPgpDetails(email.getOriginalOpenPgpDetails());
		((InternalEmailPopulatingBuilder) builder).withDeliveryStatusReport(email.getDeliveryStatusReport());
        ((InternalEmailPopulatingBuilder) builder).retainInspection(email.getSourceData(), email.getProblems());

		builder.retainCopiedSmimeContent(internalEmail.wasMergedWithSmimeSignedMessage());
		return builder;
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
		return new ConfiguredEmailConverterImpl(config, smimeRuntime);
	}

	private EmailPopulatingBuilderImpl newPopulatingBuilder() {
		return new EmailPopulatingBuilderImpl(config);
	}
}
