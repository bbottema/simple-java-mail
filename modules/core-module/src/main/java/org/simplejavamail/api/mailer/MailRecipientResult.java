package org.simplejavamail.api.mailer;

import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Immutable facts about one envelope recipient. RCPT TO and final submission are deliberately separate:
 * a recipient can have a positive RCPT reply and still have UNKNOWN submission after the final DATA reply is lost.
 * Addresses and replies are provider data and may contain personal information; they are not a log-safe diagnostic report.
 */
public final class MailRecipientResult implements Serializable {

	private static final long serialVersionUID = 1L;

	/** The original transport address, retaining its display name when supplied. */
	@Getter @NotNull private final String originalAddress;
	@Nullable private final String envelopeAddress;
	/** Final submission knowledge, independent of the RCPT reply. */
	@Getter @NotNull private final MailRecipientDisposition disposition;
	@Nullable private final Boolean rcptAttempted;
	@Nullable private final SmtpServerResponse rcptResponse;

	/**
	 * Creates an immutable provider snapshot. Null attempted/reply values mean unavailable facts, not a negative reply.
	 * The envelope address is the provider's mailbox form; its local part is not lowercased or rewritten.
	 */
	public MailRecipientResult(@NotNull final String originalAddress, @Nullable final String envelopeAddress,
			@NotNull final MailRecipientDisposition disposition, @Nullable final Boolean rcptAttempted,
			@Nullable final SmtpServerResponse rcptResponse) {
		this.originalAddress = requireNonNull(originalAddress, "originalAddress");
		this.envelopeAddress = envelopeAddress;
		this.disposition = requireNonNull(disposition, "disposition");
		if (rcptResponse != null && (!Boolean.TRUE.equals(rcptAttempted)
				|| rcptResponse.getReturnCode() < 200 || rcptResponse.getReturnCode() >= 600)) {
			throw new IllegalArgumentException("An RCPT reply requires an attempted command and a real SMTP reply code");
		}
		this.rcptAttempted = rcptAttempted;
		this.rcptResponse = rcptResponse;
	}

	/** @return The normalized mailbox used in the envelope, when the provider exposes it. */
	@NotNull
	public Optional<String> getEnvelopeAddress() {
		return Optional.ofNullable(envelopeAddress);
	}

	/** @return True or false when the provider knows whether RCPT TO was attempted; empty when it does not. */
	@NotNull
	public Optional<Boolean> getRcptAttempted() {
		return Optional.ofNullable(rcptAttempted);
	}

	/** @return The actual RCPT reply, never a synthesized final DATA reply or EOF sentinel. */
	@NotNull
	public Optional<SmtpServerResponse> getRcptResponse() {
		return Optional.ofNullable(rcptResponse);
	}

	/** @return The RCPT outcome, derived from the primary reply code rather than human-readable text. */
	@NotNull
	public SmtpRecipientStatus getRcptStatus() {
		if (Boolean.FALSE.equals(rcptAttempted)) {
			return SmtpRecipientStatus.NOT_ATTEMPTED;
		}
		if (rcptResponse != null) {
			switch (rcptResponse.getReturnCode() / 100) {
				case 2:
					return SmtpRecipientStatus.ACCEPTED;
				case 4:
					return SmtpRecipientStatus.TEMPORARILY_REJECTED;
				case 5:
					return SmtpRecipientStatus.PERMANENTLY_REJECTED;
				default:
					return SmtpRecipientStatus.UNKNOWN;
			}
		}
		return SmtpRecipientStatus.UNKNOWN;
	}
}
