package org.simplejavamail.api.mailer.spi;

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.MailSubmissionStatus;
import org.simplejavamail.api.mailer.SmtpServerResponse;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Provider-neutral result returned by a {@link MailTransportAdapter} after one transport submission call.
 * <p>
 * Recipient arrays are copied on input and output. A failed result retains the original Jakarta Mail exception so the high-level send path can expose
 * it as the cause of a {@link org.simplejavamail.api.mailer.MailSubmissionException}.
 */
public final class MailTransportResult {

	@NotNull private final MailSubmissionStatus status;
	@Nullable private final SmtpServerResponse smtpResponse;
	@NotNull private final Address[] acceptedRecipients;
	@NotNull private final Address[] validUnsentRecipients;
	@NotNull private final Address[] invalidRecipients;
	@Nullable private final MessagingException failure;

	private MailTransportResult(@NotNull final MailSubmissionStatus status,
			@Nullable final SmtpServerResponse smtpResponse,
			@Nullable final Address[] acceptedRecipients,
			@Nullable final Address[] validUnsentRecipients,
			@Nullable final Address[] invalidRecipients,
			@Nullable final MessagingException failure) {
		this.status = requireNonNull(status, "status");
		this.smtpResponse = smtpResponse;
		this.acceptedRecipients = copy(acceptedRecipients);
		this.validUnsentRecipients = copy(validUnsentRecipients);
		this.invalidRecipients = copy(invalidRecipients);
		this.failure = failure;
	}

	/**
	 * Records a transport call that returned normally and accepted every submitted recipient.
	 * The recipient array is copied so adapters may safely reuse their own transport data.
	 */
	@NotNull
	public static MailTransportResult accepted(@NotNull final Address[] acceptedRecipients,
			@Nullable final SmtpServerResponse smtpResponse) {
		return new MailTransportResult(MailSubmissionStatus.ACCEPTED, smtpResponse,
				requireNonNull(acceptedRecipients, "acceptedRecipients"), null, null, null);
	}

	/**
	 * Records a transport call that returned normally but cannot report server acceptance, such as an adapter around an opaque external service.
	 */
	@NotNull
	public static MailTransportResult unknown(@Nullable final SmtpServerResponse smtpResponse) {
		return new MailTransportResult(MailSubmissionStatus.UNKNOWN, smtpResponse, null, null, null, null);
	}

	/**
	 * Records a failed transport call, deriving recipient groups from a {@link SendFailedException} when available while retaining the original failure.
	 */
	@NotNull
	public static MailTransportResult failed(@NotNull final MessagingException failure,
			@Nullable final SmtpServerResponse smtpResponse) {
		final SendFailedException sendFailedException = findSendFailedException(requireNonNull(failure, "failure"));
		return sendFailedException == null
				? failed(failure, smtpResponse, null, null, null)
				: failed(failure, smtpResponse, sendFailedException.getValidSentAddresses(),
						sendFailedException.getValidUnsentAddresses(), sendFailedException.getInvalidAddresses());
	}

	/**
	 * Records a failed transport call with explicitly classified recipient groups.
	 * Accepted recipients produce {@link MailSubmissionStatus#PARTIALLY_ACCEPTED}; known unsent or invalid recipients, or a negative SMTP response,
	 * produce {@link MailSubmissionStatus#REJECTED}. Without those facts the outcome remains {@link MailSubmissionStatus#UNKNOWN}.
	 */
	@NotNull
	public static MailTransportResult failed(@NotNull final MessagingException failure,
			@Nullable final SmtpServerResponse smtpResponse,
			@Nullable final Address[] acceptedRecipients,
			@Nullable final Address[] validUnsentRecipients,
			@Nullable final Address[] invalidRecipients) {
		final MailSubmissionStatus status = determineFailureStatus(
				smtpResponse, acceptedRecipients, validUnsentRecipients, invalidRecipients);
		return new MailTransportResult(status, smtpResponse, acceptedRecipients, validUnsentRecipients, invalidRecipients,
				requireNonNull(failure, "failure"));
	}

	@NotNull
	private static MailSubmissionStatus determineFailureStatus(@Nullable final SmtpServerResponse smtpResponse,
			@Nullable final Address[] acceptedRecipients,
			@Nullable final Address[] validUnsentRecipients,
			@Nullable final Address[] invalidRecipients) {
		if (hasRecipients(acceptedRecipients)) {
			return MailSubmissionStatus.PARTIALLY_ACCEPTED;
		}
		if (hasRecipients(validUnsentRecipients) || hasRecipients(invalidRecipients)
				|| smtpResponse != null && !smtpResponse.isPositiveCompletionReply()) {
			return MailSubmissionStatus.REJECTED;
		}
		return MailSubmissionStatus.UNKNOWN;
	}

	private static boolean hasRecipients(@Nullable final Address[] recipients) {
		return recipients != null && recipients.length > 0;
	}

	@Nullable
	private static SendFailedException findSendFailedException(@NotNull final MessagingException failure) {
		if (failure instanceof SendFailedException) {
			return (SendFailedException) failure;
		}
		final Exception nextFailure = failure.getNextException();
		return nextFailure instanceof SendFailedException
				? (SendFailedException) nextFailure
				: null;
	}

	@NotNull
	private static Address[] copy(@Nullable final Address[] addresses) {
		return addresses == null ? new Address[0] : addresses.clone();
	}

	/**
	 * @return The provider-neutral acceptance status for this attempt.
	 */
	@NotNull
	public MailSubmissionStatus getStatus() {
		return status;
	}

	/**
	 * @return The response captured during this attempt, if the provider exposed one.
	 */
	@NotNull
	public Optional<SmtpServerResponse> getSmtpResponse() {
		return Optional.ofNullable(smtpResponse);
	}

	/**
	 * @return A defensive copy of recipients known to have been accepted.
	 */
	@NotNull
	public Address[] getAcceptedRecipients() {
		return acceptedRecipients.clone();
	}

	/**
	 * @return A defensive copy of valid recipients that were not submitted.
	 */
	@NotNull
	public Address[] getValidUnsentRecipients() {
		return validUnsentRecipients.clone();
	}

	/**
	 * @return A defensive copy of recipients rejected as invalid.
	 */
	@NotNull
	public Address[] getInvalidRecipients() {
		return invalidRecipients.clone();
	}

	/**
	 * @return The original Jakarta Mail failure, or empty when the transport call returned normally.
	 */
	@NotNull
	public Optional<MessagingException> getFailure() {
		return Optional.ofNullable(failure);
	}

	/**
	 * @return {@code true} when the transport call returned normally. An {@link MailSubmissionStatus#UNKNOWN} result can still be successful.
	 */
	public boolean isSuccessful() {
		return failure == null;
	}
}
