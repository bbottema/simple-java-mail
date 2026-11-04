package org.simplejavamail.internal.util;

import org.jetbrains.annotations.ApiStatus;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.nio.channels.ClosedByInterruptException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CancellationException;

/** Keeps an actual interrupted I/O operation out of best-effort content recovery. */
@ApiStatus.Internal
public final class InterruptedIoGuard {

	private InterruptedIoGuard() { }

	/** Checks typed causes/suppressed failures without clearing or manufacturing the caller's interrupt state. */
	public static void rethrowIfInterrupted(final Throwable failure) {
		final boolean interrupted = Thread.currentThread().isInterrupted();
		final Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		final ArrayDeque<Throwable> pending = new ArrayDeque<>();
		pending.add(failure);
		while (!pending.isEmpty()) {
			final Throwable current = pending.removeFirst();
			if (!seen.add(current)) continue;
			if (current instanceof CancellationException) throw (CancellationException) current;
			if (current instanceof InterruptedException || current instanceof ClosedByInterruptException
					|| current instanceof InterruptedIOException && !(current instanceof SocketTimeoutException)
					|| interrupted && current instanceof IOException) {
				final CancellationException cancellation = new CancellationException("Email conversion was interrupted");
				cancellation.initCause(failure);
				throw cancellation;
			}
			if (current.getCause() != null) pending.addLast(current.getCause());
			for (Throwable suppressed : current.getSuppressed()) pending.addLast(suppressed);
		}
	}
}
