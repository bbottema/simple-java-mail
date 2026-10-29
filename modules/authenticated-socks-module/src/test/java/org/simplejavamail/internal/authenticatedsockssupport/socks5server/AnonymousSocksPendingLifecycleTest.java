package org.simplejavamail.internal.authenticatedsockssupport.socks5server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

/** Real loopback client connections across listener generations; remote handles have explicit bounded fixture lifetimes. */
@Timeout(20)
class AnonymousSocksPendingLifecycleTest {

    @Test
    void stoppingTheListenerClosesAnOwnedPendingSocketAndUnblocksItsSetupWorker() throws Exception {
        final RemoteSocket remote = new RemoteSocket();
        final CountDownLatch registered = new CountDownLatch(1);
        final CountDownLatch setupFinished = new CountDownLatch(1);
        final AnonymousSocks5ServerImpl server = new AnonymousSocks5ServerImpl((id, address, owner) -> {
            owner.own(remote);
            registered.countDown();
            try {
                awaitRemoteClosure(remote);
                throw new SocketException("pending proxy socket closed");
            } finally {
                setupFinished.countDown();
            }
        }, 0);
        server.start();
        try (Socket client = beginConnect(server)) {
            assertThat(registered.await(3, SECONDS)).as("pending raw socket published").isTrue();

            server.stop();

            assertThat(remote.closed.await(3, SECONDS)).as("listener retirement closes pending setup").isTrue();
            assertThat(setupFinished.await(3, SECONDS)).as("setup worker released").isTrue();
            assertThat(remote.closeCalls).hasValue(1);
            assertPendingPeerClosed(client);
        } finally {
            server.stop();
            remote.closeIfOpen();
        }
    }

    @Test
    void anEstablishedTunnelSurvivesListenerStopRestartAndAnotherPendingGeneration() throws Exception {
        final RemoteSocket establishedRemote = new RemoteSocket();
        final RemoteSocket pendingRemote = new RemoteSocket();
        final AtomicInteger connections = new AtomicInteger();
        final CountDownLatch pendingRegistered = new CountDownLatch(1);
        final AnonymousSocks5ServerImpl server = new AnonymousSocks5ServerImpl((id, address, owner) -> {
            if (connections.incrementAndGet() == 1) {
                owner.own(establishedRemote);
                return establishedRemote;
            }
            owner.own(pendingRemote);
            pendingRegistered.countDown();
            awaitRemoteClosure(pendingRemote);
            throw new SocketException("later pending generation closed");
        }, 0);
        server.start();
        try {
            try (Socket establishedClient = beginConnect(server)) {
                final byte[] success = establishedClient.getInputStream().readNBytes(4);
                assertThat(success).hasSize(4);
                assertThat(success[0]).isEqualTo((byte) 5);
                assertThat(success[1]).isZero();
                assertThat(success[3]).isIn((byte) 1, (byte) 4);
                final int boundAddressLength = success[3] == 1 ? 4 : 16;
                assertThat(establishedClient.getInputStream().readNBytes(boundAddressLength + 2)).hasSize(boundAddressLength + 2);
                assertThat(establishedRemote.reading.await(3, SECONDS)).as("reverse forwarding started").isTrue();

                server.stop();
                establishedClient.getOutputStream().write(1);
                establishedClient.getOutputStream().flush();
                establishedRemote.output.awaitSize(1);
                assertThat(establishedRemote.closeCalls).hasValue(0);

                server.start();
                try (Socket pendingClient = beginConnect(server)) {
                    assertThat(pendingRegistered.await(3, SECONDS)).isTrue();
                    server.stop();
                    assertThat(pendingRemote.closed.await(3, SECONDS)).isTrue();
                    assertPendingPeerClosed(pendingClient);
                }

                establishedClient.getOutputStream().write(2);
                establishedClient.getOutputStream().flush();
                establishedRemote.output.awaitSize(2);
                assertThat(establishedRemote.output.bytes()).containsExactly(1, 2);
                assertThat(establishedRemote.closeCalls).hasValue(0);
                assertThat(pendingRemote.closeCalls).hasValue(1);
            }
            assertThat(establishedRemote.closed.await(3, SECONDS)).as("established peer EOF releases its old-generation owner").isTrue();
            assertThat(establishedRemote.closeCalls).hasValue(1);
        } finally {
            server.stop();
            establishedRemote.closeIfOpen();
            pendingRemote.closeIfOpen();
        }
    }

