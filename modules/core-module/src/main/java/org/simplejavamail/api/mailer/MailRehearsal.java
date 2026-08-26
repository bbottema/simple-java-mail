package org.simplejavamail.api.mailer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Email;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Immutable snapshot of preparing an {@link Email} through one {@link Mailer}, without submitting it to a mail server.
 * <p>
 * The EML representation is the authoritative output of this rehearsal. It is returned defensively and no mutable Jakarta Mail message is exposed.
 * A later send prepares the message again, so generated dates, MIME boundaries, Message-IDs and cryptographic output can differ unless the caller fixes
 * those inputs.
 * Callers that need only a success-or-exception preparation check can use {@link Mailer#validate(Email)} instead. A successful rehearsal has already
 * performed that validation, so calling {@code validate} before {@code rehearse} is redundant.
 *
 * @see Mailer#rehearse(Email)
 * @see Mailer#validate(Email)
 */
public final class MailRehearsal implements Serializable {

	private static final long serialVersionUID = 1L;

	@NotNull private final Email effectiveEmail;
	private final byte @NotNull [] emlBytes;
	@Nullable private final String emailId;
	@Nullable private final String envelopeSender;
	@NotNull private final List<String> envelopeRecipients;
	private final boolean fullRehearsal;

	/**
	 * Creates a rehearsal snapshot. Applications normally obtain instances from {@link Mailer#rehearse(Email)}.
	 *
	 * @param effectiveEmail     The governed email after this mailer's defaults and overrides have been applied.
	 * @param emlBytes           The rendered EML representation.
	 * @param emailId            The Message-ID in the rendered EML, if one was produced.
	 * @param envelopeSender     The explicit SMTP envelope sender, or {@code null} when the provider will derive its default.
	 * @param envelopeRecipients The mailbox addresses that would be supplied to the transport.
	 * @param fullRehearsal      Whether security processing and configured maximum-size validation were requested.
	 */
	public MailRehearsal(@NotNull final Email effectiveEmail,
			final byte @NotNull [] emlBytes,
			@Nullable final String emailId,
			@Nullable final String envelopeSender,
			@NotNull final List<String> envelopeRecipients,
			final boolean fullRehearsal) {
		this.effectiveEmail = requireNonNull(effectiveEmail, "effectiveEmail");
		this.emlBytes = requireNonNull(emlBytes, "emlBytes").clone();
		this.emailId = emailId;
		this.envelopeSender = envelopeSender;
		this.envelopeRecipients = immutableRecipientCopy(envelopeRecipients);
		this.fullRehearsal = fullRehearsal;
	}

	@NotNull
	private static List<String> immutableRecipientCopy(@NotNull final List<String> envelopeRecipients) {
		final List<String> copy = new ArrayList<>(requireNonNull(envelopeRecipients, "envelopeRecipients").size());
		for (String envelopeRecipient : envelopeRecipients) {
			copy.add(requireNonNull(envelopeRecipient, "envelopeRecipient"));
		}
		return Collections.unmodifiableList(copy);
	}

	/**
	 * @return The effective email after this mailer's defaults and overrides have been applied. Its generated Message-ID matches {@link #getEmailId()}.
	 */
	@NotNull
	public Email getEffectiveEmail() {
		return effectiveEmail;
	}

	/**
	 * @return A defensive copy of the complete rendered EML bytes.
	 */
	public byte @NotNull [] getEmlBytes() {
		return emlBytes.clone();
	}

	/**
	 * @return The encoded EML size in bytes.
	 */
	public long getEncodedSize() {
		return emlBytes.length;
	}

	/**
	 * @return The effective Message-ID in the rendered EML, or {@code null} if no Message-ID was produced.
	 */
	@Nullable
	public String getEmailId() {
		return emailId;
	}

	/**
	 * @return The explicit SMTP envelope sender, or {@code null} when no override is configured and the mail provider will derive its default.
	 */
	@Nullable
	public String getEnvelopeSender() {
		return envelopeSender;
	}

	/**
	 * @return The immutable, ordered mailbox addresses that would be supplied to the transport. Override receivers replace the MIME recipients here.
	 */
	@NotNull
	public List<String> getEnvelopeRecipients() {
		return envelopeRecipients;
	}

	/**
	 * @return {@code true} when security processing and configured maximum-size validation were requested; {@code false} for base-MIME rehearsal.
	 */
	public boolean isFullRehearsal() {
		return fullRehearsal;
	}
}
