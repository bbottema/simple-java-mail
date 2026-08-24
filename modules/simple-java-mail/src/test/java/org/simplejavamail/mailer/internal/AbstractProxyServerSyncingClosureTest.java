package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.internal.authenticatedsockssupport.socks5server.AnonymousSocks5Server;
import org.simplejavamail.api.mailer.config.TransportStrategy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AbstractProxyServerSyncingClosureTest {

	@Test
	void updatesCustomSessionWithEveryEphemeralBridgeRestart() {
		final Session session = Session.getInstance(new Properties());
		final RecordingProxyServer proxyServer = new RecordingProxyServer(41001, 41002);
		final AtomicInteger connectionCounter = new AtomicInteger();
		final List<String> portsSeenByOperation = new ArrayList<>();

		new InspectingClosure(connectionCounter, proxyServer, session, "mail.smtp.socks.port", portsSeenByOperation, null).run();
		new InspectingClosure(connectionCounter, proxyServer, session, "mail.smtp.socks.port", portsSeenByOperation, null).run();

		assertThat(portsSeenByOperation).containsExactly("41001", "41002");
		assertThat(session.getProperty("mail.smtp.socks.port")).isEqualTo("41002");
		assertThat(proxyServer.startCount).hasValue(2);
		assertThat(proxyServer.stopCount).hasValue(2);
	}

	@Test
	void updatesTheSmtpsPropertySelectedByTheSession() {
		final Session session = Session.getInstance(TransportStrategy.SMTPS.generateProperties());
		final RecordingProxyServer proxyServer = new RecordingProxyServer(42001);
		final List<String> portsSeenByOperation = new ArrayList<>();

		new InspectingClosure(new AtomicInteger(), proxyServer, session, "mail.smtps.socks.port", portsSeenByOperation, null).run();

		assertThat(portsSeenByOperation).containsExactly("42001");
		assertThat(session.getProperty("mail.smtps.socks.port")).isEqualTo("42001");
		assertThat(session.getProperty("mail.smtp.socks.port")).isNull();
	}

	@Test
	void concurrentOperationsShareOneBridgeGeneration() throws Exception {
		final Session session = Session.getInstance(TransportStrategy.SMTP_TLS.generateProperties());
		final RecordingProxyServer proxyServer = new RecordingProxyServer(43001, 43002);
		final AtomicInteger connectionCounter = new AtomicInteger();
		final CountDownLatch operationsEntered = new CountDownLatch(2);
		final List<String> portsSeenByOperation = Collections.synchronizedList(new ArrayList<>());
		final ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			final Future<?> first = executor.submit(new InspectingClosure(connectionCounter, proxyServer, session,
					"mail.smtp.socks.port", portsSeenByOperation, operationsEntered));
			final Future<?> second = executor.submit(new InspectingClosure(connectionCounter, proxyServer, session,
					"mail.smtp.socks.port", portsSeenByOperation, operationsEntered));

			first.get(5, TimeUnit.SECONDS);
			second.get(5, TimeUnit.SECONDS);
		} finally {
			executor.shutdownNow();
		}

		assertThat(portsSeenByOperation).containsExactlyInAnyOrder("43001", "43001");
		assertThat(proxyServer.startCount).hasValue(1);
		assertThat(proxyServer.stopCount).hasValue(1);
		assertThat(connectionCounter).hasValue(0);
	}

	private static final class InspectingClosure extends AbstractProxyServerSyncingClosure {
		private final Session session;
		private final String propertyName;
		private final List<String> portsSeenByOperation;
		private final CountDownLatch operationsEntered;

		private InspectingClosure(final AtomicInteger connectionCounter, final AnonymousSocks5Server proxyServer, final Session session,
				final String propertyName, final List<String> portsSeenByOperation, final CountDownLatch operationsEntered) {
			super(connectionCounter, proxyServer, session);
			this.session = session;
			this.propertyName = propertyName;
			this.portsSeenByOperation = portsSeenByOperation;
			this.operationsEntered = operationsEntered;
		}

		@Override
		void executeClosure() {
			portsSeenByOperation.add(session.getProperty(propertyName));
			if (operationsEntered != null) {
				operationsEntered.countDown();
				try {
					if (!operationsEntered.await(5, TimeUnit.SECONDS)) {
						throw new AssertionError("Concurrent proxy operations did not overlap");
					}
				} catch (final InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new AssertionError("Interrupted while coordinating concurrent proxy operations", e);
				}
			}
		}
	}

	private static final class RecordingProxyServer implements AnonymousSocks5Server {
		private final int[] ports;
		private final AtomicInteger startCount = new AtomicInteger();
		private final AtomicInteger stopCount = new AtomicInteger();
		private volatile boolean running;
		private volatile int localPort = -1;

		private RecordingProxyServer(final int... ports) {
			this.ports = ports;
		}

		@Override
		public void start() {
			localPort = ports[startCount.getAndIncrement()];
			running = true;
		}

		@Override
		public void stop() {
			stopCount.incrementAndGet();
			running = false;
		}

		@Override
		public boolean isStopping() {
			return false;
		}

		@Override
		public boolean isRunning() {
			return running;
		}

		@Override
		public int getLocalPort() {
			return localPort;
		}

		@Override
		public void run() {
			throw new UnsupportedOperationException("Not needed by this lifecycle test");
		}
	}
}
