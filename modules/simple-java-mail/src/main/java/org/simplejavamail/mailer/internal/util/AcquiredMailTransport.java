package org.simplejavamail.mailer.internal.util;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.internal.batchsupport.LifecycleDelegatingTransport;
import org.simplejavamail.api.internal.batchsupport.PoolConnectionSetupException;
import org.simplejavamail.api.internal.batchsupport.PoolConnectionObserver;
import org.simplejavamail.api.internal.batchsupport.SelectedPoolTransport;
import org.simplejavamail.api.internal.batchsupport.SendingAllowance;
import org.simplejavamail.api.mailer.MailSubmissionException;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.MailSubmissionStatus;
import org.simplejavamail.api.mailer.spi.MailTransportCompatibilityException;
import org.simplejavamail.api.mailer.spi.PreparedMail;
import org.simplejavamail.internal.util.MailTransportLifecycleResolver;
import org.simplejavamail.internal.util.concurrent.MailSendControl;
import org.simplejavamail.mailer.internal.ConnectionSetupFailure;
import org.simplejavamail.mailer.internal.MailSendDiagnosticsRecorder;

/**
 * Owns one usable connection. Routing ends before send() is called; cleanup completes before another destination can be considered.
 * The owning execution confines acquisition, sending and cleanup to its thread; this wrapper is not independently thread-safe.
 * Cancellation may abort the underlying connection, but does not call this wrapper's close().
 */
public final class AcquiredMailTransport implements AutoCloseable {

	private final Session session;
	private final Transport transport;
	@Nullable private final LifecycleDelegatingTransport pooledLease;
	@Nullable private final MailSendControl.Registration abortRegistration;
	private MailSendControl activeControl;
	/** Used only for lease health; a retained callback may already have caught this send failure. */
	@Nullable private Throwable sendFailure;
	@Nullable private MailSubmissionReceipt receipt;
	private boolean closed;

	private AcquiredMailTransport(final Session session, final Transport transport, @Nullable final LifecycleDelegatingTransport pooledLease,
			@Nullable final MailSendControl.Registration abortRegistration, final MailSendControl activeControl) {
		this.session = session;
		this.transport = transport;
		this.pooledLease = pooledLease;
		this.abortRegistration = abortRegistration;
		this.activeControl = activeControl;
	}

	public static AcquiredMailTransport acquire(final Session session, @Nullable final SelectedPoolTransport selected, final MailSendControl control,
			final PoolConnectionObserver observer, final MailSendDiagnosticsRecorder diagnostics) throws MessagingException {
		control.checkStopped();
		return selected != null ? acquirePooled(selected, control, observer, diagnostics) : acquireDirect(session, control, observer, diagnostics);
	}

	private static AcquiredMailTransport acquirePooled(final SelectedPoolTransport selected, final MailSendControl control,
			final PoolConnectionObserver observer, final MailSendDiagnosticsRecorder diagnostics) throws MessagingException {
		final PoolConnectionObserver.Attempt observation = new ConnectionObservation(observer.connectionStarting());
		try {
			final LifecycleDelegatingTransport pooledLease = selected.acquireTransport(control, observePhysicalSetup(observation, diagnostics, control));
			final AcquiredMailTransport acquiredConnection = new AcquiredMailTransport(pooledLease.getSessionUsedToObtainTransport(),
					pooledLease.getTransport(), pooledLease, null, control);
			try {
				control.checkStopped();
				// An already-connected lease is usable evidence too; it may recover a destination without opening another socket.
				observation.connected();
				return acquiredConnection;
			} catch (RuntimeException | Error failure) {
				try {
					acquiredConnection.close();
				} catch (MessagingException | RuntimeException | Error cleanupFailure) {
					failure.addSuppressed(cleanupFailure);
				}
				throw failure;
			}
		} catch (PoolConnectionSetupException failure) {
			throw new ConnectionSetupFailure(failure.getCause());
		} finally {
			// A capacity wait or discarded lease supplies no recovery evidence. Completed observations ignore abandonment.
			observation.abandoned();
		}
	}

	/** Physical success is provisional until the pool and cancellation checks accept the lease; failure remains setup evidence. */
	private static PoolConnectionObserver observePhysicalSetup(final PoolConnectionObserver.Attempt observation,
			final MailSendDiagnosticsRecorder diagnostics, final MailSendControl control) {
		return new PoolConnectionObserver() {
			@Override
			public Attempt connectionStarting(final Transport transport) {
				final ConnectionProgressObservation progress = ConnectionProgressObservation.start(transport, diagnostics, control);
				return new Attempt() {
					@Override public void connected() { closeProgress(); }
					@Override public void failed(final Throwable failure) { closeProgress(); observation.failed(failure); }
					@Override public void abandoned() { closeProgress(); observation.abandoned(); }

					private void closeProgress() {
						if (progress != null) { progress.close(); }
					}
				};
			}
		};
	}

