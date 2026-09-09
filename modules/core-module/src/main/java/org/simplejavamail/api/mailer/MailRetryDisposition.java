package org.simplejavamail.api.mailer;

/**
 * Conservative guidance for retrying this attempt's envelope. No value triggers a retry or promises SMTP idempotency.
 * Applications still choose backoff, attempt limits and any changes needed to address a rejection.
 */
public enum MailRetryDisposition {
	/** No recipient was submitted and the known facts permit retrying the whole envelope. */
	SAFE_TO_RETRY_ALL,
	/** Retry only known unsubmitted recipients without permanent rejections; accepted recipients must be excluded. */
	SAFE_TO_RETRY_UNACCEPTED,
	/** Acceptance is ambiguous, or a retryable mailbox also appears as accepted; resending could duplicate the message. */
	DUPLICATE_RISK,
	/** The provider supplied too little information to recommend a retry policy. */
	CALLER_POLICY_REQUIRED,
	/** The message was accepted, or the known rejections require a change before resending. */
	DO_NOT_RETRY
}
