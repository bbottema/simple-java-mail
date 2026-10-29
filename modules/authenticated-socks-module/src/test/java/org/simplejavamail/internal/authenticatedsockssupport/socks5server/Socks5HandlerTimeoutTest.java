package org.simplejavamail.internal.authenticatedsockssupport.socks5server;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.internal.authenticatedsockssupport.common.Socks5Bridge;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;

class Socks5HandlerTimeoutTest {

    @Test
    void removesTheLocalParsingGuardBeforeWaitingForRemoteSetupOrForwarding() throws Exception {
        final ClientSocket client = new ClientSocket();
        client.setSoTimeout(10000);
        final AtomicInteger forwardingTimeout = new AtomicInteger(-1);
        final Socket remote = new Socket() {
            @Override public InetAddress getLocalAddress() { return InetAddress.getLoopbackAddress(); }
            @Override public int getLocalPort() { return 50025; }
            @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
            @Override public InputStream getInputStream() throws IOException {
                // Reverse forwarding obtains the remote stream only after the local CONNECT success.
                forwardingTimeout.set(client.getSoTimeout());
                throw new IOException("Stop this fixture at the forwarding boundary");
            }
        };
        final Socks5Bridge bridge = (id, address, owner) -> {
            assertThat(client.getSoTimeout()).as("local command parsed before potentially unlimited remote setup").isZero();
            owner.own(remote);
            return remote;
        };
        final SocksTunnel tunnel = new SocksTunnel(client, () -> { });
        try {
            new Socks5Handler(new SocksSession(client), bridge, tunnel).run();
            tunnel.awaitReservedForwarding();
        } finally {
            tunnel.close();
            tunnel.awaitClientReader();
        }

        assertThat(forwardingTimeout).as("established tunnel has no independent timeout").hasValue(0);
        assertThat(client.closed).isTrue();
    }

    @Test
    void failedRemoteSetupDoesNotRestoreTheLocalParsingGuard() throws Exception {
        final ClientSocket client = new ClientSocket();
        client.setSoTimeout(10000);
        new Socks5Handler(new SocksSession(client), (id, address, owner) -> {
            throw new IOException("Connection refused");
        }).run();

        assertThat(client.getSoTimeout()).isZero();
        final byte[] response = client.response.toByteArray();
        assertThat(response[5]).isIn((byte) 1, (byte) 4);
        assertThat(response).hasSize(8 + (response[5] == 1 ? 4 : 16));
        assertThat(client.response.toByteArray()[3]).isEqualTo((byte) 5);
        assertThat(client.closed).isTrue();
    }

    private static final class ClientSocket extends Socket {
        // Anonymous method selection followed by IPv4 CONNECT to localhost:25.
        private final CountDownLatch closedSignal = new CountDownLatch(1);
        private final InputStream request = new ByteArrayInputStream(new byte[] {
                5, 1, 0, 5, 1, 0, 1, 127, 0, 0, 1, 0, 25
        }) {
            @Override public synchronized int read(final byte[] bytes, final int offset, final int length) {
                final int count = super.read(bytes, offset, length);
                if (count >= 0) { return count; }
                try {
                    closedSignal.await();
                    return -1;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
            }
        };
        private final ByteArrayOutputStream response = new ByteArrayOutputStream();
        private int timeout;
        private boolean closed;

        @Override public boolean isConnected() { return true; }
        @Override public SocketAddress getRemoteSocketAddress() { return new InetSocketAddress(InetAddress.getLoopbackAddress(), 50000); }
        @Override public InputStream getInputStream() { return request; }
        @Override public OutputStream getOutputStream() { return response; }
        @Override public void setSoTimeout(final int timeout) { this.timeout = timeout; }
        @Override public int getSoTimeout() { return timeout; }
        @Override public void close() { closed = true; closedSignal.countDown(); }
    }
}
