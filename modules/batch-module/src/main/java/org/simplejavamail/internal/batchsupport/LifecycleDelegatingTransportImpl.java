package org.simplejavamail.internal.batchsupport;

import org.bbottema.clusterstormpot.util.SimpleDelegatingPoolable;
import org.simplejavamail.api.internal.batchsupport.LifecycleDelegatingTransport;
import org.simplejavamail.smtpconnectionpool.SmtpConnectionPool;

import javax.annotation.Nonnull;
import javax.mail.Transport;

/**
 * Wraps {@link SimpleDelegatingPoolable} to implement {@link LifecycleDelegatingTransport}, so transport resources
 * can be used outside the batchmodule and released to be reused.
 */
class LifecycleDelegatingTransportImpl implements LifecycleDelegatingTransport {
	private final SimpleDelegatingPoolable<Transport> pooledTransport;
	private final SmtpConnectionPool smtpConnectionPool;

	LifecycleDelegatingTransportImpl(final SmtpConnectionPool smtpConnectionPool, final SimpleDelegatingPoolable<Transport> pooledTransport) {
		this.smtpConnectionPool = smtpConnectionPool;
		this.pooledTransport = pooledTransport;
	}

	@Nonnull
	@Override
	public Transport getTransport() {
		return pooledTransport.getDelegate();
	}

	@Override
	public void signalTransportUsed() {
		pooledTransport.release();
	}

	@Override
	public void signalTransportFailed() {
		// FIXME handle failed Transport intance
		//smtpConnectionPool.
		//pooledTransport.
	}
}