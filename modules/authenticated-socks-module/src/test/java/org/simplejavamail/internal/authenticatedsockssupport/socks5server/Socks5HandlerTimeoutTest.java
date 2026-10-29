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

import static org.assertj.core.api.Assertions.assertThat;

class Socks5HandlerTimeoutTest {

    @Test
    void retainsTheHandshakeGuardUntilTheConnectionBecomesATransparentTunnel() throws Exception {
        final ClientSocket client = new ClientSocket();
        client.setSoTimeout(10000);
        final AtomicInteger forwardingTimeout = new AtomicInteger(-1);
        final Socket remote = new Socket() {
            @Override public InetAddress getLocalAddress() { return InetAddress.getLoopbackAddress(); }
            @Override public int getLocalPort() { return 50025; }
            @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
            @Override public InputStream getInputStream() throws IOException {
                // SocketPipe first obtains both streams before starting forwarding threads.
                forwardingTimeout.set(client.getSoTimeout());
                throw new IOException("Stop this fixture at the forwarding boundary");
            }
        };
        final Socks5Bridge bridge = (id, address, port) -> {
            assertThat(client.getSoTimeout()).as("local SOCKS handshake guard").isEqualTo(10000);
            return remote;
        };
        new Socks5Handler(new SocksSession(client), bridge).run();

        assertThat(forwardingTimeout).as("established tunnel has no independent timeout").hasValue(0);
        assertThat(client.closed).isTrue();
    }

    @Test
    void failedConnectionKeepsTheHandshakeGuardAndDoesNotStartForwarding() throws Exception {
        final ClientSocket client = new ClientSocket();
        client.setSoTimeout(10000);
        new Socks5Handler(new SocksSession(client), (id, address, port) -> {
            throw new IOException("Connection refused");
        }).run();

        assertThat(client.getSoTimeout()).isEqualTo(10000);
        assertThat(client.response.toByteArray()).hasSize(12);
        assertThat(client.response.toByteArray()[3]).isEqualTo((byte) 5);
        assertThat(client.closed).isTrue();
    }

    private static final class ClientSocket extends Socket {
        // Anonymous method selection followed by IPv4 CONNECT to localhost:25.
        private final InputStream request = new ByteArrayInputStream(new byte[] {
                5, 1, 0, 5, 1, 0, 1, 127, 0, 0, 1, 0, 25
        });
        private final ByteArrayOutputStream response = new ByteArrayOutputStream();
        private int timeout;
        private boolean closed;

        @Override public boolean isConnected() { return true; }
        @Override public SocketAddress getRemoteSocketAddress() { return new InetSocketAddress(InetAddress.getLoopbackAddress(), 50000); }
        @Override public InputStream getInputStream() { return request; }
        @Override public OutputStream getOutputStream() { return response; }
        @Override public void setSoTimeout(final int timeout) { this.timeout = timeout; }
        @Override public int getSoTimeout() { return timeout; }
        @Override public void close() { closed = true; }
    }
}
