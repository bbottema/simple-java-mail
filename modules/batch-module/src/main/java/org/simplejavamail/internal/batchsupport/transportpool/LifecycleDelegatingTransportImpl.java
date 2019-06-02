package org.simplejavamail.internal.batchsupport.transportpool;

import org.simplejavamail.api.internal.batchsupport.LifecycleDelegatingTransport;
import org.simplejavamail.internal.batchsupport.transportpool.keyedcloseablepools.SimpleDelegatingPoolable;

import javax.annotation.Nonnull;
import javax.mail.Transport;

/**
 * Wraps {@link SimpleDelegatingPoolable} to implement {@link LifecycleDelegatingTransport}, so transport resources
 * can be used outside the batchmodule and released to be reused.
 */
public class LifecycleDelegatingTransportImpl implements LifecycleDelegatingTransport {
	private final SimpleDelegatingPoolable<Transport> pooledTransport;

	/**
	 * @deprecated For internal use only.
	 */
	@Deprecated
	@SuppressWarnings("DeprecatedIsStillUsed")
	public LifecycleDelegatingTransportImpl(final SimpleDelegatingPoolable<Transport> pooledTransport) {
		this.pooledTransport = pooledTransport;
	}

	/**
	 * @deprecated For internal use only.
	 */
	@Nonnull
	@Override
	@Deprecated
	@SuppressWarnings("DeprecatedIsStillUsed")
	public Transport getTransport() {
		return pooledTransport.getDelegate();
	}

	/**
	 * @deprecated For internal use only.
	 */
	@Override
	@Deprecated
	@SuppressWarnings("DeprecatedIsStillUsed")
	public void signalTransportUsed() {
		pooledTransport.release();
	}
}