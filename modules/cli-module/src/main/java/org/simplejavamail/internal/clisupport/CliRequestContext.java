package org.simplejavamail.internal.clisupport;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable state that must follow one command even when daemon requests execute concurrently.
 * A strictly nested thread-local scope lets existing file interpreters resolve relative paths against the originating
 * client's working directory without sharing mutable process-wide state.
 */
public final class CliRequestContext {
	private static final ThreadLocal<CliRequestContext> CURRENT = new ThreadLocal<>();

	private final UUID requestId;
	private final Path workingDirectory;

	public CliRequestContext(@NotNull final UUID requestId, @NotNull final Path workingDirectory) {
		this.requestId = Objects.requireNonNull(requestId, "requestId");
		this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory").toAbsolutePath().normalize();
	}

	public UUID requestId() {
		return requestId;
	}

	public Path workingDirectory() {
		return workingDirectory;
	}

	public static Scope install(@NotNull final CliRequestContext context) {
		if (CURRENT.get() != null) {
			throw new IllegalStateException("A CLI request context is already installed on this thread");
		}
		CURRENT.set(context);
		return new Scope(context);
	}

	public static File resolveFile(@NotNull final String value) {
		final Path path = Path.of(value);
		final CliRequestContext context = CURRENT.get();
		return (path.isAbsolute() || context == null ? path : context.workingDirectory.resolve(path)).normalize().toFile();
	}

	/** Removes exactly the context installed by this scope and rejects out-of-order closure. */
	public static final class Scope implements AutoCloseable {
		private final CliRequestContext installed;
		private boolean closed;

		private Scope(final CliRequestContext installed) {
			this.installed = installed;
		}

		@Override
		public void close() {
			if (!closed) {
				if (CURRENT.get() != installed) {
					throw new IllegalStateException("CLI request contexts were closed out of order");
				}
				CURRENT.remove();
				closed = true;
			}
		}
	}
}
