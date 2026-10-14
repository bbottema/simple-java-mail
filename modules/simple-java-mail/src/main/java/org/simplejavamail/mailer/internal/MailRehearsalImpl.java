package org.simplejavamail.mailer.internal;

import lombok.Builder;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.MailRehearsal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static java.util.Objects.requireNonNull;

/** Library-owned immutable implementation of {@link MailRehearsal}. */
@ApiStatus.Internal
public final class MailRehearsalImpl implements MailRehearsal {

	private static final long serialVersionUID = 1L;

	@NotNull private final Email effectiveEmail;
	private final byte @NotNull [] emlBytes;
	@Nullable private final String emailId;
	@Nullable private final String envelopeSender;
	@NotNull private final List<String> envelopeRecipients;
	private final boolean fullRehearsal;

	/** Copies and validates the supplied observations; it performs no send work. */
	@Builder
	private MailRehearsalImpl(@NotNull final Email effectiveEmail,
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

	/** @see MailRehearsal#getEffectiveEmail() */
	@Override
	@NotNull
	public Email getEffectiveEmail() {
		return effectiveEmail;
	}

	/** @see MailRehearsal#getEmlBytes() */
	@Override
	public byte @NotNull [] getEmlBytes() {
		return emlBytes.clone();
	}

	/** @see MailRehearsal#getEncodedSize() */
	@Override
	public long getEncodedSize() {
		return emlBytes.length;
	}

	/** @see MailRehearsal#getEmailId() */
	@Override
	@Nullable
	public String getEmailId() {
		return emailId;
	}

	/** @see MailRehearsal#getEnvelopeSender() */
	@Override
	@Nullable
	public String getEnvelopeSender() {
		return envelopeSender;
	}

	/** @see MailRehearsal#getEnvelopeRecipients() */
	@Override
	@NotNull
	public List<String> getEnvelopeRecipients() {
		return envelopeRecipients;
	}

	/** @see MailRehearsal#isFullRehearsal() */
	@Override
	public boolean isFullRehearsal() {
		return fullRehearsal;
	}
}
