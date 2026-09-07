package org.simplejavamail.batch;

import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.URLName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BatchTransportExecutorTest {

	@Test
	void reusesConnectionAndExposesActuallySelectedSession() throws Exception {
		TestSession testSession = testSession();
		BatchTransportExecutor<String> executor = executorBuilder().build();
		try {
			executor.registerSession("transactional", testSession.session);

			Transport first = executor.execute("transactional", (session, transport) -> {
				assertThat(session).isSameAs(testSession.session);
				return transport;
			});
			Transport second = executor.execute("transactional", (session, transport) -> transport);

			assertThat(second).isSameAs(first);
			assertThat(testSession.allocatedTransports).containsExactly(first);
			verify(first).connect();
		} finally {
			executor.close();
		}
	}

	@Test
	void selectsAcrossClusterAndSupportsExactSessionClaims() throws Exception {
		TestSession sessionA = testSession();
		TestSession sessionB = testSession();
		BatchTransportExecutor<String> executor = executorBuilder().build();
		try {
			executor.registerSession("cluster", sessionA.session)
					.registerSession("cluster", sessionB.session);

			List<Session> selected = new ArrayList<>();
			executor.execute("cluster", (session, transport) -> selected.add(session));
			executor.execute("cluster", (session, transport) -> selected.add(session));

			assertThat(selected).containsExactlyInAnyOrder(sessionA.session, sessionB.session);
			Session sticky = executor.execute("cluster", sessionB.session, (session, transport) -> session);
			assertThat(sticky).isSameAs(sessionB.session);
		} finally {
			executor.close();
		}
	}

	@Test
	void invalidatesAfterFailureAndPreservesCheckedCause() throws Exception {
		TestSession testSession = testSession();
		BatchTransportExecutor<String> executor = executorBuilder().build();
		IOException callbackFailure = new IOException("deliberate callback failure");
		try {
			executor.registerSession("cluster", testSession.session);

			assertThatThrownBy(() -> executor.execute("cluster", (session, transport) -> {
				throw callbackFailure;
			})).isSameAs(callbackFailure);

			Transport replacement = executor.execute("cluster", (session, transport) -> transport);
			assertThat(testSession.allocatedTransports).hasSize(2);
			assertThat(replacement).isSameAs(testSession.allocatedTransports.get(1));
		} finally {
			executor.close();
		}
		verify(testSession.allocatedTransports.get(0), timeout(2000).atLeastOnce()).close();
	}

	@Test
	void asyncFailureCompletesFutureWithOriginalCause() throws Exception {
		TestSession testSession = testSession();
		BatchTransportExecutor<String> executor = executorBuilder().build();
		IOException callbackFailure = new IOException("checked async failure");
		try {
			executor.registerSession("cluster", testSession.session);
			CompletableFuture<Object> result = executor.submit("cluster", (session, transport) -> {
				throw callbackFailure;
			});

			assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS))
					.isInstanceOf(ExecutionException.class)
					.hasCause(callbackFailure);
		} finally {
			executor.close();
		}
	}

	@Test
	void selectedSessionOwnsItsOAuth2Supplier() throws Exception {
		TestSession sessionA = testSession();
		TestSession sessionB = testSession();
		AtomicInteger supplierACalls = new AtomicInteger();
		AtomicInteger supplierBCalls = new AtomicInteger();
		Supplier<String> supplierA = () -> "token-a-" + supplierACalls.incrementAndGet();
		Supplier<String> supplierB = () -> "token-b-" + supplierBCalls.incrementAndGet();
		sessionA.properties.put(BatchTransportExecutor.OAUTH2_TOKEN_PROVIDER_PROPERTY, supplierA);
		sessionB.properties.put(BatchTransportExecutor.OAUTH2_TOKEN_PROVIDER_PROPERTY, supplierB);
		sessionA.properties.setProperty("mail.smtp.user", "a@example.com");
		sessionB.properties.setProperty("mail.smtp.user", "b@example.com");
		BatchTransportExecutor<String> executor = executorBuilder().build();
		try {
			executor.registerSession("cluster", sessionA.session)
					.registerSession("cluster", sessionB.session);

			executor.execute("cluster", (session, transport) -> null);
			executor.execute("cluster", (session, transport) -> null);

			assertThat(supplierACalls).hasValue(1);
			assertThat(supplierBCalls).hasValue(1);
			verify(sessionA.allocatedTransports.get(0)).connect("a@example.com", "token-a-1");
			verify(sessionB.allocatedTransports.get(0)).connect("b@example.com", "token-b-1");
		} finally {
			executor.close();
		}
	}

	@ParameterizedTest(name = "core={0}, sticky={1}, recipientRejected={2}")
	@CsvSource({"0,false,false", "0,false,true", "0,true,false", "0,true,true",
			"1,false,false", "1,false,true", "1,true,false", "1,true,true"})
	void failedAsyncOperationsWakeAlreadyWaitingAttempts(final int coreSize, final boolean stickySession,
			final boolean recipientRejected) throws Exception {
		final TestSession testSession = testSession();
		final AtomicReference<Thread> waitingThread = new AtomicReference<>();
		final ExecutorService workers = Executors.newFixedThreadPool(2, task -> {
			final Thread worker = new Thread(task, "batch-claim-recovery-test");
			waitingThread.set(worker);
			return worker;
		});
		final BatchTransportExecutor<String> executor = executorBuilder()
				.withCorePoolSize(coreSize).withMaxPoolSize(1).withExecutorService(workers).build();
		final CountDownLatch firstActive = new CountDownLatch(1);
		final CountDownLatch allowFailure = new CountDownLatch(1);
		final AtomicReference<Transport> firstTransport = new AtomicReference<>();
		final MessagingException callbackFailure = recipientRejected
				? new SendFailedException("recipient rejected") : new MessagingException("connection failed");
		try {
			executor.registerSession("cluster", testSession.session);
			final CompletableFuture<Object> failed = executor.submit("cluster", (session, transport) -> {
				firstTransport.set(transport);
				firstActive.countDown();
				assertThat(allowFailure.await(5, TimeUnit.SECONDS)).isTrue();
				throw callbackFailure;
			});
			assertThat(firstActive.await(3, TimeUnit.SECONDS)).isTrue();
			final CompletableFuture<Transport> waiting = stickySession
					? executor.submit("cluster", testSession.session, (session, transport) -> transport)
					: executor.submit("cluster", (session, transport) -> transport);
			awaitBlockedPoolClaim(waitingThread);
			allowFailure.countDown();

			assertThatThrownBy(() -> failed.get(3, TimeUnit.SECONDS))
					.isInstanceOf(ExecutionException.class).hasCause(callbackFailure);
			final Transport recovered = waiting.get(3, TimeUnit.SECONDS);
			assertThat(testSession.allocatedTransports).hasSize(2);
			assertThat(recovered).isNotSameAs(firstTransport.get())
					.isSameAs(testSession.allocatedTransports.get(1));
		} finally {
			allowFailure.countDown();
			workers.shutdownNow();
			assertThat(workers.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
			executor.shutdownNow().get(3, TimeUnit.SECONDS);
		}
		verify(firstTransport.get(), atLeastOnce()).close();
	}

	@Test
	void bridgesFixedOAuth2TokenAtRegistration() throws Exception {
		TestSession testSession = testSession();
		testSession.properties.setProperty(BatchTransportExecutor.OAUTH2_TOKEN_PROPERTY, "fixed-token");
		testSession.properties.setProperty("mail.smtp.user", "fixed@example.com");
		BatchTransportExecutor<String> executor = executorBuilder().build();
		try {
			executor.registerSession("cluster", testSession.session);
			executor.execute("cluster", (session, transport) -> null);

			verify(testSession.allocatedTransports.get(0)).connect("fixed@example.com", "fixed-token");
		} finally {
			executor.close();
		}
	}

	@Test
	void suppliedExecutorRemainsCallerOwned() throws Exception {
		ExecutorService callerExecutor = Executors.newSingleThreadExecutor();
		TestSession testSession = testSession();
		BatchTransportExecutor<String> executor = executorBuilder()
				.withExecutorService(callerExecutor)
				.build();
		try {
			executor.registerSession("cluster", testSession.session);
			assertThat(executor.submit("cluster", (session, transport) -> "done").get(5, TimeUnit.SECONDS)).isEqualTo("done");

			executor.close();

			assertThat(callerExecutor.isShutdown()).isFalse();
			assertThat(callerExecutor.submit(() -> "still-owned-by-caller").get(5, TimeUnit.SECONDS))
					.isEqualTo("still-owned-by-caller");
		} finally {
			callerExecutor.shutdownNow();
		}
	}

	@Test
	void gracefulShutdownWaitsForAcceptedWorkAndIsIdempotent() throws Exception {
		TestSession testSession = testSession();
		CountDownLatch callbackStarted = new CountDownLatch(1);
		CountDownLatch releaseCallback = new CountDownLatch(1);
		BatchTransportExecutor<String> executor = executorBuilder().build();
		executor.registerSession("cluster", testSession.session);
		CompletableFuture<String> work = executor.submit("cluster", (session, transport) -> {
			callbackStarted.countDown();
			releaseCallback.await();
			return "complete";
		});
		assertThat(callbackStarted.await(5, TimeUnit.SECONDS)).isTrue();

		CompletableFuture<Void> shutdown = executor.shutdown();
		assertThat(executor.shutdown()).isSameAs(shutdown);
		assertThat(shutdown).isNotDone();
		assertThatThrownBy(() -> executor.submit("cluster", (session, transport) -> null))
				.isInstanceOf(BatchTransportException.class);
		assertThatThrownBy(() -> executor.execute("cluster", (session, transport) -> null))
				.isInstanceOf(BatchTransportException.class);
		assertThatThrownBy(() -> executor.registerSession("cluster", testSession.session))
				.isInstanceOf(BatchTransportException.class);

		releaseCallback.countDown();
		assertThat(work.get(5, TimeUnit.SECONDS)).isEqualTo("complete");
		shutdown.get(5, TimeUnit.SECONDS);
		assertThat(executor.isShutdown()).isTrue();
	}

	@Test
	void forcedShutdownInvalidatesActiveLease() throws Exception {
		TestSession testSession = testSession();
		CountDownLatch callbackStarted = new CountDownLatch(1);
		BatchTransportExecutor<String> executor = executorBuilder().build();
		executor.registerSession("cluster", testSession.session);
		CompletableFuture<Void> work = executor.submit("cluster", (session, transport) -> {
			callbackStarted.countDown();
			new CountDownLatch(1).await();
			return null;
		});
		assertThat(callbackStarted.await(5, TimeUnit.SECONDS)).isTrue();

		CompletableFuture<Void> shutdown = executor.shutdownNow();

		assertThat(executor.shutdownNow()).isSameAs(shutdown);
		assertThatThrownBy(() -> work.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
		shutdown.get(5, TimeUnit.SECONDS);
		verify(testSession.allocatedTransports.get(0), atLeastOnce()).close();
	}

	@Test
	void rejectsProviderOwnedPoolBeforeRegistration() throws Exception {
		Session session = mock(Session.class);
		Properties properties = new Properties();
		properties.setProperty("mail.transport.protocol", "smtppool");
		when(session.getProperties()).thenReturn(properties);
		BatchTransportExecutor<String> executor = executorBuilder().build();
		try {
			assertThatThrownBy(() -> executor.registerSession("cluster", session))
					.isInstanceOf(BatchTransportException.class)
					.hasMessageContaining("exactly one component");
			verify(session, never()).getTransport();
		} finally {
			executor.close();
		}
	}

	@Test
	void rejectsProviderOwnedPoolSelectedByProviderRegistry() throws Exception {
		Session session = mock(Session.class);
		Transport providerTransport = transport("smtppool");
		when(session.getProperties()).thenReturn(new Properties());
		when(session.getTransport()).thenReturn(providerTransport);
		BatchTransportExecutor<String> executor = executorBuilder().build();
		try {
			assertThatThrownBy(() -> executor.registerSession("cluster", session))
					.isInstanceOf(BatchTransportException.class)
					.hasMessageContaining("exactly one component");
			verify(providerTransport).close();
		} finally {
			executor.close();
		}
	}

	@Test
	void validatesPoolAndExecutorSettingsAtBuildTime() {
		assertThatThrownBy(() -> BatchTransportPoolConfiguration.builder()
				.withCorePoolSize(2)
				.withMaxPoolSize(1)
				.build()).isInstanceOf(IllegalArgumentException.class);
		BatchTransportExecutor<String> noExpiry = BatchTransportExecutor.<String>builder()
				.withExpireAfterMillis(0)
				.build();
		noExpiry.close();
		assertThatThrownBy(() -> BatchTransportExecutor.<String>builder()
				.withThreadPoolSize(0)
				.build()).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void interruptedClaimRestoresInterruptFlagAndRetainsCause() throws Exception {
		TestSession testSession = testSession();
		CountDownLatch firstClaimActive = new CountDownLatch(1);
		CountDownLatch releaseFirstClaim = new CountDownLatch(1);
		BatchTransportExecutor<String> executor = executorBuilder().withMaxPoolSize(1).build();
		executor.registerSession("cluster", testSession.session);
		CompletableFuture<Void> first = executor.submit("cluster", (session, transport) -> {
			firstClaimActive.countDown();
			releaseFirstClaim.await();
			return null;
		});
		assertThat(firstClaimActive.await(5, TimeUnit.SECONDS)).isTrue();

		CountDownLatch secondClaimStarted = new CountDownLatch(1);
		AtomicReference<Throwable> observedFailure = new AtomicReference<>();
		AtomicBoolean interruptRestored = new AtomicBoolean();
		Thread blockedClaim = new Thread(() -> {
			secondClaimStarted.countDown();
			try {
				executor.execute("cluster", (session, transport) -> null);
			} catch (Throwable failure) {
				observedFailure.set(failure);
				interruptRestored.set(Thread.currentThread().isInterrupted());
			}
		}, "interrupted batch claim test");
		blockedClaim.start();
		assertThat(secondClaimStarted.await(5, TimeUnit.SECONDS)).isTrue();
		blockedClaim.interrupt();
		blockedClaim.join(TimeUnit.SECONDS.toMillis(5));

		assertThat(blockedClaim.isAlive()).isFalse();
		assertThat(observedFailure.get()).isInstanceOf(BatchTransportException.class)
				.hasCauseInstanceOf(InterruptedException.class);
		assertThat(interruptRestored).isTrue();

		releaseFirstClaim.countDown();
		first.get(5, TimeUnit.SECONDS);
		executor.close();
	}

	private static BatchTransportExecutorBuilder<String> executorBuilder() {
		return BatchTransportExecutor.<String>builder()
				.withCorePoolSize(0)
				.withMaxPoolSize(2)
				.withClaimTimeoutMillis(1000)
				.withExpireAfterMillis(60000);
	}

	private static void awaitBlockedPoolClaim(final AtomicReference<Thread> thread) throws InterruptedException {
		final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
		while (System.nanoTime() < deadline) {
			final Thread worker = thread.get();
			if (worker != null && worker.getState() == Thread.State.TIMED_WAITING) {
				for (StackTraceElement frame : worker.getStackTrace()) {
					if (frame.getClassName().equals("org.bbottema.genericobjectpool.GenericObjectPool")
							&& frame.getMethodName().equals("waitForAvailableObjectOrTimeout")) {
						return;
					}
				}
			}
			Thread.sleep(1);
		}
		fail("The asynchronous attempt did not reach the underlying pool's condition wait");
	}

	private static TestSession testSession() throws Exception {
		Session session = mock(Session.class);
		Properties properties = new Properties();
		Transport probe = transport("probe");
		List<Transport> allocated = new ArrayList<>();
		AtomicInteger calls = new AtomicInteger();
		when(session.getProperties()).thenReturn(properties);
		when(session.getTransport()).thenAnswer(invocation -> {
			if (calls.getAndIncrement() == 0) {
				return probe;
			}
			Transport transport = transport("smtp-" + calls.get());
			allocated.add(transport);
			return transport;
		});
		return new TestSession(session, properties, allocated);
	}

	private static Transport transport(String protocol) throws Exception {
		Transport transport = mock(Transport.class);
		when(transport.getURLName()).thenReturn(new URLName(protocol, "localhost", 25, null, null, null));
		when(transport.isConnected()).thenReturn(true);
		return transport;
	}

	private static final class TestSession {
		private final Session session;
		private final Properties properties;
		private final List<Transport> allocatedTransports;

		private TestSession(Session session, Properties properties, List<Transport> allocatedTransports) {
			this.session = session;
			this.properties = properties;
			this.allocatedTransports = allocatedTransports;
		}
	}
}
