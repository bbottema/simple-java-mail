package org.simplejavamail.internal.batchsupport.transportpool.keyedcloseablepools;

import stormpot.Allocator;
import stormpot.BlazePool;
import stormpot.CompoundExpiration;
import stormpot.Config;
import stormpot.Expiration;
import stormpot.Pool;
import stormpot.Poolable;
import stormpot.Timeout;

import java.util.concurrent.atomic.AtomicBoolean;

class CloseablePool<T extends Poolable> {
	private final AtomicBoolean killSwitch = new AtomicBoolean();
	private final Pool<T> pool;

	CloseablePool(final Allocator<T> allocator, final Expiration<T> expirationPolicy) {
		this.pool = new BlazePool<>(new Config<T>()
				.setExpiration(new CompoundExpiration<>(expirationPolicy, new KillSwitchExpiration<T>(killSwitch)))
				.setAllocator(allocator));
	}

	T claim(Timeout timeout) throws InterruptedException {
		return pool.claim(timeout);
	}

	void shutDown() {
		killSwitch.set(true);
	}
}
