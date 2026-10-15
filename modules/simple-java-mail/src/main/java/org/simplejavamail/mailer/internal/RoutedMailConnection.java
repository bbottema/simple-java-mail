package org.simplejavamail.mailer.internal;

import jakarta.mail.MessagingException;
import jakarta.mail.Address;
import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.internal.batchsupport.SelectedPoolTransport;
import org.simplejavamail.api.internal.batchsupport.SendingAllowance;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.internal.util.MailTransportLifecycleResolver;
import org.simplejavamail.internal.util.concurrent.MailSendControl;
import org.simplejavamail.mailer.internal.util.TransportRunner;
import org.simplejavamail.mailer.internal.util.AcquiredMailTransport;

import java.util.List;
import java.util.function.Supplier;

/**
 * A deliberately retained batch/open connection and all participating Mailer lifetimes. No per-email observer owns this setup cost.
 * Sending and cleanup stay on the owning execution thread; ScopedMailSender guards access from application callbacks.
 */
final class RoutedMailConnection extends RoutedConnectionScope {

	private final MailerImpl initiator;
	private final SendingAllowance allowance;

	RoutedMailConnection(final MailerImpl initiator, final MailerImpl destination, @Nullable final SelectedPoolTransport selected,
			final List<MailerImpl> stages, final MailSendControl control) {
		super(initiator, destination, stages, MailSendDiagnosticsRecorder.unobserved());
		this.initiator = initiator;
		this.allowance = selected == null ? destination.sendingAllowance() : selected.getSendingAllowance();
		try {
			destination.validateSelectedDestination(control.isDeadlineEnabled());
		} catch (RuntimeException | Error failure) {
			closeAfterFailure(failure);
			throw failure;
		}
	}

	MailSubmissionReceipt send(final Email original, final MailSendAttempt attempt, final MailSendControl control) {
		requireOpenScope();
		return executeWithinOwners(() -> sendOnSelectedConnection(original, attempt, control));
	}

	private MailSubmissionReceipt sendOnSelectedConnection(final Email original, final MailSendAttempt attempt, final MailSendControl control) {
		attempt.started();
		attempt.selectedSendingMode(selectedDestination.getOperationalConfig().isTransportModeLoggingOnly());
		final MailSendDiagnosticsRecorder diagnostics = attempt.diagnostics();
		diagnostics.useSharedConnection();
		try {
			final Email prepared = prepareEmailForSelectedDestination(original, attempt, control);
			if (selectedDestination.getOperationalConfig().isTransportModeLoggingOnly()) {
				return sendLoggingOnly(prepared, control, diagnostics);
			}
			return sendWithSendingAllowance(prepared, control, diagnostics);
		} catch (MessagingException failure) {
			throw control.translateFailure(new MailerException("Couldn't send the email through the selected shared connection. Check the cause.", failure));
		} catch (RuntimeException failure) {
			throw control.translateFailure(failure);
		}
	}

	private Email prepareEmailForSelectedDestination(final Email original, final MailSendAttempt attempt, final MailSendControl control) {
		attempt.preparationStarted();
		final Email prepared = initiator.prepareSelectedEmail(original, selectedDestination, routeStages);
		attempt.prepared(prepared);
		control.checkStopped();
		return prepared;
	}

	private MailSubmissionReceipt sendLoggingOnly(final Email prepared, final MailSendControl control, final MailSendDiagnosticsRecorder diagnostics)
			throws MessagingException {
		diagnostics.useLoggingOnly();
		diagnostics.startMimePreparation();
		SessionBasedEmailToMimeMessageConverter.convertAndLogMimeMessage(selectedDestination.getSession(), prepared);
		control.checkStopped();
		final MailSubmissionReceipt result = TransportRunner.buildReceipt(prepared, null);
		diagnostics.receiptProduced(result);
		return result;
	}

	private MailSubmissionReceipt sendWithSendingAllowance(final Email prepared, final MailSendControl control,
			final MailSendDiagnosticsRecorder diagnostics) throws MessagingException {
		if (selectedDestination.usesSmtpConnection() && selectedDestination.getOperationalConfig().getMailSendRetryPolicy().isRetryEnabled()) {
			return sendWithRetries(prepared, control, diagnostics);
		}
		try (SendingAllowance.Reservation reservation = EmailSendingAllowance.reserve(allowance, selectedDestination.getSession(), prepared, control, diagnostics)) {
			if (selectedDestination.getOperationalConfig().getCustomMailer() != null) {
				return sendUsingCustomMailer(prepared, control, diagnostics, reservation);
			}
			// The retained connection stays open, but cancellation belongs to this email rather than the scope-opening attempt.
			try (MailSendControl.Registration ignored = MailTransportLifecycleResolver.registerAbort(transport().getTransport(), control)) {
				return transport().send(prepared, diagnostics, reservation, control);
			}
		}
	}

	private MailSubmissionReceipt sendWithRetries(final Email prepared, final MailSendControl control,
			final MailSendDiagnosticsRecorder diagnostics) {
		final MailSendRetryingSubmission.Connection retained = new MailSendRetryingSubmission.Connection() {
			@Override public <T> T withinOwners(final Supplier<T> work) { return executeWithinOwners(work); }
			@Override public AcquiredMailTransport current() { return transport(); }
			@Override public SendingAllowance.Reservation reserve(final Address[] recipients) throws MessagingException {
				return EmailSendingAllowance.reserve(allowance, recipients, control, diagnostics);
			}
			@Override public void reacquire(final Address[] recipients) {
				// Keep the retained transport. Its provider preflight must fail if closed; do not probe with isConnected() or reconnect it.
			}
			@Override public boolean canRetryAfter(final MailSubmissionReceipt receipt) { return RetainedMailSendRetry.connectionCanRemainInScope(receipt); }
			@Override public void releaseAttempt() { /* The batch/callback, not this email, owns the connection. */ }
			@Override public void close() { /* The outer scope closes the connection once. */ }
		};
		try (MailSendRetryingSubmission retry = new MailSendRetryingSubmission(selectedDestination, prepared, control, diagnostics,
				initiator.replayBudget(), retained);
				MailSendControl.Registration ignored = MailTransportLifecycleResolver.registerAbort(transport().getTransport(), control)) {
			return retry.completeOnCurrentThread();
		}
	}

	private MailSubmissionReceipt sendUsingCustomMailer(final Email prepared, final MailSendControl control, final MailSendDiagnosticsRecorder diagnostics,
			final SendingAllowance.Reservation reservation) throws MessagingException {
		diagnostics.useCustomMailer();
		diagnostics.startMimePreparation();
		final MimeMessage message = SessionBasedEmailToMimeMessageConverter.convertAndLogMimeMessage(selectedDestination.getSession(), prepared);
		diagnostics.effectiveMessageId(prepared.getId());
		control.checkStopped();
		diagnostics.startSubmission();
		reservation.commit(control);
		diagnostics.submissionInvoked();
		selectedDestination.getOperationalConfig().getCustomMailer()
				.sendMessage(selectedDestination.getOperationalConfig(), selectedDestination.getSession(), prepared, message);
		final MailSubmissionReceipt result = TransportRunner.buildReceipt(prepared, null);
		diagnostics.receiptProduced(result);
		return result;
	}

	@Override
	public void close() {
		try {
			super.close();
		} catch (MessagingException failure) {
			throw new MailerException("Couldn't close the selected shared SMTP connection. Already submitted emails were not retried.", failure);
		}
	}
}
