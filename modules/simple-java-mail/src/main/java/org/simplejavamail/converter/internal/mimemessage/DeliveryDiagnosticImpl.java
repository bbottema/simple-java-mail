package org.simplejavamail.converter.internal.mimemessage;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.DeliveryDiagnostic;
import org.simplejavamail.api.mailer.SmtpServerResponse;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

/** Preserve diagnostic source text even when its protocol-specific view cannot be interpreted. */
@Value
class DeliveryDiagnosticImpl implements DeliveryDiagnostic {

	private static final long serialVersionUID = 1L;
	private static final Pattern SMTP_REPLY = Pattern.compile("[2-5][0-9]{2}(?=$|[ \\t-])");

	String type;
	String text;
	// The response is derived, and SmtpServerResponse has identity equality. Compare the source facts instead, including after serialization.
	@EqualsAndHashCode.Exclude
	@Nullable SmtpServerResponse smtpResponse;

	@Builder
	private DeliveryDiagnosticImpl(final String type, final String text) {
		this.type = requireNonNull(type, "type");
		this.text = requireNonNull(text, "text");
		smtpResponse = parseSmtpResponse(type, text);
	}

	@Nullable
	private static SmtpServerResponse parseSmtpResponse(final String type, final String text) {
		if (!"smtp".equalsIgnoreCase(type)) {
			return null;
		}
		final Matcher reply = SMTP_REPLY.matcher(text);
		// Only a code at the start of an SMTP diagnostic is evidence; never mine human prose for a plausible number.
		return reply.lookingAt() ? new SmtpServerResponse(Integer.parseInt(reply.group()), text) : null;
	}

	@Override
	public String toString() {
		return "DeliveryDiagnostic{smtpResponseAvailable=" + (smtpResponse != null) + "}";
	}
}
