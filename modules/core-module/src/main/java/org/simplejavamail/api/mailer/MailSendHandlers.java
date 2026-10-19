package org.simplejavamail.api.mailer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.Executor;

import static java.util.Objects.requireNonNull;

/** Immutable callback choices for shared Mailer reporting or one logical send. Callbacks and executors remain application-owned. */
public final class MailSendHandlers {

	@Nullable private final MailSendResultObserver resultObserver;
	@Nullable private final Executor resultObserverExecutor;
	@Nullable private final MailSendProgressObserver progressObserver;
	@Nullable private final Executor progressObserverExecutor;

	private MailSendHandlers(final Builder builder) {
		resultObserver = builder.resultObserver;
		resultObserverExecutor = builder.resultObserverExecutor;
		progressObserver = builder.progressObserver;
		progressObserverExecutor = builder.progressObserverExecutor;
	}

	/** Starts with neither observer configured. */
	@NotNull public static Builder builder() { return new Builder(); }
	/** Returns an independent builder retaining these borrowed callbacks and executors. */
	@NotNull public Builder toBuilder() { return new Builder(this); }
	/** @see Builder#withResultObserver(MailSendResultObserver) */
	@Nullable public MailSendResultObserver getResultObserver() { return resultObserver; }
	/** @see Builder#withResultObserver(MailSendResultObserver, Executor) */
	@Nullable public Executor getResultObserverExecutor() { return resultObserverExecutor; }
	/** @see Builder#withProgressObserver(MailSendProgressObserver) */
	@Nullable public MailSendProgressObserver getProgressObserver() { return progressObserver; }
	/** @see Builder#withProgressObserver(MailSendProgressObserver, Executor) */
	@Nullable public Executor getProgressObserverExecutor() { return progressObserverExecutor; }

	/** Mutable choices copied into each built handler group. */
	public static final class Builder {
		@Nullable private MailSendResultObserver resultObserver;
		@Nullable private Executor resultObserverExecutor;
		@Nullable private MailSendProgressObserver progressObserver;
		@Nullable private Executor progressObserverExecutor;

		private Builder() { }
		private Builder(final MailSendHandlers handlers) {
			resultObserver = handlers.resultObserver;
			resultObserverExecutor = handlers.resultObserverExecutor;
			progressObserver = handlers.progressObserver;
			progressObserverExecutor = handlers.progressObserverExecutor;
		}

		/**
		 * Receives the final outcome before send completion on the completing thread. Keep it quick and thread-safe.
		 * Runtime callback failures are logged and ignored. Replaces the observer and restores inline execution.
		 * @param observer Final-result callback.
		 * @return This builder.
		 */
		@NotNull public Builder withResultObserver(@NotNull final MailSendResultObserver observer) {
			resultObserver = requireNonNull(observer, "resultObserver"); resultObserverExecutor = null; return this;
		}
		/**
		 * Hands the final outcome to the supplied executor before send completion, without awaiting callback execution.
		 * Direct/caller-runs/blocking executors retain their behavior; rejection is logged without retry or inline fallback.
		 * The application owns executor capacity, draining and shutdown. Callbacks can run concurrently across sends.
		 * @param observer Final-result callback.
		 * @param executor Borrowed callback executor.
		 * @return This builder.
		 */
		@NotNull public Builder withResultObserver(@NotNull final MailSendResultObserver observer, @NotNull final Executor executor) {
			requireNonNull(observer, "resultObserver"); requireNonNull(executor, "resultObserverExecutor");
			resultObserver = observer; resultObserverExecutor = executor; return this;
		}
		/**
		 * Removes the result callback and its executor only.
		 * @see #withResultObserver(MailSendResultObserver)
		 */
		@NotNull public Builder clearResultObserver() { resultObserver = null; resultObserverExecutor = null; return this; }
		/**
		 * Receives best-effort progress on the Mailer's shared daemon dispatcher, never on the sending thread.
		 * Only the latest pending snapshot per ID is retained, up to 1,024 IDs and one in-flight callback; older pending IDs can be dropped.
		 * Upload-only updates are throttled to 250 ms. Results and progress are independent: ignore progress after that ID's final outcome.
		 * Runtime callback failures are logged and ignored. Closing the Mailer discards pending progress without awaiting running callbacks.
		 * Replaces the observer and restores dispatcher execution.
		 * @param observer Progress callback.
		 * @return This builder.
		 */
		@NotNull public Builder withProgressObserver(@NotNull final MailSendProgressObserver observer) {
			progressObserver = requireNonNull(observer, "progressObserver"); progressObserverExecutor = null; return this;
		}
		/**
		 * Delivers progress using a borrowed executor with the bounds and ordering of {@link #withProgressObserver(MailSendProgressObserver)}.
		 * Handoff occurs on the shared dispatcher, so direct/caller-runs execution never invokes the callback on the sending thread.
		 * Callbacks remain serialized per Mailer. Rejected handoffs are logged and dropped; supplied executors are never closed.
		 * @param observer Progress callback.
		 * @param executor Borrowed progress executor, preferably separate from sending workers.
		 * @return This builder.
		 */
		@NotNull public Builder withProgressObserver(@NotNull final MailSendProgressObserver observer, @NotNull final Executor executor) {
			requireNonNull(observer, "progressObserver"); requireNonNull(executor, "progressObserverExecutor");
			progressObserver = observer; progressObserverExecutor = executor; return this;
		}
		/**
		 * Removes the progress callback and its executor only.
		 * @see #withProgressObserver(MailSendProgressObserver)
		 */
		@NotNull public Builder clearProgressObserver() { progressObserver = null; progressObserverExecutor = null; return this; }
		/** @see #withResultObserver(MailSendResultObserver) */
		@Nullable public MailSendResultObserver getResultObserver() { return resultObserver; }
		/** @see #withResultObserver(MailSendResultObserver, Executor) */
		@Nullable public Executor getResultObserverExecutor() { return resultObserverExecutor; }
		/** @see #withProgressObserver(MailSendProgressObserver) */
		@Nullable public MailSendProgressObserver getProgressObserver() { return progressObserver; }
		/** @see #withProgressObserver(MailSendProgressObserver, Executor) */
		@Nullable public Executor getProgressObserverExecutor() { return progressObserverExecutor; }
		/** Captures these choices without allocating threads or taking ownership of application resources. */
		@NotNull public MailSendHandlers build() { return new MailSendHandlers(this); }
	}
}
