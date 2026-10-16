package org.simplejavamail.mailer.internal;

import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.internal.batchsupport.SelectedPoolTransport;
import org.simplejavamail.api.mailer.MailSender;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.OpenConnectionCallback;
import org.simplejavamail.internal.util.concurrent.MailSendControl;
import org.simplejavamail.internal.telemetry.MailSendTelemetryBridge;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.simplejavamail.internal.util.Preconditions.verifyNonnull;

/** Adapts the same connection route to lazy batches and pinned application scopes; it never replays the current email or callback. */
final class FailoverSharedSends {

	private final MailerImpl initiatingMailer;
	private final MailSendOperations sendOperations;
	private final MailSendObserverNotifier observerNotifier;

	FailoverSharedSends(final MailerImpl initiator, final MailSendOperations operations, final MailSendObserverNotifier observer) {
		this.initiatingMailer = initiator;
		this.sendOperations = operations;
		this.observerNotifier = observer;
	}

	void sendBatch(final Iterable<Email> emails, final MailSendControl control) {
		control.checkStopped();
		final Iterator<Email> iterator = emails.iterator();
		if (!iterator.hasNext()) {
			return;
		}
		try (RetainedRoute route = new RetainedRoute()) {
			do {
				control.checkStopped();
				final RoutedMailConnection routedConnection = route.selectConnection(control);
				final Email email = verifyNonnull(iterator.next());
				routedConnection.executeWithinOwners(() -> {
					sendObservedEmail(routedConnection, email, control);
					return null;
				});
				// Observer handoff has finished. Only the untouched next email may move back to a recovered primary.
			} while (iterator.hasNext());
		}
	}

	/** A batch observer runs before the next email is selected, while its retained route remains alive on this execution stack. */
	private void sendObservedEmail(final RoutedMailConnection routedConnection, final Email email, final MailSendControl control) {
		final MailSendAttempt attempt = observerNotifier.beginAttempt(email, control);
		attempt.readyForAdmission();
		final MailSubmissionReceipt receipt;
		try (MailSendTelemetryBridge.Scope ignored = attempt.attachTelemetry()) {
			receipt = routedConnection.send(email, attempt, control);
		} catch (RuntimeException | Error failure) {
			attempt.completeWithFailure(failure);
			throw failure;
		}
		attempt.completeSuccessfully(receipt);
	}

	<E extends Exception> void withOpenConnection(final OpenConnectionCallback<E> callback) throws E {
		try (RetainedRoute route = new RetainedRoute()) {
			final MailSendOperation<RoutedMailConnection> openingOperation = sendOperations.begin(unused -> { }, failure -> { });
			final RoutedMailConnection routedConnection = openingOperation.executeSync(() -> route.selectConnection(openingOperation.control()));
			requireScopedSenderSupport(routedConnection);
			invokePinnedCallback(routedConnection, callback);
		}
	}

	private void requireScopedSenderSupport(final RoutedMailConnection connection) {
		if (connection.selectedDestination.getOperationalConfig().getCustomMailer() != null) {
			throw new MailerException("The selected failover destination uses CustomMailer, which owns its connections. "
					+ "Use ordinary sending or configure an SMTP Mailer for withOpenConnection; the callback was not invoked.");
		}
	}

	private <E extends Exception> void invokePinnedCallback(final RoutedMailConnection routedConnection, final OpenConnectionCallback<E> callback) throws E {
		final ScopedMailSender sender = new ScopedMailSender(new PinnedSender(routedConnection));
		try {
			routedConnection.executeWithinOwners(() -> {
				try {
					callback.accept(sender);
					return null;
				} catch (RuntimeException failure) {
					throw failure;
				} catch (Exception failure) {
					throw new CheckedCallbackFailure(failure);
				}
			});
		} catch (CheckedCallbackFailure failure) {
			@SuppressWarnings("unchecked") final E original = (E) failure.getCause();
			throw original;
		} finally {
			sender.endCallback();
		}
	}

	private final class PinnedSender implements MailSender {
		private final RoutedMailConnection connection;

		private PinnedSender(final RoutedMailConnection connection) {
			this.connection = connection;
		}

		@Override
		public void sendMail(final Email email) {
			sendMailAndGetReceipt(email);
		}

		@Override
		public MailSubmissionReceipt sendMailAndGetReceipt(final Email email) {
			final Email checked = verifyNonnull(email);
			final MailSendAttempt attempt = observerNotifier.beginAttempt(checked);
			final MailSendOperation<MailSubmissionReceipt> operation;
			try {
				operation = sendOperations.begin(attempt::completeSuccessfully, attempt::completeWithFailure);
			} catch (RuntimeException failure) {
				attempt.completeWithFailure(failure);
				throw failure;
			}
			attempt.attachControl(operation.control());
			operation.observeWith(attempt);
			attempt.readyForAdmission();
			return operation.executeSync(() -> connection.send(checked, attempt, operation.control()));
		}
	}

	private final class RetainedRoute implements AutoCloseable {
		@Nullable private RoutedMailConnection retainedConnection;
		private final Map<UUID, List<SelectedPoolTransport>> candidateGroups = new HashMap<>();

		RoutedMailConnection selectConnection(final MailSendControl control) {
			if (canReuseRetainedConnection()) {
				return retainedConnection;
			}
			final RoutedMailConnection selected = acquireConnectionForNextEmail(control);
			replaceRetainedConnection(selected);
			return retainedConnection;
		}

		/** A route that never left its initiating stage has no earlier destination to recheck before the next email. */
		private boolean canReuseRetainedConnection() {
			return retainedConnection != null && retainedConnection.routeStages.size() == 1;
		}

		private RoutedMailConnection acquireConnectionForNextEmail(final MailSendControl control) {
			final MailConnectionRoute<RoutedMailConnection> route = new MailConnectionRoute<>(initiatingMailer,
					MailSendDiagnosticsRecorder.unobserved(), control, createConnectionOpening(control), candidateGroups);
			return route.execute();
		}

		private MailConnectionRoute.DestinationPreparation<RoutedMailConnection> createConnectionOpening(final MailSendControl control) {
			return new MailConnectionRoute.DestinationPreparation<RoutedMailConnection>() {
				@Override
				public RoutedMailConnection prepare(final MailerImpl destination, final SelectedPoolTransport registration,
						final List<MailerImpl> routeStages) {
					return new RoutedMailConnection(initiatingMailer, destination, registration, routeStages, control);
				}

				@Override
				public RoutedMailConnection retainAtRouteStage(final MailerImpl stage) {
					return retainedConnection != null && retainedConnection.routeStages.get(retainedConnection.routeStages.size() - 1) == stage
							? retainedConnection : null;
				}
			};
		}

		private void replaceRetainedConnection(final RoutedMailConnection selected) {
			if (selected != retainedConnection) {
				final RoutedMailConnection previous = retainedConnection;
				// Retain ownership of the new connection even if closing the earlier destination fails.
				retainedConnection = selected;
				if (previous != null) {
					previous.close();
				}
			}
		}

		@Override
		public void close() {
			if (retainedConnection != null) {
				retainedConnection.close();
			}
		}
	}

	private static final class CheckedCallbackFailure extends RuntimeException {
		private static final long serialVersionUID = 1L;

		private CheckedCallbackFailure(final Exception cause) {
			super(cause);
		}
	}
}
