package org.simplejavamail.api.mailer;

/**
 * Provider-neutral knowledge about SMTP submission acceptance for one message attempt.
 * <p>
 * This describes acceptance for submission, not final mailbox delivery.
 */
public enum MailSubmissionStatus {

	/**
	 * The send path completed and reports that every submitted envelope recipient was accepted for submission.
	 */
	ACCEPTED,

	/**
	 * At least one envelope recipient was accepted for submission, but the attempt did not complete as a clean successful submission for every
	 * recipient. The recipient groups in the accompanying receipt contain the provider's more precise facts when available.
	 */
	PARTIALLY_ACCEPTED,

	/**
	 * The transport reports that no envelope recipient was accepted for submission.
	 */
	REJECTED,

	/**
	 * The send path cannot state whether any recipient was accepted.
	 * <p>
	 * Some or all recipients may already have been accepted. Applications should not automatically retry an unknown outcome unless their delivery
	 * design accounts for duplicate submission.
	 */
	UNKNOWN;

	/**
	 * @return {@code true} when at least one recipient is known to have been accepted for submission.
	 */
	public boolean isAcceptedForAtLeastOneRecipient() {
		return this == ACCEPTED || this == PARTIALLY_ACCEPTED;
	}

	/**
	 * @return {@code true} unless the send path could not determine server acceptance.
	 */
	public boolean isServerAcceptanceKnown() {
		return this != UNKNOWN;
	}
}
