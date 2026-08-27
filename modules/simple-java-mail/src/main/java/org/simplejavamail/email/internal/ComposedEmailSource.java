package org.simplejavamail.email.internal;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.config.EmailGovernance;
import org.simplejavamail.api.mailer.spi.ContentRequirement;
import org.simplejavamail.converter.internal.mimemessage.MimeMessageProducerHelper;
import org.simplejavamail.mailer.MailerHelper;

import java.io.UnsupportedEncodingException;

enum ComposedEmailSource implements EmailSource {
	INSTANCE;

	@Override
	@NotNull
	public Email prepareForConversion(@NotNull final Email email, @NotNull final EmailGovernance emailGovernance) {
		return emailGovernance.produceEmailApplyingDefaultsAndOverrides(email);
	}

	@Override
	@NotNull
	public Email prepareForSending(@NotNull final Email email,
			@NotNull final EmailGovernance emailGovernance,
			final boolean disableAllClientValidation) {
		final Email governedEmail = prepareForConversion(email, emailGovernance);
		final boolean valid = disableAllClientValidation
				? MailerHelper.validateLenient(governedEmail, emailGovernance.getEmailValidator())
				: MailerHelper.validate(governedEmail, emailGovernance.getEmailValidator());
		if (!valid) {
			throw new IllegalStateException("Email not valid, but no MailException was thrown for it");
		}
		return governedEmail;
	}

	@Override
	@NotNull
	public MimeMessage renderMimeMessage(@NotNull final Email email, @NotNull final Session session, final boolean processSecurity)
			throws MessagingException, UnsupportedEncodingException {
		return processSecurity
				? MimeMessageProducerHelper.produceMimeMessage(email, session)
				: MimeMessageProducerHelper.produceBaseMimeMessage(email, session);
	}

	@Override
	@NotNull
	public ContentRequirement determineContentRequirement(@NotNull final Email email) {
		return hasProtectedContent(email)
				? ContentRequirement.PRESERVE_PROTECTED_CONTENT
				: ContentRequirement.NORMAL;
	}

	private static boolean hasProtectedContent(@NotNull final Email email) {
		return email.getDkimConfig() != null
				|| email.getSmimeSigningConfig() != null
				|| email.getSmimeEncryptionConfig() != null
				|| email.getOpenPgpSigningConfig() != null
				|| email.getOpenPgpEncryptionConfig() != null
				|| email.getRecipients().stream().anyMatch(recipient -> recipient.getSmimeCertificate() != null);
	}
}
