package org.simplejavamail.api.mailer.spi;

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.MailRecipientDisposition;
import org.simplejavamail.api.mailer.MailRecipientResult;
import org.simplejavamail.api.mailer.MailRetryDisposition;
import org.simplejavamail.api.mailer.MailSubmissionStatus;
import org.simplejavamail.api.mailer.SmtpServerResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Provider-neutral result returned by a {@link MailTransportAdapter} after one transport submission call.
 * <p>
 * Recipient arrays are copied on input and output. A failed result retains the original Jakarta Mail exception so the high-level send path can expose
 * it as the cause of a {@link org.simplejavamail.api.mailer.MailSubmissionException}.
 * Structured recipient facts are captured on creation; later changes to caller-owned addresses cannot change them.
 */
public final class MailTransportResult {

	@NotNull private final MailSubmissionStatus status;
	@Nullable private final SmtpServerResponse smtpResponse;
	@NotNull private final Address[] acceptedRecipients;
	@NotNull private final Address[] validUnsentRecipients;
	@NotNull private final Address[] invalidRecipients;
	@Nullable private final MessagingException failure;
	@NotNull private final List<MailRecipientResult> recipientResults;
	@NotNull private final MailRetryDisposition retryDisposition;
	// Basic adapter results still need envelope order and unknown recipients, even though their known facts are already frozen.
	private final boolean envelopeRecipientsResolved;

	@NotNull
	private static MailTransportResult fromRecipientGroups(@NotNull final MailSubmissionStatus status,
			@Nullable final SmtpServerResponse smtpResponse,
			@Nullable final Address[] acceptedRecipients,
			@Nullable final Address[] validUnsentRecipients,
			@Nullable final Address[] invalidRecipients,
			@Nullable final MessagingException failure) {
		final List<MailRecipientResult> recipients = new ArrayList<>();
		appendRecipients(recipients, acceptedRecipients, MailRecipientDisposition.ACCEPTED);
		appendRecipients(recipients, validUnsentRecipients, MailRecipientDisposition.VALID_UNSENT);
		appendRecipients(recipients, invalidRecipients, MailRecipientDisposition.INVALID);
		return new MailTransportResult(status, smtpResponse, acceptedRecipients, validUnsentRecipients, invalidRecipients, failure,
				recipients, basicRetryDisposition(status, failure), false);
	}

	@NotNull
	private static MailRetryDisposition basicRetryDisposition(final MailSubmissionStatus status, @Nullable final MessagingException failure) {
		if (status == MailSubmissionStatus.ACCEPTED) {
			return MailRetryDisposition.DO_NOT_RETRY;
		}
		return status == MailSubmissionStatus.UNKNOWN && failure != null ? MailRetryDisposition.DUPLICATE_RISK
				: MailRetryDisposition.CALLER_POLICY_REQUIRED;
	}

	private MailTransportResult(@NotNull final MailSubmissionStatus status, @Nullable final SmtpServerResponse smtpResponse,
			@Nullable final Address[] acceptedRecipients, @Nullable final Address[] validUnsentRecipients,
			@Nullable final Address[] invalidRecipients, @Nullable final MessagingException failure,
			@NotNull final List<MailRecipientResult> recipientResults, @NotNull final MailRetryDisposition retryDisposition,
			final boolean envelopeRecipientsResolved) {
		this.status = requireNonNull(status, "status");
		this.smtpResponse = smtpResponse;
		this.acceptedRecipients = copy(acceptedRecipients);
		this.validUnsentRecipients = copy(validUnsentRecipients);
		this.invalidRecipients = copy(invalidRecipients);
		this.failure = failure;
		this.recipientResults = List.copyOf(recipientResults);
		this.retryDisposition = requireNonNull(retryDisposition, "retryDisposition");
		this.envelopeRecipientsResolved = envelopeRecipientsResolved;
	}

	/**
	 * Adds immutable, ordered recipient facts and conservative advice to this result. Adapters must classify the same
	 * recipients as the compatibility arrays and include every envelope recipient, using UNKNOWN for unavailable facts.
	 * RCPT replies describe their commands, independently of final message acceptance.
	 */
	@NotNull
	public MailTransportResult withRecipientResults(@NotNull final List<MailRecipientResult> recipients,
			@NotNull final MailRetryDisposition retryDisposition) {
		return new MailTransportResult(status, smtpResponse, acceptedRecipients, validUnsentRecipients, invalidRecipients,
				failure, requireNonNull(recipients, "recipients"), retryDisposition, true);
	}

