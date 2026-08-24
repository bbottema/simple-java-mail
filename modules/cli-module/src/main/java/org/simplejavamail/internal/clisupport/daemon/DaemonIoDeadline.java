package org.simplejavamail.internal.clisupport.daemon;

import java.nio.channels.Channel;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Bounds one blocking channel exchange by closing the channel when its deadline expires.
 * A shared daemon timer provides the portable timeout mechanism; closing the scope cancels its pending close task.
 */
final class DaemonIoDeadline implements AutoCloseable {
	private static final ScheduledThreadPoolExecutor TIMER = timer();

	private final ScheduledFuture<?> closeTask;

	private DaemonIoDeadline(final ScheduledFuture<?> closeTask) {
		this.closeTask = closeTask;
	}

	static DaemonIoDeadline after(final Channel channel, final Duration timeout) {
		return new DaemonIoDeadline(TIMER.schedule(() -> {
			try {
				channel.close();
			} catch (Exception ignored) {
				// A concurrently completed exchange may already have closed the channel.
			}
		}, timeout.toMillis(), TimeUnit.MILLISECONDS));
	}

	@Override
	public void close() {
		closeTask.cancel(false);
	}

	private static ScheduledThreadPoolExecutor timer() {
		final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
			final Thread thread = new Thread(runnable, "sjm-daemon-io-deadline");
			thread.setDaemon(true);
			return thread;
		});
		executor.setRemoveOnCancelPolicy(true);
		return executor;
	}
}
