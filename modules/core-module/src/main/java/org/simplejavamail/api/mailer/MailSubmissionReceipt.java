package org.simplejavamail.api.mailer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;

/**
 * Immutable provider-neutral facts for one message submission attempt.
 * <p>
 * This represents SMTP submission acceptance, not final mailbox delivery. Final delivery remains asynchronous and should be tracked through delivery
 * status notifications, read receipts, bounces, or provider-specific webhooks.
 * <p>
 * A successful {@code sendMailAndGetReceipt(...)} call returns this type. When submission fails or only some recipients were accepted,
 * {@link MailSubmissionException#getSubmissionReceipt()} exposes the same facts together with the original Jakarta Mail failure.
 *
 * @see Mailer#sendMailAndGetReceipt(org.simplejavamail.api.email.Email)
 * @see MailSender#sendMailAndGetReceipt(org.simplejavamail.api.email.Email)
 */
public final class MailSubmissionReceipt implements Serializable {

	private static final long serialVersionUID = 1L;

	@Nullable private final String emailId;
	@Nullable private final SmtpServerResponse smtpResponse;
	@NotNull private final Instant submittedAt;
	// These 10.0 fields deserialize as null from older receipts because the serialVersionUID intentionally remains stable.
	@Nullable private final MailSubmissionStatus status;
	@Nullable private final List<String> acceptedRecipients;
	@Nullable private final List<String> validUnsentRecipients;
	@Nullable private final List<String> invalidRecipients;

	/**
	 * Creates a receipt without recipient-level transport facts. This constructor remains useful for send paths that expose only an SMTP response.
	 */
	public MailSubmissionReceipt(@Nullable final String emailId, @Nullable final SmtpServerResponse smtpResponse, @NotNull final Instant submittedAt) {
		this(emailId, smtpResponse, submittedAt, statusFromResponse(smtpResponse),
				Collections.<String>emptyList(), Collections.<String>emptyList(), Collections.<String>emptyList());
	}

	/**
	 * Creates a receipt from one coherent transport attempt. Recipient groups are copied and exposed as immutable lists.
	 *
	 * @param emailId               The effective Message-ID, if one was produced.
	 * @param smtpResponse           The response exposed by the selected provider, if any.
	 * @param submittedAt            The time captured after the attempt completed or failed.
	 * @param status                 The provider-neutral acceptance status for this attempt.
	 * @param acceptedRecipients     Mailbox addresses known to have been accepted.
	 * @param validUnsentRecipients  Valid mailbox addresses that were not submitted.
	 * @param invalidRecipients      Mailbox addresses rejected as invalid.
	 */
	public MailSubmissionReceipt(@Nullable final String emailId,
			@Nullable final SmtpServerResponse smtpResponse,
			@NotNull final Instant submittedAt,
			@NotNull final MailSubmissionStatus status,
			@NotNull final List<String> acceptedRecipients,
			@NotNull final List<String> validUnsentRecipients,
			@NotNull final List<String> invalidRecipients) {
		this.emailId = emailId;
		this.smtpResponse = smtpResponse;
		this.submittedAt = checkNonEmptyArgument(submittedAt, "submittedAt");
		this.status = requireNonNull(status, "status");
		this.acceptedRecipients = immutableRecipientCopy(acceptedRecipients, "acceptedRecipients");
		this.validUnsentRecipients = immutableRecipientCopy(validUnsentRecipients, "validUnsentRecipients");
		this.invalidRecipients = immutableRecipientCopy(invalidRecipients, "invalidRecipients");
	}

	@NotNull
	private static MailSubmissionStatus statusFromResponse(@Nullable final SmtpServerResponse smtpResponse) {
		if (smtpResponse == null) {
			return MailSubmissionStatus.UNKNOWN;
		}
		return smtpResponse.isPositiveCompletionReply() ? MailSubmissionStatus.ACCEPTED : MailSubmissionStatus.REJECTED;
	}

	@NotNull
	private static List<String> immutableRecipientCopy(@NotNull final List<String> recipients, @NotNull final String name) {
		final List<String> copy = new ArrayList<>(requireNonNull(recipients, name));
		for (String recipient : copy) {
			requireNonNull(recipient, name + " element");
		}
		return Collections.unmodifiableList(copy);
	}

	/**
	 * @return The effective Message-ID for the submitted email, or {@code null} if no message id was produced by the sending path.
	 */
	@Nullable
	public String getEmailId() {
		return emailId;
	}

	/**
	 * @return The server response if the selected transport provider exposed one.
	 */
	@NotNull
	public Optional<SmtpServerResponse> getSmtpResponse() {
		return Optional.ofNullable(smtpResponse);
	}

	/**
	 * @return {@code true} when at least one recipient is known to have been accepted for submission.
	 */
	public boolean isAcceptedByServer() {
		return getStatus().isAcceptedForAtLeastOneRecipient();
	}

	/**
	 * @return Whether this send path could determine server acceptance. If false, some recipients may still have been accepted.
	 */
	public boolean hasServerAcceptanceInformation() {
		return getStatus().isServerAcceptanceKnown();
	}

	/**
	 * @return The provider-neutral submission status.
	 */
	@NotNull
	public MailSubmissionStatus getStatus() {
		return status == null ? statusFromResponse(smtpResponse) : status;
	}

	/**
	 * @return Immutable mailbox addresses known to have been accepted for submission.
	 */
	@NotNull
	public List<String> getAcceptedRecipients() {
		return acceptedRecipients == null ? Collections.<String>emptyList() : acceptedRecipients;
	}

	/**
	 * @return Immutable mailbox addresses that were valid but were not submitted.
	 */
	@NotNull
	public List<String> getValidUnsentRecipients() {
		return validUnsentRecipients == null ? Collections.<String>emptyList() : validUnsentRecipients;
	}

	/**
	 * @return Immutable mailbox addresses rejected as invalid by the transport.
	 */
	@NotNull
	public List<String> getInvalidRecipients() {
		return invalidRecipients == null ? Collections.<String>emptyList() : invalidRecipients;
	}

	/**
	 * @return The timestamp captured after the send attempt completed or failed.
	 */
	@NotNull
	public Instant getSubmittedAt() {
		return submittedAt;
	}
}
