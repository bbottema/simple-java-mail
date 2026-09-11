package org.simplejavamail.internal.batchsupport;

import jakarta.mail.Session;
import jakarta.mail.Transport;
import org.bbottema.genericobjectpool.PoolableObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.batch.BatchTransportException;
import org.simplejavamail.batch.BatchTransportPoolConfiguration;
import org.simplejavamail.smtpconnectionpool.SessionTransport;
import org.simplejavamail.smtpconnectionpool.SmtpTransportLease;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LifecycleDelegatingTransportImplTest {

	private final BatchTransportEngine<String> engine = new BatchTransportEngine<>(BatchTransportPoolConfiguration.builder().build());
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private final CountDownLatch disposalWaitStarted = new CountDownLatch(1);
	private final CompletableFuture<Void> disposal = new CompletableFuture<Void>() {
		@Override
		public Void join() {
			disposalWaitStarted.countDown();
			return super.join();
		}
	};
	private PoolableObject<SessionTransport> pooledTransport;
	private SmtpTransportLease lease;
	private LifecycleDelegatingTransportImpl transport;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void prepareLease() {
		pooledTransport = mock(PoolableObject.class);
		when(pooledTransport.getAllocatedObject()).thenReturn(new SessionTransport(mock(Session.class), mock(Transport.class)));
		when(pooledTransport.getDisposalCompletion()).thenReturn(disposal);
		lease = new SmtpTransportLease(pooledTransport);
		transport = new LifecycleDelegatingTransportImpl(engine, lease, null);
	}

	@AfterEach
	void closeResources() throws Exception {
		disposal.complete(null);
		executor.shutdownNow();
		try {
			assertThat(executor.awaitTermination(5, SECONDS)).isTrue();
		} finally {
			engine.shutdown().get(5, SECONDS);
		}
	}

	@Test
	void healthyReleaseDoesNotWaitForEventualDisposal() throws Exception {
		executor.submit(transport::signalTransportUsed).get(5, SECONDS);

		assertThat(lease.getState()).isEqualTo(SmtpTransportLease.State.RELEASED);
		assertThat(disposal).isNotDone();
		verify(pooledTransport).release();
		verify(pooledTransport, never()).getDisposalCompletion();
	}

	@Test
	void failedReleaseWaitsForInvalidatedDisposal() throws Exception {
		final RuntimeException releaseFailure = new IllegalStateException("release failed");
		doThrow(releaseFailure).when(pooledTransport).release();

		final Future<?> completion = executor.submit(transport::signalTransportUsed);
		assertThat(disposalWaitStarted.await(5, SECONDS)).isTrue();
		assertThat(lease.getState()).isEqualTo(SmtpTransportLease.State.INVALIDATED);
		assertThat(completion.isDone()).isFalse();
		disposal.complete(null);

		final Throwable reported = catchThrowable(() -> completion.get(5, SECONDS));
		assertThat(reported).isInstanceOf(ExecutionException.class);
		assertThat(reported.getCause()).isInstanceOf(BatchTransportException.class).hasCause(releaseFailure).hasNoSuppressedExceptions();
		verify(pooledTransport).release();
		verify(pooledTransport).invalidate();
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void disposalFailureDoesNotReplaceReleaseFailure(final boolean releaseThrowsError) {
		final Throwable releaseFailure = releaseThrowsError ? new AssertionError("release failed") : new IllegalStateException("release failed");
		final RuntimeException disposalFailure = new IllegalArgumentException("disposal failed");
		doThrow(releaseFailure).when(pooledTransport).release();
		disposal.completeExceptionally(disposalFailure);

		final Throwable reported = catchThrowable(transport::signalTransportUsed);

		if (releaseThrowsError) {
			assertThat(reported).isSameAs(releaseFailure);
		} else {
			assertThat(reported).isInstanceOf(BatchTransportException.class).hasCause(releaseFailure);
		}
		assertThat(reported.getSuppressed()).hasSize(1);
		assertThat(reported.getSuppressed()[0]).isInstanceOf(CompletionException.class).hasCause(disposalFailure);
		assertThat(lease.getState()).isEqualTo(SmtpTransportLease.State.INVALIDATED);
		verify(pooledTransport).release();
		verify(pooledTransport).invalidate();
	}

	@Test
	void failedInvalidationStillWaitsForDisposal() throws Exception {
		final RuntimeException invalidationFailure = new IllegalStateException("invalidation failed");
		doThrow(invalidationFailure).when(pooledTransport).invalidate();

		final Future<?> completion = executor.submit(transport::signalTransportFailed);
		assertThat(disposalWaitStarted.await(5, SECONDS)).isTrue();
		assertThat(lease.getState()).isEqualTo(SmtpTransportLease.State.INVALIDATED);
		assertThat(completion.isDone()).isFalse();
		disposal.complete(null);

		final Throwable reported = catchThrowable(() -> completion.get(5, SECONDS));
		assertThat(reported).isInstanceOf(ExecutionException.class);
		assertThat(reported.getCause()).isInstanceOf(BatchTransportException.class).hasCause(invalidationFailure).hasNoSuppressedExceptions();
		verify(pooledTransport).invalidate();
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void disposalFailureDoesNotReplaceInvalidationFailure(final boolean invalidationThrowsError) {
		final Throwable invalidationFailure = invalidationThrowsError ? new AssertionError("invalidation failed") : new IllegalStateException("invalidation failed");
		final RuntimeException disposalFailure = new IllegalArgumentException("disposal failed");
		doThrow(invalidationFailure).when(pooledTransport).invalidate();
		disposal.completeExceptionally(disposalFailure);

		final Throwable reported = catchThrowable(transport::signalTransportFailed);

		if (invalidationThrowsError) {
			assertThat(reported).isSameAs(invalidationFailure);
		} else {
			assertThat(reported).isInstanceOf(BatchTransportException.class).hasCause(invalidationFailure);
		}
		assertThat(reported.getSuppressed()).hasSize(1);
		assertThat(reported.getSuppressed()[0]).isInstanceOf(CompletionException.class).hasCause(disposalFailure);
		assertThat(lease.getState()).isEqualTo(SmtpTransportLease.State.INVALIDATED);
		verify(pooledTransport).invalidate();
	}

	@Test
	void disposalFailurePropagatesWhenInvalidationDoesNotFail() {
		final RuntimeException disposalFailure = new IllegalStateException("disposal failed");
		disposal.completeExceptionally(disposalFailure);

		assertThat(catchThrowable(transport::signalTransportFailed)).isInstanceOf(CompletionException.class).hasCause(disposalFailure);
		assertThat(lease.getState()).isEqualTo(SmtpTransportLease.State.INVALIDATED);
		verify(pooledTransport).invalidate();
	}

	@Test
	void abortFailureRetainsBothInvalidationAndDisposalFailures() {
		final RuntimeException abortFailure = new IllegalStateException("abort failed");
		final RuntimeException invalidationFailure = new IllegalArgumentException("invalidation failed");
		final RuntimeException disposalFailure = new IllegalArgumentException("disposal failed");
		final SmtpTransportLease abortableLease = mock(SmtpTransportLease.class);
		when(abortableLease.getCancellation()).thenReturn(Optional.of(() -> { throw abortFailure; }));
		when(abortableLease.getState()).thenReturn(SmtpTransportLease.State.INVALIDATED);
		when(abortableLease.getDisposalCompletion()).thenReturn(disposal);
		when(abortableLease.invalidate()).thenThrow(invalidationFailure);
		disposal.completeExceptionally(disposalFailure);
		final LifecycleDelegatingTransportImpl abortableTransport = new LifecycleDelegatingTransportImpl(engine, abortableLease, null);

		final Throwable reported = catchThrowable(abortableTransport::signalTransportFailed);

		assertThat(reported).isSameAs(abortFailure);
		assertThat(reported.getSuppressed()).hasSize(2);
		assertThat(reported.getSuppressed()[0]).isInstanceOf(BatchTransportException.class).hasCause(invalidationFailure);
		assertThat(reported.getSuppressed()[1]).isInstanceOf(CompletionException.class).hasCause(disposalFailure);
		verify(abortableLease).invalidate();
	}

	@Test
	void repeatedAbortFailureIsNotSuppressedOntoItself() {
		final AssertionError abortFailure = new AssertionError("abort failed");
		final SmtpTransportLease abortableLease = mock(SmtpTransportLease.class);
		when(abortableLease.getCancellation()).thenReturn(Optional.of(() -> { throw abortFailure; }));
		when(abortableLease.getState()).thenReturn(SmtpTransportLease.State.INVALIDATED);
		when(abortableLease.getDisposalCompletion()).thenReturn(disposal);
		when(abortableLease.invalidate()).thenThrow(abortFailure);
		disposal.complete(null);
		final LifecycleDelegatingTransportImpl abortableTransport = new LifecycleDelegatingTransportImpl(engine, abortableLease, null);

		assertThat(catchThrowable(abortableTransport::signalTransportFailed)).isSameAs(abortFailure).hasNoSuppressedExceptions();
		verify(abortableLease).invalidate();
		verify(abortableLease).getDisposalCompletion();
	}

	@Test
	void returningAnAlreadyInvalidatedLeaseWaitsForDisposal() throws Exception {
		lease.invalidate();

		final Future<?> completion = executor.submit(transport::signalTransportUsed);
		assertThat(disposalWaitStarted.await(5, SECONDS)).isTrue();
		assertThat(completion.isDone()).isFalse();
		disposal.complete(null);
		completion.get(5, SECONDS);

		verify(pooledTransport, never()).release();
		verify(pooledTransport).invalidate();
	}

	@Test
	void disposalFailurePropagatesWhenReleaseDoesNotFail() {
		final RuntimeException disposalFailure = new IllegalStateException("disposal failed");
		lease.invalidate();
		disposal.completeExceptionally(disposalFailure);

		assertThat(catchThrowable(transport::signalTransportUsed)).isInstanceOf(CompletionException.class).hasCause(disposalFailure);
		verify(pooledTransport, never()).release();
		verify(pooledTransport).invalidate();
	}
}
