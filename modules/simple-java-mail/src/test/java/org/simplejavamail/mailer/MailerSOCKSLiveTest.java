package org.simplejavamail.mailer;


import jakarta.mail.Session;
import org.simplejavamail.api.SimpleJavaMail;

import org.bbottema.javasocksproxyserver.RunningSocksServer;
import org.bbottema.javasocksproxyserver.SyncSocksServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.subethamail.smtp.server.SMTPServer;
import org.subethamail.wiser.Wiser;
import testutil.ConfigLoaderTestHelper;
import testutil.EmailHelper;

import javax.net.ServerSocketFactory;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.internal.util.Preconditions.verifyNonnullOrEmpty;

public class MailerSOCKSLiveTest {
	private static final char[] TLS_KEYSTORE_PASSWORD = "changeit".toCharArray();

	private final SyncSocksServer socksServer = new SyncSocksServer();
	private final AtomicInteger acceptedProxyConnections = new AtomicInteger();
	private RunningSocksServer proxyServer;
	private Wiser smtpServer;

	@BeforeEach
	public void setup() {
		proxyServer = socksServer.startServer(0, new CountingServerSocketFactory(acceptedProxyConnections));
	}

	@AfterEach
	public void tearDown() {
		if (proxyServer != null) {
			proxyServer.stop();
			proxyServer = null;
		}
		if (smtpServer != null) {
			smtpServer.stop();
			smtpServer = null;
		}
	}

	@Test
	public void testSOCKSPassthrough_Anonymous() throws Exception {
		startPlainSmtpServer();
		try (Mailer mailer = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder()
				.withSMTPServer("localhost", smtpServer.getServer().getPortAllocated())
				.withProxy("localhost", proxyServer.getPort())
				.buildMailer()) {
			assertSendingEmail(mailer, EmailHelper.createDummyEmailBuilder(true, true, false, false, false, false), false);
		}
	}

	@Test
	// NOTE: this doesn't really trigger authentication because the embedded SOCKS server doesn't support it,
	// but it triggers the code on the mailer side, which should not produce errors either
	public void testSOCKSPassthrough_Authenticating() throws Exception {
		startPlainSmtpServer();
		try (Mailer mailer = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder()
				.withSMTPServer("localhost", smtpServer.getServer().getPortAllocated())
				.withProxy("localhost", proxyServer.getPort(), "username", "password")
				.buildMailer()) {
			assertSendingEmail(mailer, EmailHelper.createDummyEmailBuilder(true, true, false, false, false, false), false);
		}
	}

	@Test
	public void testAutomaticBridgePortWithoutBatchModule() throws Exception {
		startPlainSmtpServer();
		try (MockedStatic<ModuleLoader> moduleLoader = Mockito.mockStatic(ModuleLoader.class, Mockito.CALLS_REAL_METHODS)) {
			moduleLoader.when(ModuleLoader::batchModuleAvailable).thenReturn(false);
			try (Mailer mailer = newAuthenticatedMailer()) {
				assertSendingEmail(mailer, EmailHelper.createDummyEmailBuilder(true, true, false, false, false, false), false);
				assertThat(Integer.parseInt(mailer.getSession().getProperty("mail.smtp.socks.port"))).isPositive();
			}
		}
	}

	@Test
	public void testAutomaticBridgePortWithConnectionTest() throws Exception {
		startPlainSmtpServer();
		try (Mailer mailer = newAuthenticatedMailer()) {
			mailer.testConnection();

			assertThat(Integer.parseInt(mailer.getSession().getProperty("mail.smtp.socks.port"))).isPositive();
			assertThat(acceptedProxyConnections).hasValueGreaterThan(0);
		}
	}

	@Test
	public void testAutomaticBridgePortWithConnectionPool() throws Exception {
		startPlainSmtpServer();
		try (Mailer mailer = newAuthenticatedMailer()) {
			assertSendingEmail(mailer, EmailHelper.createDummyEmailBuilder(true, true, false, false, false, false), false);
			assertSendingEmail(mailer, EmailHelper.createDummyEmailBuilder(true, true, false, false, false, false), false);

			assertThat(Integer.parseInt(mailer.getSession().getProperty("mail.smtp.socks.port"))).isPositive();
		}
	}

	@Test
	public void testAutomaticBridgePortWithSimpleBatch() throws Exception {
		startPlainSmtpServer();
		try (Mailer mailer = newAuthenticatedMailer()) {
			final Email first = EmailHelper.createDummyEmailBuilder(true, true, false, false, false, false).buildEmail();
			final Email second = EmailHelper.createDummyEmailBuilder(true, true, false, false, false, false).buildEmail();

			mailer.sendMailsInSimpleBatch(Arrays.asList(first, second), false).get();

			assertThat(Integer.parseInt(mailer.getSession().getProperty("mail.smtp.socks.port"))).isPositive();
			assertThat(acceptedProxyConnections).hasValueGreaterThan(0);
			assertThat(smtpServer.getMessages()).hasSize(2);
		}
	}

