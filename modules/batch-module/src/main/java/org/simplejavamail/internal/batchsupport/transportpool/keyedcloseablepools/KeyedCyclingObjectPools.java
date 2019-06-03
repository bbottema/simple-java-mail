package org.simplejavamail.internal.batchsupport.transportpool.keyedcloseablepools;

import stormpot.BlazePool;
import stormpot.Config;
import stormpot.Expiration;
import stormpot.LifecycledPool;
import stormpot.Poolable;
import stormpot.Timeout;

import javax.annotation.Nonnull;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Collections.singletonList;

/**
 * <ol>
 *     <li>Keyed: because you govern object pools based on keys, like a cache on steroids</li>
 *     <li>Cycling: because you can cycle multiple pools per key round robin</li>
 * </ol>
 * Example usage:
 * <p>
 * Say you have two different mail clusters, each with several servers. The keys map to the clusters, where each server
 * is accessed round robin, and the objects in the respective cluster-pool are multiple connections to the same mail server.
 * <p>
 * FIXME implement key properly and then document
 */
public class KeyedCyclingObjectPools<Key, T extends Poolable> {

	private final ConcurrentHashMap<Key, Queue<LifecycledPool<T>>> keyedPool = new ConcurrentHashMap<>();

	@Nonnull private final AllocatorFactory<Key, T> allocatorFactory;
	@Nonnull private final Expiration<T> expirationPolicy;
	@Nonnull private final Timeout claimTimeout;

	public KeyedCyclingObjectPools(
			@Nonnull final AllocatorFactory<Key, T> allocatorFactory,
			@Nonnull final Expiration<T> expirationPolicy,
			@Nonnull final Timeout claimTimeout) {
		this.allocatorFactory = allocatorFactory;
		this.expirationPolicy = expirationPolicy;
		this.claimTimeout = claimTimeout;
	}

	public T acquire(final Key key) throws InterruptedException {
		return findPool(key).claim(claimTimeout);
	}

	/**
	 * Clearing a pool is like shutting down a pool, but semantically speaking, a cleared pool can be restarted again.
	 */
	public synchronized void clearPool(final Key key) {
		if (keyedPool.containsKey(key)) {
			for (final LifecycledPool<T> poolInCluster : keyedPool.remove(key)) {
				poolInCluster.shutdown();
			}
		}
	}

	private synchronized LifecycledPool<T> findPool(final Key key) {
		if (!keyedPool.containsKey(key)) {
			LifecycledPool<T> pool = new BlazePool<>(new Config<T>()
					.setExpiration(expirationPolicy)
					// FIXME how to create a cluster?
					.setAllocator(allocatorFactory.create(key)));
			keyedPool.put(key, new LinkedList<>(singletonList(pool)));
		}
		Queue<LifecycledPool<T>> lifecycledPools = keyedPool.get(key);
		LifecycledPool<T> nextRoundrobinInCluster = lifecycledPools.remove();
		lifecycledPools.add(nextRoundrobinInCluster);
		return nextRoundrobinInCluster;
	}
}