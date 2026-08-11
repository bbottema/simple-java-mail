package org.simplejavamail.internal.batchsupport;

import jakarta.mail.Session;
import jakarta.mail.Transport;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.internal.batchsupport.LifecycleDelegatingTransport;
import org.simplejavamail.smtpconnectionpool.SmtpTransportLease;

/**
 * Wraps {@link SmtpTransportLease} to implement {@link LifecycleDelegatingTransport}, so transport resources
 * can be used outside the batchmodule and released to be reused in connection pool.
 */
class LifecycleDelegatingTransportImpl implements LifecycleDelegatingTransport {
	private final BatchTransportEngine<?> engine;
	private final SmtpTransportLease transportLease;

	LifecycleDelegatingTransportImpl(final BatchTransportEngine<?> engine, final SmtpTransportLease transportLease) {
		this.engine = engine;
		this.transportLease = transportLease;
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
		engine.release(transportLease);
	}

	@Override
	public void signalTransportFailed() {
		engine.invalidate(transportLease);
	}
}
