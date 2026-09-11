package org.simplejavamail.internal.batchsupport;

import jakarta.mail.Session;
import jakarta.mail.Transport;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.internal.batchsupport.LifecycleDelegatingTransport;
import org.simplejavamail.internal.util.concurrent.MailSendControl;
import org.simplejavamail.smtpconnectionpool.SmtpTransportLease;

/**
 * Wraps {@link SmtpTransportLease} to implement {@link LifecycleDelegatingTransport}, so transport resources
 * can be used outside the batchmodule and released to be reused in connection pool.
 */
class LifecycleDelegatingTransportImpl implements LifecycleDelegatingTransport {
	private final BatchTransportEngine<?> engine;
	private final SmtpTransportLease transportLease;
	@Nullable private final MailSendControl.Registration stopRegistration;

	LifecycleDelegatingTransportImpl(final BatchTransportEngine<?> engine, final SmtpTransportLease transportLease,
			@Nullable final MailSendControl control) {
		this.engine = engine;
		this.transportLease = transportLease;
		stopRegistration = control == null ? null : control.onStop(() -> transportLease.getCancellation().ifPresent(cancellation -> cancellation.request()));
	}

	@NotNull
	@Override
	public Session getSessionUsedToObtainTransport() {
		return transportLease.getSession();
	}

	@NotNull
	@Override
	public Transport getTransport() {
		return transportLease.getTransport();
	}

	@Override
	public void signalTransportUsed() {
		fenceStopRegistration();
		try {
			engine.release(transportLease);
		} catch (RuntimeException | Error releaseFailure) {
			awaitInvalidatedDisposal(releaseFailure);
			throw releaseFailure;
		}
		awaitInvalidatedDisposal();
	}

	@Override
	public void signalTransportFailed() {
		// An unhealthy connection will not be reused; close its socket before provider cleanup can wait for QUIT.
		Throwable abortFailure = null;
		try {
			transportLease.getCancellation().ifPresent(cancellation -> cancellation.request());
		} catch (RuntimeException | Error failure) {
			abortFailure = failure;
			throw failure;
		} finally {
			fenceStopRegistration();
			invalidateAndAwaitDisposal(abortFailure);
		}
	}

	private void invalidateAndAwaitDisposal(@Nullable final Throwable abortFailure) {
		Throwable primaryFailure = abortFailure;
		try {
			engine.invalidate(transportLease);
		} catch (RuntimeException | Error invalidationFailure) {
			if (primaryFailure == null) {
				primaryFailure = invalidationFailure;
				throw invalidationFailure;
			}
			if (primaryFailure != invalidationFailure) {
				primaryFailure.addSuppressed(invalidationFailure);
			}
		} finally {
			awaitInvalidatedDisposal(primaryFailure);
		}
	}

	private void fenceStopRegistration() {
		if (stopRegistration != null) {
			stopRegistration.close();
		}
	}

	private void awaitInvalidatedDisposal() {
		if (transportLease.getState() == SmtpTransportLease.State.INVALIDATED) {
			transportLease.getDisposalCompletion().toCompletableFuture().join();
		}
	}

	private void awaitInvalidatedDisposal(@Nullable final Throwable primaryFailure) {
		try {
			awaitInvalidatedDisposal();
		} catch (RuntimeException | Error disposalFailure) {
			if (primaryFailure == null) {
				throw disposalFailure;
			}
			if (primaryFailure != disposalFailure) {
				primaryFailure.addSuppressed(disposalFailure);
			}
		}
	}
}
