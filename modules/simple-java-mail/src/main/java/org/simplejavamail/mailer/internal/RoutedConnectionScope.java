package org.simplejavamail.mailer.internal;

import jakarta.mail.MessagingException;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.MailException;
import org.simplejavamail.api.internal.batchsupport.PoolConnectionObserver;
import org.simplejavamail.api.internal.batchsupport.SelectedPoolTransport;
import org.simplejavamail.internal.util.concurrent.MailSendControl;
import org.simplejavamail.mailer.internal.util.AcquiredMailTransport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Owns the selected connection, proxy and participating Mailer lifetimes. Routing may acquire this scope, but cannot submit through it.
 * Ordinary sends add a prepared email and reservation; retained scopes add their per-email work later. Execution confines this owner to one thread.
 */
abstract class RoutedConnectionScope implements AutoCloseable {

	final MailerImpl selectedDestination;
	final List<MailerImpl> routeStages;
	private final MailSendDiagnosticsRecorder diagnostics;
	private final List<MailSendOperations.Scope> delegatedScopes = new ArrayList<>();
	private final List<MailerImpl> executionOwners = new ArrayList<>();
	@Nullable private SmtpProxyScope proxyScope;
	@Nullable private AcquiredMailTransport acquiredTransport;
	private boolean scopeClosed;

	RoutedConnectionScope(final MailerImpl initiator, final MailerImpl destination, final List<MailerImpl> stages,
			final MailSendDiagnosticsRecorder diagnostics) {
		selectedDestination = destination;
		routeStages = List.copyOf(stages);
		this.diagnostics = diagnostics;
		try {
			retainParticipatingMailerScopes(initiator);
		} catch (RuntimeException | Error failure) {
			closeDelegatedScopes();
			throw failure;
		}
	}

	private void retainParticipatingMailerScopes(final MailerImpl initiator) {
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

	/** The route's retry boundary calls only this final method: no MIME conversion, provider invocation or application callback. */
	final void acquireConnection(@Nullable final SelectedPoolTransport selected, final MailSendControl control, final PoolConnectionObserver observer) {
		try {
			control.checkStopped();
			if (selectedDestination.usesSmtpConnection()) {
				diagnostics.startConnectionAcquisition();
				if (diagnostics.retainsConnectionHistory()) {
					diagnostics.beginConnectionStep(selectedDestination.definition().name, selectedDestination.logicalHost(), selectedDestination.logicalPort());
				}
				proxyScope = selectedDestination.openProxy();
				acquiredTransport = AcquiredMailTransport.acquire(selectedDestination.getSession(), selected, control, observer, diagnostics);
			}
		} catch (MessagingException | RuntimeException | Error failure) {
			closeAfterFailure(failure);
			if (failure instanceof Error) {
				throw (Error) failure;
			}
			// Preserve setup evidence and cancellation identities, but keep pool/provider setup errors in the public MailException family.
			throw failure instanceof MailException ? (MailException) failure
					: new MailerException("Couldn't acquire the selected SMTP connection; no email was submitted. Check the cause for details.", (Exception) failure);
		}
	}

	final AcquiredMailTransport transport() {
		if (acquiredTransport == null || scopeClosed) {
			throw new IllegalStateException("This send has no open SMTP connection.");
		}
		return acquiredTransport;
	}

	final void requireOpenScope() {
		if (scopeClosed) {
			throw new IllegalStateException("This open-connection sender's scope has ended; send through the Mailer or open a new scope.");
		}
	}

	final <T> T executeWithinOwners(final Supplier<T> operation) {
		return executeWithinOwner(0, operation);
	}

	private <T> T executeWithinOwner(final int index, final Supplier<T> operation) {
		return index == executionOwners.size() ? operation.get()
				: executionOwners.get(index).withinDelegatedExecution(() -> executeWithinOwner(index + 1, operation));
	}

	final void closeAfterFailure(final Throwable failure) {
		diagnostics.failed();
		try {
			close();
		} catch (MessagingException | RuntimeException | Error cleanupFailure) {
			if (failure != cleanupFailure) {
				failure.addSuppressed(cleanupFailure);
			}
		}
	}

	@Override
	public void close() throws MessagingException {
		if (scopeClosed) {
			return;
		}
		scopeClosed = true;
		try {
			closeAttemptResources();
		} finally {
			closeDelegatedScopes();
		}
	}

	/** Retry waits keep participating Mailers alive, but own no ordinary lease, proxy or sending reservation. */
	final void closeAttemptResources() throws MessagingException {
		diagnostics.startCleanup();
		Throwable primaryFailure = null;
		try {
			primaryFailure = executeWithinOwners(this::closeConnectionAndProxy);
		} catch (RuntimeException | Error failure) {
			primaryFailure = failure;
		}
		try {
			releaseReservation();
		} catch (RuntimeException | Error failure) {
			if (primaryFailure == null) {
				primaryFailure = failure;
			} else if (primaryFailure != failure) {
				primaryFailure.addSuppressed(failure);
			}
		}
		try {
			if (primaryFailure != null) {
				diagnostics.failed();
			}
			throwCleanupFailure(primaryFailure);
		} finally {
			diagnostics.cleanupCompleted();
		}
	}

	@Nullable
	private Throwable closeConnectionAndProxy() {
		final AcquiredMailTransport connection = acquiredTransport;
		final SmtpProxyScope proxy = proxyScope;
		// Detach these attempt resources before cleanup: a thrown close must not make terminal cleanup try to dispose them a second time.
		acquiredTransport = null;
		proxyScope = null;
		Throwable primaryFailure = null;
		try {
			if (connection != null) {
				connection.close();
			}
		} catch (MessagingException | RuntimeException | Error failure) {
			primaryFailure = failure;
		} finally {
			try {
				if (proxy != null) {
					proxy.close();
				}
			} catch (RuntimeException | Error failure) {
				if (primaryFailure == null) {
					primaryFailure = failure;
				} else if (primaryFailure != failure) {
					primaryFailure.addSuppressed(failure);
				}
			}
		}
		return primaryFailure;
	}

	/** Ordinary sending owns one reservation; retained connections reserve and release separately for each email. */
	void releaseReservation() { }

	private void closeDelegatedScopes() {
		for (int index = delegatedScopes.size() - 1; index >= 0; index--) {
			delegatedScopes.get(index).close();
		}
	}

	/** Preserve the original cleanup exception so try-with-resources can suppress it on the actual send failure. */
	private static void throwCleanupFailure(@Nullable final Throwable failure) throws MessagingException {
		if (failure instanceof MessagingException) {
			throw (MessagingException) failure;
		}
		if (failure instanceof RuntimeException) {
			throw (RuntimeException) failure;
		}
		if (failure instanceof Error) {
			throw (Error) failure;
		}
	}
}
