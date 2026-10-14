package org.simplejavamail.mailer.internal;

import lombok.Builder;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.MailRecipientDisposition;
import org.simplejavamail.api.mailer.MailRecipientResult;
import org.simplejavamail.api.mailer.MailRetryDisposition;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.MailSubmissionStatus;
import org.simplejavamail.api.mailer.SmtpRecipientStatus;
import org.simplejavamail.api.mailer.SmtpServerResponse;

import java.io.InvalidObjectException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Library-owned immutable implementation of {@link MailSubmissionReceipt}. */
@ApiStatus.Internal
public final class MailSubmissionReceiptImpl implements MailSubmissionReceipt {

	private static final long serialVersionUID = 1L;

	@Nullable private final String emailId;
	@Nullable private final String envelopeId;
	private final boolean requireTlsUsed;
	@Nullable private final Long messageSize;
	@Nullable private final Long serverMaximumMessageSize;
	@Nullable private final SmtpServerResponse smtpResponse;
	@NotNull private final Instant submittedAt;
	@NotNull private final MailSubmissionStatus status;
	@NotNull private final List<String> acceptedRecipients;
	@NotNull private final List<String> validUnsentRecipients;
	@NotNull private final List<String> invalidRecipients;
	@NotNull private final List<MailRecipientResult> recipientResults;
	@NotNull private final MailRetryDisposition retryDisposition;
	@NotNull private final transient List<MailRecipientResult> retryableRecipients;

	/** Copies and validates the supplied observations; it performs no send work. */
	@Builder
	private MailSubmissionReceiptImpl(@Nullable final String emailId, @Nullable final SmtpServerResponse smtpResponse,
			@NotNull final Instant submittedAt, @NotNull final MailSubmissionStatus status,
			@NotNull final List<MailRecipientResult> recipientResults, @NotNull final MailRetryDisposition retryDisposition,
			@Nullable final String envelopeId, final boolean requireTlsUsed,
			@Nullable final Long messageSize, @Nullable final Long serverMaximumMessageSize) {
		if ((messageSize != null && messageSize < 0) || (serverMaximumMessageSize != null && serverMaximumMessageSize <= 0)) {
			throw new IllegalArgumentException("Message size must be non-negative and the server maximum must be positive; use null for unknown sizes.");
		}
		this.emailId = emailId;
		this.envelopeId = envelopeId;
		this.requireTlsUsed = requireTlsUsed;
		this.messageSize = messageSize;
		this.serverMaximumMessageSize = serverMaximumMessageSize;
		this.smtpResponse = smtpResponse;
		this.submittedAt = requireNonNull(submittedAt, "submittedAt");
		this.status = requireNonNull(status, "status");
		this.recipientResults = List.copyOf(recipientResults);
		this.retryDisposition = requireNonNull(retryDisposition, "retryDisposition");
		this.acceptedRecipients = recipientsWithDisposition(this.recipientResults, MailRecipientDisposition.ACCEPTED);
		this.validUnsentRecipients = recipientsWithDisposition(this.recipientResults, MailRecipientDisposition.VALID_UNSENT);
		this.invalidRecipients = recipientsWithDisposition(this.recipientResults, MailRecipientDisposition.INVALID);
		this.retryableRecipients = retryableRecipients(this.recipientResults, this.retryDisposition);
	}

	@NotNull
	private static List<MailRecipientResult> retryableRecipients(final List<MailRecipientResult> recipients, final MailRetryDisposition disposition) {
		if (disposition != MailRetryDisposition.SAFE_TO_RETRY_ALL && disposition != MailRetryDisposition.SAFE_TO_RETRY_UNACCEPTED) {
			return Collections.emptyList();
		}
		final List<MailRecipientResult> retryable = new ArrayList<>();
		for (final MailRecipientResult recipient : recipients) {
			if (recipient.getDisposition() == MailRecipientDisposition.VALID_UNSENT
					&& recipient.getRcptStatus() != SmtpRecipientStatus.PERMANENTLY_REJECTED) {
				retryable.add(recipient);
			}
		}
		return Collections.unmodifiableList(retryable);
	}

