package org.simplejavamail.internal.authenticatedsockssupport.socks5server;

import java.net.InetAddress;

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

	private static void waitUntilStopped(final AnonymousSocks5ServerImpl server) throws InterruptedException {
		for (int elapsedMillis = 0; server.isRunning() && elapsedMillis < 1000; elapsedMillis += 10) {
			Thread.sleep(10);
		}
		assertFalse(server.isRunning(), "The SOCKS bridge should stop after its socket closes");
	}
}
