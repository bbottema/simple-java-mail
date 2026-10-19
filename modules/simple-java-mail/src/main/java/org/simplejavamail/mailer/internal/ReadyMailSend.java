package org.simplejavamail.mailer.internal;

import jakarta.mail.MessagingException;
import jakarta.mail.Address;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.internal.batchsupport.SelectedPoolTransport;
import org.simplejavamail.api.internal.batchsupport.SendingAllowance;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.MailSendCleanupException;
import org.simplejavamail.internal.util.concurrent.MailSendControl;
import org.simplejavamail.mailer.internal.util.AcquiredMailTransport;

import java.util.List;
import java.util.function.Supplier;

/** One governed email and its reserved destination resources. Submission happens only after the connection route has returned this owner. */
final class ReadyMailSend extends RoutedConnectionScope {

	private final MailSendDiagnosticsRecorder diagnostics;
	private final MailSendControl control;
	private Email email;
	@Nullable private SendingAllowance.Reservation reservation;
	@Nullable private SelectedPoolTransport selected;

	private ReadyMailSend(final MailerImpl initiator, final MailerImpl destination, final List<MailerImpl> stages,
			final MailSendDiagnosticsRecorder diagnostics, final MailSendControl control) {
		super(initiator, destination, stages, diagnostics);
		this.diagnostics = diagnostics;
		this.control = control;
	}

	static ReadyMailSend prepare(final MailerImpl initiator, final MailerImpl destination, @Nullable final SelectedPoolTransport selected,
			final List<MailerImpl> stages, final Email original, final MailSendAttempt attempt, final MailSendControl control) {
		attempt.preparationStarted();
		attempt.selectedSendingMode(destination.getOperationalConfig().isTransportModeLoggingOnly());
		final ReadyMailSend ready = new ReadyMailSend(initiator, destination, stages, attempt.diagnostics(), control);
		ready.selected = selected;
		try {
			destination.validateSelectedDestination(control.isDeadlineEnabled());
			ready.email = initiator.prepareSelectedEmail(original, destination, stages);
			control.checkStopped();
			attempt.prepared(ready.email);
			ready.reserveAllowance(selected);
			return ready;
		} catch (RuntimeException | Error failure) {
			ready.closeAfterFailure(failure);
			throw failure;
		}
	}

	private void reserveAllowance(@Nullable final SelectedPoolTransport selected) {
		if (selectedDestination.getOperationalConfig().isTransportModeLoggingOnly()) {
			diagnostics.useLoggingOnly();
			return;
		}
		try {
			reservation = EmailSendingAllowance.reserve(selected == null ? selectedDestination.sendingAllowance() : selected.getSendingAllowance(),
					selectedDestination.getSession(), email, control, diagnostics);
			if (selectedDestination.getOperationalConfig().getCustomMailer() != null) {
				diagnostics.useCustomMailer();
			}
		} catch (MessagingException failure) {
			throw new MailerException("Unable to resolve the delivery recipients before waiting for sending allowance. "
					+ "Check the email's recipient addresses and any custom To, Cc or Bcc headers; no message was submitted.", failure);
		}
	}

	MailSubmissionReceipt sendAndClose() {
		final MailSubmissionReceipt receipt;
		try {
			receipt = send();
		} catch (RuntimeException | Error failure) {
			// Suppress the original cleanup failure, without replacing the exception that actually ended the send.
			closeAfterFailure(failure);
			throw failure;
		}
		try {
			close();
		} catch (MessagingException | RuntimeException failure) {
			throw new MailSendCleanupException("The email was submitted, but finishing its connection cleanup failed. "
					+ "Check the cause and submission receipt before deciding whether to retry.", failure, receipt);
		}
		return receipt;
	}

	boolean supportsAutomaticRetry() {
		return selectedDestination.usesSmtpConnection() && selectedDestination.getOperationalConfig().getMailSendRetryPolicy().isRetryEnabled();
	}

	MailSendRetryingSubmission retryingSubmission(final MailSendReplayBudget budget) {
		return new MailSendRetryingSubmission(selectedDestination, email, control, diagnostics, budget, new MailSendRetryingSubmission.Connection() {
			@Override public <T> T withinOwners(final Supplier<T> work) { return executeWithinOwners(work); }
			@Override public AcquiredMailTransport current() { return transport(); }
			@Override public SendingAllowance.Reservation reserve(final Address[] recipients) throws MessagingException {
				if (reservation != null) {
					final SendingAllowance.Reservation initial = reservation;
					reservation = null;
					return initial;
				}
				return EmailSendingAllowance.reserve(selected == null ? selectedDestination.sendingAllowance() : selected.getSendingAllowance(),
						recipients, control, diagnostics);
			}
			@Override public void reacquire(final Address[] recipients) {
				// Retry allowance may wait. Reserve it before borrowing another connection, just as the initial routed attempt does.
				try { reservation = reserve(recipients); }
				catch (MessagingException failure) { throw new MailerException("Couldn't reserve sending allowance for this email's retry.", failure); }
				acquireConnection(selected, control, selectedDestination.connectionObserver());
			}
			@Override public void releaseAttempt() throws MessagingException { closeAttemptResources(); }
			@Override public void close() throws MessagingException { ReadyMailSend.this.close(); }
		});
	}

	private MailSubmissionReceipt send() {
		requireOpenScope();
		return executeWithinOwners(() -> {
			try {
				final SendMailClosure closure = new SendMailClosure(selectedDestination.getOperationalConfig(), selectedDestination.getSession(), email,
						control, diagnostics, selectedDestination.usesSmtpConnection() ? transport() : null, reservation);
				closure.run();
				return closure.getReceipt();
			} catch (RuntimeException | Error failure) {
				diagnostics.failed();
				throw failure;
			}
		});
	}

	@Override
	void releaseReservation() {
		if (reservation != null) {
			final SendingAllowance.Reservation released = reservation;
			reservation = null;
			released.close();
		}
	}
}
