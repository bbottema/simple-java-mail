package org.simplejavamail.mailer.internal;

import org.simplejavamail.api.mailer.MailRecipientDisposition;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.MailSubmissionException;
import org.simplejavamail.api.mailer.MailSubmissionStatus;
import org.simplejavamail.api.mailer.MailRetryDisposition;
import org.simplejavamail.api.mailer.SmtpRecipientStatus;

/** Retries need positive evidence of a temporary refusal, not merely the absence of known acceptance. */
final class MailSendRetryEligibility {

	private MailSendRetryEligibility() { }

	static boolean isTemporaryWholeMessageRefusal(final MailSubmissionException failure) {
		final MailSubmissionReceipt receipt = failure.getSubmissionReceipt().orElseThrow();
		return !hasUnsafeSubmissionFacts(receipt)
				&& !hasContradictoryRecipientFacts(receipt)
				&& !hasNonTemporaryTransactionReply(receipt)
				&& hasTemporaryRefusalEvidence(receipt);
	}

	private static boolean hasUnsafeSubmissionFacts(final MailSubmissionReceipt receipt) {
		// SAFE_TO_RETRY_ALL establishes duplicate safety, not whether waiting can remedy the refusal. Require temporary evidence too.
		return receipt.getStatus() != MailSubmissionStatus.REJECTED || receipt.getRetryDisposition() != MailRetryDisposition.SAFE_TO_RETRY_ALL
				|| !receipt.getAcceptedRecipients().isEmpty() || !receipt.getInvalidRecipients().isEmpty();
	}

	private static boolean hasContradictoryRecipientFacts(final MailSubmissionReceipt receipt) {
		// Contrary recipient facts veto whole-envelope replay, even when the receipt's summary would otherwise allow it.
		return receipt.getRecipientResults().stream().anyMatch(recipient -> recipient.getDisposition() == MailRecipientDisposition.UNKNOWN
				|| recipient.getDisposition() == MailRecipientDisposition.ACCEPTED
				|| recipient.getRcptStatus() == SmtpRecipientStatus.PERMANENTLY_REJECTED);
	}

	private static boolean hasNonTemporaryTransactionReply(final MailSubmissionReceipt receipt) {
		final Integer code = receipt.getSmtpResponse().map(response -> response.getReturnCode()).orElse(null);
		return code != null && code / 100 != 4;
	}

	private static boolean hasTemporaryRefusalEvidence(final MailSubmissionReceipt receipt) {
		return receipt.getSmtpResponse().map(response -> response.getReturnCode() / 100 == 4).orElse(false)
				|| receipt.getRecipientResults().stream()
				.anyMatch(recipient -> recipient.getRcptStatus() == SmtpRecipientStatus.TEMPORARILY_REJECTED);
	}
}