	/**
	 * Completes legacy adapter results in original envelope order. Existing detailed snapshots are retained unchanged;
	 * missing SMTP details remain absent. Duplicate mailbox occurrences are matched individually.
	 */
	@NotNull
	public MailTransportResult withEnvelopeRecipients(@NotNull final Address[] envelopeRecipients) {
		return envelopeRecipientsResolved ? this : withRecipientResults(describeEnvelopeRecipients(envelopeRecipients), retryDisposition);
	}

	@NotNull
	private List<MailRecipientResult> describeEnvelopeRecipients(@NotNull final Address[] envelopeRecipients) {
		final List<MailRecipientResult> remaining = new ArrayList<>(recipientResults);
		final List<MailRecipientResult> results = new ArrayList<>();
		for (final Address address : envelopeRecipients) {
			results.add(describeRecipient(address, consumeRecipientDisposition(remaining, address)));
		}
		return results;
	}

	@NotNull
	private static MailRecipientDisposition consumeRecipientDisposition(final List<MailRecipientResult> recipients, final Address address) {
		final String mailbox = address instanceof InternetAddress ? ((InternetAddress) address).getAddress() : address.toString();
		for (int index = 0; index < recipients.size(); index++) {
			final MailRecipientResult candidate = recipients.get(index);
			if (Objects.equals(mailbox, candidate.getEnvelopeAddress().orElse(candidate.getOriginalAddress()))) {
				return recipients.remove(index).getDisposition();
			}
		}
		return MailRecipientDisposition.UNKNOWN;
	}

	private static void appendRecipients(final List<MailRecipientResult> recipients, @Nullable final Address[] addresses,
			final MailRecipientDisposition disposition) {
		if (addresses != null) {
			for (final Address address : addresses) {
				recipients.add(describeRecipient(address, disposition));
			}
		}
	}

	@NotNull
	private static MailRecipientResult describeRecipient(final Address address, final MailRecipientDisposition disposition) {
		return new MailRecipientResult(address.toString(), address instanceof InternetAddress
				? ((InternetAddress) address).getAddress() : null, disposition, null, null);
	}

	/** @return Immutable recipient facts, in envelope order when the adapter or caller supplied that envelope. */
	@NotNull
	public List<MailRecipientResult> getRecipientResults() {
		return recipientResults;
	}

	/** @return Advice based only on this attempt's facts; adapters never retry automatically. */
	@NotNull
	public MailRetryDisposition getRetryDisposition() {
		return retryDisposition;
	}

	/**
	 * Records a transport call that returned normally and accepted every submitted recipient.
	 * The recipient array is copied so adapters may safely reuse their own transport data.
	 */
	@NotNull
	public static MailTransportResult accepted(@NotNull final Address[] acceptedRecipients,
			@Nullable final SmtpServerResponse smtpResponse) {
		return fromRecipientGroups(MailSubmissionStatus.ACCEPTED, smtpResponse,
				requireNonNull(acceptedRecipients, "acceptedRecipients"), null, null, null);
	}

	/**
	 * Records a transport call that returned normally but cannot report server acceptance, such as an adapter around an opaque external service.
	 */
	@NotNull
	public static MailTransportResult unknown(@Nullable final SmtpServerResponse smtpResponse) {
		return fromRecipientGroups(MailSubmissionStatus.UNKNOWN, smtpResponse, null, null, null, null);
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
		return fromRecipientGroups(status, smtpResponse, acceptedRecipients, validUnsentRecipients, invalidRecipients,
				requireNonNull(failure, "failure"));
	}

	/**
	 * Records a failed transport call for which final server acceptance is unknown, while retaining recipient facts that remain certain.
	 * <p>
	 * This is appropriate when the transport loses the final submission response after transferring message data. Recipients whose acceptance is
	 * ambiguous must not be included in either recipient array.
	 */
	@NotNull
	public static MailTransportResult failedWithUnknownAcceptance(@NotNull final MessagingException failure,
			@Nullable final Address[] knownValidUnsentRecipients,
			@Nullable final Address[] knownInvalidRecipients) {
		return fromRecipientGroups(MailSubmissionStatus.UNKNOWN, null, null,
				knownValidUnsentRecipients, knownInvalidRecipients, requireNonNull(failure, "failure"));
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
