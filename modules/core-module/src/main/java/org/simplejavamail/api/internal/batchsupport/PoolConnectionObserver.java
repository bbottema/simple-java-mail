package org.simplejavamail.api.internal.batchsupport;

import jakarta.mail.Transport;

/**
 * Lets a factory observe physical connection setup in an optional connection pool. It can defer background replenishment after an
 * observed outage, but does not select destinations, retry submissions or alter pool capacity.
 */
public interface PoolConnectionObserver {

	/** Performs no observation and leaves background connection allocation enabled. */
	PoolConnectionObserver NONE = new PoolConnectionObserver() { };

	/**
	 * Whether the pool may start a background replacement connection now. Returning {@code false} does not close healthy connections
	 * or reject a foreground claim; it only prevents the background allocator from bypassing the observer's recovery delay.
	 */
	default boolean isBackgroundConnectionAllowed() {
		return true;
	}

	/**
	 * Starts one acquisition observation. Recovery uses this entry point for the logical lease handoff; physical setup uses
	 * {@link #connectionStarting(Transport)}. The owner reports exactly one terminal outcome. Keep callbacks short and do not perform I/O.
	 */
	default Attempt connectionStarting() {
		return Attempt.NONE;
	}

	/** Supplies the actual physical transport immediately before setup; logical lease observations keep the parameterless entry point. */
	default Attempt connectionStarting(final Transport transport) {
		return connectionStarting();
	}

	/** One physical setup or logical acquisition observed by {@link PoolConnectionObserver}. */
	interface Attempt {
		/** Performs no observation for this setup. */
		Attempt NONE = new Attempt() { };

		/** Records that the physical connection became usable. */
		default void connected() { }

		/**
		 * Records that physical setup failed before a usable connection was available.
		 *
		 * @param failure The provider failure that prevented the connection from becoming usable.
		 */
		default void failed(Throwable failure) { }

		/** Cancelled or discarded acquisition is neither an outage nor evidence of recovery. */
		default void abandoned() { }
	}
}
