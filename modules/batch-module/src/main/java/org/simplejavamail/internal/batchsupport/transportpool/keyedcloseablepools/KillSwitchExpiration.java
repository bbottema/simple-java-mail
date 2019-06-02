package org.simplejavamail.internal.batchsupport.transportpool.keyedcloseablepools;

import stormpot.Expiration;
import stormpot.Poolable;
import stormpot.SlotInfo;

import java.util.concurrent.atomic.AtomicBoolean;

public class KillSwitchExpiration<T extends Poolable> implements Expiration<T> {
	private final AtomicBoolean killSwitch;

	public KillSwitchExpiration(final AtomicBoolean killSwitch) {
		this.killSwitch = killSwitch;
	}

	@Override
	public boolean hasExpired(final SlotInfo<? extends T> info) {
		return killSwitch.get();
	}
}