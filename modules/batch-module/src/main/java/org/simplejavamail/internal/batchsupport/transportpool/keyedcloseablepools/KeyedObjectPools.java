package org.simplejavamail.internal.batchsupport.transportpool.keyedcloseablepools;

import stormpot.Expiration;
import stormpot.Poolable;
import stormpot.Timeout;

import javax.annotation.Nonnull;
import java.util.concurrent.ConcurrentHashMap;

public class KeyedObjectPools<Key, T extends Poolable> {

	private final ConcurrentHashMap<Key, CloseablePool<T>> keyedPool = new ConcurrentHashMap<>();

	@Nonnull private final AllocatorFactory<Key, T> allocatorFactory;
	@Nonnull private final Expiration<T> expirationPolicy;
	@Nonnull private final Timeout claimTimeout;

	public KeyedObjectPools(
			@Nonnull final AllocatorFactory<Key, T> allocatorFactory,
			@Nonnull final Expiration<T> expirationPolicy,
			@Nonnull final Timeout claimTimeout) {
		this.allocatorFactory = allocatorFactory;
		this.expirationPolicy = expirationPolicy;
		this.claimTimeout = claimTimeout;
	}

	public T acquire(final Key key) {
		try {
			return findPool(key).claim(claimTimeout);
		} catch (Exception e) {
			throw new AcquireKeyedPoolableException(key, e);
		}
	}

	/**
	 * Clearing a pool is like shutting down a pool, but semantically speaking, a cleared pool can be restarted again.
	 */
	public synchronized void clearPool(final Key key) {
		if (keyedPool.containsKey(key)) {
			keyedPool.remove(key).shutDown();
		}
	}

	private CloseablePool<T> findPool(final Key key) {
		if (!keyedPool.containsKey(key)) {
			keyedPool.put(key, new CloseablePool<>(allocatorFactory.create(key), expirationPolicy));
		}
		return keyedPool.get(key);
	}

}