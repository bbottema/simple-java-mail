package org.simplejavamail.api.internal.batchsupport;

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
	 * Starts observing one physical connection setup. The pool calls exactly one terminal method when setup completes or fails.
	 * Implementations must keep these callbacks short because pool allocation invokes them synchronously.
	 */
	default Attempt connectionStarting() {
		return Attempt.NONE;
	}

	/** One physical connection setup observed by {@link PoolConnectionObserver}. */
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
	}
}
