package org.simplejavamail.internal.authenticatedsockssupport.socks5server;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.mailer.MailFailureKind;
import org.simplejavamail.internal.authenticatedsockssupport.common.SocksException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnonymousSocks5ServerImplTest {

    @Test
    void socketCloseFailureIsClassifiedAsCleanupAndRetainsItsCause() throws Exception {
        final AnonymousSocks5ServerImpl server = newEphemeralServer();
        final ServerSocket socket = mock(ServerSocket.class);
        final IOException original = new IOException("close failed");
        doThrow(original).when(socket).close();
        setField(server, "running", true);
        setField(server, "serverSocket", socket);

        final Throwable failure = catchThrowable(server::stop);
        assertThat(failure).isInstanceOf(SocksException.class).hasCause(original);
        assertThat(((SocksException) failure).getKind()).isEqualTo(MailFailureKind.CLEANUP);
        assertThat(((SocksException) failure).getSubmissionReceipt()).isEmpty();
        assertThat(new SocksException("connection failed", original).getKind()).isEqualTo(MailFailureKind.CONNECTION);
    }

    @Test
    void interruptedListenerJoinIsClassifiedAsCleanupAndPreservesInterruption() throws Exception {
        final AnonymousSocks5ServerImpl server = newEphemeralServer();
        final CountDownLatch release = new CountDownLatch(1);
        final Thread listener = new Thread(() -> {
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        listener.setDaemon(true);
        listener.start();
        setField(server, "running", true);
        setField(server, "listenerThread", listener);
        try {
            Thread.currentThread().interrupt();
            final Throwable failure = catchThrowable(server::stop);
            assertThat(failure).isInstanceOf(SocksException.class).hasCauseInstanceOf(InterruptedException.class);
            assertThat(((SocksException) failure).getKind()).isEqualTo(MailFailureKind.CLEANUP);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
            release.countDown();
            listener.join(TimeUnit.SECONDS.toMillis(5));
            assertFalse(listener.isAlive());
        }
    }

    private static void setField(final AnonymousSocks5ServerImpl server, final String name, final Object value) throws Exception {
        final Field field = AnonymousSocks5ServerImpl.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(server, value);
    }

	@Test
	void bindsTheBridgeToLoopbackOnly() throws InterruptedException {
		final AnonymousSocks5ServerImpl server = new AnonymousSocks5ServerImpl(
				(sessionId, remoteServerAddress, remoteServerPort, owner) -> {
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
				(sessionId, remoteServerAddress, remoteServerPort, owner) -> {
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
