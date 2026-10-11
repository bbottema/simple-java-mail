package org.simplejavamail.mailer.internal;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.internal.batchsupport.SelectedPoolTransport;
import org.simplejavamail.api.internal.batchsupport.SendingAllowance;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.internal.util.MailTransportLifecycleResolver;
import org.simplejavamail.internal.util.concurrent.MailSendControl;
import org.simplejavamail.mailer.internal.util.AcquiredMailTransport;
import org.simplejavamail.mailer.internal.util.TransportRunner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A deliberately retained batch/open connection and all participating Mailer lifetimes. No per-email observer owns this setup cost.
 * Sending and cleanup stay on the owning execution thread; ScopedMailSender guards access from application callbacks.
 */
final class RoutedMailConnection implements AutoCloseable {

	final MailerImpl selectedDestination;
	final List<MailerImpl> routeStages;
	private final MailerImpl initiator;
	private final List<MailSendOperations.Scope> delegatedScopes = new ArrayList<>();
	private final List<MailerImpl> executionOwners = new ArrayList<>();
	private final SendingAllowance allowance;
	@Nullable private SmtpProxyScope proxyScope;
	@Nullable private AcquiredMailTransport acquiredTransport;
	private boolean scopeClosed;

	RoutedMailConnection(final MailerImpl initiator, final MailerImpl destination, @Nullable final SelectedPoolTransport selected,
			final List<MailerImpl> stages, final MailSendControl control) {
		this.initiator = initiator;
		this.selectedDestination = destination;
		this.routeStages = List.copyOf(stages);
		this.allowance = selected == null ? destination.sendingAllowance() : selected.getSendingAllowance();
		try {
			openSelectedConnection(selected, control);
		} catch (MessagingException | RuntimeException | Error failure) {
			closeAfterFailedOpening(failure);
			rethrowOpeningFailure(failure);
		}
	}

	private void openSelectedConnection(@Nullable final SelectedPoolTransport selected, final MailSendControl control) throws MessagingException {
		retainParticipatingMailerScopes();
		selectedDestination.validateSelectedDestination(control.isDeadlineEnabled());
		if (selectedDestination.usesSmtpConnection()) {
			proxyScope = selectedDestination.openProxy();
			acquiredTransport = AcquiredMailTransport.acquire(selectedDestination.getSession(), selected, control, selectedDestination.connectionObserver());
		}
	}

	private void closeAfterFailedOpening(final Throwable openingFailure) {
		try {
			close();
		} catch (RuntimeException | Error cleanupFailure) {
			openingFailure.addSuppressed(cleanupFailure);
		}
	}

	private static void rethrowOpeningFailure(final Throwable openingFailure) {
		if (openingFailure instanceof Error) {
			throw (Error) openingFailure;
		}
		if (openingFailure instanceof RuntimeException) {
			throw (RuntimeException) openingFailure;
		}
		throw new MailerException("Couldn't prepare the selected SMTP connection; no email was submitted.", (Exception) openingFailure);
	}

	private void retainParticipatingMailerScopes() {
		final Set<MailerImpl> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		final List<MailerImpl> participants = new ArrayList<>(routeStages);
		participants.add(selectedDestination);
		for (MailerImpl participant : participants) {
			if (seen.add(participant)) {
				executionOwners.add(participant);
				if (participant != initiator) {
					delegatedScopes.add(participant.retainDelegatedScope());
				}
			}
		}
	}

	<T> T executeWithinOwners(final Supplier<T> operation) {
		return executeWithinOwner(0, operation);
	}

	private <T> T executeWithinOwner(final int ownerIndex, final Supplier<T> operation) {
		return ownerIndex == executionOwners.size()
				? operation.get()
				: executionOwners.get(ownerIndex).withinDelegatedExecution(() -> executeWithinOwner(ownerIndex + 1, operation));
	}

	MailSubmissionReceipt send(final Email original, final MailSendAttempt attempt, final MailSendControl control) {
		if (scopeClosed) {
			throw new IllegalStateException("This open-connection sender's scope has ended; send through the Mailer or open a new scope.");
		}
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
		try (SendingAllowance.Reservation reservation = EmailSendingAllowance.reserve(allowance, selectedDestination.getSession(), prepared, control, diagnostics)) {
			if (selectedDestination.getOperationalConfig().getCustomMailer() != null) {
				return sendUsingCustomMailer(prepared, control, diagnostics, reservation);
			}
			// The retained connection stays open, but cancellation belongs to this email rather than the scope-opening attempt.
			try (MailSendControl.Registration ignored = MailTransportLifecycleResolver.registerAbort(acquiredTransport.getTransport(), control)) {
				return acquiredTransport.send(prepared, diagnostics, reservation, control);
			}
		}
	}

	private MailSubmissionReceipt sendUsingCustomMailer(final Email prepared, final MailSendControl control, final MailSendDiagnosticsRecorder diagnostics,
			final SendingAllowance.Reservation reservation) throws MessagingException {
		diagnostics.useCustomMailer();
		diagnostics.startMimePreparation();
		final MimeMessage message = SessionBasedEmailToMimeMessageConverter.convertAndLogMimeMessage(selectedDestination.getSession(), prepared);
		control.checkStopped();
		diagnostics.startSubmission();
		reservation.commit(control);
		selectedDestination.getOperationalConfig().getCustomMailer()
				.sendMessage(selectedDestination.getOperationalConfig(), selectedDestination.getSession(), prepared, message);
		final MailSubmissionReceipt result = TransportRunner.buildReceipt(prepared, null);
		diagnostics.receiptProduced(result);
		return result;
	}

	@Override
	public void close() {
		if (scopeClosed) {
			return;
		}
		scopeClosed = true;
		Throwable primaryFailure = null;
		try {
			if (acquiredTransport != null) {
				acquiredTransport.close();
			}
		} catch (MessagingException | RuntimeException | Error failure) {
			primaryFailure = failure;
		} finally {
			try {
				if (proxyScope != null) {
					proxyScope.close();
				}
			} catch (RuntimeException | Error cleanupFailure) {
				if (primaryFailure == null) {
					primaryFailure = cleanupFailure;
				} else {
					primaryFailure.addSuppressed(cleanupFailure);
				}
			} finally {
				for (int index = delegatedScopes.size() - 1; index >= 0; index--) {
					delegatedScopes.get(index).close();
				}
			}
		}
		if (primaryFailure instanceof Error) {
			throw (Error) primaryFailure;
		} else if (primaryFailure instanceof RuntimeException) {
			throw (RuntimeException) primaryFailure;
		} else if (primaryFailure != null) {
			throw new MailerException("Couldn't close the selected shared SMTP connection. Already submitted emails were not retried.", (Exception) primaryFailure);
		}
	}
}
