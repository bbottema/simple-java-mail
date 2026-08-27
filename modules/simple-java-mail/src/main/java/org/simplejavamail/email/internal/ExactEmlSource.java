package org.simplejavamail.email.internal;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.config.EmailGovernance;
import org.simplejavamail.api.mailer.spi.ContentRequirement;
import org.simplejavamail.internal.util.FinalizedMimeMessage;

import java.util.Arrays;

final class ExactEmlSource implements EmailSource {

	private static final long serialVersionUID = 1234567L;

	private final byte[] emlBytes;

	ExactEmlSource(final byte @NotNull [] emlBytes) {
		this.emlBytes = emlBytes.clone();
	}

	@Override
	@NotNull
	public Email prepareForConversion(@NotNull final Email email, @NotNull final EmailGovernance emailGovernance) {
		return email;
	}

	@Override
	@NotNull
	public Email prepareForSending(@NotNull final Email email,
			@NotNull final EmailGovernance emailGovernance,
			final boolean disableAllClientValidation) {
		ExactEmlValidator.validateEnvelope(email);
		return email;
	}

	@Override
	@NotNull
	public MimeMessage renderMimeMessage(@NotNull final Email email, @NotNull final Session session, final boolean processSecurity)
			throws MessagingException {
		return FinalizedMimeMessage.fromExactMessageBytes(session, emlBytes);
	}

	@Override
	@NotNull
	public ContentRequirement determineContentRequirement(@NotNull final Email email) {
		return ContentRequirement.PRESERVE_ALL_BYTES;
	}

	@Override
	public boolean equals(final Object other) {
		return this == other || other instanceof ExactEmlSource
				&& Arrays.equals(emlBytes, ((ExactEmlSource) other).emlBytes);
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(emlBytes);
	}
}
