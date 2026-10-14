package org.simplejavamail.mailer.internal;

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.internal.batchsupport.SendingAllowance;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.internal.util.concurrent.MailSendControl;
import org.simplejavamail.mailer.internal.util.AcquiredMailTransport;

import java.util.function.Supplier;

/** Retries inside an existing dedicated batch/callback scope without introducing pool selection or taking its connection ownership. */
final class RetainedMailSendRetry {

	private RetainedMailSendRetry() { }

	/** SMTP 421 closes the connection. A retained scope cannot fix that by replacing its deliberately shared transport. */
	static boolean connectionCanRemainInScope(final MailSubmissionReceipt receipt) {
		return receipt.getSmtpResponse().map(response -> response.getReturnCode() != 421).orElse(true);
	}

	static MailSubmissionReceipt send(final Session session, final Transport transport, final Email email,
			final SendingAllowance allowance, final MailSendControl control, final MailSendDiagnosticsRecorder diagnostics) {
		final MailerImpl owner = MailerImpl.selectedOwner(session);
		final AcquiredMailTransport borrowed = AcquiredMailTransport.borrowedForSending(session, transport, control);
		final MailSendRetryingSubmission.Connection connection = new MailSendRetryingSubmission.Connection() {
			@Override public <T> T withinOwners(final Supplier<T> work) { return owner.withinDelegatedExecution(work); }
			@Override public AcquiredMailTransport current() { return borrowed; }
			@Override public SendingAllowance.Reservation reserve(final Address[] recipients) throws MessagingException {
				return EmailSendingAllowance.reserve(allowance, recipients, control, diagnostics);
			}
			@Override public void reacquire(final Address[] recipients) {
				// The provider's normal send preflight checks this same connection. Angus isConnected() would issue an extra NOOP here.
			}
			@Override public boolean canRetryAfter(final MailSubmissionReceipt receipt) { return connectionCanRemainInScope(receipt); }
			@Override public void releaseAttempt() { /* The outer dedicated scope retains its transport and proxy. */ }
			@Override public void close() { /* Closing this email must not close the enclosing batch/callback. */ }
		};
		try (MailSendRetryingSubmission retry = new MailSendRetryingSubmission(owner, email, control, diagnostics, owner.replayBudget(), connection)) {
			return retry.completeOnCurrentThread();
		}
	}
}