	@NotNull
	private static List<String> recipientsWithDisposition(final List<MailRecipientResult> recipients, final MailRecipientDisposition disposition) {
		final List<String> addresses = new ArrayList<>();
		for (final MailRecipientResult recipient : recipients) {
			if (recipient.getDisposition() == disposition) {
				addresses.add(recipient.getEnvelopeAddress().orElse(recipient.getOriginalAddress()));
			}
		}
		return Collections.unmodifiableList(addresses);
	}

	/** Rebuild derived views and validate the detached data when restoring a snapshot of this implementation. */
	private Object readResolve() throws InvalidObjectException {
		try {
			return MailSubmissionReceiptImpl.builder()
				.emailId(emailId)
				.smtpResponse(smtpResponse)
				.submittedAt(submittedAt)
				.status(status)
				.recipientResults(recipientResults)
				.retryDisposition(retryDisposition)
				.envelopeId(envelopeId)
				.requireTlsUsed(requireTlsUsed)
				.messageSize(messageSize)
				.serverMaximumMessageSize(serverMaximumMessageSize)
				.build();
		} catch (final RuntimeException failure) {
			final InvalidObjectException invalidReceipt = new InvalidObjectException("Invalid serialized mail submission receipt");
			invalidReceipt.initCause(failure);
			throw invalidReceipt;
		}
	}

	/** @see MailSubmissionReceipt#getRecipientResults() */
	@Override
	@NotNull
	public List<MailRecipientResult> getRecipientResults() {
		return recipientResults;
	}

	/** @see MailSubmissionReceipt#getRetryDisposition() */
	@Override
	@NotNull
	public MailRetryDisposition getRetryDisposition() {
		return retryDisposition;
	}

	/** @see MailSubmissionReceipt#getRetryableRecipients() */
	@Override
	@NotNull
	public List<MailRecipientResult> getRetryableRecipients() {
		return retryableRecipients;
	}

	/** @see MailSubmissionReceipt#getEmailId() */
	@Override
	@Nullable
	public String getEmailId() {
		return emailId;
	}

	/** @see MailSubmissionReceipt#getEnvelopeId() */
	@Override
	@Nullable
	public String getEnvelopeId() {
		return envelopeId;
	}

	/** @see MailSubmissionReceipt#isRequireTlsUsed() */
	@Override
	public boolean isRequireTlsUsed() {
		return requireTlsUsed;
	}

	/** @see MailSubmissionReceipt#getMessageSize() */
	@Override
	@Nullable
	public Long getMessageSize() {
		return messageSize;
	}

	/** @see MailSubmissionReceipt#getServerMaximumMessageSize() */
	@Override
	@Nullable
	public Long getServerMaximumMessageSize() {
		return serverMaximumMessageSize;
	}

	/** @see MailSubmissionReceipt#getSmtpResponse() */
	@Override
	@NotNull
	public Optional<SmtpServerResponse> getSmtpResponse() {
		return Optional.ofNullable(smtpResponse);
	}

	/** @see MailSubmissionReceipt#isAcceptedByServer() */
	@Override
	public boolean isAcceptedByServer() {
		return getStatus().isAcceptedForAtLeastOneRecipient();
	}

	/** @see MailSubmissionReceipt#hasServerAcceptanceInformation() */
	@Override
	public boolean hasServerAcceptanceInformation() {
		return getStatus().isServerAcceptanceKnown();
	}

	/** @see MailSubmissionReceipt#getStatus() */
	@Override
	@NotNull
	public MailSubmissionStatus getStatus() {
		return status;
	}

	/** @see MailSubmissionReceipt#getAcceptedRecipients() */
	@Override
	@NotNull
	public List<String> getAcceptedRecipients() {
		return acceptedRecipients;
	}

	/** @see MailSubmissionReceipt#getValidUnsentRecipients() */
	@Override
	@NotNull
	public List<String> getValidUnsentRecipients() {
		return validUnsentRecipients;
	}

	/** @see MailSubmissionReceipt#getInvalidRecipients() */
	@Override
	@NotNull
	public List<String> getInvalidRecipients() {
		return invalidRecipients;
	}

	/** @see MailSubmissionReceipt#getSubmittedAt() */
	@Override
	@NotNull
	public Instant getSubmittedAt() {
		return submittedAt;
	}
}
