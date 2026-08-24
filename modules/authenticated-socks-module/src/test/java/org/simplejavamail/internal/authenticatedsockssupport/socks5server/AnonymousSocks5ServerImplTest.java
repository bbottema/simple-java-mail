package org.simplejavamail.internal.authenticatedsockssupport.socks5server;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnonymousSocks5ServerImplTest {

	@Test
	void bindsTheBridgeToLoopbackOnly() throws InterruptedException {
		final AnonymousSocks5ServerImpl server = new AnonymousSocks5ServerImpl(
				(sessionId, remoteServerAddress, remoteServerPort) -> {
					throw new UnsupportedOperationException("The bridge must not be used by this binding test");
				},
				0);

		server.start();
		try {
			final InetAddress localAddress = server.getLocalAddress();
			assertNotNull(localAddress);
			assertTrue(localAddress.isLoopbackAddress());
			assertTrue(server.getLocalPort() > 0);
		} finally {
			server.stop();
			waitUntilStopped(server);
		}
	}

	@Test
	void canRestartOnAnotherEphemeralPortAfterStoppingSynchronously() throws Exception {
		final AnonymousSocks5ServerImpl server = newEphemeralServer();

		server.start();
		final int firstPort = server.getLocalPort();
		server.stop();
		assertFalse(server.isRunning());

		try (ServerSocket portBlocker = new ServerSocket()) {
			portBlocker.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), firstPort));
			server.start();
			try {
				assertTrue(server.getLocalPort() > 0);
				assertTrue(server.getLocalPort() != firstPort);
			} finally {
				server.stop();
			}
		}
		assertFalse(server.isRunning());
	}

	private static AnonymousSocks5ServerImpl newEphemeralServer() {
		return new AnonymousSocks5ServerImpl(
				(sessionId, remoteServerAddress, remoteServerPort) -> {
					throw new UnsupportedOperationException("The bridge must not be used by this binding test");
				},
				0);
	}

	private static void waitUntilStopped(final AnonymousSocks5ServerImpl server) throws InterruptedException {
		for (int elapsedMillis = 0; server.isRunning() && elapsedMillis < 1000; elapsedMillis += 10) {
			Thread.sleep(10);
		}
		assertFalse(server.isRunning(), "The SOCKS bridge should stop after its socket closes");
	}
}
