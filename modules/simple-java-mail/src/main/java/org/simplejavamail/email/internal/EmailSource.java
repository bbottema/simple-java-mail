package org.simplejavamail.email.internal;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.config.EmailGovernance;
import org.simplejavamail.api.mailer.spi.ContentRequirement;

import java.io.Serializable;
import java.io.UnsupportedEncodingException;

/** Internal strategy for the preparation and rendering rules attached to an {@link Email}. */
interface EmailSource extends Serializable {

	@NotNull
	Email prepareForConversion(@NotNull Email email, @NotNull EmailGovernance emailGovernance);

	@NotNull
	Email prepareForSending(@NotNull Email email, @NotNull EmailGovernance emailGovernance, boolean disableAllClientValidation);

	@NotNull
	MimeMessage renderMimeMessage(@NotNull Email email, @NotNull Session session, boolean processSecurity)
			throws MessagingException, UnsupportedEncodingException;

	@NotNull
	ContentRequirement determineContentRequirement(@NotNull Email email);
}
