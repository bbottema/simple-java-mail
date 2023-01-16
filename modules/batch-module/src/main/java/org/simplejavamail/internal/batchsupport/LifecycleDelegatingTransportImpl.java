package org.simplejavamail.internal.batchsupport;

import jakarta.mail.Session;
import jakarta.mail.Transport;
import org.bbottema.genericobjectpool.PoolableObject;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.internal.batchsupport.LifecycleDelegatingTransport;
import org.simplejavamail.smtpconnectionpool.SessionTransport;

/**
 * Wraps {@link PoolableObject} to implement {@link LifecycleDelegatingTransport}, so transport resources
 * can be used outside the batchmodule and released to be reused in connection pool.
 */
class LifecycleDelegatingTransportImpl implements LifecycleDelegatingTransport {
	private final PoolableObject<SessionTransport> pooledTransport;

	LifecycleDelegatingTransportImpl(final PoolableObject<SessionTransport> pooledTransport) {
		this.pooledTransport = pooledTransport;
	}

	@NotNull
	@Override
	public Session getSessionUsedToObtainTransport() {
		return pooledTransport.getAllocatedObject().getSession();
	}

	@NotNull
	@Override
	public Transport getTransport() {
		return pooledTransport.getAllocatedObject().getTransport();
	}

	@Override
	public void signalTransportUsed() {
		pooledTransport.release();
	}

	@Override
	public void signalTransportFailed() {
		pooledTransport.invalidate();
	}
}