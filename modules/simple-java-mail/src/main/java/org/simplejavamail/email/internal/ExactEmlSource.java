package org.simplejavamail.email.internal;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetHeaders;
import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.config.EmailGovernance;
import org.simplejavamail.api.mailer.spi.ContentRequirement;
import org.simplejavamail.internal.util.FinalizedMimeMessage;
import org.simplejavamail.mailer.internal.EmailGovernanceImpl;

import java.io.ByteArrayInputStream;
import java.util.Arrays;

final class ExactEmlSource implements EmailSource {

	private static final long serialVersionUID = 1234567L;

	private final byte[] emlBytes;

	ExactEmlSource(final byte @NotNull [] emlBytes) {
		this.emlBytes = emlBytes.clone();
	}

	@NotNull
	InternetHeaders readHeaders() throws MessagingException {
		return new InternetHeaders(new ByteArrayInputStream(emlBytes));
	}

	@Override
	@NotNull
	public Email prepareForConversion(@NotNull final Email email, @NotNull final EmailGovernance emailGovernance) {
		return emailGovernance instanceof EmailGovernanceImpl ? ((EmailGovernanceImpl) emailGovernance).prepareExactEmail(email) : email;
	}

	@Override
	@NotNull
	public Email prepareForSending(@NotNull final Email email,
			@NotNull final EmailGovernance emailGovernance,
			final boolean disableAllClientValidation) {
		final Email prepared = prepareForConversion(email, emailGovernance);
		ExactEmlValidator.validateEnvelope(prepared);
		return prepared;
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
