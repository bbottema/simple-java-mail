package org.simplejavamail.api.mailer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Email;

import java.io.Serializable;
import java.util.List;

/**
 * Immutable snapshot of preparing an {@link Email} through one {@link Mailer}, without submitting it to a mail server.
 * <p>
 * The EML representation is the authoritative output of this rehearsal. It is returned defensively and no mutable Jakarta Mail message is exposed.
 * A later send prepares the message again, so generated dates, MIME boundaries, Message-IDs and cryptographic output can differ unless the caller fixes
 * those inputs. For an exact email, the supplied bytes are already authoritative and therefore remain identical across rehearsal and sending.
 * Callers that need only a success-or-exception preparation check can use {@link Mailer#validate(Email)} instead. A successful rehearsal has already
 * performed that validation, so calling {@code validate} before {@code rehearse} is redundant.
 *
 * @see Mailer#rehearse(Email)
 * @see Mailer#validate(Email)
 */
public interface MailRehearsal extends Serializable {

	/**
	 * @return The effective composed email after governance, or the unchanged exact email. Its Message-ID matches {@link #getEmailId()} when present.
	 */
	@NotNull
	Email getEffectiveEmail();

	/**
	 * @return A defensive copy of the complete rendered EML bytes.
	 */
	byte @NotNull [] getEmlBytes();

	/**
	 * @return The encoded EML size in bytes.
	 */
	long getEncodedSize();

	/**
	 * @return The effective Message-ID in the rendered EML, or {@code null} if no Message-ID was produced.
	 */
	@Nullable
	String getEmailId();

	/**
	 * @return The explicit SMTP envelope sender, or {@code null} when no override is configured and the mail provider will derive its default.
	 */
	@Nullable
	String getEnvelopeSender();

	/**
	 * @return The immutable, ordered mailbox addresses that would be supplied to the transport. Override receivers replace the MIME recipients here.
	 */
	@NotNull
	List<String> getEnvelopeRecipients();

	/**
	 * @return {@code true} when security processing and configured maximum-size validation were requested; {@code false} for base-MIME rehearsal.
	 */
	boolean isFullRehearsal();
}
