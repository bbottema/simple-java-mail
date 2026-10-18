package org.simplejavamail.api.mailer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

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
 * @see MailSender#sendMail(org.simplejavamail.api.email.Email)
 */
public interface MailSubmissionReceipt extends Serializable {

	/**
	 * @return Immutable results in envelope order.
	 */
	@NotNull
	List<MailRecipientResult> getRecipientResults();

	/**
	 * @return Conservative guidance for the original envelope. UNKNOWN failed attempts can carry duplicate risk even
	 * after positive RCPT replies. This does not perform retries, select backoff or guarantee exactly-once submission.
	 */
	@NotNull
	MailRetryDisposition getRetryDisposition();

	/**
	 * @return The known unsubmitted recipients for {@link MailRetryDisposition#SAFE_TO_RETRY_ALL} or
	 * {@link MailRetryDisposition#SAFE_TO_RETRY_UNACCEPTED}, excluding permanent RCPT rejections.
	 * Empty for all other dispositions, including permanent message rejection and ambiguous acceptance.
	 */
	@NotNull
	List<MailRecipientResult> getRetryableRecipients();

	/**
	 * @return The effective Message-ID for the submitted email, or {@code null} if no message id was produced by the sending path.
	 */
	@Nullable
	String getEmailId();

	/**
	 * Returns the unencoded SMTP envelope identifier (ENVID) used for this submission. A later DSN can return it as Original-Envelope-ID;
	 * applications receiving those notifications can match it to this receipt. It identifies a send attempt, not the MIME message.
	 * <p>
	 * The bundled Angus adapter generates a fresh UUID when the actual connection supports DSN, unless the Email fixes an identifier.
	 * A failed SMTP submission can still have an identifier: its presence does not imply server acceptance or guarantee a later notification.
	 *
	 * @return The effective identifier, or {@code null} when unavailable, including unsupported DSN, local pre-submission checks, logging-only,
	 * CustomMailer and adapters that do not report it. Unvalidated raw MAIL extensions are not reported here.
	 * @see org.simplejavamail.api.email.EmailPopulatingBuilder#fixingEnvelopeId(String)
	 */
	@Nullable
	String getEnvelopeId();

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
	boolean isRequireTlsUsed();

	/**
	 * Returns the complete SMTP content size prepared for this attempt, in bytes. This is not a count of bytes successfully transferred:
	 * a locally rejected oversized email can still have a measured size. The count describes the content after any provider conversion,
	 * including SMTP line endings but excluding dot-stuffing and the DATA terminator. Availability depends on the selected transport adapter.
	 * <p>
	 * This can differ from {@link MailRehearsal#getEncodedSize()}: rehearsal renders EML without a connected provider, while sending can omit
	 * headers normally excluded from submission and apply negotiated conversion. It is unrelated to final delivery or the server's later storage usage.
	 *
	 * @return The measured size, or {@code null} when unavailable, including incomplete measurement, logging-only, CustomMailer, adapters that
	 * do not report it. No estimate or partial byte count is substituted.
	 */
	@Nullable
	Long getMessageSize();

	/**
	 * Returns the positive fixed maximum advertised by the actual SMTP connection used for this attempt, in bytes. Only that connection's
	 * current advertisements apply, including any replacement after STARTTLS; a separate connection probe may describe a different server.
	 * Being within this limit does not guarantee server acceptance or final delivery.
	 *
	 * @return The server maximum, or {@code null} when unavailable. Missing SIZE, no numeric limit, zero (no fixed maximum), malformed,
	 * overflowing or contradictory advertisements do not establish a usable maximum. Also absent when the adapter cannot report this fact.
	 */
	@Nullable
	Long getServerMaximumMessageSize();

	/**
	 * @return The server response if the selected transport provider exposed one.
	 */
	@NotNull
	Optional<SmtpServerResponse> getSmtpResponse();

	/**
	 * @return {@code true} when at least one recipient is known to have been accepted for submission.
	 */
	boolean isAcceptedByServer();

	/**
	 * @return Whether this send path could determine server acceptance. If false, some recipients may still have been accepted.
	 */
	boolean hasServerAcceptanceInformation();

	/**
	 * @return The provider-neutral submission status.
	 */
	@NotNull
	MailSubmissionStatus getStatus();

	/**
	 * @return Immutable mailbox addresses known to have been accepted for submission.
	 */
	@NotNull
	List<String> getAcceptedRecipients();

	/**
	 * @return Immutable mailbox addresses that were valid but were not submitted.
	 */
	@NotNull
	List<String> getValidUnsentRecipients();

	/**
	 * @return Immutable mailbox addresses rejected as invalid by the transport.
	 */
	@NotNull
	List<String> getInvalidRecipients();

	/**
	 * @return The timestamp captured after the send attempt completed or failed.
	 */
	@NotNull
	Instant getSubmittedAt();
}