	@Test
	public void testAutomaticBridgePortWithCustomSession() throws Exception {
		startPlainSmtpServer();
		final Properties properties = new Properties();
		properties.setProperty("mail.transport.protocol", "smtp");
		properties.setProperty("mail.smtp.host", "localhost");
		properties.setProperty("mail.smtp.port", String.valueOf(smtpServer.getServer().getPortAllocated()));
		final Session session = Session.getInstance(properties);

		try (Mailer mailer = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder(session)
				.withProxy("localhost", proxyServer.getPort(), "username", "password")
				.buildMailer()) {
			mailer.withOpenConnection(sender -> {
				assertThat(Integer.parseInt(session.getProperty("mail.smtp.socks.port"))).isPositive();
			});
			assertThat(acceptedProxyConnections).hasValueGreaterThan(0);
		}
	}

	@Test
	public void testExplicitBridgePortRemainsFixed() throws Exception {
		startPlainSmtpServer();
		final int fixedBridgePort;
		try (ServerSocket availablePort = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
			fixedBridgePort = availablePort.getLocalPort();
		}

		try (Mailer mailer = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder()
				.withSMTPServer("localhost", smtpServer.getServer().getPortAllocated())
				.withProxy("localhost", proxyServer.getPort(), "username", "password")
				.withProxyBridgePort(fixedBridgePort)
				.buildMailer()) {
			mailer.withOpenConnection(sender -> assertThat(mailer.getSession().getProperty("mail.smtp.socks.port"))
					.isEqualTo(String.valueOf(fixedBridgePort)));
		}
	}

	@Test
	public void testSeparateMailersUseDifferentAutomaticBridgePortsConcurrently() throws Exception {
		startPlainSmtpServer();
		final CountDownLatch bothConnectionsOpen = new CountDownLatch(2);
		final CountDownLatch releaseConnections = new CountDownLatch(1);
		final ExecutorService executor = Executors.newFixedThreadPool(2);
		try (Mailer firstMailer = newAuthenticatedMailer(); Mailer secondMailer = newAuthenticatedMailer()) {
			final Session firstSession = firstMailer.getSession();
			final Session secondSession = secondMailer.getSession();
			final Future<?> first = executor.submit(() -> {
				firstMailer.withOpenConnection(sender -> awaitConcurrentConnection(bothConnectionsOpen, releaseConnections));
				return null;
			});
			final Future<?> second = executor.submit(() -> {
				secondMailer.withOpenConnection(sender -> awaitConcurrentConnection(bothConnectionsOpen, releaseConnections));
				return null;
			});

			try {
				assertThat(bothConnectionsOpen.await(10, TimeUnit.SECONDS)).isTrue();
				final int firstPort = Integer.parseInt(firstSession.getProperty("mail.smtp.socks.port"));
				final int secondPort = Integer.parseInt(secondSession.getProperty("mail.smtp.socks.port"));
				assertThat(firstPort).isPositive();
				assertThat(secondPort).isPositive().isNotEqualTo(firstPort);
			} finally {
				releaseConnections.countDown();
			}

			first.get(10, TimeUnit.SECONDS);
			second.get(10, TimeUnit.SECONDS);
			assertThat(acceptedProxyConnections).hasValueGreaterThanOrEqualTo(2);
		} finally {
			releaseConnections.countDown();
			executor.shutdownNow();
		}
	}

	@Test
	public void testSOCKSPassthrough_SMTPS_Anonymous() throws Exception {
		assertSmtpsPassthrough(false);
	}

	@Test
	// The embedded remote proxy does not challenge credentials. This still exercises the authenticated-proxy bridge and verifies that SMTPS traverses it.
	public void testSOCKSPassthrough_SMTPS_Authenticating() throws Exception {
		assertSmtpsPassthrough(true);
	}

