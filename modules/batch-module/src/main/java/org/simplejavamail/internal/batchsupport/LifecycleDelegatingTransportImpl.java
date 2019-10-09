package org.simplejavamail.internal.batchsupport;

import org.bbottema.genericobjectpool.PoolableObject;
import org.simplejavamail.api.internal.batchsupport.LifecycleDelegatingTransport;

import javax.annotation.Nonnull;
import javax.mail.Transport;

/**
 * Wraps {@link PoolableObject} to implement {@link LifecycleDelegatingTransport}, so transport resources
 * can be used outside the batchmodule and released to be reused in connection pool.
 */
class LifecycleDelegatingTransportImpl implements LifecycleDelegatingTransport {
	private final PoolableObject<Transport> pooledTransport;

	LifecycleDelegatingTransportImpl(final PoolableObject<Transport> pooledTransport) {
		this.pooledTransport = pooledTransport;
	}

	@Nonnull
	@Override
	public Transport getTransport() {
		return pooledTransport.getAllocatedObject();
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