package org.simplejavamail.batch;

import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.URLName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;
import org.simplejavamail.api.mailer.MailFailureKind;
import org.simplejavamail.internal.batchsupport.BatchTransportEngine;

import java.io.IOException;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BatchTransportExecutorShutdownTest {

	private enum ViewMutation { COMPLETE, FAIL, CANCEL, TIME_OUT, COMPLETE_ON_TIMEOUT, OBTRUDE_VALUE, OBTRUDE_FAILURE }

	private enum ExecutionMode { OWNED, SUPPLIED, DIRECT }

	@ParameterizedTest
	@EnumSource(ViewMutation.class)
	void callerMutationCannotChangeAnotherShutdownViewOrReleaseBlockingClose(final ViewMutation mutation) throws Exception {
		final TestSession session = new TestSession();
		final BatchTransportExecutor<String> pool = builder().build().registerSession("cluster", session.session);
		final CountDownLatch started = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);
		final ExecutorService closingCaller = Executors.newSingleThreadExecutor();
		try {
			final CompletableFuture<String> operation = pool.submit("cluster", (selected, transport) -> {
				started.countDown();
				assertThat(release.await(5, SECONDS)).isTrue();
				return "finished";
			});
			assertThat(started.await(5, SECONDS)).isTrue();
			final CompletableFuture<Void> changed = pool.shutdown();
			final CompletableFuture<Void> untouched = pool.shutdown();
			mutate(changed, mutation);

			assertThat(changed).isDone();
			assertThat(untouched).isNotDone().isNotSameAs(changed);
			final CompletableFuture<Void> subsequent = pool.shutdown();
			assertThat(subsequent).isNotDone().isNotSameAs(untouched).isNotSameAs(changed);
			final CountDownLatch closeEntered = new CountDownLatch(1);
			final Future<?> close = closingCaller.submit(() -> {
				closeEntered.countDown();
				pool.close();
			});
			assertThat(closeEntered.await(5, SECONDS)).isTrue();
			assertThatThrownBy(() -> close.get(100, MILLISECONDS)).isInstanceOf(TimeoutException.class);
			verify(session.transport, never()).close();

			release.countDown();
			assertThat(operation.get(5, SECONDS)).isEqualTo("finished");
			untouched.get(5, SECONDS);
			subsequent.get(5, SECONDS);
			close.get(5, SECONDS);
			final CompletableFuture<Void> afterCleanup = pool.shutdown();
			assertThat(afterCleanup).isNotSameAs(subsequent).isCompletedWithValue(null);
			verify(session.transport).close();
		} finally {
			release.countDown();
			closingCaller.shutdownNow();
			assertThat(closingCaller.awaitTermination(5, SECONDS)).isTrue();
			final Throwable cleanup = catchThrowable(() -> pool.shutdownNow().get(5, SECONDS));
			assertThat(cleanup instanceof TimeoutException).isFalse();
		}
	}

	@Test
	@SuppressWarnings("rawtypes")
	void injectedCleanupFailureSurvivesCallerMutationAndIsReportedByAllUntouchedViewsAndClose() {
		final IOException cleanupFailure = new IOException("deliberate engine cleanup failure");
		final CompletableFuture<Void> engineCleanup = new CompletableFuture<>();
		// Inject at the engine boundary: individual transport-disposal failures are not whole-pool shutdown failures upstream.
		try (MockedConstruction<BatchTransportEngine> engines = mockConstruction(BatchTransportEngine.class,
				(engine, context) -> when(engine.shutdown()).thenReturn(engineCleanup))) {
			final BatchTransportExecutor<String> pool = builder().build();
			try {
				final CompletableFuture<Void> changed = pool.shutdown();
				final CompletableFuture<Void> untouched = pool.shutdown();
				assertThat(changed.complete(null)).isTrue();
				engineCleanup.completeExceptionally(cleanupFailure);

				final Throwable reported = catchThrowable(() -> untouched.get(5, SECONDS));
				assertThat(reported).isInstanceOf(ExecutionException.class).hasRootCause(cleanupFailure);
				assertThat(reported.getCause()).isInstanceOf(BatchTransportException.class).hasCause(cleanupFailure);
				assertThat(((BatchTransportException) reported.getCause()).getKind()).isEqualTo(MailFailureKind.CLEANUP);
				assertThat(catchThrowable(() -> pool.shutdown().get(5, SECONDS))).hasCause(reported.getCause());
				assertThat(catchThrowable(() -> pool.shutdownNow().get(5, SECONDS))).hasCause(reported.getCause());
				assertThat(catchThrowable(pool::close)).isSameAs(reported.getCause());
				assertThat(changed).isCompletedWithValue(null);
				verify(engines.constructed().get(0)).shutdown();
			} finally {
				engineCleanup.complete(null);
				// Cleanup is expected to fail, but it must still finish and terminate the owned executor.
				final Throwable cleanup = catchThrowable(() -> pool.shutdown().get(5, SECONDS));
				assertThat(cleanup instanceof TimeoutException).isFalse();
			}
		}
	}

	@Test
	void forcedShutdownEscalatesGracefulShutdownAndPreservesBorrowedExecutorOwnership() throws Exception {
		final TestSession session = new TestSession();
		final ExecutorService workers = Executors.newSingleThreadExecutor();
		final BatchTransportExecutor<String> pool = builder().withExecutorService(workers).build().registerSession("cluster", session.session);
		final CountDownLatch started = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);
		final AtomicBoolean queuedRan = new AtomicBoolean();
		try {
			final CompletableFuture<Void> running = pool.submit("cluster", (selected, transport) -> {
				started.countDown();
				assertThat(release.await(5, SECONDS)).isTrue();
				return null;
			});
			assertThat(started.await(5, SECONDS)).isTrue();
			final CompletableFuture<Void> queued = pool.submit("cluster", (selected, transport) -> {
				queuedRan.set(true);
				return null;
			});
			final CompletableFuture<Void> graceful = pool.shutdown();
			final CompletableFuture<Void> forced = pool.shutdownNow();
			final CompletableFuture<Void> forcedAgain = pool.shutdownNow();
			assertThat(forced).isNotSameAs(graceful).isNotSameAs(forcedAgain).isNotDone();
			assertThat(graceful).isNotDone();
			assertThat(catchThrowable(() -> running.get(5, SECONDS))).hasCauseInstanceOf(BatchTransportException.class);
			assertThat(catchThrowable(() -> queued.get(5, SECONDS))).hasCauseInstanceOf(BatchTransportException.class);
			assertThat(workers.isShutdown()).isFalse();
			release.countDown();
			graceful.get(5, SECONDS);
			forced.get(5, SECONDS);
			forcedAgain.get(5, SECONDS);
			assertThat(workers.submit(() -> "caller-owned").get(5, SECONDS)).isEqualTo("caller-owned");
			assertThat(queuedRan).isFalse();
			verify(session.transport, atLeastOnce()).close();
		} finally {
			release.countDown();
			workers.shutdownNow();
			assertThat(workers.awaitTermination(5, SECONDS)).isTrue();
			pool.shutdownNow().get(5, SECONDS);
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void synchronousCallbackCannotCloseItsOwnerAndContextIsRestored(final boolean exactSession) throws Exception {
		final TestSession session = new TestSession();
		final BatchTransportExecutor<String> pool = builder().build().registerSession("cluster", session.session);
		try {
			onBoundedCaller(() -> {
				final BatchTransportOperation<String, Exception> operation = (selected, transport) -> {
					assertSelfCloseRejected(pool);
					assertThat(pool.isShutdown()).isFalse();
					return "still open";
				};
				assertThat(exactSession ? pool.execute("cluster", session.session, operation) : pool.execute("cluster", operation))
						.isEqualTo("still open");
				final String nextResult = pool.execute("cluster", (selected, transport) -> "usable");
				assertThat(nextResult).isEqualTo("usable");
				pool.close();
				return null;
			});
		} finally {
			pool.shutdownNow().get(5, SECONDS);
		}
	}

	@ParameterizedTest
	@EnumSource(ExecutionMode.class)
	void submittedCallbackCannotCloseItsOwner(final ExecutionMode mode) throws Exception {
		final TestSession session = new TestSession();
		final ExecutorService borrowed = mode == ExecutionMode.OWNED ? null
				: mode == ExecutionMode.SUPPLIED ? Executors.newSingleThreadExecutor() : directExecutor();
		final BatchTransportExecutor<String> pool = (borrowed == null ? builder() : builder().withExecutorService(borrowed))
				.build().registerSession("cluster", session.session);
		try {
			onBoundedCaller(() -> {
				assertThat(pool.submit("cluster", (selected, transport) -> {
					assertSelfCloseRejected(pool);
					assertThat(pool.isShutdown()).isFalse();
					return "still open";
				}).get(2, SECONDS)).isEqualTo("still open");
				pool.close();
				return null;
			});
		} finally {
			if (borrowed != null && mode == ExecutionMode.SUPPLIED) {
				borrowed.shutdownNow();
				assertThat(borrowed.awaitTermination(5, SECONDS)).isTrue();
			}
			pool.shutdownNow().get(5, SECONDS);
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void inlineCompletionCannotCloseItsOwner(final boolean callbackFails) throws Exception {
		final TestSession session = new TestSession();
		final ExecutorService workers = Executors.newSingleThreadExecutor();
		final BatchTransportExecutor<String> pool = builder().withExecutorService(workers).build().registerSession("cluster", session.session);
		final CountDownLatch started = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);
		final IOException callbackFailure = new IOException("callback failed");
		try {
			final CompletableFuture<String> operation = pool.submit("cluster", (selected, transport) -> {
				started.countDown();
				assertThat(release.await(5, SECONDS)).isTrue();
				if (callbackFails) {
					throw callbackFailure;
				}
				return "result";
			});
			assertThat(started.await(5, SECONDS)).isTrue();
			final CompletableFuture<Void> completion = operation.handle((result, failure) -> {
				assertThat(failure).isSameAs(callbackFails ? callbackFailure : null);
				assertSelfCloseRejected(pool);
				assertThat(pool.isShutdown()).isFalse();
				return null;
			});
			release.countDown();
			completion.get(2, SECONDS);
			// The same borrowed worker is free of this facade's context after completion (including failure).
			workers.submit(pool::close).get(2, SECONDS);
			assertThat(workers.isShutdown()).isFalse();
		} finally {
			release.countDown();
			workers.shutdownNow();
			assertThat(workers.awaitTermination(5, SECONDS)).isTrue();
			pool.shutdownNow().get(5, SECONDS);
		}
	}

	@Test
	void inlineForcedCancellationCannotCloseBeforeCleanupCanStart() throws Exception {
		final ExecutorService queuedExecutor = mock(ExecutorService.class);
		final BatchTransportExecutor<String> pool = builder().withExecutorService(queuedExecutor).build();
		final CompletableFuture<Void> queued = pool.submit("cluster", (selected, transport) -> null);
		final CompletableFuture<Void> completion = queued.handle((result, failure) -> {
			assertThat(failure).isInstanceOf(BatchTransportException.class);
			assertThat(((BatchTransportException) failure).getKind()).isEqualTo(MailFailureKind.CANCELLED);
			assertSelfCloseRejected(pool);
			return null;
		});
		try {
			onBoundedCaller(() -> {
				pool.shutdownNow().get(2, SECONDS);
				completion.get(2, SECONDS);
				pool.close();
				return null;
			});
			verify(queuedExecutor, never()).shutdown();
			verify(queuedExecutor, never()).shutdownNow();
		} finally {
			pool.shutdownNow().get(5, SECONDS);
		}
	}

	@Test
	void nestedFacadesRetainEveryOwnerAndRestoreOuterContextAfterFailure() throws Exception {
		final TestSession outerSession = new TestSession();
		final TestSession innerSession = new TestSession();
		final BatchTransportExecutor<String> outer = builder().withMaxPoolSize(2).build().registerSession("cluster", outerSession.session);
		final BatchTransportExecutor<String> inner = builder().build().registerSession("cluster", innerSession.session);
		final IOException callbackFailure = new IOException("nested callback failed");
		try {
			onBoundedCaller(() -> {
				outer.execute("cluster", (selected, transport) -> {
					assertThat(catchThrowable(() -> inner.execute("cluster", (innerSelected, innerTransport) -> {
						assertSelfCloseRejected(outer);
						assertSelfCloseRejected(inner);
						throw callbackFailure;
					}))).isSameAs(callbackFailure);
					assertSelfCloseRejected(outer);
					assertThat(outer.isShutdown()).isFalse();
					assertThat(inner.isShutdown()).isFalse();
					// Closing a different facade is safe once its nested operation has unwound.
					inner.close();
					return null;
				});
				outer.close();
				return null;
			});
		} finally {
			inner.shutdownNow().get(5, SECONDS);
			outer.shutdownNow().get(5, SECONDS);
		}
	}

	@Test
	void nestedOperationsOfSameFacadeRetainOuterOwnerAfterInnerFailure() throws Exception {
		final TestSession session = new TestSession();
		final BatchTransportExecutor<String> pool = builder().withMaxPoolSize(2).build().registerSession("cluster", session.session);
		final IOException callbackFailure = new IOException("inner callback failed");
		try {
			onBoundedCaller(() -> {
				pool.execute("cluster", (selected, transport) -> {
					assertThat(catchThrowable(() -> pool.execute("cluster", (nestedSession, nestedTransport) -> {
						assertSelfCloseRejected(pool);
						throw callbackFailure;
					}))).isSameAs(callbackFailure);
					assertSelfCloseRejected(pool);
					assertThat(pool.isShutdown()).isFalse();
					return null;
				});
				pool.close();
				return null;
			});
		} finally {
			pool.shutdownNow().get(5, SECONDS);
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void callbacksMayInitiateNonblockingShutdown(final boolean force) throws Exception {
		final TestSession session = new TestSession();
		final BatchTransportExecutor<String> pool = builder().build().registerSession("cluster", session.session);
		try {
			final CompletableFuture<Void> cleanup = onBoundedCaller(() -> pool.execute("cluster", (selected, transport) -> {
				final CompletableFuture<Void> initiated = force ? pool.shutdownNow() : pool.shutdown();
				assertThat(initiated).isNotDone();
				return initiated;
			}));
			cleanup.get(5, SECONDS);
			pool.close();
		} finally {
			pool.shutdownNow().get(5, SECONDS);
		}
	}

	@Test
	void directExecutorRestoresContextBeforeAlreadyCompletedFutureContinuation() throws Exception {
		final TestSession session = new TestSession();
		final ExecutorService direct = directExecutor();
		final BatchTransportExecutor<String> pool = builder().withExecutorService(direct).build().registerSession("cluster", session.session);
		try {
			onBoundedCaller(() -> {
				pool.submit("cluster", (selected, transport) -> "done").thenRun(pool::close).get(2, SECONDS);
				return null;
			});
			verify(direct, never()).shutdown();
			verify(direct, never()).shutdownNow();
		} finally {
			pool.shutdownNow().get(5, SECONDS);
		}
	}

	@Test
	void rejectedSchedulingRestoresContextAndPreservesOriginalCause() throws Exception {
		final ExecutorService rejecting = mock(ExecutorService.class);
		final RejectedExecutionException rejection = new RejectedExecutionException("deliberate rejection");
		doThrow(rejection).when(rejecting).execute(any(Runnable.class));
		final BatchTransportExecutor<String> pool = builder().withExecutorService(rejecting).build();
		try {
			onBoundedCaller(() -> {
				final Throwable failure = catchThrowable(() -> pool.submit("cluster", (selected, transport) -> null).get(2, SECONDS));
				assertThat(failure).hasCauseInstanceOf(BatchTransportException.class).hasRootCause(rejection);
				assertThat(((BatchTransportException) failure.getCause()).getKind()).isEqualTo(MailFailureKind.CAPACITY);
				assertThat(pool.isShutdown()).isFalse();
				pool.close();
				return null;
			});
		} finally {
			pool.shutdownNow().get(5, SECONDS);
		}
	}

	private static void mutate(final CompletableFuture<Void> view, final ViewMutation mutation) throws Exception {
		switch (mutation) {
			case COMPLETE:
				assertThat(view.complete(null)).isTrue();
				break;
			case FAIL:
				assertThat(view.completeExceptionally(new IllegalStateException("caller failure"))).isTrue();
				break;
			case CANCEL:
				assertThat(view.cancel(true)).isTrue();
				break;
			case TIME_OUT:
				assertThat(catchThrowable(() -> view.orTimeout(1, MILLISECONDS).get(2, SECONDS))).hasCauseInstanceOf(TimeoutException.class);
				break;
			case COMPLETE_ON_TIMEOUT:
				view.completeOnTimeout(null, 1, MILLISECONDS).get(2, SECONDS);
				break;
			case OBTRUDE_VALUE:
				view.obtrudeValue(null);
				break;
			case OBTRUDE_FAILURE:
				view.obtrudeException(new IllegalStateException("caller failure"));
				break;
			default:
				throw new AssertionError(mutation);
		}
	}

	private static void assertSelfCloseRejected(final BatchTransportExecutor<?> pool) {
		assertThat(catchThrowable(pool::close)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("own shutdown").hasMessageContaining("application thread");
	}

	private static <T> T onBoundedCaller(final Callable<T> operation) throws Exception {
		final FutureTask<T> result = new FutureTask<>(operation);
		final Thread caller = new Thread(result, "batch shutdown regression caller");
		caller.start();
		try {
			return result.get(3, SECONDS);
		} finally {
			// Release the old, unguarded self-wait as well, so a failing regression cannot leave a stuck caller behind.
			caller.interrupt();
			caller.join(SECONDS.toMillis(5));
			assertThat(caller.isAlive()).isFalse();
		}
	}

	private static ExecutorService directExecutor() {
		final ExecutorService direct = mock(ExecutorService.class);
		doAnswer(invocation -> {
			invocation.<Runnable>getArgument(0).run();
			return null;
		}).when(direct).execute(any(Runnable.class));
		return direct;
	}

	private static BatchTransportExecutorBuilder<String> builder() {
		return BatchTransportExecutor.<String>builder().withCorePoolSize(0).withMaxPoolSize(1)
				.withClaimTimeout(Duration.ofSeconds(1)).withExpireAfter(Duration.ofMinutes(1)).withThreadPoolSize(1);
	}

	private static final class TestSession {
		private final Session session = mock(Session.class);
		private final Transport transport = mock(Transport.class);

		private TestSession() throws Exception {
			final Transport probe = mock(Transport.class);
			when(session.getProperties()).thenReturn(new Properties());
			when(probe.getURLName()).thenReturn(new URLName("smtp", "localhost", 25, null, null, null));
			when(transport.getURLName()).thenReturn(new URLName("smtp", "localhost", 25, null, null, null));
			when(transport.isConnected()).thenReturn(true);
			when(session.getTransport()).thenReturn(probe, transport);
		}
	}
}