	private void assertSmtpsPassthrough(final boolean authenticatingProxy) throws Exception {
		final Wiser smtpsServer = Wiser.create(SMTPServer.port(0)
				.serverSocketFactory(createServerSslContext()));
		smtpsServer.start();
		try {
			final int smtpsPort = smtpsServer.getServer().getPortAllocated();
			try (Mailer mailer = authenticatingProxy
					? SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder().withSMTPServer("localhost", smtpsPort)
							.withTransportStrategy(TransportStrategy.SMTPS)
							.withProxy("localhost", proxyServer.getPort(), "username", "password")
							.trustingAllHosts(true)
							.verifyingServerIdentity(false)
							.buildMailer()
					: SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder().withSMTPServer("localhost", smtpsPort)
							.withTransportStrategy(TransportStrategy.SMTPS)
							.withProxy("localhost", proxyServer.getPort())
							.trustingAllHosts(true)
							.verifyingServerIdentity(false)
							.buildMailer()) {
				assertSendingEmail(mailer, EmailHelper.createDummyEmailBuilder(true, true, false, false, false, false), false, smtpsServer);
			}
		} finally {
			smtpsServer.stop();
		}
	}

	private void assertSendingEmail(final Mailer mailer, final EmailPopulatingBuilder originalEmailPopulatingBuilder, boolean async) throws Exception {
		assertSendingEmail(mailer, originalEmailPopulatingBuilder, async, smtpServer);
	}

	private void startPlainSmtpServer() {
		smtpServer = Wiser.port(0);
		smtpServer.start();
	}

	private Mailer newAuthenticatedMailer() {
		return SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder()
				.withSMTPServer("localhost", smtpServer.getServer().getPortAllocated())
				.withProxy("localhost", proxyServer.getPort(), "username", "password")
				.buildMailer();
	}

	private static void awaitConcurrentConnection(final CountDownLatch bothConnectionsOpen, final CountDownLatch releaseConnections)
			throws InterruptedException {
		bothConnectionsOpen.countDown();
		if (!releaseConnections.await(10, TimeUnit.SECONDS)) {
			throw new AssertionError("Timed out while keeping both authenticated proxy bridges active");
		}
	}

	private void assertSendingEmail(final Mailer mailer, final EmailPopulatingBuilder originalEmailPopulatingBuilder, boolean async, final Wiser smtpServer)
			throws Exception {
		Email originalEmail = originalEmailPopulatingBuilder.buildEmail();

		if (!async) {
			mailer.sendMail(originalEmail);
		} else {
			verifyNonnullOrEmpty(mailer.sendMail(originalEmail, async)).get();
		}
		assertThat(acceptedProxyConnections).hasValueGreaterThan(0);
		assertThat(smtpServer.getMessages()).hasSize(1);
		assertThat(smtpServer.getMessages().remove(0).getMimeMessage().getMessageID()).isEqualTo(originalEmail.getId());
	}

	private static SSLContext createServerSslContext() throws Exception {
		final KeyStore serverKeyStore = KeyStore.getInstance("JKS");
		try (InputStream keyStoreStream = requireNonNull(MailerSOCKSLiveTest.class.getResourceAsStream("/smtp_test_server.jks"))) {
			serverKeyStore.load(keyStoreStream, TLS_KEYSTORE_PASSWORD);
		}

		final KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		keyManagerFactory.init(serverKeyStore, TLS_KEYSTORE_PASSWORD);
		final SSLContext sslContext = SSLContext.getInstance("TLS");
		sslContext.init(keyManagerFactory.getKeyManagers(), null, null);
		return sslContext;
	}

	private static final class CountingServerSocketFactory extends ServerSocketFactory {
		private final AtomicInteger acceptedConnections;

		private CountingServerSocketFactory(final AtomicInteger acceptedConnections) {
			this.acceptedConnections = acceptedConnections;
		}

		@Override
		public ServerSocket createServerSocket(final int port) throws IOException {
			return new CountingServerSocket(port, acceptedConnections);
		}

		@Override
		public ServerSocket createServerSocket(final int port, final int backlog) throws IOException {
			return new CountingServerSocket(port, backlog, acceptedConnections);
		}

		@Override
		public ServerSocket createServerSocket(final int port, final int backlog, final InetAddress ifAddress) throws IOException {
			return new CountingServerSocket(port, backlog, ifAddress, acceptedConnections);
		}
	}

	private static final class CountingServerSocket extends ServerSocket {
		private final AtomicInteger acceptedConnections;

		private CountingServerSocket(final int port, final AtomicInteger acceptedConnections) throws IOException {
			super(port);
			this.acceptedConnections = acceptedConnections;
		}

		private CountingServerSocket(final int port, final int backlog, final AtomicInteger acceptedConnections) throws IOException {
			super(port, backlog);
			this.acceptedConnections = acceptedConnections;
		}

		private CountingServerSocket(final int port, final int backlog, final InetAddress ifAddress, final AtomicInteger acceptedConnections)
				throws IOException {
			super(port, backlog, ifAddress);
			this.acceptedConnections = acceptedConnections;
		}

		@Override
		public Socket accept() throws IOException {
			final Socket socket = super.accept();
			acceptedConnections.incrementAndGet();
			return socket;
		}
	}
}