	private static AcquiredMailTransport acquireDirect(final Session session, final MailSendControl control,
			final PoolConnectionObserver observer, final MailSendDiagnosticsRecorder diagnostics) throws MessagingException {
		final Transport transport = TransportRunner.transportFor(session);
		MailSendControl.Registration abortRegistration = null;
		try {
			abortRegistration = MailTransportLifecycleResolver.registerAbort(transport, control);
			control.checkStopped();
			connectAndObserve(transport, session, control, observer, diagnostics);
			return new AcquiredMailTransport(session, transport, null, abortRegistration, control);
		} catch (RuntimeException | Error failure) {
			try {
				transport.close();
			} catch (MessagingException | RuntimeException | Error cleanupFailure) {
				failure.addSuppressed(cleanupFailure);
			} finally {
				if (abortRegistration != null) {
					abortRegistration.close();
				}
			}
			throw failure;
		}
	}

	private static void connectAndObserve(final Transport transport, final Session session, final MailSendControl control,
			final PoolConnectionObserver observer, final MailSendDiagnosticsRecorder diagnostics) {
		final PoolConnectionObserver.Attempt observation = new ConnectionObservation(observer.connectionStarting());
		try (ConnectionProgressObservation ignored = ConnectionProgressObservation.start(transport, diagnostics, control)) {
			TransportConnectionHelper.connectForSending(transport, session);
			control.checkStopped();
			observation.connected();
		} catch (ConnectionSetupFailure failure) {
			// Cancellation is not evidence that the destination is unavailable.
			if (!control.isStopRequested() && !Thread.currentThread().isInterrupted()) {
				observation.failed(failure);
			}
			throw failure;
		} finally {
			observation.abandoned();
		}
	}

	public Transport getTransport() {
		return transport;
	}

	/** Adapts an already-owned dedicated scope for one email; its caller retains responsibility for closing the actual transport. */
	public static AcquiredMailTransport borrowedForSending(final Session session, final Transport transport, final MailSendControl control) {
		return new AcquiredMailTransport(session, transport, null, null, control);
	}

	public Session getSession() {
		return session;
	}

	public MailSubmissionReceipt send(final Email email, final MailSendDiagnosticsRecorder diagnostics, final SendingAllowance.Reservation allowance)
			throws MessagingException {
		return send(email, diagnostics, allowance, activeControl);
	}

	/** Retained connections use the current email's control, not the already completed scope-opening control. */
	public MailSubmissionReceipt send(final Email email, final MailSendDiagnosticsRecorder diagnostics, final SendingAllowance.Reservation allowance,
			final MailSendControl control) throws MessagingException {
		this.activeControl = control;
		receipt = null;
		try {
			receipt = TransportRunner.sendMessageOnTransport(transport, session, email, control, diagnostics, allowance);
			return receipt;
		} catch (MessagingException | RuntimeException | Error failure) {
			sendFailure = failure;
			throw failure;
		}
	}

	/** One retry-enabled invocation; no terminal stop/failure arbitration occurs until its logical owner decides. */
	public MailSubmissionReceipt sendPrepared(final PreparedMail prepared, @Nullable final String messageId, @Nullable final String subject,
			final MailSendControl control, final MailSendDiagnosticsRecorder diagnostics, final SendingAllowance.Reservation allowance,
			final Runnable invocationStarted) throws MessagingException {
		activeControl = control;
		receipt = null;
		try {
			receipt = TransportRunner.sendPreparedMessageOnTransport(transport, prepared, messageId, subject, control, diagnostics, allowance,
					invocationStarted);
			return receipt;
		} catch (MessagingException | RuntimeException | Error failure) {
			sendFailure = failure;
			throw failure;
		}
	}

	@Override
	public void close() throws MessagingException {
		if (closed) {
			return;
		}
		closed = true;
		try {
			if (pooledLease == null) {
				transport.close();
			} else if (canReturnPooledLeaseAsHealthy()) {
				pooledLease.signalTransportUsed();
			} else {
				pooledLease.signalTransportFailed();
			}
		} catch (MessagingException | RuntimeException | Error cleanupFailure) {
			// The owning try-with-resources suppresses cleanup only on an exception still escaping, not one already handled by a callback.
			if (receipt == null || receipt.getStatus() != MailSubmissionStatus.ACCEPTED || !activeControl.isStopRequested()) {
				throw cleanupFailure;
			}
		} finally {
			if (abortRegistration != null) {
				abortRegistration.close();
			}
		}
	}

	/** A local compatibility check did not make the connected pooled transport unhealthy. */
	private boolean canReturnPooledLeaseAsHealthy() {
		return sendFailure == null || isLocalCompatibilityFailure(sendFailure);
	}

	private static boolean isLocalCompatibilityFailure(final Throwable failure) {
		return failure instanceof MailSubmissionException && failure.getCause() instanceof MailTransportCompatibilityException;
	}

	/** Acquisition-thread bookkeeping: finally abandons unfinished setup, but never sends a second terminal callback. */
	private static final class ConnectionObservation implements PoolConnectionObserver.Attempt {

		private final PoolConnectionObserver.Attempt delegate;
		private boolean completed;

		private ConnectionObservation(final PoolConnectionObserver.Attempt delegate) {
			this.delegate = delegate;
		}

		@Override
		public void connected() {
			if (completeOnce()) {
				delegate.connected();
			}
		}

		@Override
		public void failed(final Throwable failure) {
			if (completeOnce()) {
				delegate.failed(failure);
			}
		}

		@Override
		public void abandoned() {
			if (completeOnce()) {
				delegate.abandoned();
			}
		}

		private boolean completeOnce() {
			if (completed) {
				return false;
			}
			completed = true;
			return true;
		}
	}
}