    private static Socket beginConnect(final AnonymousSocks5ServerImpl server) throws Exception {
        final Socket client = new Socket();
        try {
            client.connect(new InetSocketAddress("127.0.0.1", server.getLocalPort()), 2000);
            client.setSoTimeout(3000);
            client.getOutputStream().write(new byte[] {5, 1, 0});
            client.getOutputStream().flush();
            assertThat(client.getInputStream().readNBytes(2)).containsExactly(5, 0);
            client.getOutputStream().write(new byte[] {5, 1, 0, 1, 127, 0, 0, 1, 0, 25});
            client.getOutputStream().flush();
            return client;
        } catch (Throwable failure) {
            client.close();
            throw failure;
        }
    }

    private static void awaitRemoteClosure(final RemoteSocket remote) throws IOException {
        try {
            if (!remote.closed.await(5, SECONDS)) { throw new IOException("fixture pending remote close watchdog expired"); }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("fixture setup interrupted", interrupted);
        }
    }

    private static void assertPendingPeerClosed(final Socket client) throws IOException {
        // The setup worker may send a SOCKS rejection while the owner concurrently closes its local socket.
        // Either a rejection followed by EOF or immediate EOF is valid; success or an open peer is not.
        final ByteArrayOutputStream finalReply = new ByteArrayOutputStream();
        int octet;
        while ((octet = client.getInputStream().read()) != -1) {
            finalReply.write(octet);
            assertThat(finalReply.size()).as("at most one final numeric SOCKS reply before closure").isLessThanOrEqualTo(22);
        }
        final byte[] received = finalReply.toByteArray();
        if (received.length >= 2) {
            assertThat(received[0]).isEqualTo((byte) 5);
            assertThat(received[1]).as("pending connection cannot claim success").isNotZero();
        }
    }

    private static final class RemoteSocket extends Socket {
        private final CountDownLatch reading = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private final AtomicInteger closeCalls = new AtomicInteger();
        private final Sink output = new Sink();
        private final InputStream input = new InputStream() {
            @Override public int read() throws IOException {
                reading.countDown();
                try {
                    if (!closed.await(8, SECONDS)) { throw new IOException("fixture forwarding read watchdog expired"); }
                    return -1;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("fixture forwarding interrupted", interrupted);
                }
            }

            @Override public int read(final byte[] bytes, final int offset, final int length) throws IOException { return read(); }
            @Override public void close() { closed.countDown(); }
        };

        @Override public boolean isConnected() { return true; }
        @Override public boolean isClosed() { return closed.getCount() == 0; }
        @Override public SocketAddress getRemoteSocketAddress() { return new InetSocketAddress(InetAddress.getLoopbackAddress(), 50025); }
        @Override public InetAddress getLocalAddress() { return InetAddress.getLoopbackAddress(); }
        @Override public int getLocalPort() { return 50026; }
        @Override public InputStream getInputStream() { return input; }
        @Override public OutputStream getOutputStream() { return output; }
        @Override public void setSoTimeout(final int timeout) throws SocketException {
            if (isClosed()) { throw new SocketException("fixture socket closed"); }
            assertThat(timeout).as("transparent remote forwarding").isZero();
        }
        @Override public int getSoTimeout() { return 0; }
        @Override public void close() {
            closeCalls.incrementAndGet();
            output.close();
            closed.countDown();
        }

        private void closeIfOpen() {
            if (!isClosed()) { close(); }
        }
    }

    private static final class Sink extends OutputStream {
        private final ByteArrayOutputStream content = new ByteArrayOutputStream();
        private boolean closed;

        @Override public synchronized void write(final int value) throws IOException {
            if (closed) { throw new SocketException("fixture output closed"); }
            content.write(value);
            notifyAll();
        }

        @Override public synchronized void write(final byte[] bytes, final int offset, final int length) throws IOException {
            if (closed) { throw new SocketException("fixture output closed"); }
            content.write(bytes, offset, length);
            notifyAll();
        }

        private synchronized byte[] bytes() { return content.toByteArray(); }

        private synchronized void awaitSize(final int expected) throws Exception {
            final long deadline = System.nanoTime() + SECONDS.toNanos(3);
            while (content.size() < expected) {
                final long remaining = deadline - System.nanoTime();
                assertThat(remaining).as("old established tunnel still forwards").isPositive();
                wait(Math.max(1, remaining / 1_000_000));
            }
        }

        @Override public synchronized void close() { closed = true; notifyAll(); }
    }
}
