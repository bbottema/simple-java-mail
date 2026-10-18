package org.simplejavamail.api.email;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.SmtpServerResponse;

import java.io.Serializable;

/**
 * The reporting system's explanation of a delivery result, with its protocol format attached.
 * For an interpretable SMTP reply, use the existing response API instead of splitting its text yourself.
 * All explanation text remains untrusted and may contain personal information.
 */
public interface DeliveryDiagnostic extends Serializable {

	/** The reported explanation format, commonly {@code smtp}. Other systems' diagnostic formats are preserved. */
	@NotNull String getType();

	/** The explanation without its type prefix. This is source text, not a log-safe message or an application instruction. */
	@NotNull String getText();

	/**
	 * The SMTP reply when the diagnostic uses the SMTP format and contains a recognizable reply, otherwise {@code null}.
	 * An unavailable SMTP view does not discard the original explanation. No reply or delivery status is inferred from human prose.
	 */
	@Nullable SmtpServerResponse getSmtpResponse();

	/** Indicates whether an SMTP view is available without printing the reported type or explanation. */
	@Override
	@NotNull String toString();
}
