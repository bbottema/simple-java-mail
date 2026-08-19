package org.simplejavamail.mailer;


import org.simplejavamail.api.SimpleJavaMail;

import org.bbottema.javasocksproxyserver.RunningSocksServer;
import org.bbottema.javasocksproxyserver.SyncSocksServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.TransportStrategy;
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
