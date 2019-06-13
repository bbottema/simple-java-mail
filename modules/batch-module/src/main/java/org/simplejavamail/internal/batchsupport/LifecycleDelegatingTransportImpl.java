package org.simplejavamail.internal.batchsupport;

import org.bbottema.clusterstormpot.util.SimpleDelegatingPoolable;
import org.simplejavamail.api.internal.batchsupport.LifecycleDelegatingTransport;

import javax.annotation.Nonnull;
import javax.mail.Transport;

/**
 * Wraps {@link SimpleDelegatingPoolable} to implement {@link LifecycleDelegatingTransport}, so transport resources
 * can be used outside the batchmodule and released to be reused in connection pool.
 */
class LifecycleDelegatingTransportImpl implements LifecycleDelegatingTransport {
	private final SimpleDelegatingPoolable<Transport> pooledTransport;

	LifecycleDelegatingTransportImpl(final SimpleDelegatingPoolable<Transport> pooledTransport) {
		this.pooledTransport = pooledTransport;
	}

	@Nonnull
	@Override
	public Transport getTransport() {
		return pooledTransport.getAllocatedDelegate();
	}

	@Override
	public void signalTransportUsed() {
		pooledTransport.release();
	}

	@Override
	public void signalTransportFailed() {
		pooledTransport.releaseFaulty();
	}
}