package org.simplejavamail.api.mailer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.InvalidObjectException;
import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Immutable provider-neutral facts for one message submission attempt.
 * <p>
 * This represents SMTP submission acceptance, not final mailbox delivery. Final delivery remains asynchronous and should be tracked through delivery
 * status notifications, read receipts, bounces, or provider-specific webhooks.
 * <p>
 * A successful {@code mailer.sync().sendMail(...)} call returns this type; asynchronous send completion carries the same receipt.
 * When submission fails or only some recipients were accepted,
 * {@link MailSubmissionException#getSubmissionReceipt()} exposes the same facts together with the original Jakarta Mail failure.
 *
 * @see Mailer.Sync#sendMail(org.simplejavamail.api.email.Email)
 * @see Mailer.Async#sendMail(org.simplejavamail.api.email.Email)
 * @see MailSender#sendMailAndGetReceipt(org.simplejavamail.api.email.Email)
 */
public final class MailSubmissionReceipt implements Serializable {

	private static final long serialVersionUID = 1L;

	@Nullable private final String emailId;
	@Nullable private final String envelopeId;
	private final boolean requireTlsUsed;
	@Nullable private final SmtpServerResponse smtpResponse;
	@NotNull private final Instant submittedAt;
	@NotNull private final MailSubmissionStatus status;
	@NotNull private final List<String> acceptedRecipients;
	@NotNull private final List<String> validUnsentRecipients;
	@NotNull private final List<String> invalidRecipients;
	@NotNull private final List<MailRecipientResult> recipientResults;
	@NotNull private final MailRetryDisposition retryDisposition;
	@NotNull private final transient List<MailRecipientResult> retryableRecipients;

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
		this(emailId, smtpResponse, submittedAt, status,
				legacyRecipientResults(acceptedRecipients, validUnsentRecipients, invalidRecipients), legacyRetryDisposition(status));
	}

	/**
	 * Creates a receipt with one immutable result per envelope recipient, in original envelope order.
	 * Compatibility recipient lists are derived from these same results. The supplied retry disposition must be based
	 * on the provider's transaction facts, including whether final acceptance could be determined.
	 */
	public MailSubmissionReceipt(@Nullable final String emailId, @Nullable final SmtpServerResponse smtpResponse,
			@NotNull final Instant submittedAt, @NotNull final MailSubmissionStatus status,
			@NotNull final List<MailRecipientResult> recipientResults, @NotNull final MailRetryDisposition retryDisposition) {
		this(emailId, smtpResponse, submittedAt, status, recipientResults, retryDisposition, null);
	}

	/**
	 * Creates a receipt retaining the transport's effective SMTP envelope identifier alongside its submission facts.
	 *
	 * @param envelopeId Unencoded ENVID used for the submission, or {@code null} when none was used or reported.
	 * @see #getEnvelopeId()
	 * @see #MailSubmissionReceipt(String, SmtpServerResponse, Instant, MailSubmissionStatus, List, MailRetryDisposition)
	 */
	public MailSubmissionReceipt(@Nullable final String emailId, @Nullable final SmtpServerResponse smtpResponse,
			@NotNull final Instant submittedAt, @NotNull final MailSubmissionStatus status,
			@NotNull final List<MailRecipientResult> recipientResults, @NotNull final MailRetryDisposition retryDisposition,
			@Nullable final String envelopeId) {
		this(emailId, smtpResponse, submittedAt, status, recipientResults, retryDisposition, envelopeId, false);
	}

	/**
	 * Creates a receipt retaining SMTP envelope facts reported by the transport adapter.
	 *
	 * @param emailId The effective Message-ID, if one was produced.
	 * @param smtpResponse The response exposed by the selected provider, if any.
	 * @param submittedAt The time captured after the attempt completed or failed.
	 * @param status The provider-neutral acceptance status for this attempt.
	 * @param recipientResults One immutable result per envelope recipient, in original envelope order.
	 * @param retryDisposition Conservative retry guidance derived from the same transport attempt.
	 * @param envelopeId Unencoded ENVID used for the submission, or {@code null} when none was used or reported.
	 * @param requireTlsUsed Whether RFC 8689 REQUIRETLS was actually supplied to MAIL FROM.
	 * @see #isRequireTlsUsed()
	 */
	public MailSubmissionReceipt(@Nullable final String emailId, @Nullable final SmtpServerResponse smtpResponse,
			@NotNull final Instant submittedAt, @NotNull final MailSubmissionStatus status,
			@NotNull final List<MailRecipientResult> recipientResults, @NotNull final MailRetryDisposition retryDisposition,
			@Nullable final String envelopeId, final boolean requireTlsUsed) {
		this.emailId = emailId;
		this.envelopeId = envelopeId;
		this.requireTlsUsed = requireTlsUsed;
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
	private static List<MailRecipientResult> legacyRecipientResults(final List<String> accepted, final List<String> unsent,
			final List<String> invalid) {
		final List<MailRecipientResult> results = new ArrayList<>();
		appendLegacyRecipients(results, requireNonNull(accepted, "acceptedRecipients"), MailRecipientDisposition.ACCEPTED);
		appendLegacyRecipients(results, requireNonNull(unsent, "validUnsentRecipients"), MailRecipientDisposition.VALID_UNSENT);
		appendLegacyRecipients(results, requireNonNull(invalid, "invalidRecipients"), MailRecipientDisposition.INVALID);
		return results;
	}

	private static void appendLegacyRecipients(final List<MailRecipientResult> results, final List<String> addresses,
			final MailRecipientDisposition disposition) {
		for (final String address : addresses) {
			results.add(new MailRecipientResult(address, address, disposition, null, null));
		}
	}

	@NotNull
	private static MailRetryDisposition legacyRetryDisposition(final MailSubmissionStatus status) {
		return status == MailSubmissionStatus.ACCEPTED ? MailRetryDisposition.DO_NOT_RETRY : MailRetryDisposition.CALLER_POLICY_REQUIRED;
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

	@NotNull
	private static MailSubmissionStatus statusFromResponse(@Nullable final SmtpServerResponse smtpResponse) {
		if (smtpResponse == null) {
			return MailSubmissionStatus.UNKNOWN;
		}
		return smtpResponse.isPositiveCompletionReply() ? MailSubmissionStatus.ACCEPTED : MailSubmissionStatus.REJECTED;
	}

	/** Older streams leave newer fields null; restore one complete snapshot before callers can observe it. */
	@NotNull
	private Object readResolve() throws InvalidObjectException {
		try {
			final MailSubmissionStatus restoredStatus = status == null ? statusFromResponse(smtpResponse) : status;
			final List<MailRecipientResult> restoredRecipients = recipientResults != null ? recipientResults : legacyRecipientResults(
					acceptedRecipients == null ? Collections.emptyList() : acceptedRecipients,
					validUnsentRecipients == null ? Collections.emptyList() : validUnsentRecipients,
					invalidRecipients == null ? Collections.emptyList() : invalidRecipients);
			return new MailSubmissionReceipt(emailId, smtpResponse, submittedAt, restoredStatus, restoredRecipients,
					retryDisposition == null ? legacyRetryDisposition(restoredStatus) : retryDisposition, envelopeId, requireTlsUsed);
		} catch (final RuntimeException failure) {
			final InvalidObjectException invalidReceipt = new InvalidObjectException("Invalid serialized mail submission receipt");
			invalidReceipt.initCause(failure);
			throw invalidReceipt;
		}
	}

	/**
	 * @return Immutable results in envelope order. Legacy constructors/serialized receipts retain their known groups in
	 * accepted, unsent, invalid order because the original envelope and RCPT details were not recorded.
	 */
	@NotNull
	public List<MailRecipientResult> getRecipientResults() {
		return recipientResults;
	}

	/**
	 * @return Conservative guidance for the original envelope. UNKNOWN failed attempts can carry duplicate risk even
	 * after positive RCPT replies. This does not perform retries, select backoff or guarantee exactly-once submission.
	 */
	@NotNull
	public MailRetryDisposition getRetryDisposition() {
		return retryDisposition;
	}

	/**
	 * @return The known unsubmitted recipients for a SAFE_TO_RETRY disposition, excluding permanent RCPT rejections.
	 * Empty for all other dispositions, including permanent message rejection and ambiguous acceptance.
	 */
	@NotNull
	public List<MailRecipientResult> getRetryableRecipients() {
		return retryableRecipients;
	}

	/**
	 * @return The effective Message-ID for the submitted email, or {@code null} if no message id was produced by the sending path.
	 */
	@Nullable
	public String getEmailId() {
		return emailId;
	}

	/**
	 * Returns the unencoded SMTP envelope identifier (ENVID) used for this submission. A later DSN can return it as Original-Envelope-ID;
	 * applications receiving those notifications can match it to this receipt. It identifies a send attempt, not the MIME message.
	 * <p>
	 * The bundled Angus adapter generates a fresh UUID when the actual connection supports DSN, unless the Email fixes an identifier.
	 * A failed SMTP submission can still have an identifier: its presence does not imply server acceptance or guarantee a later notification.
	 *
	 * @return The effective identifier, or {@code null} when unavailable, including unsupported DSN, local pre-submission checks, logging-only,
	 * CustomMailer/older adapters that do not report it, and legacy receipts. Unvalidated raw MAIL extensions are not reported here.
	 * @see org.simplejavamail.api.email.EmailPopulatingBuilder#fixingEnvelopeId(String)
	 */
	@Nullable
	public String getEnvelopeId() {
		return envelopeId;
	}

	/**
	 * Reports whether this attempt actually supplied RFC 8689 REQUIRETLS to the first server's MAIL FROM command. A failed MAIL FROM
	 * can therefore still return {@code true}. A {@code false} value means the send path could not confirm use, including logging-only,
	 * CustomMailer, older adapters and local failures before MAIL FROM.
	 * <p>
	 * This is not proof of server acceptance, later-relay compliance or final delivery.
	 *
	 * @return Whether REQUIRETLS was issued for this first-hop submission attempt.
	 * @see org.simplejavamail.api.email.EmailPopulatingBuilder#withTlsRequiredForOnwardDelivery()
	 */
	public boolean isRequireTlsUsed() {
		return requireTlsUsed;
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
		return status;
	}

	/**
	 * @return Immutable mailbox addresses known to have been accepted for submission.
	 */
	@NotNull
	public List<String> getAcceptedRecipients() {
		return acceptedRecipients;
	}

	/**
	 * @return Immutable mailbox addresses that were valid but were not submitted.
	 */
	@NotNull
	public List<String> getValidUnsentRecipients() {
		return validUnsentRecipients;
	}

	/**
	 * @return Immutable mailbox addresses rejected as invalid by the transport.
	 */
	@NotNull
	public List<String> getInvalidRecipients() {
		return invalidRecipients;
	}

	/**
	 * @return The timestamp captured after the send attempt completed or failed.
	 */
	@NotNull
	public Instant getSubmittedAt() {
		return submittedAt;
	}
}
